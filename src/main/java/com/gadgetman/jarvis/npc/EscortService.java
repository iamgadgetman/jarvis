package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.Jarvis;
import com.gadgetman.jarvis.npc.provider.INPCProvider;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/**
 * EscortService (v0.6.0) - "Take me home, Jarvis."
 *
 * The player sets a home point (/jarvis home set); "take me home" has Jarvis
 * lead the way there at walking pace — pausing when the player falls behind,
 * torch-lighting dark stretches as he goes. Pairs naturally with defensive
 * guarding: he leads, you follow, nothing sneaks up in the dark.
 */
public class EscortService {

    private final Jarvis plugin;
    private final JarvisNPC host;
    private final INPCProvider provider;
    private final DepositManager data;

    private static final double ARRIVE_DISTANCE = 4.0;
    private static final double WAIT_FOR_PLAYER_DISTANCE = 10.0;
    private static final double LEAD_DISTANCE = 6.0;          // How far ahead he walks
    private static final double LEG_DISTANCE = 40.0;          // One planned stretch of the walk
    private static final double HOP_DISTANCE = 8.0;           // A stalled bound, toward home
    private static final int STALL_HOP_TICKS = 8;
    private static final int TORCH_LIGHT_THRESHOLD = 7;

    public EscortService(Jarvis plugin, JarvisNPC host, DepositManager data) {
        this.plugin = plugin;
        this.host = host;
        this.provider = host.getProvider();
        this.data = data;
    }

    public void setHome(Player player) {
        data.setHome(player, player.getLocation());
        host.say(player, "Home noted, sir. Say the word and I shall lead you back.");
    }

    public void takeHome(Player player) {
        Location home = data.getHome(player);
        if (home == null) {
            host.say(player, "No home on record, sir. Stand where you'd like it and say '/jarvis home set'.");
            return;
        }
        escortTo(player, home,
                "This way, sir. Stay close — I'll light the road.",
                "Home, sir. No casualties — I do like a quiet walk.",
                "Home is in another world, sir — a portal is required first.");
    }

