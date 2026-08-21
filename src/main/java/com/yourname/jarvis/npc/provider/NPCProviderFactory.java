package com.yourname.jarvis.npc.provider;

import com.yourname.jarvis.Jarvis;
import com.yourname.jarvis.npc.custom.CustomNPCProvider;
import org.bukkit.Bukkit;

/**
 * Factory for creating and selecting NPC providers.
 * Supports auto-detection, Citizens, and custom providers.
 */
public class NPCProviderFactory {

    private final Jarvis plugin;

    public NPCProviderFactory(Jarvis plugin) {
        this.plugin = plugin;
    }

    /**
     * Create an NPC provider based on configuration.
     *
     * Config options:
     * - "auto": Use Citizens if available, fall back to custom
     * - "citizens": Require Citizens (returns null if not available)
     * - "custom": Use custom provider (no Citizens required)
     *
     * @return The selected NPC provider, or null if unavailable
     */
    public INPCProvider createProvider() {
        String providerType = plugin.getConfig().getString("npc.provider", "auto").toLowerCase();

        plugin.getLogger().info("NPC provider requested: " + providerType);

        return switch (providerType) {
            case "citizens" -> createCitizensProvider();
            case "custom" -> createCustomProvider();
            case "auto" -> createAutoProvider();
            default -> {
                plugin.getLogger().warning("Unknown NPC provider type: " + providerType + ", using auto");
                yield createAutoProvider();
            }
        };
    }

    /**
     * Create Citizens provider if available.
     * @return CitizensNPCProvider or null if Citizens not installed
     */
    private INPCProvider createCitizensProvider() {
        if (!isCitizensAvailable()) {
            plugin.getLogger().severe("Citizens plugin not found! NPC features disabled.");
            plugin.getLogger().severe("Install Citizens or set npc.provider to 'custom' in config.yml");
            return null;
        }

        plugin.getLogger().info("Using Citizens NPC provider");
        return new CitizensNPCProvider(plugin);
    }

    /**
     * Create the custom (Citizens-free) provider.
     * @return CustomNPCProvider
     */
    private INPCProvider createCustomProvider() {
        plugin.getLogger().info("Using Custom NPC provider (Citizens-free)");
        return new CustomNPCProvider(plugin);
    }

    /**
     * Auto-detect the best available provider.
     * Prefers Citizens if available, falls back to custom.
     * @return The best available provider
     */
    private INPCProvider createAutoProvider() {
        if (isCitizensAvailable()) {
            plugin.getLogger().info("Auto-detected Citizens, using Citizens provider");
            return new CitizensNPCProvider(plugin);
        } else {
            plugin.getLogger().info("Citizens not found, using Custom NPC provider");
            return new CustomNPCProvider(plugin);
        }
    }

    /**
     * Check if Citizens plugin is installed and enabled.
     */
    public boolean isCitizensAvailable() {
        return Bukkit.getPluginManager().getPlugin("Citizens") != null;
    }

    /**
     * Get information about available providers.
     * @return Status string for debugging
     */
    public String getAvailabilityInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("NPC Provider Status:\n");
        sb.append("  - Citizens: ").append(isCitizensAvailable() ? "Available" : "Not Found").append("\n");
        sb.append("  - Custom: Always Available\n");
        sb.append("  - Config: ").append(plugin.getConfig().getString("npc.provider", "auto"));
        return sb.toString();
    }
}
