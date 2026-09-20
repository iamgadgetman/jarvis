package com.gadgetman.jarvis.voice.svc;

import com.gadgetman.jarvis.JarvisCore;
import com.gadgetman.jarvis.core.platform.Audience;
import com.gadgetman.jarvis.core.platform.Log;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Platform;
import com.gadgetman.jarvis.core.platform.Task;
import com.gadgetman.jarvis.core.text.Colors;
import com.gadgetman.jarvis.intent.IntentPipeline;
import com.gadgetman.jarvis.voice.Speech;
import com.gadgetman.jarvis.voice.SpeechService;
import com.gadgetman.jarvis.voice.VoiceStatus;
import com.gadgetman.jarvis.voice.WakeWords;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Jarvis's ears, as a Simple Voice Chat plugin. One class for Paper, Fabric
 * and NeoForge; only how it gets registered differs, and that is the
 * adapter's business.
 *
 * <p>Simple Voice Chat hands the server Opus frames as players speak. This
 * decodes them, works out when a sentence has ended, transcribes it, and
 * drops the text into the same {@link IntentPipeline} that chat uses. Voice
 * is not a second brain; it is a second microphone on the existing one.
 *
 * <h2>How Jarvis knows he's being addressed</h2>
 * By default, the voice chat <em>whisper</em> key. It costs nothing, needs
 * no wake word, never mishears a conversation with another player as an
 * order, and is a key every voice chat user has bound. {@code voice.gate}
 * can relax this to {@code always} for playing alone, or to
 * {@code wake-word} to act only on sentences that carry his name (see
 * {@link WakeWords}).
 *
 * <h2>Where the utterance ends</h2>
 * Packets only arrive while a player is transmitting, so the end of a
 * sentence is the absence of packets. A short trailing silence
 * ({@code voice.silence-ms}) closes the buffer and sends it off.
 *
 * <h2>Lifetimes</h2>
 * On the mods, Simple Voice Chat constructs this at mod load, long before
 * a server (or, in singleplayer, the first of several) exists, so the
 * plugin is handed a supplier of the {@link VoiceHost} rather than one
 * host. It attaches to whatever host the supplier gives it, reads the
 * settings then, and notices when the host changes. Packets arrive on
 * voice chat's own threads, so the buffers are concurrent and hold nothing
 * but samples; the sweep runs on the platform's tick scheduler,
 * transcription is pushed off-thread, and the pipeline puts execution back
 * on the server thread.
 */
public class SvcVoicePlugin implements VoicechatPlugin, VoiceStatus {

    private static volatile SvcVoicePlugin current;

    /** The plugin Simple Voice Chat constructed, if it has. */
    public static Optional<SvcVoicePlugin> current() {
        return Optional.ofNullable(current);
    }

    /** One player's in-progress sentence. */
    private static final class Utterance {
        final OpusDecoder decoder;
        short[] samples = new short[48000];   // one second, grown as needed
        int length;
        volatile long lastPacketAt;

        Utterance(OpusDecoder decoder) {
            this.decoder = decoder;
            this.lastPacketAt = System.currentTimeMillis();
        }

        synchronized void append(short[] frame) {
            if (length + frame.length > samples.length) {
                int target = Math.max(samples.length * 2, length + frame.length);
                short[] bigger = new short[target];
                System.arraycopy(samples, 0, bigger, 0, length);
                samples = bigger;
            }
            System.arraycopy(frame, 0, samples, length, frame.length);
            length += frame.length;
        }

        synchronized short[] drain() {
            short[] out = new short[length];
            System.arraycopy(samples, 0, out, 0, length);
            length = 0;
            return out;
        }

        void close() {
            try { decoder.close(); } catch (Exception ignored) { }
        }
    }

    private final Supplier<VoiceHost> hosts;
    private final Map<UUID, Utterance> open = new ConcurrentHashMap<>();
    /** Throttle for the debug line; mic packets arrive 50 times a second. */
    private final Map<UUID, Long> lastDebugLog = new ConcurrentHashMap<>();

    private VoicechatApi api;
    private VoicechatServerApi serverApi;

    private VoiceHost attached;
    private VoiceSettings settings;

    // For the status report: is anything arriving, and what did he make of it.
    private volatile long lastPacketAt;
    private volatile long lastPacketRejectedAt;
    private volatile long lastUtteranceAt;
    private volatile String lastTranscript;
    private volatile long lastTranscriptAt;
    private Speech speech;
    private SvcVoiceResponder responder;
    private Task sweeper;

    public SvcVoicePlugin(Supplier<VoiceHost> hosts) {
        this.hosts = hosts;
        current = this;
    }

    // ==================== VOICECHAT PLUGIN ====================

    @Override
    public String getPluginId() {
        return "jarvis";
    }

    @Override
    public void initialize(VoicechatApi api) {
        this.api = api;
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
    }

