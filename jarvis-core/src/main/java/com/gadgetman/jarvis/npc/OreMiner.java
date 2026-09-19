package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.core.platform.BlockScan;
import com.gadgetman.jarvis.core.platform.Butler;
import com.gadgetman.jarvis.core.platform.Entity;
import com.gadgetman.jarvis.core.platform.EntityKind;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Task;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.Ids;
import com.gadgetman.jarvis.core.world.Look;
import com.gadgetman.jarvis.core.world.Vec3;
import com.gadgetman.jarvis.progression.ServiceRecord;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The ore seeker: "mine some diamonds, Jarvis."
 *
 * <p>A state machine. SEARCHING runs one async scan of the loaded chunks for
 * the best ore; MOVING walks to it, which works for exposed ores; TUNNELING
 * is the buried-ore workhorse, digging a 1x2 corridor toward it cell by cell
 * with staircases up and down and lava checks before every block; MINING
 * breaks it with vanilla timing; COLLECTING sweeps up the drops. Never
 * teleports more than the single adjacent cell, and only when the one-block
 * walk stalls.
 *
 * <p>This was the body of JarvisNPC before the platform split; it is a task
 * like the branch miner now, driven by {@link ButlerService}.
 */
class OreMiner {

    private enum Phase {
        SEARCHING,    // Kicking off / waiting on the async ore scan
        MOVING,       // Walking to the ore (the platform's pathfinder — works for exposed ores)
        TUNNELING,    // Digging a 1x2 tunnel toward a buried ore, cell by cell
        MINING,       // Breaking the ore
        COLLECTING    // Picking up the drops
    }

    private static final int TICK_RATE = 10;             // Decision loop: every 0.5s
    private static final int MOVE_TIMEOUT_TICKS = 40;    // 20s of walking before we tunnel instead
    private static final int MAX_TUNNEL_STEPS = 64;      // Tunnel cells per target before giving up
    private static final int ADVANCE_NUDGE_TICKS = 5;    // 2.5s to walk one dug cell before nudging
    private static final double REACH_DISTANCE = 3.5;

    private final ButlerService host;
    private final Owner player;
    private final Butler butler;
    private final Set<String> requestedOreTypes;         // null = any ore
    private final int searchRadius;

    private World world;
    private Phase phase = Phase.SEARCHING;
    private int ticksInPhase = 0;

    private BlockPos targetOre = null;
    private String targetOreType = null;
    private Vec3 collectLocation = null;

    private int oresMined = 0;
    private boolean scanInFlight = false;
    private boolean navStuck = false;
    private boolean breaking = false;
    private boolean stopped = false;

    // Tunnel executor state
    private final ArrayDeque<BlockPos> digQueue = new ArrayDeque<>();
    private BlockPos stepCell = null;
    private int tunnelSteps = 0;
    private int advanceTicks = 0;

    private final Set<Long> unreachable = new HashSet<>();

    OreMiner(ButlerService host, Owner player, Set<String> requestedOreTypes) {
        this.host = host;
        this.player = player;
        this.butler = host.butler(player);
        this.requestedOreTypes = requestedOreTypes;
        this.searchRadius = host.config().getInt("mining.search-radius", 24);
    }

    /** The ore he is after right now, for the status line. */
    String targetOreType() {
        return targetOreType;
    }

    int oresMined() {
        return oresMined;
    }

    /** The loop is done with; anything still in flight must not act. */
    void stop() {
        stopped = true;
    }

