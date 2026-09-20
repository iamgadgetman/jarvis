package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.core.platform.Butler;
import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Site;
import com.gadgetman.jarvis.core.platform.Task;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.BlockState;
import com.gadgetman.jarvis.core.world.Facing;
import com.gadgetman.jarvis.core.world.Ids;
import com.gadgetman.jarvis.core.world.Look;
import com.gadgetman.jarvis.core.world.Vec3;
import com.gadgetman.jarvis.recovery.TaskFailure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * BranchMiner (v0.2.0) - the deterministic branch mine.
 *
 * The MineColonies lesson: worker NPCs are reliable when they only ever walk
 * corridors they dug themselves. Jarvis digs a staircase down to the target
 * depth, then a main corridor with branch tunnels on a fixed grid — torch-lit,
 * lava-sealed, and reusable by the player afterwards. Ores encountered in the
 * tunnel walls are harvested along the way (that's the whole point of branch
 * mining: the grid statistically intersects the veins).
 *
 * Movement/digging reuses the v0.1.1 tunnel-executor primitives: dig with
 * vanilla timing, walk into the cell, nudge only if the 1-block walk stalls.
 */
class BranchMiner {

    private enum Mode { EXECUTING, RETURNING, DONE }

    /** One planned cell of the mine. */
    private static class Step {
        final BlockPos cell;            // Where Jarvis stands after this step
        final List<BlockPos> digs;      // Blocks to clear for this step
        final boolean torch;            // Place a torch at the previous cell
        final int segment;              // Contiguous tunnel section id

        Step(BlockPos cell, List<BlockPos> digs, boolean torch, int segment) {
            this.cell = cell;
            this.digs = digs;
            this.torch = torch;
            this.segment = segment;
        }
    }

    private final ButlerHost host;
    private final Owner player;
    private final Butler butler;
    private final DepositManager deposits;

    /** What shape of excavation this run is. */
    enum Layout {
        /** Staircase to depth, long gallery, ribs off both sides. Narrow. */
        BRANCH_MINE,
        /** A straight 3x3 passage on the level, going where he is facing. */
        TUNNEL
    }

    private final Layout layout;
    private final int tunnelLength;
    /** Forced heading as {dx, dz}, or null to use whichever way he is facing. */
    private final int[] heading;

    private final List<Step> plan = new ArrayList<>();
    private int index = 0;
    private Mode mode = Mode.EXECUTING;
    private World world;

    private final ArrayDeque<BlockPos> digQueue = new ArrayDeque<>();

    /**
     * 3x3 rather than 1x2. Always on for a tunnel — a passage you cannot see
     * down is not what anyone means by "tunnel" — and never on for a branch
     * mine, where narrow corridors are the whole point of the pattern.
     */
    private boolean wideBore;
    private BlockPos stepCell = null;
    private BlockPos resumeCell = null;
    private int advanceTicks = 0;
    private boolean breaking = false;
    private boolean navStuck = false;
    /** Set when self-explain decides to keep digging with nowhere to put the loot. */
    private boolean depositsPaused = false;
    private final ArrayDeque<BlockPos> harvestQueue = new ArrayDeque<>();

    private int oresMined = 0;
    private int blocksDug = 0;
    private int sealedPockets = 0;
    private boolean announcedFull = false;

    // Config
    private final int targetY;
    private final int corridorLength;
    private final int branchLength;
    private final int branchSpacing;
    private final boolean placeTorches;
    private final int torchInterval;
    private final boolean autoDeposit;

    private static final double REACH = 3.5;
    private static final int NUDGE_TICKS = 6;          // 3s stall on a 1-block walk
    private static final int FAR_NUDGE_TICKS = 16;     // 8s stall on longer transitions
    private static final double MAX_TRANSITION_DISTANCE = 32.0;

    BranchMiner(ButlerHost host, Owner player, DepositManager deposits) {
        this(host, player, deposits, Layout.BRANCH_MINE, 0, null);
    }

