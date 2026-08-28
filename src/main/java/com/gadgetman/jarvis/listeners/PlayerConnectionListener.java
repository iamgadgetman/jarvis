package com.gadgetman.jarvis.listeners;

import com.gadgetman.jarvis.Jarvis;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * PlayerConnectionListener - Handles player disconnect events
 *
 * On quit: Drops NPC inventory items, cleans up all state maps, destroys NPC
 */
public class PlayerConnectionListener implements Listener {

    private final Jarvis plugin;

    public PlayerConnectionListener(Jarvis plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // Handle NPC cleanup - drops inventory items and destroys NPC
        if (plugin.getJarvisNPC() != null) {
            plugin.getJarvisNPC().handlePlayerDisconnect(player);
        }

        plugin.getLogger().fine("Cleaned up Jarvis state for disconnected player: " + player.getName());
    }
}