    void start() {
        world = butler.world().orElse(null);
        if (world == null) return;

        butler.applyNavigationDefaults(() -> navStuck = true);
        host.giveStartingEquipment(player); // Make sure the pickaxe is in hand

        if (requestedOreTypes != null) {
            host.say(player, "Very good, sir. Commencing the search for "
                    + Blocks.formatOre(requestedOreTypes.iterator().next()).toLowerCase() + ".");
        } else {
            host.say(player, "Very good, sir. I shall see to the excavation.");
        }

        Task task = host.platform().scheduler().every(0L, TICK_RATE, self -> {
            if (stopped || !butler.isSpawned() || !player.isOnline()) {
                self.cancel();
                host.taskDone(player, self);
                host.minerDone(player, this);
                return;
            }

            Vec3 npcLoc = butler.pos();
            ticksInPhase++;

            // Always sweep up nearby drops
            host.pickupNearbyItems(player, npcLoc);

            // Bags full? Wrap up (and deliver, if a chest is registered).
            if (!breaking && host.lootSlotsUsed(player) >= host.lootCapacity() - 2) {
                self.cancel();
                host.taskDone(player, self);
                host.minerDone(player, this);
                host.say(player, "My bags are full, sir. " + oresMined + " ores this trip.");
                DepositManager deposits = host.deposits();
                if (deposits != null && deposits.hasChest(player)) {
                    deposits.startDepositRun(player, deposits.getChest(player).orElseThrow(), () -> {});
                }
                return;
            }

            switch (phase) {
                case SEARCHING  -> tickSearching(npcLoc);
                case MOVING     -> tickMoving(npcLoc);
                case TUNNELING  -> tickTunneling(npcLoc);
                case MINING     -> tickMining(npcLoc);
                case COLLECTING -> tickCollecting(npcLoc);
            }
        });
        host.registerTask(player, task);
        host.debug("Mining task started for " + player.name());
    }

    private void transitionTo(Phase next) {
        phase = next;
        ticksInPhase = 0;
    }

    private void clearTarget() {
        targetOre = null;
        targetOreType = null;
        navStuck = false;
        digQueue.clear();
        stepCell = null;
        tunnelSteps = 0;
        advanceTicks = 0;
    }

    /** SEARCHING — run one async ore scan; act on the result. */
    private void tickSearching(Vec3 npcLoc) {
        if (scanInFlight) return;

        scanInFlight = true;
        scanForOreAsync(npcLoc.block(), best -> {
            scanInFlight = false;
            // The mining task may have been stopped while we scanned
            if (stopped) return;

            if (best == null) {
                String suffix = requestedOreTypes != null
                        ? " None of that variety remain nearby." : "";
                host.say(player, "The seam appears exhausted, sir." + suffix
                        + " Final tally: " + oresMined + " ores.");
                host.stopTask(player);
                return;
            }

            clearTarget();
            targetOre = best;
            targetOreType = world.block(best).id();
            host.sayQuiet(player, "Located " + Blocks.formatOre(targetOreType) + ".");
            host.debug("Found ore: " + targetOreType + " at " + best);

            butler.navigateTo(best.center(), () -> navStuck = true);
            transitionTo(Phase.MOVING);
        });
    }

    /** MOVING — try walking (works for exposed ores); switch to tunneling when pathing fails. */
    private void tickMoving(Vec3 npcLoc) {
        if (targetOre == null) {
            transitionTo(Phase.SEARCHING);
            return;
        }

        // Ore vanished while we walked (another player got it, etc.)
        if (!Blocks.isOre(world.block(targetOre).id())) {
            host.debug("Target ore gone en route");
            butler.cancelNavigation();
            clearTarget();
            transitionTo(Phase.SEARCHING);
            return;
        }

        double distance = npcLoc.distance(targetOre.center());

        // Close enough — start mining
        if (distance <= REACH_DISTANCE) {
            butler.cancelNavigation();
            transitionTo(Phase.MINING);
            return;
        }

        // Path failed or ended short — the ore is buried. Dig to it.
        if (navStuck || !butler.isNavigating()) {
            navStuck = false;
            butler.cancelNavigation();
            host.debug("Walking failed at distance " + String.format("%.1f", distance) + " — tunneling");
            transitionTo(Phase.TUNNELING);
            return;
        }

        // Taking too long — tunnel the rest of the way
        if (ticksInPhase > MOVE_TIMEOUT_TICKS) {
            butler.cancelNavigation();
            transitionTo(Phase.TUNNELING);
        }
    }