    BranchMiner(ButlerHost host, Owner player, DepositManager deposits,
                Layout layout, int tunnelLength, int[] heading) {
        this.layout = layout;
        this.tunnelLength = tunnelLength;
        this.heading = heading;
        this.host = host;
        this.player = player;
        this.butler = host.butler(player);
        this.deposits = deposits;

        Config cfg = host.config();
        this.targetY = cfg.getInt("mining.branch.target-y", -54);
        this.corridorLength = Math.max(4, cfg.getInt("mining.branch.corridor-length", 32));
        this.branchLength = Math.max(2, cfg.getInt("mining.branch.branch-length", 12));
        this.branchSpacing = Math.max(2, cfg.getInt("mining.branch.branch-spacing", 3));
        this.placeTorches = cfg.getBoolean("mining.branch.place-torches", true);
        this.torchInterval = Math.max(2, cfg.getInt("mining.branch.torch-interval", 8));
        this.autoDeposit = cfg.getBoolean("mining.auto-deposit", true);
    }

    // ==================== PLANNING ====================

    /**
     * The cells to clear for one step along a tunnel.
     *
     * <p>Ordinarily a 1-wide, 2-high corridor — enough to walk down. At
     * Peerless it becomes the full 3x3: one block either side on the
     * perpendicular axis, three high. Nine times the digging for a gallery you
     * can actually see down, which is the point of earning it.
     *
     * @param pdx,pdz the perpendicular axis to widen along
     */
    private List<BlockPos> bore(int x, int y, int z, int pdx, int pdz) {
        List<BlockPos> cells = new ArrayList<>(wideBore ? 9 : 2);
        if (!wideBore) {
            cells.add(new BlockPos(x, y, z));
            cells.add(new BlockPos(x, y + 1, z));
            return cells;
        }
        for (int side = -1; side <= 1; side++) {
            for (int up = 0; up <= 2; up++) {
                cells.add(new BlockPos(x + pdx * side, y + up, z + pdz * side));
            }
        }
        return cells;
    }

    /** Build the whole mine as a deterministic list of steps, then start digging. */
    void start() {
        Vec3 anchorPos = host.currentLocation(player);
        world = butler.world().orElse(null);
        if (anchorPos == null || world == null) return;
        BlockPos anchor = anchorPos.block();

        int minY = world.minY() + 5;
        int depth = Math.max(targetY, minY);

        int dx, dz;
        if (heading != null) {
            dx = heading[0];
            dz = heading[1];
        } else {
            // Facing: snap the NPC's yaw to a cardinal direction
            float yaw = ((butler.look().yaw() % 360) + 360) % 360;
            dx = 0; dz = 0;
            if (yaw >= 315 || yaw < 45) dz = 1;        // south
            else if (yaw < 135) dx = -1;               // west
            else if (yaw < 225) dz = -1;               // north
            else dx = 1;                               // east
        }

        // The rank gate lives on the /jarvis tunnel command, not here.
        wideBore = layout == Layout.TUNNEL;

        int fx = anchor.x(), fy = anchor.y(), fz = anchor.z();
        int segment = 0;

        if (layout == Layout.TUNNEL) {
            int tx = fx, ty = fy, tz = fz;
            for (int i = 1; i <= tunnelLength; i++) {
                tx += dx; tz += dz;
                plan.add(new Step(new BlockPos(tx, ty, tz),
                        bore(tx, ty, tz, dz, dx),
                        placeTorches && i % torchInterval == 0, 0));
            }
            host.say(player, "Very good, sir. A three-by-three passage, "
                    + tunnelLength + " blocks, heading " + Compass.name(dx, dz)
                    + ". I shall keep it lit.");
            butler.applyNavigationDefaults(() -> navStuck = true);
            host.giveStartingEquipment(player);
            runLoop();
            return;
        }

        // 1) Staircase down to depth (3-high clearance for the diagonal walk)
        int px = fx, py = fy, pz = fz;
        while (py > depth) {
            px += dx; py -= 1; pz += dz;
            List<BlockPos> digs = new ArrayList<>(3);
            digs.add(new BlockPos(px, py, pz));
            digs.add(new BlockPos(px, py + 1, pz));
            digs.add(new BlockPos(px, py + 2, pz));
            plan.add(new Step(new BlockPos(px, py, pz), digs, false, segment));
        }

        // 2) Main corridor with branch pairs on the grid
        segment++;
        int corridorSegment = segment;
        int branchCount = 0;
        for (int i = 1; i <= corridorLength; i++) {
            px += dx; pz += dz;
            List<BlockPos> digs = bore(px, py, pz, dz, dx);
            plan.add(new Step(new BlockPos(px, py, pz),
                    digs, i % torchInterval == 0, corridorSegment));

            if (i % branchSpacing == 0 && i < corridorLength) {
                // Branch pair: perpendicular to the corridor
                int bdx = dz, bdz = dx; // rotate 90°
                for (int side = -1; side <= 1; side += 2) {
                    segment++;
                    branchCount++;
                    int bx = px, bz = pz;
                    for (int j = 1; j <= branchLength; j++) {
                        bx += bdx * side; bz += bdz * side;
                        // Perpendicular to a branch is the corridor's own axis.
                        List<BlockPos> bdigs = bore(bx, py, bz, dx, dz);
                        plan.add(new Step(new BlockPos(bx, py, bz),
                                bdigs, j % torchInterval == 0, segment));
                    }
                    // Then walk back to the corridor (already dug — no digs needed)
                    plan.add(new Step(new BlockPos(px, py, pz),
                            new ArrayList<>(), false, segment));
                }
            }
        }

        host.say(player, "Very good, sir. Sinking a shaft to Y=" + depth + " — "
                + corridorLength + "-block gallery, " + branchCount + " branches. I shall keep it lit.");
        butler.applyNavigationDefaults(() -> navStuck = true);
        host.giveStartingEquipment(player);
        runLoop();
    }

