package com.gadgetman.jarvis.steward;

import com.gadgetman.jarvis.Jarvis;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.StringJoiner;

/**
 * MorningReport (v0.5.0) - "Good evening, sir. TPS 19.8, three guests online."
 *
 * The house AI's status briefing: server health (TPS/MSPT), who's online,
 * world time and weather, what Jarvis is carrying, and pending item requests.
 * Delivered on demand (/jarvis report) and optionally on join.
 */
public class MorningReport implements Listener {

    private final Jarvis plugin;
    private final boolean reportOnJoin;
    private final long joinDelayTicks;

    public MorningReport(Jarvis plugin) {
        this.plugin = plugin;
        this.reportOnJoin = plugin.getConfig().getBoolean("steward.report-on-join", true);
        this.joinDelayTicks = plugin.getConfig().getLong("steward.report-join-delay-ticks", 60L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!reportOnJoin) return;
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                deliver(player, true);
            }
        }, joinDelayTicks);
    }

    /** Assemble and deliver the report. Compact on join, full on demand. */
    public void deliver(Player player, boolean compact) {
        World world = player.getWorld();

        // --- Server health ---
        double tps = 20.0;
        double[] tpsArr = Bukkit.getTPS();
        if (tpsArr.length > 0) tps = Math.min(20.0, tpsArr[0]);
        double mspt = Bukkit.getAverageTickTime();

        // --- World state ---
        long time = world.getTime();
        String timeOfDay = time < 6000 ? "morning" : time < 12000 ? "afternoon"
                : time < 13800 ? "evening" : "night";
        String weather = world.isThundering() ? "thundering"
                : world.hasStorm() ? "raining" : "clear skies";
        long day = world.getFullTime() / 24000L;

        int online = Bukkit.getOnlinePlayers().size();

        // --- Jarvis state ---
        String jarvisLine = null;
        if (plugin.getJarvisNPC() != null
                && plugin.getJarvisNPC().getNPCForPlayer(player.getUniqueId()) != null) {
            var npc = plugin.getJarvisNPC().getNPCForPlayer(player.getUniqueId());
            int loot = plugin.getJarvisNPC().lootSlotsUsedPublic(npc);
            jarvisLine = loot == 0 ? "My bags are empty and I am at your disposal."
                    : "I'm carrying " + loot + " stacks of your goods"
                      + (plugin.getJarvisNPC().getDepositManager().hasChest(player)
                         ? " — say the word and I'll deposit them." : ".");
        }

        // --- Pending requests (admins only) ---
        String requestsLine = null;
        if (player.hasPermission("jarvis.admin") && plugin.getPlayerRequestManager() != null
                && plugin.getPlayerRequestManager().hasPending()) {
            requestsLine = plugin.getPlayerRequestManager().getAllRequests().size()
                    + " item request(s) await your review — /jarvis requests.";
        }

        String greeting = "Good " + timeOfDay + ", sir.";

        player.sendMessage(Component.text("— Jarvis's " + (compact ? "briefing" : "full report") + " —",
                NamedTextColor.GOLD));
        player.sendMessage(Component.text("Jarvis: ", NamedTextColor.GOLD)
                .append(Component.text(greeting + " Day " + day + ", " + weather + ".",
                        NamedTextColor.WHITE)));

        StringJoiner health = new StringJoiner(", ");
        health.add("TPS " + String.format("%.1f", tps));
        health.add(String.format("%.1f", mspt) + " ms/tick");
        health.add(online + (online == 1 ? " player" : " players") + " online");
        NamedTextColor healthColor = tps >= 19.0 ? NamedTextColor.GREEN
                : tps >= 16.0 ? NamedTextColor.YELLOW : NamedTextColor.RED;
        player.sendMessage(Component.text("  The estate: ", NamedTextColor.GRAY)
                .append(Component.text(health.toString(), healthColor))
                .append(Component.text(tps < 16.0 ? " — the server is straining, sir." : "",
                        NamedTextColor.RED)));

        if (jarvisLine != null) {
            player.sendMessage(Component.text("  Myself: ", NamedTextColor.GRAY)
                    .append(Component.text(jarvisLine, NamedTextColor.WHITE)));
        }
        if (requestsLine != null) {
            player.sendMessage(Component.text("  Business: ", NamedTextColor.GRAY)
                    .append(Component.text(requestsLine, NamedTextColor.YELLOW)));
        }

        if (!compact && plugin.getDutyScheduler() != null) {
            int duties = plugin.getDutyScheduler().count();
            if (duties > 0) {
                player.sendMessage(Component.text("  Duties: ", NamedTextColor.GRAY)
                        .append(Component.text(duties + " scheduled — /jarvis duties.",
                                NamedTextColor.WHITE)));
            }
        }
    }
}
