package com.yourname.jarvis.building;

import com.yourname.jarvis.Jarvis;
import org.bukkit.entity.Player;

/**
 * BuildingAssistant - Stub implementation for v0.0.5
 * 
 * This is a minimal stub to allow compilation.
 * Full implementation can be added in future versions.
 */
public class BuildingAssistant {
    
    private final Jarvis plugin;
    
    public BuildingAssistant(Jarvis plugin) {
        this.plugin = plugin;
    }
    
    public void startBuild(Player player, String description) {
        player.sendMessage("§cBuilding system not yet implemented in v0.0.5");
        player.sendMessage("§7This feature is planned for a future release");
    }
    
    public void cancelBuild(Player player) {
        player.sendMessage("§cBuilding system not yet implemented in v0.0.5");
    }
}