    // ==================== EXECUTION ====================

    private void runLoop() {
        Task task = host.platform().scheduler().every(0L, 10L, self -> {
            if (!butler.isSpawned() || !player.isOnline() || mode == Mode.DONE) {
                self.cancel();
                host.taskDone(player, self);
                return;
            }
            Vec3 npcLoc = butler.pos();
            host.pickupNearbyItems(player, npcLoc);
            tick(npcLoc, self);
        });
        host.registerTask(player, task);
    }

    private void tick(Vec3 npcLoc, Task self) {
        if (breaking) return;

        // Bags full? Deliver to the chest and come back.
        if (autoDeposit && !depositsPaused
                && host.lootSlotsUsed(player) >= host.lootCapacity() - 2) {
            if (deposits.hasChest(player)) {
                host.say(player, "Bags are full, sir — running a delivery. Back shortly.");
                resumeCell = npcLoc.block();
                self.cancel();
                deposits.startDepositRun(player, deposits.getChest(player).orElseThrow(), () -> {
                    // v0.8.0: if the chest couldn't take it, don't loop forever
                    if (host.lootSlotsUsed(player) >= host.lootCapacity() - 2) {
                        mode = Mode.DONE;
                        host.reportFailure(TaskFailure.of(player, "branch_mine")
                                .step("delivering a full load to the deposit chest")
                                .reason("the chest would not take the load and his own bags are still "
                                        + "full, so nothing further can be picked up")
                                .say("The chest is full and so are my bags, sir. "
                                        + "Pausing the mine until there's room somewhere.")
                                .where(here())
                                .state("loot slots used", host.lootSlotsUsed(player)
                                        + " of " + host.lootCapacity())
                                .state("ores recovered", oresMined)
                                .state("plan progress", index + " of " + plan.size() + " cells")
                                .option("mine_without_collecting",
                                        "carry on excavating the plan and leave what drops on the "
                                        + "floor for the player to collect",
                                        () -> resumeWithoutDeposits())
                                .build());
                        return;
                    }
                    mode = Mode.RETURNING;
                    butler.applyNavigationDefaults(() -> navStuck = true);
                    runLoop();
                });
                return;
            } else if (!announcedFull) {
                announcedFull = true;
                host.say(player, "My bags are full, sir. Register a chest with '/jarvis chest' "
                        + "and I'll handle deliveries myself.");
            }
        }

        if (mode == Mode.RETURNING) {
            tickReturning(npcLoc);
            return;
        }

        // Harvest ores exposed in the tunnel walls
        if (!harvestQueue.isEmpty()) {
            BlockPos ore = harvestQueue.peek();
            String oreType = world.block(ore).id();
            if (!Blocks.isOre(oreType) || npcLoc.distance(ore.center()) > REACH + 1) {
                harvestQueue.poll();
                return;
            }
            breaking = true;
            host.breakBlockProperly(player, world, ore, success -> {
                breaking = false;
                harvestQueue.poll();
                if (success) {
                    oresMined++;
                    host.sayQuiet(player, "Harvested " + Blocks.formatOre(oreType) + " — " + oresMined + " so far.");
                    if (oresMined % 10 == 0) {
                        host.say(player, oresMined + " ores from this mine so far, sir.");
                    }
                    // Vein following: neighbors of the mined ore, if still in reach
                    queueAdjacentOres(ore);
                }
            });
            return;
        }

        // Dig the current step's blocks
        if (!digQueue.isEmpty()) {
            BlockPos toDig = digQueue.peek();
            String digType = world.block(toDig).id();

            if (Blocks.isPassable(world, toDig)) {
                digQueue.poll();
                return;
            }
            if (Blocks.isFluid(digType)) {
                sealAndSkipSegment(toDig);
                return;
            }
            if (!Blocks.canDig(digType)) {
                skipSegment("Something rather solid blocks that tunnel, sir. Rerouting.");
                return;
            }
            // Seal any fluid neighbors BEFORE opening the block
            if (!sealFluidNeighbors(toDig)) {
                skipSegment("Too much lava that way, sir. Sealing it off.");
                return;
            }

            boolean wasOre = Blocks.isOre(digType);
            breaking = true;
            host.breakBlockProperly(player, world, toDig, success -> {
                breaking = false;
                if (success) {
                    digQueue.poll();
                    blocksDug++;
                    if (wasOre) {
                        oresMined++;
                        host.sayQuiet(player, "Ore in the tunnel itself — " + oresMined + " so far.");
                    }
                    queueAdjacentOres(toDig);
                } else {
                    skipSegment("That block refuses to cooperate, sir. Rerouting.");
                }
            });
            return;
        }

        // Walk into the cleared cell
        if (stepCell != null) {
            tickAdvance(npcLoc);
            return;
        }

        // Next step of the plan
        if (index >= plan.size()) {
            finish();
            return;
        }
        beginStep(plan.get(index++), npcLoc);
    }