    /**
     * TUNNELING — the buried-ore workhorse. Repeat: plan one step cell toward
     * the ore, dig the 1-2 blocks occupying it (vanilla timing), walk into it,
     * plan the next. Descends and ascends as staircases. Never teleports more
     * than the single adjacent cell (and only if the 1-block walk stalls).
     */
    private void tickTunneling(Vec3 npcLoc) {
        if (breaking) return; // A dig is in progress

        if (targetOre == null || !Blocks.isOre(world.block(targetOre).id())) {
            butler.cancelNavigation();
            clearTarget();
            transitionTo(Phase.SEARCHING);
            return;
        }

        // Reached the ore?
        if (npcLoc.distance(targetOre.center()) <= REACH_DISTANCE) {
            butler.cancelNavigation();
            transitionTo(Phase.MINING);
            return;
        }

        // Out of patience for this target?
        if (tunnelSteps > MAX_TUNNEL_STEPS) {
            abandonTarget("That one is buried deeper than it's worth, sir. Moving on.");
            return;
        }

        // 1) Dig any pending blocks for the current step
        if (!digQueue.isEmpty()) {
            BlockPos toDig = digQueue.peek();

            if (Blocks.isPassable(world, toDig)) {          // already clear
                digQueue.poll();
                return;
            }
            if (toDig.equals(targetOre)) {
                // We tunneled right into the target — mine it properly
                butler.cancelNavigation();
                transitionTo(Phase.MINING);
                return;
            }
            if (!Blocks.canDig(world, toDig)) {
                abandonTarget("Something rather solid is in the way, sir. Moving on.");
                return;
            }

            breaking = true;
            host.debug("Tunnel dig: " + world.block(toDig).id() + " at " + toDig);
            host.breakBlockProperly(player, world, toDig, success -> {
                breaking = false;
                if (stopped) return;
                if (success) {
                    digQueue.poll();
                } else {
                    abandonTarget("That block refuses to cooperate, sir. Moving on.");
                }
            });
            return;
        }

        // 2) Walk into the cleared step cell
        if (stepCell != null) {
            Vec3 cellCenter = stepCell.standing();
            double horiz = Math.hypot(npcLoc.x() - cellCenter.x(), npcLoc.z() - cellCenter.z());
            double vert = Math.abs(npcLoc.y() - stepCell.y());

            if (horiz < 0.7 && vert < 1.3) {  // arrived in the cell
                stepCell = null;
                advanceTicks = 0;
                return;
            }

            advanceTicks++;
            if (!butler.isNavigating() || navStuck) {
                navStuck = false;
                butler.navigateTo(cellCenter, () -> navStuck = true);
            }
            if (advanceTicks > ADVANCE_NUDGE_TICKS) {
                // The 1-block walk stalled (the pathfinder dislikes fresh tunnels
                // sometimes) — nudge him the single block. Visually a step.
                butler.cancelNavigation();
                Look look = butler.look();
                butler.teleport(cellCenter, look);
                host.debug("Nudged into step cell " + stepCell);
                stepCell = null;
                advanceTicks = 0;
            }
            return;
        }

        // 3) Plan the next step toward the ore
        planTunnelStep(npcLoc);
    }

