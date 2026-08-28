package com.gadgetman.jarvis.listeners;

import com.gadgetman.jarvis.Jarvis;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Set;
import java.util.UUID;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles join greetings and death commentary.
 * Both are optional and controlled by config.
 */
public class PlayerEventListener implements Listener {

    private final Jarvis plugin;
    private final boolean greetEnabled;
    private final boolean deathEnabled;

    // Prevent double-greeting in quick reconnects
    private final Set<UUID> recentlyGreeted =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    public PlayerEventListener(Jarvis plugin) {
        this.plugin       = plugin;
        this.greetEnabled = plugin.getConfig().getBoolean("butler.auto-greet", true);
        this.deathEnabled = plugin.getConfig().getBoolean("butler.death-commentary", true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!greetEnabled) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (recentlyGreeted.contains(uuid)) return;
        recentlyGreeted.add(uuid);

        // Remove from cooldown set after 10 s
        new BukkitRunnable() {
            @Override public void run() { recentlyGreeted.remove(uuid); }
        }.runTaskLater(plugin, 200L);

        boolean firstJoin = !player.hasPlayedBefore();
        int onlineCount   = plugin.getServer().getOnlinePlayers().size();

        new BukkitRunnable() {
            @Override public void run() {
                try {
                    String context = "Player " + player.getName() + " joined the server. "
                            + (firstJoin ? "This is their FIRST time here — make it special." : "They have played before.")
                            + " There are now " + onlineCount + " players online.";

                    String greeting = plugin.getAIConnector()
                            .generateDialogue("greet " + player.getName() + " who just joined", context);

                    new BukkitRunnable() {
                        @Override public void run() {
                            if (player.isOnline()) {
                                player.sendMessage(ChatColor.AQUA + "Jarvis: " + ChatColor.WHITE + greeting);
                            }
                        }
                    }.runTask(plugin);

                } catch (Exception ignored) { /* greeting is non-critical */ }
            }
        }.runTaskAsynchronously(plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!deathEnabled) return;

        Player player     = event.getEntity();
        String deathCause = event.getDeathMessage() != null
                ? event.getDeathMessage()
                : player.getName() + " died somehow";

        new BukkitRunnable() {
            @Override public void run() {
                try {
                    String comment = plugin.getAIConnector().generateDialogue(
                            "deliver a short snarky butler comment on this death: " + deathCause,
                            "Death event in Minecraft. Keep it to one sentence.");

                    new BukkitRunnable() {
                        @Override public void run() {
                            plugin.getServer().broadcastMessage(
                                    ChatColor.AQUA + "Jarvis: " + ChatColor.GRAY + comment);
                        }
                    }.runTask(plugin);

                } catch (Exception ignored) { /* non-critical */ }
            }
        }.runTaskAsynchronously(plugin);
    }
}
