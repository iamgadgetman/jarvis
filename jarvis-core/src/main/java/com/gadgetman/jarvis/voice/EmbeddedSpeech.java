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
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

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
    private final boolean debug;

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
    private volatile String lastProblem;

    public EmbeddedSpeech(Config cfg, Log log, Path dataDir) {
        this.log = log;
        this.hotwords = cfg.getString("voice.hotwords", "");
        this.debug = cfg.getBoolean("voice.debug", false);
        int configured = cfg.getInt("voice.whisper-threads", 0);
        this.threads = configured > 0 ? configured : autoThreads(Runtime.getRuntime().availableProcessors());
        Path dir = dataDir.resolve(cfg.getString("voice.models-dir", "models"));
        this.models = new SpeechModels(dir,
                cfg.getString("voice.whisper-model", "base.en"),
                cfg.getString("voice.piper-voice", "en_GB-alan-medium"),
                cfg.getString("voice.models-source", SpeechModels.DEFAULT_SOURCE), log);
    }

    public SpeechModels models() {
        return models;
    }

    /** What the benchmark says, so the numbers are comparable between machines. */
    static final String BENCH_SENTENCE = "Jarvis, build a small cottage by the river.";

    /**
     * Threads for whisper when none are configured: four, or half the
     * logical CPUs when there are fewer than eight. More is not faster on a
     * machine that is also running the game: ggml's worker threads spin
     * while they wait for each other, and once they outnumber the cores
     * that are actually free the whole pass crawls. Four is whisper.cpp's
     * own default; {@code /jarvis voice bench} measures the alternatives.
     */
    static int autoThreads(int cores) {
        return Math.max(2, Math.min(4, cores / 2));
    }

    /** Thread counts worth timing on a machine with {@code cores} CPUs, or the one asked for. */
    static List<Integer> candidateThreads(int cores, int wanted) {
        if (wanted > 0) return List.of(wanted);
        TreeSet<Integer> set = new TreeSet<>(List.of(2, 4, autoThreads(cores), Math.min(8, cores)));
        set.removeIf(n -> n > Math.max(2, cores));
        return List.copyOf(set);
    }

    /**
     * How much of the encoder to run. whisper.cpp always pads audio to a
     * thirty-second window and encodes all of it, 1500 positions, however
     * short the clip; a three-word order spends most of its time encoding
     * silence. Cutting the context to what the clip needs (fifty positions a
     * second, with room to spare) is the single biggest saving available and
     * costs nothing in accuracy for short speech.
     */
    static int audioContextFor(int samples16k) {
        int needed = (int) Math.ceil(samples16k / 16000.0 * 50) + 128;
        return Math.max(512, Math.min(1500, needed));
    }

    @Override
    public void warmUp() {
        // Loading the models takes seconds and may run at the first packet
        // or at enable time, both on threads the server is waiting on.
        Thread t = new Thread(() -> models.ensure(this::load), "jarvis-speech-load");
        t.setDaemon(true);
        t.start();
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
        // The first pass through a fresh context allocates its working
        // memory; taking that hit here, on the loading thread, keeps it out
        // of the first order. It also puts a baseline number in the log.
        Run warm = recognise(new float[16000 + 1600], threads);
        if (warm != null) {
            log.info("Speech: ready; a warm run took " + warm.ms() + " ms on " + threads + " threads");
        }
        return true;
    }

    /** One recognition: what was heard (null for silence) and how long it took. */
    record Run(String text, long ms) { }

    private boolean ready() {
        if (!models.ready()) {
            if (!warnedNotReady) {
                warnedNotReady = true;
                log.warn("Speech models are not here yet (" + models.status().describe() + "); he cannot hear until they are.");
            }
            models.ensure(this::load);
            lastProblem = "the speech models are " + models.status().describe();
            return false;
        }
        if ((context != null && voice != null) || load()) {
            lastProblem = null;
            return true;
        }
        lastProblem = "the speech engines could not be loaded (" + failure + ")";
        return false;
    }

    @Override
    public String lastProblem() { return lastProblem; }

    @Override
    public String transcribe(short[] pcm48k) {
        if (pcm48k == null || pcm48k.length == 0 || !ready()) return null;
        // whisper refuses under a second of audio; a short "come" is padded out.
        Run run = recognise(Resample.to16k(Resample.padTo(pcm48k, 48000 + 4800)), threads);
        return run == null ? null : run.text();
    }

    private Run recognise(float[] samples, int nThreads) {
        WhisperFullParams params = new WhisperFullParams(WhisperSamplingStrategy.GREEDY);
        params.language = "en";
        params.nThreads = nThreads;
        params.audioCtx = audioContextFor(samples.length);
        // One pass, one answer. The binding's defaults retry at rising
        // temperatures whenever the decoder is unsure, and each retry is a
        // whole extra decode; an order misheard is cheaper to repeat than to
        // wait for. A single segment is all one sentence needs.
        params.temperature = 0f;
        params.temperatureInc = 0f;
        params.greedyBestOf = 1;
        params.singleSegment = true;
        params.noContext = true;
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
            if (context == null) return null;
            try {
                long started = System.nanoTime();
                int result = whisper.full(context, params, samples, samples.length);
                long ms = (System.nanoTime() - started) / 1_000_000;
                if (debug) {
                    log.info(String.format("Speech: whisper took %d ms for %.1f s of audio (context %d, %d threads)",
                            ms, samples.length / 16000.0, params.audioCtx, nThreads));
                }
                if (result != 0) {
                    log.warn("Speech: whisper returned " + result);
                    lastProblem = "whisper returned " + result;
                    return null;
                }
                StringBuilder text = new StringBuilder();
                int segments = whisper.fullNSegments(context);
                for (int i = 0; i < segments; i++) {
                    text.append(whisper.fullGetSegmentText(context, i));
                }
                String out = text.toString().trim();
                return new Run(out.isEmpty() ? null : out, ms);
            } catch (Throwable t) {
                log.warn("Speech transcription error: " + t);
                lastProblem = "whisper failed (" + t + ")";
                return null;
            }
        }
    }

    @Override
    public List<String> benchmark(int wanted) {
        if (!ready()) {
            return List.of("The engines are not loaded: " + (failure != null ? failure : "models " + models.status().describe()));
        }
        short[] pcm;
        long synthMs;
        synchronized (piperLock) {
            try {
                long started = System.nanoTime();
                pcm = Resample.to48k(piper.textToAudio(voice, BENCH_SENTENCE), voiceRate);
                synthMs = (System.nanoTime() - started) / 1_000_000;
            } catch (Throwable t) {
                return List.of("Piper could not make the test sentence: " + t);
            }
        }
        float[] samples = Resample.to16k(Resample.padTo(pcm, 48000 + 4800));
        int cores = Runtime.getRuntime().availableProcessors();
        List<String> out = new ArrayList<>();
        out.add(String.format("whisper %s on %.1f s of Piper speech (made in %d ms), context %d, %d CPUs, %s",
                models.whisperModel(), samples.length / 16000.0, synthMs, audioContextFor(samples.length), cores, simd()));
        int best = -1;
        long bestMs = Long.MAX_VALUE;
        String heard = null;
        for (int n : candidateThreads(cores, wanted)) {
            Run first = recognise(samples, n);
            Run second = first == null ? null : recognise(samples, n);
            if (second == null) {
                out.add(n + " threads: failed");
                continue;
            }
            out.add(n + " threads: " + VoiceTimings.format(first.ms()) + " then " + VoiceTimings.format(second.ms()));
            long ms = Math.min(first.ms(), second.ms());
            if (ms < bestMs) {
                bestMs = ms;
                best = n;
                heard = second.text();
            }
        }
        if (best > 0) {
            out.add("Fastest: " + best + " threads"
                    + (best == threads ? ", which is what he uses" : "; /jarvis voice threads " + best + " makes it so")
                    + ". Heard: \"" + (heard == null ? "" : heard) + "\"");
        }
        return out;
    }

    /** The vector instructions the bundled whisper build uses, from its own report. */
    private String simd() {
        try {
            String info = whisper.getSystemInfo();
            List<String> on = new ArrayList<>();
            for (String flag : List.of("AVX2", "AVX512", "FMA", "F16C", "NEON", "BLAS")) {
                if (info.matches("(?s).*\\b" + flag + " = 1.*")) on.add(flag);
            }
            return on.isEmpty() ? "no vector instructions in use (a slow build for this CPU)" : "using " + String.join(", ", on);
        } catch (Throwable t) {
            return "";
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