    private void beginStep(Step step, Vec3 npcLoc) {
        // Torch the cell we're leaving
        if (step.torch && placeTorches) {
            BlockPos here = npcLoc.block();
            if (world.block(here).isAir() && world.isSolid(here.below())) {
                world.setBlock(here, BlockState.of(Ids.TORCH));
            }
        }

        for (BlockPos dig : step.digs) {
            if (!Blocks.isPassable(world, dig)) {
                digQueue.add(dig);
            }
        }

        // Butler bridge: make sure the destination has a floor
        BlockPos below = step.cell.below();
        String belowType = world.block(below).id();
        if (Blocks.isFluid(belowType) || !world.isSolid(below)) {
            if (!Blocks.isFluid(belowType) || sealBlock(below)) {
                if (!world.isSolid(below)) world.setBlock(below, BlockState.of(Ids.COBBLESTONE));
            }
        }

        stepCell = step.cell;
        advanceTicks = 0;
        navStuck = false;
    }

    private void tickAdvance(Vec3 npcLoc) {
        Vec3 cellCenter = stepCell.standing();
        double horiz = Math.hypot(npcLoc.x() - cellCenter.x(), npcLoc.z() - cellCenter.z());
        double vert = Math.abs(npcLoc.y() - stepCell.y());

        if (horiz < 0.7 && vert < 1.3) {
            stepCell = null;
            advanceTicks = 0;
            return;
        }

        double distance = npcLoc.distance(cellCenter);
        if (distance > MAX_TRANSITION_DISTANCE) {
            // Shouldn't happen inside our own mine. Before v0.11.0 this simply
            // stopped; the tunnel is still there, so stepping back into it is a
            // move worth offering.
            final BlockPos target = stepCell;
            failEarly(TaskFailure.of(player, "branch_mine")
                    .step("walking to dig cell " + index + " of " + plan.size())
                    .reason("drifted " + String.format("%.1f", distance) + " blocks from the cell he was "
                            + "walking to, past the " + MAX_TRANSITION_DISTANCE + "-block limit — "
                            + "pathfinding has lost the tunnel")
                    .say("I seem to have lost the mine, sir. Stopping here. ("
                            + oresMined + " ores recovered.)")
                    .where(new Site(world, npcLoc))
                    .state("plan progress", index + " of " + plan.size() + " cells")
                    .state("ores recovered", oresMined)
                    .state("blocks excavated", blocksDug)
                    .option("return_to_mine",
                            "step back into the tunnel at the cell you were walking to and carry on digging",
                            () -> resumeAt(target)));
            return;
        }

        advanceTicks++;
        if (!butler.isNavigating() || navStuck) {
            navStuck = false;
            butler.navigateTo(cellCenter, () -> navStuck = true);
        }

        int limit = distance > 2.5 ? FAR_NUDGE_TICKS : NUDGE_TICKS;
        if (advanceTicks > limit) {
            butler.cancelNavigation();
            butler.teleport(cellCenter, new Look(butler.look().yaw(), 0));
            stepCell = null;
            advanceTicks = 0;
        }
    }

