package com.gadgetman.jarvis.voice;

import com.gadgetman.jarvis.Jarvis;
import com.gadgetman.jarvis.intent.IntentPipeline;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VoiceBridge — Jarvis's ears.
 *
 * <p>Simple Voice Chat hands the server Opus frames as players speak. This
 * decodes them, works out when a sentence has ended, transcribes it, and drops
 * the text into the same {@link IntentPipeline} that chat uses. Voice is not a
 * second brain — it is a second microphone on the existing one.
 *
 * <h2>How Jarvis knows he's being addressed</h2>
 * By default, the voice chat <em>whisper</em> key. It costs nothing, needs no
 * wake word, never mishears a conversation with another player as an order,
 * and is already a key every SVC user has bound. Talk normally to talk to
 * people; hold whisper to talk to Jarvis. {@code voice.gate} can relax this to
 * {@code always} if you play alone, or to {@code wake-word} to have him act
 * only on sentences that open with his name.
 *
 * <p>The wake word is matched loosely on purpose. Speech recognition mangles
 * uncommon proper nouns: on this server "Jarvis" came back as "garibas" and
 * "garbous", every time, so an exact match would have rejected every order the
 * player gave. {@code voice.wake-words} takes a list and {@code voice.wake-fuzz}
 * an edit-distance tolerance, so near-misses still land.
 *
 * <h2>Where the utterance ends</h2>
 * SVC only sends packets while a player is actually transmitting, so the end
 * of a sentence is simply the absence of packets. A short trailing silence
 * ({@code voice.silence-ms}) closes the buffer and sends it off.
 *
 * <h2>Threading</h2>
 * Packets arrive on voice chat's own threads, so buffers are concurrent and
 * hold nothing but samples. The sweep runs on a Bukkit timer, transcription is
 * pushed off-thread, and the pipeline puts execution back on the main thread.
 */
public class VoiceBridge implements VoicechatPlugin {

    /** One player's in-progress sentence. */
    private static class Utterance {
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
    }

    private final Jarvis plugin;
    private final SpeechService speech;
    private final Map<UUID, Utterance> open = new ConcurrentHashMap<>();

    private final boolean enabled;
    private final String gate;
    private final long silenceMs;
    private final double minSeconds;
    private final double maxSeconds;
    private final boolean echoTranscript;
    private final boolean requireSummoned;
    private final boolean debug;
    private final boolean speakReplies;
    private final java.util.List<String> wakeWords;
    private final int wakeFuzz;
    private final int wakeScan;

    /** Throttle for the debug line — mic packets arrive 50x a second. */
    private final Map<UUID, Long> lastDebugLog = new ConcurrentHashMap<>();

    private VoicechatApi api;
    private VoicechatServerApi serverApi;
    private VoiceResponder responder;
    private BukkitRunnable sweeper;

    public VoiceBridge(Jarvis plugin) {
        this.plugin    = plugin;
        this.speech    = new SpeechService(plugin);
        var cfg        = plugin.getConfig();
        this.enabled   = cfg.getBoolean("voice.enabled", false);
        this.gate      = cfg.getString("voice.gate", "whisper").toLowerCase();
        this.silenceMs = cfg.getLong("voice.silence-ms", 700);
        this.minSeconds = cfg.getDouble("voice.min-seconds", 0.4);
        this.maxSeconds = cfg.getDouble("voice.max-seconds", 15.0);
        this.echoTranscript  = cfg.getBoolean("voice.echo-transcript", true);
        this.requireSummoned = cfg.getBoolean("voice.require-summoned", false);
        this.debug           = cfg.getBoolean("voice.debug", false);
        this.speakReplies    = cfg.getBoolean("voice.speak-replies", true);
        this.wakeFuzz        = cfg.getInt("voice.wake-fuzz", 2);
        this.wakeScan        = Math.max(1, cfg.getInt("voice.wake-scan-words", 4));
        java.util.List<String> words = cfg.getStringList("voice.wake-words");
        if (words.isEmpty()) words = java.util.List.of("computer");
        this.wakeWords = words.stream()
                .map(w -> w.toLowerCase().trim())
                .filter(w -> !w.isEmpty())
                .sorted((a, b) -> Integer.compare(          // longest phrase wins
                        b.split("\\s+").length, a.split("\\s+").length))
                .toList();
    }

