package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.core.platform.Butler;
import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Site;
import com.gadgetman.jarvis.core.platform.Task;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.BlockState;
import com.gadgetman.jarvis.core.world.Ids;
import com.gadgetman.jarvis.core.world.Tag;
import com.gadgetman.jarvis.core.world.Vec3;
import com.gadgetman.jarvis.progression.ServiceRecord;
import com.gadgetman.jarvis.recovery.TaskFailure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lumberjack (v0.7.0) - "Chop some trees, Jarvis."
 *
 * Finds trees (log columns rooted on soil with leaves above), walks to the
 * base, chops the trunk base with the timed BlockBreaker — then the rest of
 * the tree comes down in a top-to-bottom cascade (timber!), drops collected,
 * and a sapling of the right species goes back in the ground. Keeps going
 * until the requested number of trees (default 5) or none remain in range.
 */
class Lumberjack {

    private static final Set<String> LOGS = Set.of(
            Ids.OAK_LOG, Ids.BIRCH_LOG, Ids.SPRUCE_LOG, Ids.JUNGLE_LOG,
            Ids.ACACIA_LOG, Ids.DARK_OAK_LOG, Ids.MANGROVE_LOG, Ids.CHERRY_LOG);

    private static final Map<String, String> SAPLINGS = Map.of(
            Ids.OAK_LOG, Ids.OAK_SAPLING,
            Ids.BIRCH_LOG, Ids.BIRCH_SAPLING,
            Ids.SPRUCE_LOG, Ids.SPRUCE_SAPLING,
            Ids.JUNGLE_LOG, Ids.JUNGLE_SAPLING,
            Ids.ACACIA_LOG, Ids.ACACIA_SAPLING,
            Ids.DARK_OAK_LOG, Ids.DARK_OAK_SAPLING,
            Ids.MANGROVE_LOG, Ids.MANGROVE_PROPAGULE,
            Ids.CHERRY_LOG, Ids.CHERRY_SAPLING);

    private static final Set<String> SOIL = Set.of(Ids.DIRT, Ids.GRASS_BLOCK, Ids.PODZOL, Ids.MUD);

    private final ButlerHost host;
    private final Config config;
    private final Owner player;
    private final Butler butler;
    private final DepositManager deposits;
    private final int treeQuota;
    private final boolean replant;

    private World world;
    private int treesFelled = 0;
    private int logsCollected = 0;
    private boolean busy = false;
    private BlockPos currentBase = null;
    private int stalled = 0;
    private Vec3 lastPos = null;
    private Task mainTask = null;   // v0.8.0: lets callbacks notice /jarvis stop
    /** Set when self-explain decides to keep felling with nowhere to put the timber. */
    private boolean depositsPaused = false;

    private static final int SEARCH_RADIUS = 24;
    private static final int MAX_TREE_LOGS = 80;
    private static final int STALL_HOP_TICKS = 8;

    Lumberjack(ButlerHost host, Owner player, DepositManager deposits, int treeQuota) {
        this.host = host;
        this.config = host.config();
        this.player = player;
        this.butler = host.butler(player);
        this.deposits = deposits;
        this.treeQuota = Math.max(1, Math.min(treeQuota, 32));
        this.replant = config.getBoolean("farming.replant-saplings", true);
    }

    void start() {
        world = butler.world().orElse(null);
        if (world == null) return;
        butler.applyNavigationDefaults(null);
        host.equipTool(player, Ids.DIAMOND_AXE);
        host.say(player, "Very good, sir. " + treeQuota + " trees, coming down — replanting as I go.");

        mainTask = host.platform().scheduler().every(10L, 10L, self -> {
            if (!butler.isSpawned() || !player.isOnline()) {
                self.cancel();
                host.taskDone(player, self);
                return;
            }
            Vec3 loc = butler.pos();
            host.pickupNearbyItems(player, loc);
            tick(loc, self);
        });
        host.registerTask(player, mainTask);
    }

