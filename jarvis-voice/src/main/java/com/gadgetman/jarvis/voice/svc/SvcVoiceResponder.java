package com.gadgetman.jarvis.voice.svc;

import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Scheduler;
import com.gadgetman.jarvis.core.text.Colors;
import com.gadgetman.jarvis.intent.IntentPipeline;
import com.gadgetman.jarvis.voice.Speech;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Jarvis's mouth: the {@link IntentPipeline.Responder} used when an order
 * arrived by voice. His own lines are spoken; mechanical feedback stays in
 * chat, because "Torch spacing: 9" is something to read, not to be told.
 *
 * <h2>One voice at a time</h2>
 * Lines are queued per player and played strictly in order. A single order
 * can produce two replies (a build says something witty, then names the
 * schematic it picked a second later) and without a queue both synthesise
 * independently and start the moment they are ready, so he talks over
 * himself. Playback completion drives the queue via
 * {@link AudioPlayer#setOnStopped}, with a duration-based backstop in case
 * that callback never arrives.
 *
 * <p>Where the sound comes from depends on where he is. Standing next to
 * you he speaks through an {@link EntityAudioChannel} on his body, so the
 * voice is positional and falls off with distance like any other player's.
 * Off mining two hundred blocks away he would be inaudible, so the reply
 * goes to a {@link StaticAudioChannel} instead: in your ear, like a radio.
 */
public final class SvcVoiceResponder implements IntentPipeline.Responder {

    private final VoiceHost host;
    private final Speech speech;
    private final VoicechatServerApi api;
    private final boolean echoSpokenText;

    /** Lines waiting to be spoken, per player, and whatever is speaking now. */
    private final Map<UUID, Deque<String>> pending = new ConcurrentHashMap<>();
    private final Map<UUID, AudioPlayer> speaking = new ConcurrentHashMap<>();
    /**
     * Who is mid-line. Claimed the moment a line is taken off the queue, not
     * when audio starts: synthesis is a network round trip, and without the
     * earlier claim a second caller could poll the queue during it and start
     * a competing synthesis, the exact overlap the queue exists to stop.
     */
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();

    public SvcVoiceResponder(VoiceHost host, Speech speech, VoicechatServerApi api, boolean echoSpokenText) {
        this.host = host;
        this.speech = speech;
        this.api = api;
        this.echoSpokenText = echoSpokenText;
    }

    @Override
    public void speak(Owner owner, String jarvisLine) {
        if (echoSpokenText) {
            owner.message(Colors.AQUA + "Jarvis: " + Colors.WHITE + jarvisLine);
        }
        say(owner, jarvisLine);
    }

    @Override
    public void feedback(Owner owner, String line) {
        // Mechanical output: numbers, lists, confirmations. Read, not heard.
        owner.message(line);
    }

    private Scheduler scheduler() {
        return host.platform().scheduler();
    }

    /** Synthesise and play, in turn. */
    public void say(Owner owner, String text) {
        if (api == null || text == null || text.isBlank()) return;
        UUID id = owner.id();

        Deque<String> queue = pending.computeIfAbsent(id, k -> new ArrayDeque<>());
        synchronized (queue) {
            // Guard against the same line arriving twice in quick succession.
            if (text.equals(queue.peekLast())) return;
            queue.addLast(text);
            if (busy.contains(id)) return;          // the queue drains itself
        }
        drain(owner);
    }

    /** Take the next line and speak it; called again when playback stops. */
    private void drain(Owner owner) {
        UUID id = owner.id();
        final String next;
        Deque<String> queue = pending.get(id);
        if (queue == null) return;
        synchronized (queue) {
            if (!busy.add(id)) return;              // someone else is already speaking
            next = queue.pollFirst();
            if (next == null) {
                busy.remove(id);
                return;
            }
        }

        // Synthesis is a network call, so off the server thread; the channel
        // is built and started back on it, because locating the butler
        // touches the world.
        scheduler().async(() -> {
            short[] pcm = speech.synthesize(Colors.strip(next).trim());
            scheduler().sync(() -> {
                if (pcm == null || pcm.length == 0 || !owner.isOnline()) {
                    finished(owner);
                    return;
                }
                play(owner, pcm);
            });
        });
    }

    /** Playback finished (or was given up on): let the next line through. */
    private void finished(Owner owner) {
        UUID id = owner.id();
        speaking.remove(id);
        busy.remove(id);
        drain(owner);
    }

    private void play(Owner owner, short[] pcm) {
        try {
            var connection = api.getConnectionOf(owner.id());
            if (connection == null) {            // not on voice chat; the chat line already went out
                finished(owner);
                return;
            }

            OpusEncoder encoder = api.createEncoder();
            UUID channelId = UUID.randomUUID();
            double audible = api.getVoiceChatDistance();

            AudioPlayer audioPlayer;
            Optional<Object> mouth = host.butlerEntityNear(owner, audible);
            if (mouth.isPresent()) {
                // He is here: let the voice come out of him.
                EntityAudioChannel channel = api.createEntityAudioChannel(channelId, api.fromEntity(mouth.get()));
                if (channel == null) { finished(owner); return; }
                channel.setCategory("jarvis");
                audioPlayer = api.createAudioPlayer(channel, encoder, pcm);
            } else {
                // He is away, or not summoned at all: speak into the player's ear.
                Object level = host.levelOf(owner).orElse(null);
                if (level == null) { finished(owner); return; }
                StaticAudioChannel channel = api.createStaticAudioChannel(channelId, api.fromServerLevel(level), connection);
                if (channel == null) { finished(owner); return; }
                channel.setCategory("jarvis");
                audioPlayer = api.createAudioPlayer(channel, encoder, pcm);
            }

            speaking.put(owner.id(), audioPlayer);
            audioPlayer.setOnStopped(() -> scheduler().sync(() -> finished(owner)));
            audioPlayer.startPlaying();

            // Backstop: if onStopped never fires the queue would wedge and he
            // would go silent for the rest of the session. 48 kHz mono, plus
            // a little slack.
            long ticks = (pcm.length / 48000L) * 20L + 30L;
            scheduler().later(ticks, () -> {
                if (speaking.get(owner.id()) == audioPlayer) finished(owner);
            });

        } catch (Exception e) {
            host.platform().log().warn("Voice playback failed: " + e.getMessage());
            finished(owner);
        }
    }
}