    private synchronized void onServerStarted(VoicechatServerStartedEvent event) {
        // The server API only exists once the voice server is up, which may
        // be before or after Jarvis attaches; whichever comes second builds
        // the responder.
        this.serverApi = event.getVoicechat();
        if (attached != null) buildResponder();
    }

    // ==================== ATTACHING TO A SERVER RUN ====================

    /**
     * The host of the moment, attached to on first sight. Null while no
     * server is up, or voice is off.
     */
    private synchronized VoiceHost host() {
        VoiceHost h = hosts.get();
        if (h == null || h.core() == null) {
            if (attached != null) detach();
            return null;
        }
        if (h != attached) attach(h);
        return settings.enabled() ? h : null;
    }

    /** Attach to the current server run now rather than at the first packet, so the log says so at startup. */
    public void refresh() {
        host();
    }

    private void attach(VoiceHost h) {
        if (attached != null) detach();
        attached = h;
        Platform platform = h.platform();
        settings = VoiceSettings.read(platform.config());
        speech = SpeechService.open(platform.config(), platform.log(), platform.dataDir());
        h.core().setVoiceStatus(this);
        if (!settings.enabled()) {
            platform.log().info("Voice: off (voice.enabled is false)");
            return;
        }
        buildResponder();
        sweeper = platform.scheduler().every(5L, 5L, t -> sweep());
        platform.log().info("Voice: listening (gate=" + settings.gate() + ", " + speech.describe() + ")");
        // Models fetched and engines loaded now, not at the first order.
        speech.warmUp();
    }

    private void buildResponder() {
        if (serverApi == null || attached == null || settings == null || !settings.speakReplies()) return;
        responder = new SvcVoiceResponder(attached, speech, serverApi, settings.echoSpokenText());
        attached.platform().log().info("Voice: ready to speak (distance " + serverApi.getVoiceChatDistance() + ")");
    }

    /** The voice section was edited: read it again and start or stop listening accordingly. */
    @Override
    public synchronized void settingsChanged() {
        VoiceHost h = attached;
        if (h == null) return;
        detach();
        attach(h);
    }

    /** Let go of the server run: the scheduler that ran the sweep is gone with it. */
    public synchronized void detach() {
        if (sweeper != null) {
            sweeper.cancel();
            sweeper = null;
        }
        open.values().forEach(Utterance::close);
        open.clear();
        responder = null;
        attached = null;
        if (speech != null) {
            speech.close();
            speech = null;
        }
    }

    // ==================== PACKETS ====================

    private void onMicrophonePacket(MicrophonePacketEvent event) {
        VoiceHost h = host();
        if (h == null) return;
        Log log = h.platform().log();
        try {
            if (event.getSenderConnection() == null) return;
            var sender = event.getSenderConnection().getPlayer();
            if (sender == null) return;
            UUID id = sender.getUuid();

            boolean whispering = event.getPacket().isWhispering();
            byte[] opus = event.getPacket().getOpusEncodedData();

            // Deliberately logged BEFORE the gate: the common failure is that
            // packets arrive fine and the gate rejects every one of them,
            // which looks identical to no audio at all from the outside.
            if (settings.debug()) {
                long now = System.currentTimeMillis();
                Long last = lastDebugLog.get(id);
                if (last == null || now - last > 1000) {
                    lastDebugLog.put(id, now);
                    log.info("Voice debug: packet from " + id
                            + " whispering=" + whispering
                            + " bytes=" + (opus == null ? 0 : opus.length)
                            + " gate=" + settings.gate()
                            + " -> " + (("whisper".equals(settings.gate()) && !whispering)
                                        ? "REJECTED by gate" : "accepted"));
                }
            }

            if ("whisper".equals(settings.gate()) && !whispering) {
                lastPacketRejectedAt = System.currentTimeMillis();
                return;
            }
            if (opus == null || opus.length == 0) return;
            lastPacketAt = System.currentTimeMillis();

            Utterance u = open.computeIfAbsent(id, k -> new Utterance(api.createDecoder()));
            short[] frame = u.decoder.decode(opus);
            if (frame != null && frame.length > 0) {
                u.append(frame);
                u.lastPacketAt = System.currentTimeMillis();
            }
        } catch (Exception e) {
            log.fine("Voice packet dropped: " + e.getMessage());
        }
    }

    // ==================== END-OF-SENTENCE ====================

