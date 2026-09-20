package com.gadgetman.jarvis.voice;

import com.gadgetman.jarvis.core.platform.Config;

import java.util.List;
import java.util.Locale;

/**
 * The operator's side of the {@code voice.*} section: the few settings that
 * decide whether he hears you at all, writable from the menu and the
 * console so nobody has to open config.yml on a mod to point him at a
 * speech server. Each write goes to the file and tells the voice plugin,
 * which re-reads the section at once.
 */
public final class VoiceConfig {

    public static final List<String> GATES = List.of("whisper", "always", "wake-word");
    public static final List<String> ENGINES = List.of("embedded", "server");

    private final Config cfg;
    private final Runnable changed;

    public VoiceConfig(Config cfg, Runnable changed) {
        this.cfg = cfg;
        this.changed = changed;
    }

    public boolean enabled() { return cfg.getBoolean("voice.enabled", false); }
    public String endpoint() { return cfg.getString("voice.endpoint", "http://127.0.0.1:8000"); }
    public String gate() { return cfg.getString("voice.gate", "whisper").toLowerCase(Locale.ROOT); }
    public boolean speakReplies() { return cfg.getBoolean("voice.speak-replies", true); }
    public String engine() {
        String e = cfg.getString("voice.engine", "embedded").trim().toLowerCase(Locale.ROOT);
        return ENGINES.contains(e) ? e : "embedded";
    }

    /** @return null when accepted, otherwise why not */
    public String setEngine(String engine) {
        String e = engine == null ? "" : engine.trim().toLowerCase(Locale.ROOT);
        if (!ENGINES.contains(e)) return "The engine is embedded or server";
        write("voice.engine", e);
        return null;
    }

    public void setEnabled(boolean on) {
        write("voice.enabled", on);
    }

    /** CPU threads for the embedded recogniser; 0 leaves it to the engine. */
    public int threads() { return cfg.getInt("voice.whisper-threads", 0); }

    /** @return null when accepted, otherwise why not */
    public String setThreads(int n) {
        if (n < 0 || n > 64) return "Threads is 0 for automatic, or 1 to 64";
        write("voice.whisper-threads", n);
        return null;
    }

    /** @return null when accepted, otherwise why not */
    public String setEndpoint(String url) {
        String u = url == null ? "" : url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) return "An endpoint starts with http:// or https://";
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        write("voice.endpoint", u);
        return null;
    }

    /** @return null when accepted, otherwise why not */
    public String setGate(String gate) {
        String g = gate == null ? "" : gate.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (!GATES.contains(g)) return "The gate is one of " + String.join(", ", GATES);
        write("voice.gate", g);
        return null;
    }

    public void setSpeakReplies(boolean on) {
        write("voice.speak-replies", on);
    }

    /** The gate after this one, for a menu button that cycles. */
    public String nextGate() {
        int i = GATES.indexOf(gate());
        return GATES.get((i + 1) % GATES.size());
    }

    private void write(String path, Object value) {
        cfg.set(path, value);
        cfg.save();
        changed.run();
    }
}