    /**
     * Lead the player to a destination on foot: he walks ahead, waits when they
     * fall behind, and lights the dark stretches.
     *
     * <p>Home was the first destination and for two versions the only one. The
     * walk itself never cared where it was going, so the portal service leads
     * with the same legs, the same waiting, and the same torches.
     *
     * @param departure what he says on setting off
     * @param arrival   what he says on getting there
     * @param wrongWorld what he says when the destination is not in this world;
     *                  he cannot take you through a portal, only to one
     */
    public void escortTo(Player player, Location destination, String departure,
                         String arrival, String wrongWorld) {
        if (!provider.isSpawned(player)) {
            host.say(player, "Summon me first, sir — /jarvis summon.");
            return;
        }
        Location npcLoc = host.getCurrentLocation(player);
        if (destination.getWorld() != npcLoc.getWorld()) {
            host.say(player, wrongWorld);
            return;
        }

        host.stopTask(player);
        host.applyNavigatorDefaults(player, null);
        host.say(player, departure);

        BukkitRunnable task = new BukkitRunnable() {
            int stalled = 0;
            boolean waiting = false;
            boolean nagged = false;
            Location lastPos = null;

            @Override
            public void run() {
                if (!provider.isSpawned(player) || !player.isOnline()) {
                    cancel();
                    host.taskDone(player, this);
                    return;
                }

                Location loc = host.getCurrentLocation(player);
                Location playerLoc = player.getLocation();
                host.pickupNearbyItems(player, loc);

                if (playerLoc.getWorld() != loc.getWorld()) {
                    cancel();
                    host.taskDone(player, this);
                    return;
                }

                // Arrived? (Both of us, ideally)
                if (playerLoc.distance(destination) <= ARRIVE_DISTANCE + 2) {
                    cancel();
                    host.taskDone(player, this);
                    provider.cancelNavigation(player);
                    host.say(player, arrival);
                    return;
                }

                // Wait for a straggling employer
                double playerGap = loc.distance(playerLoc);
                if (playerGap > WAIT_FOR_PLAYER_DISTANCE) {
                    if (!waiting) {
                        waiting = true;
                        provider.cancelNavigation(player);
                        if (!nagged) {
                            nagged = true;
                            host.say(player, "Do keep up, sir.");
                        }
                    }
                    return;
                }
                waiting = false;

                // Light the road
                lightHere(loc);

                // Lead: walk it in legs. Aiming straight at a destination three hundred
                // blocks off does not produce a long path, it produces no path,
                // and a butler who never sets off.
                if (!provider.isNavigating(player) && loc.distance(destination) > ARRIVE_DISTANCE) {
                    provider.navigateTo(player, nextLeg(loc, destination));
                }
                if (loc.distance(playerLoc) > LEAD_DISTANCE && provider.isNavigating(player)) {
                    provider.setNavigationPaused(player, true);
                } else if (provider.isNavigationPaused(player)) {
                    provider.setNavigationPaused(player, false);
                }

                // Stall watchdog
                if (lastPos != null && loc.distance(lastPos) < 0.2 && !waiting
                        && !provider.isNavigationPaused(player)) {
                    stalled++;
                } else {
                    stalled = 0;
                }
                lastPos = loc.clone();

                // Stuck: a short bound TOWARD destination, the way the recovery run does
                // it. This used to teleport him to the player, which is how an
                // escort turned into a butler who walks over and stands there.
                // He can only reach here while within LEAD_DISTANCE of the
                // player -- further ahead and the navigation is paused, which
                // does not count as stalling -- so a hop can never leave you
                // behind.
                if (stalled > STALL_HOP_TICKS) {
                    provider.cancelNavigation(player);
                    provider.teleport(player, hopToward(loc, destination));
                    stalled = 0;
                }
            }
        };

        task.runTaskTimer(plugin, 10L, 20L);
        host.registerTask(player, task);
    }

    /**
     * The next waypoint on the way home: home itself when it is close enough to
     * plan, otherwise a point {@link #LEG_DISTANCE} along the line to it.
     *
     * <p>Citizens' A* is given an iteration budget derived from the navigator's
     * range ({@code mining.navigator-range}, 64 by default), so a distant target
     * does not yield a long path — it yields none, the navigation ends the tick
     * it began, and the NPC stands still. Legs keep every request inside what
     * the pathfinder will actually solve, which is what makes him walk the road
     * rather than appear at your elbow.
     */
    private Location nextLeg(Location from, Location dest) {
        return step(from, dest, LEG_DISTANCE);
    }

    /** A stalled bound in the same direction — short, visible, and never backwards. */
    private Location hopToward(Location from, Location dest) {
        return step(from, dest, HOP_DISTANCE);
    }

    private Location step(Location from, Location dest, double distance) {
        Vector dir = dest.toVector().subtract(from.toVector());
        if (dir.length() <= distance) return dest.clone();

        Location point = from.clone().add(dir.normalize().multiply(distance));
        point.setY(point.getWorld().getHighestBlockYAt(point) + 1);
        // Home is underground, or the surface here is a cliff above us: stay in
        // the current Y band rather than surfacing and walking over the top.
        if (dest.getY() < from.getY() - 4 || from.getY() - point.getY() > 8) {
            point.setY(from.getY());
        }
        return host.findSafeNear(point);
    }

    /** Place a torch at the NPC's feet when the road is spawn-dark. */
    private void lightHere(Location loc) {
        Block block = loc.getBlock();
        if (block.getType() != Material.AIR) return;
        if (block.getLightFromBlocks() > TORCH_LIGHT_THRESHOLD) return;
        Block below = block.getRelative(BlockFace.DOWN);
        if (!below.getType().isSolid()) return;
        block.setType(Material.TORCH);
    }
}
