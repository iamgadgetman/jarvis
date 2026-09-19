package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.core.platform.Butler;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Site;
import com.gadgetman.jarvis.core.platform.Task;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.BlockState;
import com.gadgetman.jarvis.core.world.Ids;
import com.gadgetman.jarvis.core.world.Vec3;

/**
 * EscortService (v0.6.0) - "Take me home, Jarvis."
 *
 * The player sets a home point (/jarvis home set); "take me home" has Jarvis
 * lead the way there at walking pace — pausing when the player falls behind,
 * torch-lighting dark stretches as he goes. Pairs naturally with defensive
 * guarding: he leads, you follow, nothing sneaks up in the dark.
 */
public class EscortService {

    private final ButlerHost host;
    private final DepositManager data;

    private static final double ARRIVE_DISTANCE = 4.0;
    private static final double WAIT_FOR_PLAYER_DISTANCE = 10.0;
    private static final double LEAD_DISTANCE = 6.0;          // How far ahead he walks
    private static final double LEG_DISTANCE = 40.0;          // One planned stretch of the walk
    private static final double HOP_DISTANCE = 8.0;           // A stalled bound, toward home
    private static final int STALL_HOP_TICKS = 8;
    private static final int TORCH_LIGHT_THRESHOLD = 7;

    public EscortService(ButlerHost host, DepositManager data) {
        this.host = host;
        this.data = data;
    }

    public void setHome(Owner player) {
        World world = host.platform().world(player.world()).orElse(null);
        if (world == null) return;
        data.setHome(player, new Site(world, player.pos()));
        host.say(player, "Home noted, sir. Say the word and I shall lead you back.");
    }

    public void takeHome(Owner player) {
        Site home = data.getHome(player).orElse(null);
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
    public void escortTo(Owner player, Site destination, String departure,
                         String arrival, String wrongWorld) {
        Butler butler = host.butler(player);
        if (!butler.isSpawned()) {
            host.say(player, "Summon me first, sir — /jarvis summon.");
            return;
        }
        World world = butler.world().orElse(null);
        if (world == null || !world.id().equals(destination.world().id())) {
            host.say(player, wrongWorld);
            return;
        }
        Vec3 dest = destination.pos();

        host.stopTask(player);
        butler.applyNavigationDefaults(null);
        host.say(player, departure);

        Task task = host.platform().scheduler().every(10L, 20L, new java.util.function.Consumer<Task>() {
            int stalled = 0;
            boolean waiting = false;
            boolean nagged = false;
            Vec3 lastPos = null;

            @Override
            public void accept(Task self) {
                if (!butler.isSpawned() || !player.isOnline()) {
                    self.cancel();
                    host.taskDone(player, self);
                    return;
                }

                Vec3 loc = butler.pos();
                Vec3 playerLoc = player.pos();
                host.pickupNearbyItems(player, loc);

                if (!player.world().equals(world.id())) {
                    self.cancel();
                    host.taskDone(player, self);
                    return;
                }

                // Arrived? (Both of us, ideally)
                if (playerLoc.distance(dest) <= ARRIVE_DISTANCE + 2) {
                    self.cancel();
                    host.taskDone(player, self);
                    butler.cancelNavigation();
                    host.say(player, arrival);
                    return;
                }

                // Wait for a straggling employer
                double playerGap = loc.distance(playerLoc);
                if (playerGap > WAIT_FOR_PLAYER_DISTANCE) {
                    if (!waiting) {
                        waiting = true;
                        butler.cancelNavigation();
                        if (!nagged) {
                            nagged = true;
                            host.say(player, "Do keep up, sir.");
                        }
                    }
                    return;
                }
                waiting = false;

                // Light the road
                lightHere(world, loc);

                // Lead: walk it in legs. Aiming straight at a destination three hundred
                // blocks off does not produce a long path, it produces no path,
                // and a butler who never sets off.
                if (!butler.isNavigating() && loc.distance(dest) > ARRIVE_DISTANCE) {
                    butler.navigateTo(nextLeg(world, loc, dest));
                }
                if (loc.distance(playerLoc) > LEAD_DISTANCE && butler.isNavigating()) {
                    butler.setNavigationPaused(true);
                } else if (butler.isNavigationPaused()) {
                    butler.setNavigationPaused(false);
                }

                // Stall watchdog
                if (lastPos != null && loc.distance(lastPos) < 0.2 && !waiting
                        && !butler.isNavigationPaused()) {
                    stalled++;
                } else {
                    stalled = 0;
                }
                lastPos = loc;

                // Stuck: a short bound TOWARD destination, the way the recovery run does
                // it. This used to teleport him to the player, which is how an
                // escort turned into a butler who walks over and stands there.
                // He can only reach here while within LEAD_DISTANCE of the
                // player -- further ahead and the navigation is paused, which
                // does not count as stalling -- so a hop can never leave you
                // behind.
                if (stalled > STALL_HOP_TICKS) {
                    butler.cancelNavigation();
                    butler.teleport(hopToward(world, loc, dest));
                    stalled = 0;
                }
            }
        });
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
    private Vec3 nextLeg(World world, Vec3 from, Vec3 dest) {
        return step(world, from, dest, LEG_DISTANCE);
    }

    /** A stalled bound in the same direction — short, visible, and never backwards. */
    private Vec3 hopToward(World world, Vec3 from, Vec3 dest) {
        return step(world, from, dest, HOP_DISTANCE);
    }

    private Vec3 step(World world, Vec3 from, Vec3 dest, double distance) {
        Vec3 dir = dest.subtract(from);
        if (dir.length() <= distance) return dest;

        Vec3 point = from.add(dir.normalize().scale(distance));
        double y = world.highestY(point.block().x(), point.block().z()) + 1;
        // Home is underground, or the surface here is a cliff above us: stay in
        // the current Y band rather than surfacing and walking over the top.
        if (dest.y() < from.y() - 4 || from.y() - y > 8) {
            y = from.y();
        }
        return host.findSafeNear(world, new Vec3(point.x(), y, point.z()));
    }

    /** Place a torch at the NPC's feet when the road is spawn-dark. */
    private void lightHere(World world, Vec3 loc) {
        BlockPos block = loc.block();
        if (!world.block(block).isAir()) return;
        if (world.blockLight(block) > TORCH_LIGHT_THRESHOLD) return;
        if (!world.isSolid(block.below())) return;
        world.setBlock(block, BlockState.of(Ids.TORCH));
    }
}