    private void tickReturning(Vec3 npcLoc) {
        if (resumeCell == null) {
            mode = Mode.EXECUTING;
            return;
        }
        Vec3 center = resumeCell.standing();
        if (npcLoc.distance(center) < 2.0) {
            resumeCell = null;
            mode = Mode.EXECUTING;
            host.sayQuiet(player, "Back to work.");
            return;
        }
        advanceTicks++;
        if (!butler.isNavigating() || navStuck) {
            navStuck = false;
            butler.navigateTo(center, () -> navStuck = true);
        }
        if (advanceTicks > FAR_NUDGE_TICKS * 2) {
            butler.cancelNavigation();
            butler.teleport(center);
            advanceTicks = 0;
        }
    }

    // ==================== ORE HARVESTING ====================

    /** Queue ores adjacent to a just-cleared block (bounded so veins don't derail the plan). */
    private void queueAdjacentOres(BlockPos cleared) {
        if (harvestQueue.size() >= 12) return;
        for (Facing face : Facing.values()) {
            BlockPos neighbor = cleared.side(face);
            if (Blocks.isOre(world.block(neighbor).id())) {
                if (!harvestQueue.contains(neighbor)) {
                    harvestQueue.add(neighbor);
                }
            }
        }
    }

    // ==================== SAFETY ====================

    /** Seal fluid neighbors of a block about to be dug. False = too much lava. */
    private boolean sealFluidNeighbors(BlockPos about) {
        int sealed = 0;
        for (Facing face : Facing.values()) {
            BlockPos neighbor = about.side(face);
            if (Blocks.isFluid(world, neighbor)) {
                if (++sealed > 3) return false; // swimming in it — don't open this wall
                sealBlock(neighbor);
            }
        }
        return true;
    }

    private boolean sealBlock(BlockPos fluid) {
        world.setBlock(fluid, BlockState.of(Ids.COBBLESTONE));
        sealedPockets++;
        return true;
    }

    private void sealAndSkipSegment(BlockPos fluid) {
        sealBlock(fluid);
        skipSegment("Sealed off a liquid pocket, sir. Rerouting.");
    }

