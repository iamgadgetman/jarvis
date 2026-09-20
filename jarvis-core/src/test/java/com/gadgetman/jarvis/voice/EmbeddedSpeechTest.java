package com.gadgetman.jarvis.voice;

import io.github.givimad.piperjni.PiperJNI;
import io.github.givimad.whisperjni.WhisperJNI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