    /**
     * Hook into Simple Voice Chat if it is installed.
     *
     * @return true if registered; false when the plugin is absent or voice is off
     */
    public boolean register() {
        if (!enabled) return false;

        if (Bukkit.getPluginManager().getPlugin("voicechat") == null) {
            plugin.getLogger().warning("voice.enabled is true but Simple Voice Chat "
                    + "is not installed — Jarvis will not hear you.");
            return false;
        }
        BukkitVoicechatService service =
                Bukkit.getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            plugin.getLogger().warning("Simple Voice Chat is present but offered no API service.");
            return false;
        }
        service.registerPlugin(this);
        startSweeper();
        plugin.getLogger().info("Voice: listening (gate=" + gate
                + ", speech endpoint " + speech.getEndpoint() + ")");
        return true;
    }

    public void shutdown() {
        if (sweeper != null) {
            sweeper.cancel();
            sweeper = null;
        }
        open.values().forEach(u -> {
            try { u.decoder.close(); } catch (Exception ignored) { }
        });
        open.clear();
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

    private void onServerStarted(VoicechatServerStartedEvent event) {
        // The server API only exists once the voice server is up, so the
        // responder cannot be built in the constructor.
        this.serverApi = event.getVoicechat();
        if (speakReplies) {
            this.responder = new VoiceResponder(plugin, speech, serverApi);
        }
        plugin.getLogger().info("Voice: ready to speak (distance "
                + serverApi.getVoiceChatDistance() + ")");
    }

    private void onMicrophonePacket(MicrophonePacketEvent event) {
        try {
            if (event.getSenderConnection() == null) return;
            var sender = event.getSenderConnection().getPlayer();
            if (sender == null) return;
            if (!(sender.getPlayer() instanceof Player player)) return;

            boolean whispering = event.getPacket().isWhispering();
            byte[] opus = event.getPacket().getOpusEncodedData();

            // Deliberately logged BEFORE the gate: the common failure is that
            // packets arrive fine and the gate rejects every one of them,
            // which looks identical to no audio at all from the outside.
            if (debug) {
                long now = System.currentTimeMillis();
                Long last = lastDebugLog.get(player.getUniqueId());
                if (last == null || now - last > 1000) {
                    lastDebugLog.put(player.getUniqueId(), now);
                    plugin.getLogger().info("Voice debug: packet from " + player.getName()
                            + " whispering=" + whispering
                            + " bytes=" + (opus == null ? 0 : opus.length)
                            + " gate=" + gate
                            + " -> " + (("whisper".equals(gate) && !whispering)
                                        ? "REJECTED by gate" : "accepted"));
                }
            }

            // Is this aimed at Jarvis?
            if ("whisper".equals(gate) && !whispering) return;
            if (opus == null || opus.length == 0) return;

            Utterance u = open.computeIfAbsent(player.getUniqueId(),
                    id -> new Utterance(api.createDecoder()));

            short[] frame = u.decoder.decode(opus);
            if (frame != null && frame.length > 0) {
                u.append(frame);
                u.lastPacketAt = System.currentTimeMillis();
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Voice packet dropped: " + e.getMessage());
        }
    }

    // ==================== END-OF-SENTENCE ====================

    /**
     * A sentence ends when the packets stop. Checked four times a second —
     * often enough that the pause feels like the end of speaking, cheap enough
     * that it costs nothing when nobody is talking.
     */
    private void startSweeper() {
        sweeper = new BukkitRunnable() {
            @Override public void run() {
                if (open.isEmpty()) return;
                long now = System.currentTimeMillis();
                for (Map.Entry<UUID, Utterance> entry : open.entrySet()) {
                    Utterance u = entry.getValue();
                    boolean quiet = now - u.lastPacketAt >= silenceMs;
                    boolean tooLong = u.length >= maxSeconds * 48000;
                    if (!quiet && !tooLong) continue;

                    short[] pcm = u.drain();
                    if (quiet) {
                        open.remove(entry.getKey());
                        try { u.decoder.close(); } catch (Exception ignored) { }
                    }
                    Player player = Bukkit.getPlayer(entry.getKey());
                    if (player == null || !player.isOnline()) continue;
                    if (pcm.length < minSeconds * 48000) continue;   // a cough, not an order

                    handleUtterance(player, pcm);
                }
            }
        };
        sweeper.runTaskTimer(plugin, 5L, 5L);
    }

    private void handleUtterance(Player player, short[] pcm) {
        if (requireSummoned
                && plugin.getJarvisNPC().getNPCForPlayer(player.getUniqueId()) == null) {
            return;
        }

        new BukkitRunnable() {
            @Override public void run() {
                String text = speech.transcribe(pcm);
                if (text == null || text.isBlank()) return;

                String cleaned = clean(text);
                if (cleaned.isEmpty()) return;

                if ("wake-word".equals(gate)) {
                    String addressed = stripWakeWord(cleaned);
                    if (addressed == null) {
                        if (debug) {
                            plugin.getLogger().info("Voice debug: heard \"" + cleaned
                                    + "\" but no wake word — ignoring");
                        }
                        return;
                    }
                    cleaned = addressed;
                    if (cleaned.isEmpty()) return;
                }

                final String order = cleaned;
                new BukkitRunnable() {
                    @Override public void run() {
                        if (!player.isOnline()) return;
                        if (echoTranscript) {
                            player.sendMessage(ChatColor.DARK_GRAY + "You (voice): "
                                    + ChatColor.GRAY + order);
                        }
                        IntentPipeline.Responder sink = responder != null
                                ? responder : IntentPipeline.CHAT_RESPONDER;
                        plugin.getIntentPipeline().submit(
                                player, order.toLowerCase(), IntentPipeline.Source.VOICE, sink);
                    }
                }.runTask(plugin);
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * Does this sentence open with Jarvis's name? Returns the order with the
     * name removed, or null if he wasn't being addressed.
     *
     * <p>Wake phrases may be several words, and multi-word phrases are the
     * reliable ones. Recognition of a bare proper noun is poor — "Jarvis" came
     * back from a real session as "garibas" and "garbous", every time — but
     * "hey jarvis" survives even a degraded signal, because the surrounding
     * word gives the recogniser context to anchor on.
     *
     * <p>Longer phrases are tried first so that "hey jarvis" wins over a bare
     * "jarvis" and strips both words rather than one.
     */
    private String stripWakeWord(String text) {
        String[] spoken = text.trim().split("\\s+");
        if (spoken.length == 0) return null;

        for (String wake : wakeWords) {
            String[] wakeWordTokens = wake.trim().split("\\s+");

            // The phrase need not open the sentence. Recognition regularly
            // prepends filler -- a real "hey jarvis build a panic shelter"
            // arrived as "Again, a Jarvis build a panic shelter", with the
            // name third. Scan a short window and drop everything up to and
            // including the phrase.
            int limit = Math.min(wakeScan, spoken.length - wakeWordTokens.length + 1);
            for (int offset = 0; offset < limit; offset++) {
                boolean matched = true;
                for (int i = 0; i < wakeWordTokens.length; i++) {
                    String heard = spoken[offset + i].replaceAll("[^\\p{L}]", "").toLowerCase();
                    String want  = wakeWordTokens[i];
                    // Fuzz only on words long enough that it cannot swallow a
                    // different short word whole.
                    int allowed = want.length() >= 5 ? wakeFuzz : 0;
                    if (heard.isEmpty() || editDistance(heard, want) > allowed) {
                        matched = false;
                        break;
                    }
                }
                if (!matched) continue;

                String rest = String.join(" ", java.util.Arrays.copyOfRange(
                        spoken, offset + wakeWordTokens.length, spoken.length));
                return rest.replaceFirst("^[,.!?\\s]+", "").trim();
            }
        }
        return null;
    }

    /** Levenshtein, iterative and allocation-light — this runs per utterance. */
    private static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur  = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev; prev = cur; cur = swap;
        }
        return prev[b.length()];
    }

    /**
     * Whisper writes prose: capitals, a full stop, and the wake word the
     * player naturally said. The pipeline wants the order, so strip a leading
     * "jarvis," the way the chat listener strips its prefix.
     *
     * <p>It also emits bracketed non-speech tags such as "[BLANK_AUDIO]" or
     * "(wind blowing)" for noise. Those are not orders and must not reach a
     * model that will gamely try to act on them.
     */
    private String clean(String text) {
        String s = text.trim();
        s = s.replaceAll("\\[[^\\]]*\\]", " ")     // [BLANK_AUDIO]
             .replaceAll("\\([^)]*\\)", " ")       // (wind blowing)
             .replaceAll("\\*[^*]*\\*", " ")       // *sighs*
             .trim();

        return s;
    }
}
