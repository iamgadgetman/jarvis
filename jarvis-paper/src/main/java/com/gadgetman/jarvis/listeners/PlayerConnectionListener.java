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
 * On quit: forgets the per-session state the Paper side keeps for a player.
 */
public class PlayerConnectionListener implements Listener {

    private final Jarvis plugin;

    public PlayerConnectionListener(Jarvis plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // The NPC itself is cleaned up by core's ButlerService, which
        // subscribes to the platform's QuitEvent; only Paper-side state is here.

        // Idle-remark cooldowns are per-session state; the mute is not, and stays.
        if (plugin.getRemarks() != null) {
            plugin.getRemarks().forget(plugin.owner(player));
        }

        if (plugin.getPortalScout() != null) {
            plugin.getPortalScout().forget(plugin.owner(player));
        }

        plugin.getLogger().fine("Cleaned up Jarvis state for disconnected player: " + player.getName());
    }
}