    /** Plan one tunnel step: pick the next cell and queue the blocks to dig for it. */
    private void planTunnelStep(Vec3 npcLoc) {
        BlockPos from = npcLoc.block();
        int fx = from.x(), fy = from.y(), fz = from.z();
        int ox = targetOre.x(), oy = targetOre.y(), oz = targetOre.z();

        int dxT = ox - fx, dyT = oy - fy, dzT = oz - fz;
        int adx = Math.abs(dxT), ady = Math.abs(dyT), adz = Math.abs(dzT);

        // Horizontal direction: dominant horizontal axis; zigzag if none
        int hx = 0, hz = 0;
        if (adx >= adz && adx > 0) hx = Integer.signum(dxT);
        else if (adz > 0) hz = Integer.signum(dzT);
        else hx = ((fy & 1) == 0) ? 1 : -1; // straight up/down: zigzag staircase

        BlockPos cell;
        List<BlockPos> toDig = new ArrayList<>();

        if (dyT < -1 || (dyT < 0 && ady >= Math.max(adx, adz))) {
            // Staircase DOWN: step forward and one down.
            // Dug 2-high per step (3 blocks in the forward column) so the
            // player can walk the stairs behind him without breaking blocks.
            cell = new BlockPos(fx + hx, fy - 1, fz + hz);
            toDig.add(new BlockPos(fx + hx, fy + 1, fz + hz));   // player head room on the step
            toDig.add(new BlockPos(fx + hx, fy, fz + hz));       // head space of the lower step
            toDig.add(cell);                                      // feet of the lower step
        } else if (dyT > 1 || (dyT > 0 && ady >= Math.max(adx, adz))) {
            // Staircase UP: clear own headroom, step forward and one up.
            cell = new BlockPos(fx + hx, fy + 1, fz + hz);
            toDig.add(new BlockPos(fx, fy + 2, fz));              // room above own head
            toDig.add(new BlockPos(fx + hx, fy + 3, fz + hz));    // player head room on the step
            toDig.add(new BlockPos(fx + hx, fy + 2, fz + hz));    // head space of the upper step
            toDig.add(cell);                                      // feet of the upper step
        } else {
            // Horizontal 1x2 corridor
            cell = new BlockPos(fx + hx, fy, fz + hz);
            toDig.add(cell);                                      // feet
            toDig.add(new BlockPos(fx + hx, fy + 1, fz + hz));    // head
        }

        // ---- Safety checks ----
        // Fluids in any dig cell or just beyond it = stop (don't open a lava pocket)
        for (BlockPos dig : toDig) {
            if (Blocks.isFluid(world, dig)) {
                abandonTarget("There's liquid that way, sir. I'd rather not. Moving on.");
                return;
            }
            if (Ids.LAVA.equals(world.block(dig.offset(hx, 0, hz)).id())) {
                abandonTarget("Lava ahead, sir. I'd rather not melt. Moving on.");
                return;
            }
        }
        // Floor of the destination cell: solid, or at most a 1-block drop; never lava
        BlockPos below = cell.below();
        if (Blocks.isFluid(world, below)) {
            abandonTarget("The footing that way is treacherous, sir. Moving on.");
            return;
        }
        if (Blocks.isPassable(world, below)) {
            if (!world.isSolid(cell.offset(0, -2, 0))) {
                abandonTarget("There's a drop that way I don't fancy, sir. Moving on.");
                return;
            }
        }

        // Queue only blocks that actually need digging
        for (BlockPos dig : toDig) {
            if (!Blocks.isPassable(world, dig)) {
                digQueue.add(dig);
            }
        }

        stepCell = cell;
        advanceTicks = 0;
        tunnelSteps++;
        host.debug("Tunnel step " + tunnelSteps + " -> cell " + cell
                + " (" + digQueue.size() + " blocks to dig)");
    }

    /** MINING — break the ore with vanilla timing and animations. */
    private void tickMining(Vec3 npcLoc) {
        if (breaking) {
            // Safety timeout while the breaker works
            if (ticksInPhase > 80) { // 40s
                host.debug("MINING timeout");
                abandonTarget("That block is being unusually stubborn. Moving on.");
            }
            return;
        }

        if (targetOre == null) {
            transitionTo(Phase.SEARCHING);
            return;
        }

        String oreType = world.block(targetOre).id();
        if (!Blocks.isOre(oreType)) {
            host.debug("Ore already gone at mining time");
            collectLocation = targetOre.center();
            clearTarget();
            transitionTo(Phase.COLLECTING);
            return;
        }

        double distance = npcLoc.distance(targetOre.center());
        if (distance > REACH_DISTANCE + 1) {
            transitionTo(Phase.TUNNELING);
            return;
        }

        breaking = true;
        BlockPos ore = targetOre;
        host.breakBlockProperly(player, world, ore, success -> {
            breaking = false;
            if (stopped) return;

            if (success) {
                oresMined++;
                host.credit(player, ServiceRecord.Discipline.MINING, 1);
                host.sayQuiet(player, "Mined " + Blocks.formatOre(oreType) + " — " + oresMined + " so far.");
                if (oresMined % 10 == 0) {
                    host.say(player, oresMined + " ores and counting, sir. The collection grows.");
                }
                collectLocation = ore.center();
                clearTarget();
                transitionTo(Phase.COLLECTING);
            } else {
                abandonTarget("I'm unable to break that " + Blocks.formatOre(oreType) + ", sir. Moving on.");
            }
        });
    }

