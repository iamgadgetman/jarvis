package com.gadgetman.jarvis.voice;

import com.gadgetman.jarvis.core.platform.Log;
import io.github.givimad.whisperjni.WhisperJNI;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Getting whisper's native library loaded on servers that are not quite
 * complete. The Linux build of whisper.cpp's math library is linked against
 * libgomp, the GNU OpenMP runtime, which a full distribution has and a slim
 * container image very often does not; the first report from a real Paper
 * server was exactly that. So the jar carries a copy of libgomp for x86_64
 * and arm64 and loads it first when the system has none, which is what
 * the dynamic linker then finds when whisper asks for it by name. When even
 * that does not help, the error says which library is missing and how to
 * install it, rather than leaving an UnsatisfiedLinkError to be decoded.
 */
final class NativeSupport {

    private static final Pattern MISSING = Pattern.compile("(lib[\\w.+-]*\\.so(?:\\.\\d+)*): cannot open shared object file");

    private NativeSupport() { }

    /** Load whisper's native library, with the bundled libgomp as a fallback. */
    static void loadWhisper(Log log) throws IOException {
        try {
            WhisperJNI.loadLibrary();
            return;
        } catch (UnsatisfiedLinkError first) {
            String missing = missingLibrary(first.getMessage());
            if (!"libgomp.so.1".equals(missing) || !isLinux()) throw explain(first, missing);
            Path bundled;
            try {
                bundled = extractBundled("libgomp.so.1");
            } catch (IOException e) {
                throw explain(first, missing);
            }
            if (bundled == null) throw explain(first, missing);
            try {
                System.load(bundled.toString());
                WhisperJNI.loadLibrary();
                log.info("Speech: this server has no libgomp; using the copy carried in the jar");
            } catch (UnsatisfiedLinkError second) {
                throw explain(second, missingLibrary(second.getMessage()));
            }
        }
    }

    /** The shared library a loader error complains about, or null when that is not what it says. */
    static String missingLibrary(String message) {
        if (message == null) return null;
        Matcher m = MISSING.matcher(message);
        return m.find() ? m.group(1) : null;
    }

    /** The same error, with what to do about it appended. */
    static UnsatisfiedLinkError explain(UnsatisfiedLinkError e, String missing) {
        String advice = advice(missing);
        if (advice == null) return e;
        UnsatisfiedLinkError out = new UnsatisfiedLinkError(e.getMessage() + ". " + advice);
        out.initCause(e);
        return out;
    }

    static String advice(String missing) {
        if (missing == null) return null;
        if (missing.startsWith("libgomp")) {
            return "The server is missing libgomp, the GNU OpenMP runtime, which whisper needs. Install it and restart:"
                    + " Debian/Ubuntu: apt install libgomp1; Fedora: dnf install libgomp; Alpine: apk add libgomp;"
                    + " in a container, add it to the image";
        }
        return "The server is missing " + missing + "; install the package that provides it and restart";
    }

    static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    /** The folder under natives/ for this machine, or null when the jar carries nothing for it. */
    static String platformFolder() {
        if (!isLinux()) return null;
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return switch (arch) {
            case "amd64", "x86_64" -> "linux-x86_64";
            case "aarch64", "arm64" -> "linux-aarch64";
            default -> null;
        };
    }

    /** Copy a bundled library out of the jar so the linker can map it. @return its path, or null when not bundled */
    static Path extractBundled(String name) throws IOException {
        String folder = platformFolder();
        if (folder == null) return null;
        try (InputStream in = NativeSupport.class.getResourceAsStream("/natives/" + folder + "/" + name)) {
            if (in == null) return null;
            Path dir = Files.createTempDirectory("jarvis-natives");
            Path out = dir.resolve(name);
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            out.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            return out;
        }
    }
}
