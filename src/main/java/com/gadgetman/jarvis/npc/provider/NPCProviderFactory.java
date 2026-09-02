package com.gadgetman.jarvis.npc.provider;

import com.gadgetman.jarvis.Jarvis;
import org.bukkit.Bukkit;

/**
 * Chooses the NPC backend.
 *
 * <p>Only the Citizens backend exists today, so this always returns it and
 * {@code npc.provider} has one valid value. The indirection is the point: the
 * rest of the plugin talks to {@link INPCProvider}, so a Citizens-free backend
 * can be added here without touching the thirteen classes that drive the NPC.
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
        String providerType = plugin.getConfig().getString("npc.provider", "citizens").toLowerCase();
        if (!"citizens".equals(providerType)) {
            plugin.getLogger().warning("npc.provider is '" + providerType
                    + "', but Citizens is the only backend built so far. Using citizens.");
        }
        return createCitizensProvider();
    }

    /**
     * Create Citizens provider if available.
     * @return CitizensNPCProvider or null if Citizens not installed
     */
    private INPCProvider createCitizensProvider() {
        if (!isCitizensAvailable()) {
            plugin.getLogger().severe("Citizens plugin not found! NPC features disabled.");
            plugin.getLogger().severe("Install Citizens from https://citizensnpcs.co to enable NPC features.");
            return null;
        }

        plugin.getLogger().info("Using Citizens NPC provider");
        return new CitizensNPCProvider(plugin);
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
