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
}
