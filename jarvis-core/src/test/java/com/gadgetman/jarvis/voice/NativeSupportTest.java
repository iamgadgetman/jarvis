package com.gadgetman.jarvis.voice;

import io.github.givimad.whisperjni.WhisperJNI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NativeSupportTest {

    @Test
    @DisplayName("a loader error names the library it could not find, and the advice names the package")
    void readsTheMissingLibrary() {
        String msg = "/tmp/whisper-jni-2108245438348673/libwhisper-jni.so: libgomp.so.1: cannot open shared object file: No such file or directory";
        assertEquals("libgomp.so.1", NativeSupport.missingLibrary(msg));
        assertEquals("libstdc++.so.6", NativeSupport.missingLibrary("x: libstdc++.so.6: cannot open shared object file: No such file"));
        assertNull(NativeSupport.missingLibrary("wrong ELF class: ELFCLASS32"));
        assertNull(NativeSupport.missingLibrary(null));

        UnsatisfiedLinkError explained = NativeSupport.explain(new UnsatisfiedLinkError(msg), "libgomp.so.1");
        assertTrue(explained.getMessage().contains("apt install libgomp1"), explained.getMessage());
        assertTrue(explained.getMessage().startsWith("/tmp/whisper-jni"), explained.getMessage());
        assertTrue(NativeSupport.advice("libfoo.so.2").contains("missing libfoo.so.2"));
        assertNull(NativeSupport.advice(null));
    }

    @Test
    @DisplayName("the jar carries a libgomp for this Linux machine, and whisper loads on top of it")
    void bundledGompCarriesWhisper() throws Exception {
        assumeTrue(NativeSupport.platformFolder() != null, "only Linux x86_64 and arm64 carry a libgomp");
        Path gomp = NativeSupport.extractBundled("libgomp.so.1");
        assertNotNull(gomp, "no bundled libgomp for " + NativeSupport.platformFolder());
        assertTrue(Files.size(gomp) > 100_000);
        // Loaded first, it is what the linker finds when whisper's math
        // library asks for libgomp.so.1 by name, wherever the system keeps
        // its own. That is the whole of the fallback.
        System.load(gomp.toString());
        WhisperJNI.loadLibrary();
        WhisperJNI.setLibraryLogger(null);
        assertNotNull(new WhisperJNI().getSystemInfo());
    }
}
