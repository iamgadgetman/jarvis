package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.core.platform.Butler;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Site;
import com.gadgetman.jarvis.core.platform.Task;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.BlockState;
import com.gadgetman.jarvis.core.world.Ids;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.core.world.Vec3;
import com.gadgetman.jarvis.progression.ServiceRecord;
import com.gadgetman.jarvis.recovery.TaskFailure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Farmer (v0.7.0) - "Farm the carrots, Jarvis."
 *
 * Two modes:
 * - Sweep (/jarvis farm [crop]): one pass over the field — walk to each
 *   mature crop, hoe swing, harvest, replant from the seeds he just
 *   collected, report the haul, done.
 * - Tend (/jarvis tend [crop]): the standing farmhand — same loop, but he
 *   stays, rescanning as crops mature, delivering to the deposit chest when
 *   his bags fill, until told to stop.
 *
 * Replanting is honest: seeds come from what he harvests (wheat drops
 * seeds, carrots drop carrots...). No seed in the bags = that farmland
 * stays bare and he says so once.
 */
class Farmer {

    /** Crop block -> the item planted to regrow it. */
    private static final Map<String, String> CROPS = new LinkedHashMap<>();
    static {
        CROPS.put(Ids.WHEAT, Ids.WHEAT_SEEDS);
        CROPS.put(Ids.CARROTS, Ids.CARROT);
        CROPS.put(Ids.POTATOES, Ids.POTATO);
        CROPS.put(Ids.BEETROOTS, Ids.BEETROOT_SEEDS);
        CROPS.put(Ids.NETHER_WART, Ids.NETHER_WART);
    }
    /** Harvest-only (no replant onto stems — the stem regrows them). */
    private static final Map<String, Integer> GOURDS = Map.of(Ids.MELON, 0, Ids.PUMPKIN, 0);
    /** Every crop's maximum age; the game's own values. */
    private static final Map<String, Integer> MAX_AGE = Map.of(
            Ids.WHEAT, 7, Ids.CARROTS, 7, Ids.POTATOES, 7, Ids.BEETROOTS, 3, Ids.NETHER_WART, 3);

    static String cropFromKeyword(String keyword) {
        if (keyword == null) return null;
        String k = keyword.toLowerCase();
        if (k.contains("wheat")) return Ids.WHEAT;
        if (k.contains("carrot")) return Ids.CARROTS;
        if (k.contains("potato")) return Ids.POTATOES;
        if (k.contains("beet")) return Ids.BEETROOTS;
        if (k.contains("wart")) return Ids.NETHER_WART;
        if (k.contains("melon")) return Ids.MELON;
        if (k.contains("pumpkin")) return Ids.PUMPKIN;
        return null;
    }

    private final ButlerHost host;
    private final Owner player;
    private final Butler butler;
    private final DepositManager deposits;
    private final String cropFilter;      // null = all crops
    private final boolean tendMode;
    private final World world;
    private final BlockPos fieldCenter;

    private final ArrayDeque<BlockPos> targets = new ArrayDeque<>();
    private boolean working = false;
    /** Set when self-explain decides to keep harvesting with nowhere to put the produce. */
    private boolean depositsPaused = false;
    private int harvested = 0;
    private int unplanted = 0;
    private boolean warnedSeeds = false;
    private int idleRescans = 0;

    private final int fieldRadius;
    private static final double REACH = 2.6;
    private static final int STALL_HOP_TICKS = 6;
    private int stalled = 0;
    private Vec3 lastPos = null;

    Farmer(ButlerHost host, Owner player, DepositManager deposits,
           String cropFilter, boolean tendMode) {
        this.host = host;
        this.player = player;
        this.butler = host.butler(player);
        this.deposits = deposits;
        this.cropFilter = cropFilter;
        this.tendMode = tendMode;
        this.world = butler.world().orElseThrow();
        this.fieldCenter = butler.pos().block();
        this.fieldRadius = host.config().getInt("farming.field-radius", 16);
    }

