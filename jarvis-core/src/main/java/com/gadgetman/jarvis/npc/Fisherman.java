package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.core.platform.Butler;
import com.gadgetman.jarvis.core.platform.Entity;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Site;
import com.gadgetman.jarvis.core.platform.Task;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.Ids;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.core.world.Vec3;
import com.gadgetman.jarvis.progression.ServiceRecord;
import com.gadgetman.jarvis.recovery.TaskFailure;

import java.util.Random;

/**
 * Fisherman (v0.7.0) - "Do some fishing, Jarvis."
 *
 * Real fishing mechanics aren't available to NPCs (the bobber belongs to
 * real players), so this is a faithful simulation: he finds the water's
 * edge, faces the water, casts with a rod swing and the bobber sound, waits
 * a realistic 10–25 seconds, then a splash — and the catch flies out of the
 * water to him. Loot follows vanilla-ish odds: 85% fish, 10% junk, 5%
 * treasure. Continues until told to stop or his bags fill.
 */
class Fisherman {

    private static final Random RANDOM = new Random();

    private static final String[] FISH = {
            Ids.COD, Ids.COD, Ids.COD,                 // ~60% of fish
            Ids.SALMON, Ids.SALMON,                    // ~25%
            Ids.PUFFERFISH,                            // ~13%
            Ids.TROPICAL_FISH                          // rare
    };
    private static final String[] JUNK = {
            Ids.STICK, Ids.BOWL, Ids.STRING,
            Ids.LEATHER_BOOTS, Ids.ROTTEN_FLESH, Ids.INK_SAC
    };
    private static final String[] TREASURE = {
            Ids.NAME_TAG, Ids.SADDLE, Ids.BOW,
            Ids.NAUTILUS_SHELL, Ids.BOOK
    };

    private final ButlerHost host;
    private final Owner player;
    private final Butler butler;
    private final DepositManager deposits;

    private World world;
    private BlockPos waterSpot = null;    // The block of water he's fishing in
    private int catches = 0;
    private int waitTicks = 0;            // Countdown to the next bite (20-tick loop units)

    Fisherman(ButlerHost host, Owner player, DepositManager deposits) {
        this.host = host;
        this.player = player;
        this.butler = host.butler(player);
        this.deposits = deposits;
    }

    void start() {
        world = butler.world().orElse(null);
        Vec3 npcLoc = host.currentLocation(player);
        if (world == null || npcLoc == null) return;
        BlockPos edge = findWaterEdge(npcLoc.block());
        if (edge == null) {
            // Nothing to recover to — but "no water here" is worth saying with
            // the surroundings in it rather than as a stock line.
            host.reportFailure(TaskFailure.of(player, "fish")
                    .step("looking for somewhere to cast from")
                    .reason("no water edge found within the search radius")
                    .say("No fishable water nearby, sir. A pond would be a start.")
                    .where(new Site(world, npcLoc))
                    .build());
            return;
        }

        butler.applyNavigationDefaults(null);
        host.equipTool(player, Ids.FISHING_ROD);
        host.say(player, "A spot of fishing, sir. Excellent choice — I find it centres one.");

        Vec3 edgeFoot = edge.standing();
        Task task = host.platform().scheduler().every(10L, 20L, new java.util.function.Consumer<Task>() {
            boolean inPosition = false;
            int stalled = 0;
            Vec3 lastPos = null;

            @Override
            public void accept(Task self) {
                if (!butler.isSpawned() || !player.isOnline()) {
                    self.cancel();
                    host.taskDone(player, self);
                    return;
                }
                Vec3 loc = butler.pos();
                host.pickupNearbyItems(player, loc);

                // Bags full — deliver and stop (fishing is leisure, not a shift)
                if (host.lootSlotsUsed(player) >= host.lootCapacity() - 2) {
                    self.cancel();
                    host.taskDone(player, self);
                    host.say(player, "The bags are full of fish, sir — " + catches
                            + " catches. A fine session.");
                    if (deposits.hasChest(player)) {
                        deposits.startDepositRun(player, deposits.getChest(player).orElseThrow(), () -> {});
                    }
                    return;
                }

                // Get to the water's edge
                if (!inPosition) {
                    double dist = loc.distance(edgeFoot);
                    if (dist <= 1.5) {
                        inPosition = true;
                        butler.cancelNavigation();
                        cast(loc);
                        return;
                    }
                    if (!butler.isNavigating()) {
                        butler.navigateTo(edge.above().standing());
                    }
                    if (lastPos != null && loc.distance(lastPos) < 0.15) stalled++;
                    else stalled = 0;
                    lastPos = loc;
                    if (stalled > 6) {
                        butler.cancelNavigation();
                        butler.teleport(host.findSafeNear(world, edge.above().standing()));
                        stalled = 0;
                    }
                    return;
                }

                // Waiting on a bite
                if (waitTicks > 0) {
                    waitTicks--;
                    // Idle ripples so it looks alive
                    if (waterSpot != null && RANDOM.nextInt(3) == 0) {
                        world.particle(waterSpot.center().add(0, 0.5, 0), Ids.PARTICLE_SPLASH, 2, 0.2);
                    }
                    if (waitTicks == 0) {
                        reelIn();
                        cast(loc);
                    }
                }
            }
        });
        host.registerTask(player, task);
    }

