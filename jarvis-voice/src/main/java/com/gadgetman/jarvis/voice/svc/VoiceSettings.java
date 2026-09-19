package com.gadgetman.jarvis.voice.svc;

import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.voice.WakeWords;

import java.util.Locale;

/** The {@code voice.*} section of config.yml, read once per server run. */
public record VoiceSettings(boolean enabled, String gate, long silenceMs, double minSeconds, double maxSeconds,
                            boolean echoTranscript, boolean requireSummoned, boolean debug,
                            boolean speakReplies, boolean echoSpokenText, WakeWords wake) {

    public static VoiceSettings read(Config cfg) {
        return new VoiceSettings(
                cfg.getBoolean("voice.enabled", false),
                cfg.getString("voice.gate", "whisper").toLowerCase(Locale.ROOT),
                cfg.getLong("voice.silence-ms", 700),
                cfg.getDouble("voice.min-seconds", 0.4),
                cfg.getDouble("voice.max-seconds", 15.0),
                cfg.getBoolean("voice.echo-transcript", true),
                cfg.getBoolean("voice.require-summoned", false),
                cfg.getBoolean("voice.debug", false),
                cfg.getBoolean("voice.speak-replies", true),
                cfg.getBoolean("voice.echo-spoken-text", true),
                WakeWords.fromConfig(cfg));
    }
}
