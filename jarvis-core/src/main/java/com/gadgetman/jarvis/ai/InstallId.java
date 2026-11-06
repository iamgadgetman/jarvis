package com.gadgetman.jarvis.ai;

import com.gadgetman.jarvis.core.platform.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A random ID for this install, kept in {@code install-id} beside config.yml.
 * Jarvis Cloud binds a license key to it, so a key copied to another server
 * does not work there. It identifies nothing about the owner or the machine.
 */
public final class InstallId {

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{8,64}");
    static final String FILE = "install-id";

    private InstallId() { }

    public static String load(Path dataDir, Log log) {
        Path file = dataDir.resolve(FILE);
        try {
            if (Files.exists(file)) {
                String id = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (VALID.matcher(id).matches()) return id;
                log.warn("Ignoring a malformed " + FILE + " file; making a new one.");
            }
        } catch (IOException e) {
            log.warn("Could not read " + FILE + ": " + e.getMessage());
        }
        String id = UUID.randomUUID().toString();
        try {
            Files.createDirectories(dataDir);
            Files.writeString(file, id + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not save " + FILE + " (" + e.getMessage() + "); Jarvis Cloud keys will need re-pairing after a restart.");
        }
        return id;
    }
}
