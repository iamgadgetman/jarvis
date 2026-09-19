package com.gadgetman.jarvis.platform;

import com.gadgetman.jarvis.core.platform.Config;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Set;

/**
 * Core's {@link Config} over the plugin's own config.yml.
 *
 * <p>Reads through to {@code plugin.getConfig()} on every call rather than
 * holding the section, so {@code /jarvis reload} (which swaps the underlying
 * FileConfiguration) is seen by every view immediately, and the defaults
 * layered from the bundled config.yml keep working.
 */
public final class PaperConfig implements Config {

    private final JavaPlugin plugin;
    private final String prefix;   // "" at the root, "ai." for section("ai")

    public PaperConfig(JavaPlugin plugin) {
        this(plugin, "");
    }

    private PaperConfig(JavaPlugin plugin, String prefix) {
        this.plugin = plugin;
        this.prefix = prefix;
    }

    private String full(String path) {
        return prefix + path;
    }

    private ConfigurationSection self() {
        if (prefix.isEmpty()) return plugin.getConfig();
        return plugin.getConfig().getConfigurationSection(prefix.substring(0, prefix.length() - 1));
    }

    @Override public String getString(String path, String def) { return plugin.getConfig().getString(full(path), def); }
    @Override public int getInt(String path, int def) { return plugin.getConfig().getInt(full(path), def); }
    @Override public long getLong(String path, long def) { return plugin.getConfig().getLong(full(path), def); }
    @Override public double getDouble(String path, double def) { return plugin.getConfig().getDouble(full(path), def); }
    @Override public boolean getBoolean(String path, boolean def) { return plugin.getConfig().getBoolean(full(path), def); }
    @Override public List<String> getStringList(String path) { return plugin.getConfig().getStringList(full(path)); }
    @Override public boolean contains(String path) { return plugin.getConfig().contains(full(path)); }
    @Override public boolean isSection(String path) { return plugin.getConfig().isConfigurationSection(full(path)); }

    @Override
    public Set<String> keys() {
        ConfigurationSection s = self();
        return s == null ? Set.of() : s.getKeys(false);
    }

    @Override
    public Config section(String path) {
        return new PaperConfig(plugin, full(path) + ".");
    }
}
