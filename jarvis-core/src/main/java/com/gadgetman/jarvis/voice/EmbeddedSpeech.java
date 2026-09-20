package com.gadgetman.jarvis.voice;

import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Log;
import io.github.givimad.piperjni.PiperJNI;
import io.github.givimad.piperjni.PiperVoice;
import io.github.givimad.whisperjni.WhisperContext;
import io.github.givimad.whisperjni.WhisperFullParams;
import io.github.givimad.whisperjni.WhisperJNI;
import io.github.givimad.whisperjni.WhisperSamplingStrategy;

import java.nio.file.Path;

/**
 * Speech inside the server process: whisper.cpp listens and Piper speaks,
 * through their JNI bindings, whose native libraries ride in the jar. No
 * container, no second service, nothing to point at. The default engine.
 *
 * <p>The first time voice is turned on the two model files are fetched
 * (see {@link SpeechModels}); until they are here he cannot hear, and the
 * status report says so. Loading them takes a few seconds and a few hundred
 * megabytes of memory, and happens once per server run, ahead of the first
 * order when {@link #warmUp()} is called.
 *
 * <p>whisper.cpp is not thread-safe per context and Piper is not per voice,
 * so each is serialised. Two players speaking at once queue behind each
 * other for about a second; that is the cost of not running a service.
 */
public final class EmbeddedSpeech implements Speech {

    private final Log log;
    private final SpeechModels models;
    private final String hotwords;
    private final int threads;

    private final Object whisperLock = new Object();
    private final Object piperLock = new Object();
    private WhisperJNI whisper;
    private WhisperContext context;
    private PiperJNI piper;
    private PiperVoice voice;
    private int voiceRate;
    /** Why the engines could not be loaded, for the status report; null when they could or were not tried. */
    private volatile String failure;
    private volatile boolean warnedNotReady;

    public EmbeddedSpeech(Config cfg, Log log, Path dataDir) {
        this.log = log;
        this.hotwords = cfg.getString("voice.hotwords", "");
        int cores = Runtime.getRuntime().availableProcessors();
        this.threads = Math.max(2, Math.min(4, cores / 2));
        Path dir = dataDir.resolve(cfg.getString("voice.models-dir", "models"));
        this.models = new SpeechModels(dir,
                cfg.getString("voice.whisper-model", "base.en"),
                cfg.getString("voice.piper-voice", "en_GB-alan-medium"), log);
    }

    public SpeechModels models() {
        return models;
    }

    @Override
    public void warmUp() {
        models.ensure(this::load);
    }

    /** Load both engines, once. @return whether they are usable. */
    private boolean load() {
        synchronized (whisperLock) {
            if (context == null) {
                try {
                    WhisperJNI.loadLibrary();
                    WhisperJNI.setLibraryLogger(null);
                    whisper = new WhisperJNI();
                    context = whisper.init(models.whisperFile());
                    log.info("Speech: whisper " + models.whisperModel() + " loaded (" + threads + " threads)");
                } catch (Throwable t) {
                    failure = "whisper: " + t;
                    log.warn("Speech: whisper could not be loaded: " + t);
                    return false;
                }
            }
        }
        synchronized (piperLock) {
            if (voice == null) {
                try {
                    piper = new PiperJNI();
                    piper.initialize(true);
                    voice = piper.loadVoice(models.voiceFile(), models.voiceConfigFile());
                    voiceRate = voice.getSampleRate();
                    log.info("Speech: Piper voice " + models.piperVoice() + " loaded (" + voiceRate + " Hz)");
                } catch (Throwable t) {
                    failure = "piper: " + t;
                    log.warn("Speech: Piper could not be loaded: " + t);
                    return false;
                }
            }
        }
        failure = null;
        return true;
    }

    private boolean ready() {
        if (!models.ready()) {
            if (!warnedNotReady) {
                warnedNotReady = true;
                log.warn("Speech models are not here yet (" + models.status().describe() + "); he cannot hear until they are.");
            }
            models.ensure(this::load);
            return false;
        }
        return (context != null && voice != null) || load();
    }

    @Override
    public String transcribe(short[] pcm48k) {
        if (pcm48k == null || pcm48k.length == 0 || !ready()) return null;
        // whisper refuses under a second of audio; a short "come" is padded out.
        float[] samples = Resample.to16k(Resample.padTo(pcm48k, 48000 + 4800));
        WhisperFullParams params = new WhisperFullParams(WhisperSamplingStrategy.GREEDY);
        params.language = "en";
        params.nThreads = threads;
        params.noTimestamps = true;
        params.printProgress = false;
        params.printRealtime = false;
        params.printTimestamps = false;
        params.printSpecial = false;
        params.suppressBlank = true;
        params.suppressNonSpeechTokens = true;
        // Bias recognition toward names it would otherwise mangle.
        if (hotwords != null && !hotwords.isBlank()) params.initialPrompt = hotwords;

        synchronized (whisperLock) {
            try {
                int result = whisper.full(context, params, samples, samples.length);
                if (result != 0) {
                    log.warn("Speech: whisper returned " + result);
                    return null;
                }
                StringBuilder text = new StringBuilder();
                int segments = whisper.fullNSegments(context);
                for (int i = 0; i < segments; i++) {
                    text.append(whisper.fullGetSegmentText(context, i));
                }
                String out = text.toString().trim();
                return out.isEmpty() ? null : out;
            } catch (Throwable t) {
                log.warn("Speech transcription error: " + t);
                return null;
            }
        }
    }

    @Override
    public short[] synthesize(String text) {
        if (text == null || text.isBlank() || !ready()) return null;
        synchronized (piperLock) {
            try {
                short[] pcm = piper.textToAudio(voice, text);
                return Resample.to48k(pcm, voiceRate);
            } catch (Throwable t) {
                log.warn("Speech synthesis error: " + t);
                return null;
            }
        }
    }

    @Override
    public String describe() {
        return "embedded (whisper " + models.whisperModel() + ", Piper " + models.piperVoice() + ")";
    }

    @Override
    public String probe() {
        SpeechModels.Status status = models.status();
        if (!models.ready()) {
            if (status.state() == SpeechModels.State.MISSING) models.ensure(this::load);
            return "models " + models.status().describe();
        }
        if (context != null && voice != null) return null;
        return load() ? null : failure;
    }

    @Override
    public void close() {
        synchronized (whisperLock) {
            if (context != null && whisper != null) {
                try { whisper.free(context); } catch (Throwable ignored) { }
                context = null;
            }
        }
        synchronized (piperLock) {
            if (voice != null) {
                try { voice.close(); } catch (Throwable ignored) { }
                voice = null;
            }
            if (piper != null) {
                try { piper.close(); } catch (Throwable ignored) { }
                piper = null;
            }
        }
    }
}