    private void tick(Vec3 loc, Task self) {
        if (busy) return;

        if (treesFelled >= treeQuota) {
            finish(self);
            return;
        }
        if (!depositsPaused && host.lootSlotsUsed(player) >= host.lootCapacity() - 2
                && deposits.hasChest(player)) {
            host.say(player, "Bags full of timber, sir — one delivery and I'll resume.");
            self.cancel();
            deposits.startDepositRun(player, deposits.getChest(player).orElseThrow(), () -> {
                // v0.8.0: if the chest couldn't take it, don't loop forever
                if (host.lootSlotsUsed(player) >= host.lootCapacity() - 2) {
                    host.reportFailure(TaskFailure.of(player, "chop")
                            .step("delivering a full load of timber to the deposit chest")
                            .reason("the chest would not take the load and his own bags are still "
                                    + "full, so nothing further can be picked up")
                            .say("The chest is full and so are my bags, sir. "
                                    + "The timber work is paused for now.")
                            .where(here())
                            .state("loot slots used", host.lootSlotsUsed(player)
                                    + " of " + host.lootCapacity())
                            .state("trees felled", treesFelled + " of " + treeQuota)
                            .option("fell_without_collecting",
                                    "carry on felling the remaining trees and leave the logs on the "
                                    + "ground for the player to collect",
                                    () -> resumeWithoutDeposits())
                            .build());
                    return;
                }
                butler.applyNavigationDefaults(null);
                host.equipTool(player, Ids.DIAMOND_AXE);
                start();
            });
            return;
        }

        // Find (or continue toward) the next tree base
        if (currentBase == null || !LOGS.contains(world.block(currentBase).id())) {
            currentBase = findNearestTreeBase(loc.block());
            if (currentBase == null) {
                finish(self);
                return;
            }
        }

        double dist = loc.distance(currentBase.center());
        if (dist > 2.8) {
            if (!butler.isNavigating()) {
                butler.navigateTo(currentBase.above().standing());
            }
            if (lastPos != null && loc.distance(lastPos) < 0.15) stalled++;
            else stalled = 0;
            lastPos = loc;
            if (stalled > STALL_HOP_TICKS) {
                butler.cancelNavigation();
                butler.teleport(host.findSafeNear(world, currentBase.above().standing()));
                stalled = 0;
            }
            return;
        }

        // At the tree: chop the base with real timing, then TIMBER the rest
        butler.cancelNavigation();
        busy = true;
        BlockPos base = currentBase;
        String species = world.block(base).id();
        List<BlockPos> tree = collectTree(base);

        host.breakBlockProperly(player, world, base, success -> {
            // v0.8.0: /jarvis stop mid-chop must not fell the rest of the tree
            if (mainTask == null || mainTask.isCancelled()) return;
            if (!success) {
                busy = false;
                currentBase = null;
                host.sayQuiet(player, "That trunk resists me. Choosing another.");
                return;
            }
            logsCollected++;
            timberCascade(tree, base, species, () -> {
                treesFelled++;
                host.credit(player, ServiceRecord.Discipline.FORESTRY, 1);
                host.sayQuiet(player, "Timber! " + treesFelled + "/" + treeQuota + " down.");
                if (replant) {
                    replantSapling(base, species);
                }
                currentBase = null;
                busy = false;
            });
        });
    }