    void start() {
        butler.applyNavigationDefaults(null);
        host.equipTool(player, Ids.DIAMOND_HOE);

        scanField();
        if (targets.isEmpty() && !tendMode) {
            host.say(player, "Nothing here is ready for harvest, sir. Patience is a virtue.");
            return;
        }

        String what = cropFilter != null ? Blocks.pretty(cropFilter) : "the crops";
        host.say(player, tendMode
                ? "I shall tend " + what + " until you say otherwise, sir."
                : "Harvesting " + what + ", sir — " + targets.size() + " ready.");

        Task task = host.platform().scheduler().every(10L, 10L, self -> {
            if (!butler.isSpawned() || !player.isOnline()) {
                self.cancel();
                host.taskDone(player, self);
                return;
            }
            Vec3 loc = butler.pos();
            host.pickupNearbyItems(player, loc);
            tick(loc, self);
        });
        host.registerTask(player, task);
    }

    private void tick(Vec3 loc, Task self) {
        if (working) return;

        // Bags full? Deliver and continue (tend) or wrap up (sweep)
        if (!depositsPaused && host.lootSlotsUsed(player) >= host.lootCapacity() - 2
                && deposits.hasChest(player)) {
            host.say(player, "Bags full, sir — delivering the produce. Back shortly.");
            self.cancel();
            deposits.startDepositRun(player, deposits.getChest(player).orElseThrow(), () -> {
                // v0.8.0: if the chest couldn't take it, don't loop forever
                if (host.lootSlotsUsed(player) >= host.lootCapacity() - 2) {
                    host.reportFailure(TaskFailure.of(player, tendMode ? "tend" : "farm")
                            .step("delivering a full load of produce to the deposit chest")
                            .reason("the chest would not take the load and his own bags are still "
                                    + "full, so nothing further can be picked up")
                            .say("The chest is full and so are my bags, sir. "
                                    + "Farming is paused until there's somewhere to put things.")
                            .where(here())
                            .state("loot slots used", host.lootSlotsUsed(player)
                                    + " of " + host.lootCapacity())
                            .state("crops harvested", harvested)
                            .state("mode", tendMode ? "standing tend shift" : "one sweep")
                            .option("harvest_without_collecting",
                                    "carry on harvesting and replanting, leaving the produce on the "
                                    + "ground for the player to collect",
                                    () -> resumeWithoutDeposits())
                            .build());
                    return;
                }
                butler.applyNavigationDefaults(null);
                host.equipTool(player, Ids.DIAMOND_HOE);
                start(); // re-enter: rescan from the field
            });
            return;
        }

        if (targets.isEmpty()) {
            if (!tendMode) {
                finish();
                self.cancel();
                host.taskDone(player, self);
                return;
            }
            // Tend mode: idle at the field, rescan every ~15s
            idleRescans++;
            if (idleRescans >= 30) {
                idleRescans = 0;
                scanField();
                if (!targets.isEmpty()) {
                    host.sayQuiet(player, targets.size() + " ready for harvest.");
                }
            }
            // Drift back to the field center if he wandered
            if (loc.distance(fieldCenter.standing()) > fieldRadius && !butler.isNavigating()) {
                butler.navigateTo(fieldCenter.above().standing());
            }
            return;
        }

        BlockPos crop = targets.peek();

        // Crop no longer valid (broken, already harvested, grew out of filter)
        if (!isMature(crop)) {
            targets.poll();
            return;
        }

        double dist = loc.distance(crop.center());
        if (dist > REACH) {
            // Walk over
            if (!butler.isNavigating()) {
                butler.navigateTo(crop.above().standing());
            }
            if (lastPos != null && loc.distance(lastPos) < 0.15) stalled++;
            else stalled = 0;
            lastPos = loc;
            if (stalled > STALL_HOP_TICKS) {
                butler.cancelNavigation();
                butler.teleport(host.findSafeNear(world, crop.above().standing()));
                stalled = 0;
            }
            return;
        }

        // Harvest: face, swing the hoe, break, replant
        targets.poll();
        working = true;
        butler.lookAt(crop.center());
        butler.swing();

        String type = world.block(crop).id();
        world.breakNaturally(crop, host.toolInHand(player));
        harvested++;
        host.credit(player, ServiceRecord.Discipline.FARMING, 1);
        world.sound(crop.center(), Ids.SOUND_BLOCK_CROP_BREAK, 0.8f, 1.0f);

        // Replant after the drops have spawned and been swept
        host.platform().scheduler().later(8L, () -> {
            replant(crop, type);
            working = false;
        });
    }

