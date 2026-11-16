package com.gadgetman.jarvis.voice;

import com.gadgetman.jarvis.core.platform.Log;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeechModelsTest {

    private static final Log QUIET = new Log() {
        @Override public void info(String msg) { }
        @Override public void warn(String msg) { }
        @Override public void fine(String msg) { }
        @Override public void error(String msg, Throwable t) { }
    };

    @Test
    @DisplayName("a Piper voice id maps to its place in the voices repository")
    void voicePaths() {
        assertEquals("en/en_GB/alan/medium", SpeechModels.piperVoicePath("en_GB-alan-medium"));
        assertEquals("en/en_US/lessac/high", SpeechModels.piperVoicePath("en_US-lessac-high"));
        assertEquals("de/de_DE/thorsten_emotional/medium", SpeechModels.piperVoicePath("de_DE-thorsten_emotional-medium"));
        assertThrows(IllegalArgumentException.class, () -> SpeechModels.piperVoicePath("alan"));
    }

    @Test
    @DisplayName("an empty models folder reads as not downloaded, with the files named for the config")
    void missingUntilFetched(@TempDir Path dir) {
        SpeechModels m = new SpeechModels(dir.resolve("models"), "base.en", "en_GB-alan-medium", QUIET);
        assertFalse(m.ready());
        assertEquals(SpeechModels.State.MISSING, m.status().state());
        assertEquals("ggml-base.en.bin", m.whisperFile().getFileName().toString());
        assertEquals("en_GB-alan-medium.onnx", m.voiceFile().getFileName().toString());
        assertEquals("en_GB-alan-medium.onnx.json", m.voiceConfigFile().getFileName().toString());
        assertEquals(java.util.List.of(
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin",
                "https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/en/en_GB/alan/medium/en_GB-alan-medium.onnx",
                "https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/en/en_GB/alan/medium/en_GB-alan-medium.onnx.json"),
                m.urls());
    }

    @Test
    @DisplayName("a mirror stands in for huggingface.co with the same paths, and the manual route names every file")
    void mirrorAndManualRoute(@TempDir Path dir) {
        SpeechModels m = new SpeechModels(dir.resolve("models"), "tiny.en", "en_US-lessac-high", "http://models.lab:8080/", QUIET);
        assertEquals("http://models.lab:8080/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin", m.urls().get(0));
        assertEquals("http://models.lab:8080/rhasspy/piper-voices/resolve/v1.0.0/en/en_US/lessac/high/en_US-lessac-high.onnx", m.urls().get(1));
        String how = m.manualInstructions();
        assertTrue(how.contains("ggml-tiny.en.bin  from  http://models.lab:8080/"), how);
        assertTrue(how.contains("en_US-lessac-high.onnx.json"), how);
        assertTrue(how.contains(dir.resolve("models").toString()), how);
    }

    /** A stand-in for huggingface.co that counts requests and serves 2 MB per file. */
    private static com.sun.net.httpserver.HttpServer mirror(java.util.concurrent.atomic.AtomicInteger hits) throws java.io.IOException {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            ex.getRequestBody().readAllBytes();
            byte[] body = new byte[2 << 20];
            ex.sendResponseHeaders(200, body.length);
            try (var out = ex.getResponseBody()) { out.write(body); }
        });
        server.start();
        return server;
    }

    @Test
    @DisplayName("ensure never downloads: missing models stay missing and nothing is fetched (store rules)")
    void ensureNeverFetches(@TempDir Path dir) throws Exception {
        var hits = new java.util.concurrent.atomic.AtomicInteger();
        var server = mirror(hits);
        try {
            SpeechModels m = new SpeechModels(dir.resolve("models"), "base.en", "en_GB-alan-medium",
                    "http://127.0.0.1:" + server.getAddress().getPort(), QUIET);
            boolean[] ran = {false};
            m.ensure(() -> ran[0] = true);
            Thread.sleep(200);
            assertEquals(0, hits.get(), "no request without /jarvis voice download confirm");
            assertFalse(ran[0]);
            assertEquals(SpeechModels.State.MISSING, m.status().state());
            assertTrue(m.status().describe().contains("/jarvis voice download"), m.status().describe());
            assertFalse(java.nio.file.Files.exists(dir.resolve("models")), "not even the folder");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("download fetches the three files when asked, once, and another instance sees its progress")
    void downloadWhenAsked(@TempDir Path dir) throws Exception {
        var hits = new java.util.concurrent.atomic.AtomicInteger();
        var server = mirror(hits);
        try {
            String src = "http://127.0.0.1:" + server.getAddress().getPort();
            SpeechModels m = new SpeechModels(dir.resolve("models"), "base.en", "en_GB-alan-medium", src, QUIET);
            SpeechModels other = new SpeechModels(dir.resolve("models"), "base.en", "en_GB-alan-medium", src, QUIET);
            boolean[] ran = {false};
            assertTrue(m.download(() -> ran[0] = true, why -> { }));
            assertFalse(other.download(null, null), "one download per folder");
            m.join();
            assertEquals(3, hits.get());
            assertTrue(ran[0]);
            assertTrue(m.ready());
            assertEquals(SpeechModels.State.READY, other.status().state());
            assertFalse(m.download(null, null), "nothing left to fetch");
            assertEquals(3, hits.get());
            assertEquals("127.0.0.1", m.sourceHost());
            assertEquals(3, m.plannedFiles().size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("a failed download reports why, and a later download can try again")
    void downloadFails(@TempDir Path dir) throws Exception {
        SpeechModels m = new SpeechModels(dir.resolve("models"), "base.en", "en_GB-alan-medium", "http://127.0.0.1:1", QUIET);
        String[] why = {null};
        assertTrue(m.download(null, w -> why[0] = w));
        m.join();
        assertEquals(SpeechModels.State.FAILED, m.status().state());
        assertTrue(why[0] != null && why[0].contains("cannot reach 127.0.0.1"), why[0]);
        assertTrue(m.download(null, null), "a retry is allowed");
        m.join();
    }
}
