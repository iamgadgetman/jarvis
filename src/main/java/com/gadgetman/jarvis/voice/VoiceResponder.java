package com.gadgetman.jarvis.voice;

import com.gadgetman.jarvis.Jarvis;
import com.gadgetman.jarvis.intent.IntentPipeline;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.ChatColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VoiceResponder — Jarvis's mouth.
 *
 * <p>The {@link IntentPipeline.Responder} used when an order arrived by voice.
 * Jarvis's own lines are spoken; mechanical feedback stays in chat, because
 * "Torch spacing: 9" is something to read, not something to be told.
 *
 * <h2>One voice at a time</h2>
 * Lines are queued per player and played strictly in order. A single order can
 * easily produce two replies — a build says something witty, then names the
 * schematic it picked a second later — and without a queue both synthesise
 * independently and start the moment they are ready, so Jarvis talks over
 * himself. Playback completion drives the queue via
 * {@link AudioPlayer#setOnStopped}, with a duration-based backstop in case
 * that callback never arrives.
 *
 * <p>Where the sound comes from depends on where he is. Standing next to you
 * he speaks through an {@link EntityAudioChannel} attached to the NPC, so the
 * voice is positional and falls off with distance like any other player's.
 * Off mining two hundred blocks away he would simply be inaudible, so the
 * reply goes to a {@link StaticAudioChannel} instead — in your ear, like a
 * radio. The butler is either beside you or on the intercom, and both are
 * correct.
 */
public class VoiceResponder implements IntentPipeline.Responder {

    private final Jarvis plugin;
    private final SpeechService speech;
    private final VoicechatServerApi api;
    private final boolean echoSpokenText;

    /** Lines waiting to be spoken, per player, and whatever is speaking now. */
    private final Map<UUID, Deque<String>> pending = new ConcurrentHashMap<>();
    private final Map<UUID, AudioPlayer> speaking = new ConcurrentHashMap<>();
    /**
     * Who is mid-line. Claimed the moment a line is taken off the queue, not
     * when audio starts — synthesis is a network round trip, and without the
     * earlier claim a second caller could poll the queue during it and start a
     * competing synthesis, which is the exact overlap the queue exists to stop.
     */
    private final java.util.Set<UUID> busy = ConcurrentHashMap.newKeySet();

    public VoiceResponder(Jarvis plugin, SpeechService speech, VoicechatServerApi api) {
        this.plugin = plugin;
        this.speech = speech;
        this.api    = api;
        this.echoSpokenText = plugin.getConfig().getBoolean("voice.echo-spoken-text", true);
    }

    @Override
    public void speak(Player player, String jarvisLine) {
        if (echoSpokenText) {
            player.sendMessage(ChatColor.AQUA + "Jarvis: " + ChatColor.WHITE + jarvisLine);
        }
        say(player, jarvisLine);
    }

    @Override
    public void feedback(Player player, String line) {
        // Mechanical output — numbers, lists, confirmations. Read, not heard.
        player.sendMessage(line);
    }

    /**
     * Synthesise and play. Synthesis is a network call, so it happens off the
     * main thread; the channel is then built and started back on it, because
     * locating the NPC touches the Bukkit API.
     */
    public void say(Player player, String text) {
        if (api == null || text == null || text.isBlank()) return;
        UUID id = player.getUniqueId();

        Deque<String> queue = pending.computeIfAbsent(id, k -> new ArrayDeque<>());
        synchronized (queue) {
            // Guard against the same line arriving twice in quick succession.
            if (text.equals(queue.peekLast())) return;
            queue.addLast(text);
            if (busy.contains(id)) return;          // the queue drains itself
        }
        drain(player);
    }

    /** Take the next line and speak it; called again when playback stops. */
    private void drain(Player player) {
        UUID id = player.getUniqueId();
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

        new BukkitRunnable() {
            @Override public void run() {
                short[] pcm = speech.synthesize(stripFormatting(next));
                new BukkitRunnable() {
                    @Override public void run() {
                        if (pcm == null || pcm.length == 0 || !player.isOnline()) {
                            finished(player);
                            return;
                        }
                        play(player, pcm);
                    }
                }.runTask(plugin);
            }
        }.runTaskAsynchronously(plugin);
    }

    /** Playback finished (or was given up on) — let the next line through. */
    private void finished(Player player) {
        UUID id = player.getUniqueId();
        speaking.remove(id);
        busy.remove(id);
        drain(player);
    }

    private void play(Player player, short[] pcm) {
        try {
            var connection = api.getConnectionOf(player.getUniqueId());
            if (connection == null) {            // not on voice chat; the chat line already went out
                finished(player);
                return;
            }

            OpusEncoder encoder = api.createEncoder();
            UUID channelId = UUID.randomUUID();

            Entity mouth = findNpcEntity(player);
            double audible = api.getVoiceChatDistance();

            AudioPlayer audioPlayer;
            if (mouth != null && mouth.getWorld().equals(player.getWorld())
                    && mouth.getLocation().distance(player.getLocation()) <= audible) {
                // He is here: let the voice come out of him.
                EntityAudioChannel channel =
                        api.createEntityAudioChannel(channelId, api.fromEntity(mouth));
                if (channel == null) { finished(player); return; }
                channel.setCategory("jarvis");
                audioPlayer = api.createAudioPlayer(channel, encoder, pcm);
            } else {
                // He is away, or not summoned at all — speak into the player's ear.
                StaticAudioChannel channel = api.createStaticAudioChannel(
                        channelId, api.fromServerLevel(player.getWorld()), connection);
                if (channel == null) { finished(player); return; }
                channel.setCategory("jarvis");
                audioPlayer = api.createAudioPlayer(channel, encoder, pcm);
            }

            speaking.put(player.getUniqueId(), audioPlayer);
            audioPlayer.setOnStopped(() -> plugin.getServer().getScheduler()
                    .runTask(plugin, () -> finished(player)));
            audioPlayer.startPlaying();

            // Backstop: if onStopped never fires the queue would wedge and he
            // would go silent for the rest of the session. 48 kHz mono, plus a
            // little slack.
            long ticks = (pcm.length / 48000L) * 20L + 30L;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (speaking.get(player.getUniqueId()) == audioPlayer) finished(player);
            }, ticks);

        } catch (Exception e) {
            plugin.getLogger().warning("Voice playback failed: " + e.getMessage());
            finished(player);
        }
    }

    private Entity findNpcEntity(Player player) {
        try {
            NPC npc = plugin.getJarvisNPC().getNPCForPlayer(player.getUniqueId());
            if (npc == null || !npc.isSpawned()) return null;
            return npc.getEntity();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Colour codes are for the chat box. Spoken aloud, "§bJarvis" would be
     * read out as letters.
     */
    private static String stripFormatting(String text) {
        return ChatColor.stripColor(text).replaceAll("§.", "").trim();
    }
}
