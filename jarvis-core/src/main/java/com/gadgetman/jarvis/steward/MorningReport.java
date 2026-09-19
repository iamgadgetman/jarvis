package com.gadgetman.jarvis.steward;

import com.gadgetman.jarvis.PlayerRequestManager;
import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Platform;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.platform.events.JoinEvent;
import com.gadgetman.jarvis.core.text.Colors;
import com.gadgetman.jarvis.npc.ButlerHost;

import java.util.StringJoiner;

/**
 * MorningReport (v0.5.0) - "Good evening, sir. TPS 19.8, three guests online."
 *
 * The house AI's status briefing: server health (TPS/MSPT), who's online,
 * world time and weather, what Jarvis is carrying, and pending item requests.
 * Delivered on demand (/jarvis report) and optionally on join.
 */
public class MorningReport {

    private final Platform platform;
    private final ButlerHost host;
    private final PlayerRequestManager requests;
    private final DutyScheduler duties;
    private final boolean reportOnJoin;
    private final long joinDelayTicks;

    public MorningReport(Platform platform, ButlerHost host, PlayerRequestManager requests, DutyScheduler duties) {
        this.platform = platform;
        this.host = host;
        this.requests = requests;
        this.duties = duties;
        Config config = platform.config();
        this.reportOnJoin = config.getBoolean("steward.report-on-join", true);
        this.joinDelayTicks = config.getLong("steward.report-join-delay-ticks", 60L);
        platform.events().on(JoinEvent.class, this::onJoin);
    }

    private void onJoin(JoinEvent event) {
        if (!reportOnJoin) return;
        Owner player = event.who();
        platform.scheduler().later(joinDelayTicks, () -> {
            if (player.isOnline()) {
                deliver(player, true);
            }
        });
    }

    /** Assemble and deliver the report. Compact on join, full on demand. */
    public void deliver(Owner player, boolean compact) {
        World world = platform.world(player.world()).orElse(null);
        if (world == null) return;

        // --- Server health ---
        double tps = platform.tps();
        double mspt = platform.mspt();

        // --- World state ---
        long time = world.time();
        String timeOfDay = time < 6000 ? "morning" : time < 12000 ? "afternoon"
                : time < 13800 ? "evening" : "night";
        String weather = world.isThundering() ? "thundering"
                : world.isRaining() ? "raining" : "clear skies";
        long day = world.fullTime() / 24000L;

        int online = platform.players().online().size();

        // --- Jarvis state ---
        String jarvisLine = null;
        if (host != null && host.butler(player).isSpawned()) {
            int loot = host.lootSlotsUsed(player);
            jarvisLine = loot == 0 ? "My bags are empty and I am at your disposal."
                    : "I'm carrying " + loot + " stacks of your goods"
                      + (host.deposits().hasChest(player)
                         ? " — say the word and I'll deposit them." : ".");
        }

        // --- Pending requests (admins only) ---
        String requestsLine = null;
        if (player.hasPermission("jarvis.admin") && requests != null && requests.hasPending()) {
            requestsLine = requests.getAllRequests().size()
                    + " item request(s) await your review — /jarvis requests.";
        }

        String greeting = "Good " + timeOfDay + ", sir.";

        player.message(Colors.GOLD + "— Jarvis's " + (compact ? "briefing" : "full report") + " —");
        player.message(Colors.jarvis(greeting + " Day " + day + ", " + weather + "."));

        StringJoiner health = new StringJoiner(", ");
        health.add("TPS " + String.format("%.1f", tps));
        health.add(String.format("%.1f", mspt) + " ms/tick");
        health.add(online + (online == 1 ? " player" : " players") + " online");
        String healthColor = tps >= 19.0 ? Colors.GREEN : tps >= 16.0 ? Colors.YELLOW : Colors.RED;
        player.message(Colors.GRAY + "  The estate: " + healthColor + health
                + (tps < 16.0 ? Colors.RED + " — the server is straining, sir." : ""));

        if (jarvisLine != null) {
            player.message(Colors.GRAY + "  Myself: " + Colors.WHITE + jarvisLine);
        }
        if (requestsLine != null) {
            player.message(Colors.GRAY + "  Business: " + Colors.YELLOW + requestsLine);
        }

        if (!compact && duties != null) {
            int count = duties.count();
            if (count > 0) {
                player.message(Colors.GRAY + "  Duties: " + Colors.WHITE + count + " scheduled — /jarvis duties.");
            }
        }
    }
}