    private void cast(Vec3 npcLoc) {
        waterSpot = pickWaterSpot(npcLoc);
        if (waterSpot == null) {
            // v0.8.0: no castable water in view — retry shortly instead of
            // standing there forever with the rod raised (the old wedge)
            waitTicks = 3;
            return;
        }

        butler.lookAt(waterSpot.above().standing());
        butler.swing();
        world.sound(npcLoc, Ids.SOUND_ENTITY_FISHING_BOBBER_THROW, 0.8f, 1.0f);

        waitTicks = 10 + RANDOM.nextInt(16); // 10–25 seconds at the 20-tick loop
    }

    private void reelIn() {
        if (waterSpot == null || !butler.isSpawned()) return;
        Vec3 splash = waterSpot.center().add(0, 0.5, 0);

        world.sound(splash, Ids.SOUND_ENTITY_FISHING_BOBBER_SPLASH, 1.0f, 1.0f);
        world.particle(splash, Ids.PARTICLE_SPLASH, 12, 0.3);

        // Roll the catch
        String caught;
        int roll = RANDOM.nextInt(100);
        boolean treasure = false;
        if (roll < 85) caught = FISH[RANDOM.nextInt(FISH.length)];
        else if (roll < 95) caught = JUNK[RANDOM.nextInt(JUNK.length)];
        else { caught = TREASURE[RANDOM.nextInt(TREASURE.length)]; treasure = true; }

        // The catch arcs out of the water toward him
        Entity item = world.dropItem(splash, Item.of(caught, 1));
        Vec3 toNpc = butler.pos().subtract(splash).normalize().scale(0.3);
        item.setVelocity(new Vec3(toNpc.x(), 0.35, toNpc.z()));

        catches++;
        host.credit(player, ServiceRecord.Discipline.FISHING, 1);
        butler.swing();

        if (treasure) {
            host.say(player, "Well now — a " + Blocks.pretty(caught)
                    + " from the depths, sir. The lake provides.");
            Entertainer.celebrate(host, player);
        } else if (catches % 10 == 0) {
            host.sayQuiet(player, catches + " catches and counting.");
        }
    }

    /**
     * A water block 2–4 blocks out, with air above. v0.8.0: probes the facing
     * direction first, then all four compass directions — he no longer wedges
     * when he arrives at the edge facing the wrong way.
     */
    private BlockPos pickWaterSpot(Vec3 npcLoc) {
        Vec3 d = butler.look().direction();
        Vec3 facing = new Vec3(d.x(), 0, d.z());
        if (facing.length() < 0.1) facing = new Vec3(1, 0, 0);
        facing = facing.normalize();

        Vec3[] probes = {
                facing,
                new Vec3(1, 0, 0), new Vec3(-1, 0, 0),
                new Vec3(0, 0, 1), new Vec3(0, 0, -1)
        };
        for (Vec3 dir : probes) {
            for (int out = 2; out <= 4; out++) {
                BlockPos probe = npcLoc.add(dir.scale(out)).block();
                for (int dy = 0; dy >= -3; dy--) {
                    BlockPos b = probe.offset(0, dy, 0);
                    if (Ids.WATER.equals(world.block(b).id()) && world.block(b.above()).isAir()) {
                        return b;
                    }
                }
            }
        }
        return null;
    }

    /** A standable block adjacent to water, within 10 blocks. */
    private BlockPos findWaterEdge(BlockPos center) {
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int x = -10; x <= 10; x++) {
            for (int y = -4; y <= 3; y++) {
                for (int z = -10; z <= 10; z++) {
                    BlockPos stand = center.offset(x, y, z);
                    if (!world.isSolid(stand)) continue;
                    BlockPos above = stand.above();
                    if (!world.block(above).isAir() || !world.block(above.above()).isAir()) continue;

                    boolean nearWater = false;
                    for (int[] f : new int[][]{{0, -1}, {0, 1}, {1, 0}, {-1, 0}}) {
                        if (Ids.WATER.equals(world.block(stand.offset(f[0], 0, f[1])).id())
                                || Ids.WATER.equals(world.block(above.offset(f[0], 0, f[1])).id())) {
                            nearWater = true;
                            break;
                        }
                    }
                    if (!nearWater) continue;

                    double dd = x * x + y * y + z * z;
                    if (dd < bestDistSq) {
                        bestDistSq = dd;
                        best = above;
                    }
                }
            }
        }
        return best;
    }
}
