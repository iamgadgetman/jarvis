package com.gadgetman.jarvis.voice;

import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Log;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Picks the speech engine {@code voice.engine} asks for: {@code embedded}
 * (the default, whisper.cpp and Piper inside this very process) or
 * {@code server} (an OpenAI-compatible speech server on the network).
 */
public final class SpeechService {

    private SpeechService() { }

    public static Speech open(Config cfg, Log log, Path dataDir) {
        String engine = cfg.getString("voice.engine", "embedded").trim().toLowerCase(Locale.ROOT);
        return engine.equals("server") ? new RemoteSpeech(cfg, log) : new EmbeddedSpeech(cfg, log, dataDir);
    }
}