    /**
     * A sentence ends when the packets stop. Checked four times a second:
     * often enough that the pause feels like the end of speaking, cheap
     * enough that it costs nothing when nobody is talking.
     */
    private void sweep() {
        VoiceHost h = attached;
        if (h == null || open.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Utterance> entry : open.entrySet()) {
            Utterance u = entry.getValue();
            boolean quiet = now - u.lastPacketAt >= settings.silenceMs();
            boolean tooLong = u.length >= settings.maxSeconds() * 48000;
            if (!quiet && !tooLong) continue;

            short[] pcm = u.drain();
            if (quiet) {
                open.remove(entry.getKey());
                u.close();
            }
            Owner owner = h.platform().players().byId(entry.getKey()).orElse(null);
            if (owner == null || !owner.isOnline()) continue;
            if (pcm.length < settings.minSeconds() * 48000) continue;   // a cough, not an order

            lastUtteranceAt = System.currentTimeMillis();
            handleUtterance(h, owner, pcm);
        }
    }

    private void handleUtterance(VoiceHost h, Owner owner, short[] pcm) {
        JarvisCore core = h.core();
        if (core == null) return;
        if (settings.requireSummoned() && !core.butlers().exists(owner)) return;
        Log log = h.platform().log();

        h.platform().scheduler().async(() -> {
            String text = speech.transcribe(pcm);
            if (text == null || text.isBlank()) return;

            String cleaned = WakeWords.clean(text);
            lastTranscript = cleaned;
            lastTranscriptAt = System.currentTimeMillis();
            if (cleaned.isEmpty()) return;

            if ("wake-word".equals(settings.gate())) {
                String addressed = settings.wake().strip(cleaned);
                if (addressed == null) {
                    if (settings.debug()) log.info("Voice debug: heard \"" + cleaned + "\" but no wake word; ignoring");
                    return;
                }
                cleaned = addressed;
                if (cleaned.isEmpty()) return;
            }

            final String order = cleaned;
            h.platform().scheduler().sync(() -> {
                if (!owner.isOnline() || h.core() == null) return;
                if (settings.echoTranscript()) {
                    owner.message(Colors.DARK_GRAY + "You (voice): " + Colors.GRAY + order);
                }
                IntentPipeline.Responder sink = responder != null ? responder : IntentPipeline.CHAT_RESPONDER;
                h.core().intents().submit(owner, order.toLowerCase(java.util.Locale.ROOT),
                        IntentPipeline.Source.VOICE, sink);
            });
        });
    }

    // ==================== /jarvis voice ====================

    private static String ago(long at) {
        if (at == 0) return "never";
        long s = (System.currentTimeMillis() - at) / 1000;
        return s < 60 ? s + "s ago" : (s / 60) + "m ago";
    }

    @Override
    public void report(Audience to) {
        VoiceSettings st = settings;
        to.message(Colors.GRAY + "Simple Voice Chat: " + Colors.WHITE + "plugin registered"
                + (api == null ? Colors.YELLOW + " (not initialised by the mod yet)" : ""));
        to.message(Colors.GRAY + "Voice server: " + (serverApi != null
                ? Colors.GREEN + "up" + Colors.GRAY + " (audible " + serverApi.getVoiceChatDistance() + " blocks)"
                : Colors.RED + "not started" + Colors.GRAY
                        + ". A singleplayer world has none until it is opened to LAN; on a server, check the voicechat port."));
        if (st == null) {
            to.message(Colors.YELLOW + "Not attached to a server run yet.");
            return;
        }
        to.message(Colors.GRAY + "voice.enabled: " + (st.enabled()
                ? Colors.GREEN + "true"
                : Colors.RED + "false" + Colors.GRAY + " (set it in config/jarvis/config.yml and reload)"));
        to.message(Colors.GRAY + "Gate: " + Colors.WHITE + st.gate()
                + ("wake-word".equals(st.gate()) ? Colors.GRAY + " (phrases: " + String.join(", ", st.wake().phrases()) + ")"
                : "whisper".equals(st.gate()) ? Colors.GRAY + " (hold the voice chat whisper key)" : ""));
        to.message(Colors.GRAY + "Heard: " + Colors.WHITE + "last packet " + ago(lastPacketAt)
                + Colors.GRAY + ", last rejected by the gate " + ago(lastPacketRejectedAt)
                + ", last sentence closed " + ago(lastUtteranceAt));
        to.message(Colors.GRAY + "Last transcript: " + (lastTranscript == null
                ? Colors.WHITE + "none yet"
                : Colors.WHITE + "\"" + lastTranscript + "\"" + Colors.GRAY + " (" + ago(lastTranscriptAt) + ")"));
        to.message(Colors.GRAY + "Speaking: " + (responder != null
                ? Colors.GREEN + "ready"
                : Colors.YELLOW + (st.speakReplies() ? "waiting for the voice server" : "off (voice.speak-replies)")));
        if (attached == null || speech == null) return;
        final Speech engine = speech;
        final String what = engine.describe();
        attached.platform().scheduler().async(() -> {
            String problem = engine.probe();
            attached.platform().scheduler().sync(() -> to.message(Colors.GRAY + "Speech, " + what + ": "
                    + (problem == null ? Colors.GREEN + "ready" : Colors.RED + "not ready" + Colors.GRAY + " (" + problem + ")")));
        });
    }
}
