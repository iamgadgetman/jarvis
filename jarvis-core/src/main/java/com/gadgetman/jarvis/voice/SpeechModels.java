package com.gadgetman.jarvis.voice;

import com.gadgetman.jarvis.core.platform.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;

/**
 * The model files the embedded engine needs: a whisper.cpp model for
 * listening (about 150 MB for {@code base.en}) and a Piper voice for speaking
 * (about 60 MB). They live under the data directory, next to the config.
 * They are too big to bundle in the jar, and nothing fetches them on its own:
 * an admin either puts the files there ({@link #manualInstructions}) or runs
 * {@code /jarvis voice download}, which says what it will fetch and from
 * where before it starts ({@link #download}). Store rules (Modrinth) don't
 * allow a plugin to download content after install without being asked.
 */
public final class SpeechModels {

    public enum State { READY, MISSING, DOWNLOADING, FAILED }

    /** Where things stand, for the status report. */
    public record Status(State state, int percent, String detail) {
        public String describe() {
            return switch (state) {
                case READY -> "ready";
                case MISSING -> "not installed (an admin can run /jarvis voice download)";
                case DOWNLOADING -> "downloading, " + percent + "%" + (detail == null ? "" : " (" + detail + ")");
                case FAILED -> "download failed: " + detail;
            };
        }
    }

    private record Item(String label, String url, Path target) { }

    private static final long MIN_MODEL_BYTES = 1L << 20;
    /** Roughly what {@link #download} fetches with the default model and voice. */
    public static final String DEFAULT_SIZE = "about 210 MB";
    /** One download per models folder, whichever instance asked: the command's or the engine's. */
    private static final java.util.concurrent.ConcurrentHashMap<Path, SpeechModels> ACTIVE =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Where both repositories live; a mirror that keeps the same paths can stand in. */
    public static final String DEFAULT_SOURCE = "https://huggingface.co";

    private final Log log;
    private final String whisperModel;
    private final String piperVoice;
    private final Path whisperFile;
    private final Path voiceFile;
    private final Path voiceConfigFile;
    private final List<Item> items;

    private volatile Status status;
    private Thread download;

    /**
     * @param dir          where the files live, created on demand
     * @param whisperModel a whisper.cpp model name such as {@code base.en}
     * @param piperVoice   a Piper voice id such as {@code en_GB-alan-medium}
     */
    public SpeechModels(Path dir, String whisperModel, String piperVoice, Log log) {
        this(dir, whisperModel, piperVoice, DEFAULT_SOURCE, log);
    }

    /** @param source the host both repositories are fetched from; {@link #DEFAULT_SOURCE} or a mirror with the same paths */
    public SpeechModels(Path dir, String whisperModel, String piperVoice, String source, Log log) {
        this.log = log;
        this.whisperModel = whisperModel;
        this.piperVoice = piperVoice;
        this.whisperFile = dir.resolve("ggml-" + whisperModel + ".bin");
        this.voiceFile = dir.resolve(piperVoice + ".onnx");
        this.voiceConfigFile = dir.resolve(piperVoice + ".onnx.json");
        String base = source == null || source.isBlank() ? DEFAULT_SOURCE : source.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String voicePath = piperVoicePath(piperVoice);
        this.items = List.of(
                new Item("whisper " + whisperModel,
                        base + "/ggerganov/whisper.cpp/resolve/main/ggml-" + whisperModel + ".bin",
                        whisperFile),
                new Item("voice " + piperVoice,
                        base + "/rhasspy/piper-voices/resolve/v1.0.0/" + voicePath + "/" + piperVoice + ".onnx",
                        voiceFile),
                new Item("voice config " + piperVoice,
                        base + "/rhasspy/piper-voices/resolve/v1.0.0/" + voicePath + "/" + piperVoice + ".onnx.json",
                        voiceConfigFile));
        this.status = ready() ? new Status(State.READY, 100, null) : new Status(State.MISSING, 0, null);
    }

    /**
     * Where a voice lives in the Piper voices repository: {@code en_GB-alan-medium}
     * is at {@code en/en_GB/alan/medium}.
     */
    static String piperVoicePath(String voice) {
        String[] parts = voice.split("-");
        if (parts.length < 3) throw new IllegalArgumentException("A Piper voice looks like en_GB-alan-medium, not '" + voice + "'");
        String locale = parts[0];
        String family = locale.contains("_") ? locale.substring(0, locale.indexOf('_')) : locale;
        String name = String.join("-", java.util.Arrays.copyOfRange(parts, 1, parts.length - 1));
        String quality = parts[parts.length - 1];
        return family + "/" + locale + "/" + name + "/" + quality;
    }

    /** The three URLs, in fetch order, for the log and for tests. */
    List<String> urls() { return items.stream().map(Item::url).toList(); }

    /**
     * How to get the files here without this server reaching out: for a
     * server whose outbound traffic is filtered, or one with no route at all.
     */
    public String manualInstructions() {
        StringBuilder sb = new StringBuilder("Fetch them on any machine and put them in " + whisperFile.getParent() + ":");
        for (Item item : items) sb.append("\n  ").append(item.target.getFileName()).append("  from  ").append(item.url);
        sb.append("\nOr point voice.models-source at a mirror that keeps the same paths. A proxy set on the JVM"
                + " (-Dhttps.proxyHost, -Dhttps.proxyPort) is honoured.");
        return sb.toString();
    }

