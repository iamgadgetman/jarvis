package com.yourname.jarvis.listeners;

import com.yourname.jarvis.Jarvis;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * PlayerConnectionListener - Handles player connect/disconnect events
 *
 * On quit: Saves NPC inventory to database, cleans up all state maps, destroys NPC
 * On join: Notifies player if saved inventory exists from previous session
 */
public class PlayerConnectionListener implements Listener {

    private final Jarvis plugin;

    public PlayerConnectionListener(Jarvis plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // Handle NPC cleanup and inventory save
        if (plugin.getJarvisNPC() != null) {
            plugin.getJarvisNPC().handlePlayerDisconnect(player);
        }

        plugin.getLogger().fine("Cleaned up Jarvis state for disconnected player: " + player.getName());
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Check if player has saved inventory from previous session
        if (plugin.getJarvisNPC() != null && plugin.getDatabaseManager() != null) {
            if (plugin.getDatabaseManager().hasSavedInventory(player.getUniqueId())) {
                // Delay message slightly so it appears after join messages
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    player.sendMessage(ChatColor.GOLD + "[Jarvis] " + ChatColor.YELLOW +
                        "Welcome back! I have your items from last session.");
                    player.sendMessage(ChatColor.GRAY + "Use /jarvis summon to retrieve them.");
                }, 40L); // 2 seconds delay
            }
        }
    }
}