    /** COLLECTING — walk to the drops and pick them up. */
    private void tickCollecting(Vec3 npcLoc) {
        if (ticksInPhase > 12) { // 6s max
            transitionTo(Phase.SEARCHING);
            return;
        }

        // Give item entities a moment to spawn
        if (ticksInPhase < 2) return;

        if (collectLocation != null) {
            double dist = npcLoc.distance(collectLocation);
            if (dist > ButlerService.PICKUP_RADIUS) {
                if (!butler.isNavigating()) {
                    butler.navigateTo(collectLocation, () -> navStuck = true);
                }
                return;
            }
        }

        host.pickupNearbyItems(player, npcLoc);

        boolean itemsNearby = false;
        double r = ButlerService.PICKUP_RADIUS;
        for (Entity e : butler.nearbyEntities(r, r, r)) {
            if (e.kind() == EntityKind.ITEM
                    && e.asItem().map(i -> !Blocks.JUNK_DROPS.contains(i.id())).orElse(false)) {
                itemsNearby = true;
                break;
            }
        }

        if (!itemsNearby) {
            transitionTo(Phase.SEARCHING);
        }
    }

    private void abandonTarget(String message) {
        if (targetOre != null) {
            unreachable.add(targetOre.packed());
        }
        host.sayQuiet(player, message);
        clearTarget();
        transitionTo(Phase.SEARCHING);
    }

    // ==================== ORE SCANNING (async) ====================

    /**
     * Scan for the best ore off the server thread using a copied region.
     * The copy is taken on the server thread (cheap), the O(radius³) sweep
     * happens async, and the winner is delivered back on the server thread.
     */
    private void scanForOreAsync(BlockPos center, Consumer<BlockPos> callback) {
        int cx = center.x(), cy = center.y(), cz = center.z();
        int r = searchRadius;
        int minY = Math.max(world.minY(), cy - r);
        int maxY = Math.min(world.maxY(), cy + r);

        BlockScan scan = world.snapshot(new BlockPos(cx - r, minY, cz - r), new BlockPos(cx + r, maxY, cz + r));
        Set<Long> unreachableCopy = new HashSet<>(unreachable);
        Set<String> filter = requestedOreTypes;

        host.platform().scheduler().async(() -> {
            int bestX = 0, bestY = 0, bestZ = 0;
            double bestDistSq = Double.MAX_VALUE;
            int bestPriority = Integer.MAX_VALUE;
            boolean found = false;

            for (int x = cx - r; x <= cx + r; x++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    if (!scan.covers(new BlockPos(x, minY, z))) continue;
                    for (int y = minY; y <= maxY; y++) {
                        BlockPos at = new BlockPos(x, y, z);
                        String type = scan.blockId(at);

                        if (filter != null) {
                            if (!filter.contains(type)) continue;
                        } else if (!Blocks.isOre(type)) {
                            continue;
                        }

                        if (unreachableCopy.contains(at.packed())) continue;

                        int priority = Blocks.ORE_PRIORITY.indexOf(type);
                        if (priority < 0) priority = 999;

                        double distSq = (double) (x - cx) * (x - cx)
                                      + (double) (y - cy) * (y - cy)
                                      + (double) (z - cz) * (z - cz);
                        if (priority < bestPriority
                                || (priority == bestPriority && distSq < bestDistSq)) {
                            bestX = x; bestY = y; bestZ = z;
                            bestDistSq = distSq;
                            bestPriority = priority;
                            found = true;
                        }
                    }
                }
            }

            final boolean f = found;
            final BlockPos best = new BlockPos(bestX, bestY, bestZ);
            host.platform().scheduler().sync(() -> callback.accept(f ? best : null));
        });
    }
}