    /** Fell the remaining logs top-down, a couple per tick — reads as the tree falling. */
    private void timberCascade(List<BlockPos> tree, BlockPos base, String species, Runnable onDone) {
        // Highest first
        tree.sort((a, b) -> Integer.compare(b.y(), a.y()));
        ArrayDeque<BlockPos> queue = new ArrayDeque<>(tree);
        queue.remove(base);

        world.sound(base.center(), Ids.SOUND_BLOCK_WOOD_BREAK, 1.0f, 0.7f);

        host.platform().scheduler().every(2L, 2L, self -> {
            if (mainTask == null || mainTask.isCancelled() || !butler.isSpawned()) {
                self.cancel();   // stopped mid-fell — leave the rest of the tree standing
                return;
            }
            for (int i = 0; i < 2 && !queue.isEmpty(); i++) {
                BlockPos log = queue.poll();
                if (LOGS.contains(world.block(log).id())) {
                    world.breakNaturally(log, host.toolInHand(player));
                    logsCollected++;
                }
            }
            if (queue.isEmpty()) {
                self.cancel();
                onDone.run();
            }
        });
    }

    private void replantSapling(BlockPos base, String species) {
        String sapling = SAPLINGS.get(species);
        if (sapling == null) return;
        if (SOIL.contains(world.block(base.below()).id()) && world.block(base).isAir()) {
            world.setBlock(base, BlockState.of(sapling));
            world.sound(base.center(), Ids.SOUND_ITEM_CROP_PLANT, 0.7f, 0.9f);
        }
    }

    /** A tree base: a log with soil below and connected leaves somewhere above. */
    private BlockPos findNearestTreeBase(BlockPos center) {
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int x = -SEARCH_RADIUS; x <= SEARCH_RADIUS; x++) {
            for (int y = -6; y <= 6; y++) {
                for (int z = -SEARCH_RADIUS; z <= SEARCH_RADIUS; z++) {
                    BlockPos b = center.offset(x, y, z);
                    if (!LOGS.contains(world.block(b).id())) continue;
                    if (!SOIL.contains(world.block(b.below()).id())) continue;
                    if (!hasLeavesAbove(b)) continue;

                    double d = x * x + y * y + z * z;
                    if (d < bestDistSq) {
                        bestDistSq = d;
                        best = b;
                    }
                }
            }
        }
        return best;
    }

    private boolean hasLeavesAbove(BlockPos base) {
        for (int y = 1; y <= 12; y++) {
            BlockPos at = base.offset(0, y, 0);
            String t = world.block(at).id();
            if (t.endsWith("_leaves") || world.is(at, Tag.LEAVES)) return true;
            if (Ids.AIR.equals(t)) return false;
        }
        return false;
    }

    /** Flood-fill the connected logs (the trunk and branches), bounded. */
    private List<BlockPos> collectTree(BlockPos base) {
        List<BlockPos> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
        frontier.add(base);

        while (!frontier.isEmpty() && result.size() < MAX_TREE_LOGS) {
            BlockPos b = frontier.poll();
            if (!seen.add(b.packed())) continue;
            if (!LOGS.contains(world.block(b).id())) continue;
            result.add(b);

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        frontier.add(b.offset(dx, dy, dz));
                    }
                }
            }
        }
        return result;
    }

    private Site here() {
        Vec3 at = host.currentLocation(player);
        return at == null || world == null ? null : new Site(world, at);
    }

    /** Keep felling with nowhere to put the timber. Logs stay where they drop. */
    private void resumeWithoutDeposits() {
        if (!player.isOnline() || !butler.isSpawned()) return;
        depositsPaused = true;
        butler.applyNavigationDefaults(null);
        host.equipTool(player, Ids.DIAMOND_AXE);
        start();
    }

    private void finish(Task self) {
        self.cancel();
        host.taskDone(player, self);
        butler.cancelNavigation();
        host.say(player, "Timber work complete, sir — " + treesFelled + " trees, "
                + logsCollected + " logs" + (replant ? ", saplings in the ground." : "."));
        if (treesFelled >= 3) {
            Entertainer.celebrate(host, player);
        }
        if (deposits.hasChest(player) && host.lootSlotsUsed(player) > 0
                && config.getBoolean("mining.auto-deposit", true)) {
            deposits.startDepositRun(player, deposits.getChest(player).orElseThrow(), () -> {});
        }
    }
}
