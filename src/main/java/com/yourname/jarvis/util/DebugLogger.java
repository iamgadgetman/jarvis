package com.yourname.jarvis.util;

import com.yourname.jarvis.Jarvis;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

public class DebugLogger {

    private final Plugin plugin;
    private boolean enabled;
    private File logFile;

    public DebugLogger(Jarvis plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.enabled = plugin.getConfig().getBoolean("debug.enabled", false);
        String fileName = plugin.getConfig().getString("debug.file", "debug.log");
        if (fileName == null || fileName.isBlank()) {
            fileName = "debug.log";
        }
        plugin.getDataFolder().mkdirs();
        this.logFile = new File(plugin.getDataFolder(), fileName);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        plugin.getConfig().set("debug.enabled", enabled);
        plugin.saveConfig();
        debug("Debug mode " + (enabled ? "enabled" : "disabled") + " via command.");
    }

    public void debug(String message) {
        if (!enabled) return;
        String line = "[DEBUG] " + message;
        plugin.getLogger().info(line);
        appendToFile(Instant.now() + " " + line);
    }

    private void appendToFile(String line) {
        try {
            Files.writeString(logFile.toPath(), line + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to write debug log: " + e.getMessage());
        }
    }
}