    public String whisperModel() { return whisperModel; }
    public String piperVoice() { return piperVoice; }
    public Path whisperFile() { return whisperFile; }
    public Path voiceFile() { return voiceFile; }
    public Path voiceConfigFile() { return voiceConfigFile; }

    public boolean ready() {
        return present(whisperFile) && present(voiceFile) && Files.isRegularFile(voiceConfigFile);
    }

    private static boolean present(Path p) {
        try {
            return Files.isRegularFile(p) && Files.size(p) >= MIN_MODEL_BYTES;
        } catch (IOException e) {
            return false;
        }
    }

    public Status status() {
        SpeechModels running = ACTIVE.get(whisperFile);
        if (running != null && running != this) return running.status;
        if (status.state() != State.DOWNLOADING && status.state() != State.FAILED) {
            status = ready() ? new Status(State.READY, 100, null) : new Status(State.MISSING, 0, null);
        }
        return status;
    }

    /** What {@link #download} would fetch, one line per file: name and URL. */
    public List<String> plannedFiles() {
        return items.stream().map(i -> i.target.getFileName() + "  from  " + i.url).toList();
    }

    /** The host the files come from, e.g. {@code huggingface.co}. */
    public String sourceHost() {
        return URI.create(items.get(0).url).getHost();
    }

    /**
     * Run {@code onReady} on the caller's thread when the files are all here.
     * When they are not, nothing happens: this never downloads (see
     * {@link #download}).
     */
    public synchronized void ensure(Runnable onReady) {
        if (ready()) {
            status = new Status(State.READY, 100, null);
            if (onReady != null) onReady.run();
        } else if (status.state() == State.READY) {
            status = new Status(State.MISSING, 0, null);
        }
    }

    /**
     * Fetch whatever is missing, on a thread of its own, then run
     * {@code onReady} there; {@code onFail} gets the reason if it fails. Only
     * an admin's explicit {@code /jarvis voice download confirm} calls this.
     *
     * @return false when the files are already here or a download is running
     */
    public synchronized boolean download(Runnable onReady, java.util.function.Consumer<String> onFail) {
        if (ready()) {
            status = new Status(State.READY, 100, null);
            return false;
        }
        if (ACTIVE.putIfAbsent(whisperFile, this) != null) return false;
        status = new Status(State.DOWNLOADING, 0, "starting");
        download = new Thread(() -> {
            try {
                fetchAll(onReady, onFail);
            } finally {
                ACTIVE.remove(whisperFile, this);
            }
        }, "jarvis-speech-models");
        download.setDaemon(true);
        download.start();
        return true;
    }

    /** Wait for a running download; for tests. */
    void join() throws InterruptedException {
        Thread t = download;
        if (t != null) t.join();
    }

    private void fetchAll(Runnable onReady, java.util.function.Consumer<String> onFail) {
        try {
            Files.createDirectories(whisperFile.getParent());
            HttpClient http = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(20))
                    .build();
            for (Item item : items) {
                if (item.target.equals(voiceConfigFile) ? Files.isRegularFile(item.target) : present(item.target)) continue;
                fetch(http, item);
            }
            status = new Status(State.READY, 100, null);
            log.info("Speech models ready in " + whisperFile.getParent());
            if (onReady != null) onReady.run();
        } catch (Exception e) {
            status = new Status(State.FAILED, 0, e.getMessage() == null ? e.toString() : e.getMessage());
            log.warn("Speech models could not be fetched: " + status.detail()
                    + ". Run /jarvis voice download to try again. " + manualInstructions());
            if (onFail != null) onFail.accept(status.detail());
        }
    }

    private void fetch(HttpClient http, Item item) throws Exception {
        log.info("Fetching " + item.label + " from " + item.url);
        status = new Status(State.DOWNLOADING, 0, item.label);
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(item.url))
                .timeout(Duration.ofMinutes(30)).GET().build();
        HttpResponse<InputStream> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            // "ConnectException" alone says nothing about which host, or why.
            String why = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName()
                    : e.getClass().getSimpleName() + ", " + e.getMessage();
            throw new IOException("this server cannot reach " + URI.create(item.url).getHost()
                    + " for " + item.label + " (" + why + ")", e);
        }
        if (res.statusCode() != 200) {
            throw new IOException("HTTP " + res.statusCode() + " for " + item.url);
        }
        long total = res.headers().firstValueAsLong("Content-Length").orElse(-1);
        Path part = item.target.resolveSibling(item.target.getFileName() + ".part");
        long done = 0;
        int lastPct = -10;
        try (InputStream in = res.body(); OutputStream out = Files.newOutputStream(part)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    int pct = (int) (done * 100 / total);
                    if (pct >= lastPct + 10) {
                        lastPct = pct;
                        status = new Status(State.DOWNLOADING, pct, item.label);
                        log.info(item.label + ": " + pct + "%");
                    }
                }
            }
        }
        Files.move(part, item.target, StandardCopyOption.REPLACE_EXISTING);
    }
}
