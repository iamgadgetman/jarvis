package com.gadgetman.jarvis.voice;

import io.github.givimad.piperjni.PiperJNI;
import io.github.givimad.whisperjni.WhisperJNI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The native libraries are what makes embedded speech a risk, so loading
 * them is the thing to test on every build. The models themselves are not
 * fetched here; that is 200 MB the tests do not need.
 */
class EmbeddedSpeechTest {

    @Test
    @DisplayName("whisper.cpp's native library loads on this platform")
    void whisperLoads() throws Exception {
        WhisperJNI.loadLibrary();
        WhisperJNI.setLibraryLogger(null);
        String info = new WhisperJNI().getSystemInfo();
        assertNotNull(info);
    }

    @Test
    @DisplayName("Piper's native library and espeak data load on this platform")
    void piperLoads() throws Exception {
        try (PiperJNI piper = new PiperJNI()) {
            piper.initialize(true);
            assertTrue(piper.isInitialized());
            assertNotNull(piper.getPiperVersion());
        }
    }

    @Test
    @DisplayName("whisper's audio context is cut to the clip, within whisper's bounds")
    void audioContext() {
        assertEquals(512, EmbeddedSpeech.audioContextFor(16000));          // one second: the floor
        assertEquals(512, EmbeddedSpeech.audioContextFor(16000 * 7));      // 350 + 128 = 478, still the floor
        assertEquals(628, EmbeddedSpeech.audioContextFor(16000 * 10));     // 500 + 128
        assertEquals(1500, EmbeddedSpeech.audioContextFor(16000 * 30));    // the whole window
        assertEquals(1500, EmbeddedSpeech.audioContextFor(16000 * 60));    // never past it
    }

    @Test
    @DisplayName("whisper takes four threads, or half the CPUs on a small machine")
    void threads() {
        assertEquals(2, EmbeddedSpeech.autoThreads(2));
        assertEquals(2, EmbeddedSpeech.autoThreads(4));
        assertEquals(3, EmbeddedSpeech.autoThreads(6));
        assertEquals(4, EmbeddedSpeech.autoThreads(8));
        assertEquals(4, EmbeddedSpeech.autoThreads(16));
    }

    @Test
    @DisplayName("the benchmark tries the counts worth trying, or the one asked for")
    void benchmarkCandidates() {
        assertEquals(java.util.List.of(2, 4, 8), EmbeddedSpeech.candidateThreads(16, 0));
        assertEquals(java.util.List.of(2, 3, 4, 6), EmbeddedSpeech.candidateThreads(6, 0));
        assertEquals(java.util.List.of(2, 4), EmbeddedSpeech.candidateThreads(4, 0));
        assertEquals(java.util.List.of(1, 2), EmbeddedSpeech.candidateThreads(1, 0));
        assertEquals(java.util.List.of(6), EmbeddedSpeech.candidateThreads(16, 6));
    }
}