    /** Abandon the rest of the current tunnel segment; jump to the next one. */
    private void skipSegment(String message) {
        host.sayQuiet(player, message);
        digQueue.clear();
        stepCell = null;
        if (index == 0 || index > plan.size()) {
            // No earlier segment to fall back on. The rest of the plan is still
            // good, so skipping the opening one is a real way forward.
            failEarly(TaskFailure.of(player, "branch_mine")
                    .step("clearing the opening segment of the mine")
                    .reason("the first segment was blocked before any of it was dug, so there is no "
                            + "earlier segment to reroute into")
                    .say("The very first stretch is blocked, sir. Stopping here. ("
                            + oresMined + " ores recovered.)")
                    .where(here())
                    .state("plan length", plan.size() + " cells")
                    .state("ores recovered", oresMined)
                    .option("skip_first_segment",
                            "abandon the opening segment and start the mine from the second one instead",
                            () -> skipToSegment(1)));
            return;
        }
        int current = plan.get(index - 1).segment;
        while (index < plan.size() && plan.get(index).segment == current) {
            index++;
        }
        if (index >= plan.size()) {
            finish();
        }
    }

    // ==================== COMPLETION ====================

    private void finish() {
        mode = Mode.DONE;
        butler.cancelNavigation();
        String seals = sealedPockets > 0 ? " Sealed " + sealedPockets + " liquid pockets along the way." : "";
        host.say(player, "The mine is complete, sir. " + blocksDug + " blocks excavated, "
                + oresMined + " ores recovered." + seals + " It's lit and walkable whenever you care to visit.");
        Entertainer.celebrate(host, player);
        if (autoDeposit && deposits.hasChest(player) && host.lootSlotsUsed(player) > 0) {
            deposits.startDepositRun(player, deposits.getChest(player).orElseThrow(), () -> {});
        }
    }

    private void finishEarly(String message) {
        mode = Mode.DONE;
        butler.cancelNavigation();
        host.say(player, message + " (" + oresMined + " ores recovered.)");
    }

    /**
     * As {@link #finishEarly}, but the failure goes to self-explain first along
     * with whatever moves this particular site can actually make. If diagnosis
     * is off or out of attempts the builder's own message is said instead, so
     * this is the old behaviour plus a chance at something better.
     */
    private void failEarly(TaskFailure.Builder failure) {
        mode = Mode.DONE;
        butler.cancelNavigation();
        host.reportFailure(failure.build());
    }

    private Site here() {
        Vec3 at = host.currentLocation(player);
        return at == null ? null : new Site(world, at);
    }

    // ==================== RECOVERY MOVES ====================
    // Only reachable by name, and only from the site that offered them.

    /** Step back into the tunnel at a known-good cell and pick the plan up there. */
    private void resumeAt(BlockPos cell) {
        if (cell == null || !player.isOnline() || !butler.isSpawned()) return;
        butler.cancelNavigation();
        butler.teleport(cell.standing());
        restartLoop();
    }

    /** Jump the plan forward to the first cell of the given segment. */
    private void skipToSegment(int segment) {
        if (!player.isOnline() || !butler.isSpawned()) return;
        int target = -1;
        for (int i = 0; i < plan.size(); i++) {
            if (plan.get(i).segment >= segment) {
                target = i;
                break;
            }
        }
        if (target < 0) {
            finishEarly("There is no more plan to move on to, sir.");
            return;
        }
        index = target;
        restartLoop();
    }

    /** Keep digging with nowhere to put the loot. Drops stay on the tunnel floor. */
    private void resumeWithoutDeposits() {
        if (!player.isOnline() || !butler.isSpawned()) return;
        depositsPaused = true;
        restartLoop();
    }

    /** Clear the transient movement state and start the tick loop again. */
    private void restartLoop() {
        // The loop that failed cancels itself once it sees mode == DONE, but a
        // recovery move lands seconds later and must not race it into a second
        // registered task.
        host.stopTask(player);
        digQueue.clear();
        stepCell = null;
        resumeCell = null;
        advanceTicks = 0;
        navStuck = false;
        breaking = false;
        mode = Mode.EXECUTING;
        butler.applyNavigationDefaults(() -> navStuck = true);
        runLoop();
    }
}