    private void replant(BlockPos where, String cropType) {
        if (GOURDS.containsKey(cropType)) return; // stems regrow melons/pumpkins

        String seed = CROPS.get(cropType);
        if (seed == null) return;

        String below = world.block(where.below()).id();
        boolean soilOk = Ids.NETHER_WART.equals(cropType)
                ? Ids.SOUL_SAND.equals(below)
                : Ids.FARMLAND.equals(below);
        if (!soilOk || !world.block(where).isAir()) return;

        if (!consumeSeed(seed)) {
            unplanted++;
            if (!warnedSeeds) {
                warnedSeeds = true;
                host.sayQuiet(player, "Out of seed for replanting — I'll plant what I harvest.");
            }
            return;
        }

        world.setBlock(where, BlockState.of(cropType).with("age", 0));
        world.sound(where.center(), Ids.SOUND_ITEM_CROP_PLANT, 0.7f, 1.0f);
    }

    /** Take one seed item from the bags (slots 1..35). */
    private boolean consumeSeed(String seed) {
        List<Item> contents = new ArrayList<>(butler.inventory());
        for (int i = 1; i < Math.min(36, contents.size()); i++) {
            Item item = contents.get(i);
            if (item.isEmpty() || !item.is(seed)) continue;
            contents.set(i, item.count() <= 1 ? Item.EMPTY : item.withCount(item.count() - 1));
            butler.setInventory(contents);
            return true;
        }
        return false;
    }

    // ==================== FIELD SCAN ====================

    private void scanField() {
        targets.clear();
        for (int x = -fieldRadius; x <= fieldRadius; x++) {
            for (int y = -4; y <= 4; y++) {
                for (int z = -fieldRadius; z <= fieldRadius; z++) {
                    BlockPos b = fieldCenter.offset(x, y, z);
                    if (isMature(b)) {
                        targets.add(b);
                    }
                }
            }
        }
    }

    private boolean isMature(BlockPos b) {
        BlockState state = world.block(b);
        String type = state.id();
        if (cropFilter != null && !type.equals(cropFilter)) return false;

        if (GOURDS.containsKey(type)) return cropFilter == null || type.equals(cropFilter);
        if (!CROPS.containsKey(type)) return false;
        Integer max = MAX_AGE.get(type);
        return max != null && state.intProp("age", -1) >= max;
    }

    private Site here() {
        Vec3 at = host.currentLocation(player);
        return at == null ? null : new Site(world, at);
    }

    /** Keep harvesting with nowhere to put the produce. Drops stay in the field. */
    private void resumeWithoutDeposits() {
        if (!player.isOnline() || !butler.isSpawned()) return;
        depositsPaused = true;
        butler.applyNavigationDefaults(null);
        host.equipTool(player, Ids.DIAMOND_HOE);
        start();
    }

    private void finish() {
        butler.cancelNavigation();
        String seedNote = unplanted > 0
                ? " (" + unplanted + " plots await seed, I'm afraid.)" : " All replanted.";
        host.say(player, "Harvest complete, sir — " + harvested + " crops gathered." + seedNote);
        if (harvested >= 10) {
            Entertainer.celebrate(host, player);
        }
        if (host.config().getBoolean("mining.auto-deposit", true)
                && deposits.hasChest(player) && host.lootSlotsUsed(player) > 0) {
            deposits.startDepositRun(player, deposits.getChest(player).orElseThrow(), () -> {});
        }
    }
}
