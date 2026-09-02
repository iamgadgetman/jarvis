package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.Jarvis;
import com.gadgetman.jarvis.npc.provider.INPCProvider;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

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
 * Movement/digging reuses the v0.1.1 tunnel-executor primitives in JarvisNPC:
 * dig with vanilla timing, walk into the cell, nudge only if the 1-block walk
 * stalls.
 */
class BranchMiner {

    private enum Mode { EXECUTING, RETURNING, DONE }

    /** One planned cell of the mine. */
    private static class Step {
        final Location cell;            // Where Jarvis stands after this step
        final List<Location> digs;      // Blocks to clear for this step
        final boolean torch;            // Place a torch at the previous cell
        final int segment;              // Contiguous tunnel section id

        Step(Location cell, List<Location> digs, boolean torch, int segment) {
            this.cell = cell;
            this.digs = digs;
            this.torch = torch;
            this.segment = segment;
        }
    }

    private final Jarvis plugin;
    private final JarvisNPC host;
    private final Player player;
    private final INPCProvider provider;
    private final DepositManager deposits;

    private final List<Step> plan = new ArrayList<>();
    private int index = 0;
    private Mode mode = Mode.EXECUTING;

    private final ArrayDeque<Location> digQueue = new ArrayDeque<>();
    private Location stepCell = null;
    private Location resumeCell = null;
    private int advanceTicks = 0;
    private boolean breaking = false;
    private boolean navStuck = false;
    private final ArrayDeque<Location> harvestQueue = new ArrayDeque<>();

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

    BranchMiner(JarvisNPC host, Player player, DepositManager deposits) {
        this.host = host;
        this.plugin = host.getPlugin();
        this.player = player;
        this.provider = host.getProvider();
        this.deposits = deposits;

        var cfg = plugin.getConfig();
        this.targetY = cfg.getInt("mining.branch.target-y", -54);
        this.corridorLength = Math.max(4, cfg.getInt("mining.branch.corridor-length", 32));
        this.branchLength = Math.max(2, cfg.getInt("mining.branch.branch-length", 12));
        this.branchSpacing = Math.max(2, cfg.getInt("mining.branch.branch-spacing", 3));
        this.placeTorches = cfg.getBoolean("mining.branch.place-torches", true);
        this.torchInterval = Math.max(2, cfg.getInt("mining.branch.torch-interval", 8));
        this.autoDeposit = cfg.getBoolean("mining.auto-deposit", true);
    }

    // ==================== PLANNING ====================

    /** Build the whole mine as a deterministic list of steps, then start digging. */
    void start() {
        Location anchor = host.getCurrentLocation(player);
        World world = anchor.getWorld();
        if (world == null) return;

        int minY = world.getMinHeight() + 5;
        int depth = Math.max(targetY, minY);

        // Facing: snap the NPC's yaw to a cardinal direction
        float yaw = ((anchor.getYaw() % 360) + 360) % 360;
        int dx = 0, dz = 0;
        if (yaw >= 315 || yaw < 45) dz = 1;        // south
        else if (yaw < 135) dx = -1;               // west
        else if (yaw < 225) dz = -1;               // north
        else dx = 1;                               // east

        int fx = anchor.getBlockX(), fy = anchor.getBlockY(), fz = anchor.getBlockZ();
        int segment = 0;

        // 1) Staircase down to depth (3-high clearance for the diagonal walk)
        int px = fx, py = fy, pz = fz;
        while (py > depth) {
            px += dx; py -= 1; pz += dz;
            List<Location> digs = new ArrayList<>(3);
            digs.add(new Location(world, px, py, pz));
            digs.add(new Location(world, px, py + 1, pz));
            digs.add(new Location(world, px, py + 2, pz));
            plan.add(new Step(new Location(world, px, py, pz), digs, false, segment));
        }

        // 2) Main corridor with branch pairs on the grid
        segment++;
        int corridorSegment = segment;
        int branchCount = 0;
        for (int i = 1; i <= corridorLength; i++) {
            px += dx; pz += dz;
            List<Location> digs = new ArrayList<>(2);
            digs.add(new Location(world, px, py, pz));
            digs.add(new Location(world, px, py + 1, pz));
            plan.add(new Step(new Location(world, px, py, pz),
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
                        List<Location> bdigs = new ArrayList<>(2);
                        bdigs.add(new Location(world, bx, py, bz));
                        bdigs.add(new Location(world, bx, py + 1, bz));
                        plan.add(new Step(new Location(world, bx, py, bz),
                                bdigs, j % torchInterval == 0, segment));
                    }
                    // Then walk back to the corridor (already dug — no digs needed)
                    plan.add(new Step(new Location(world, px, py, pz),
                            new ArrayList<>(), false, segment));
                }
            }
        }

        host.say(player, "Very good, sir. Sinking a shaft to Y=" + depth + " — "
                + corridorLength + "-block gallery, " + branchCount + " branches. I shall keep it lit.");
        host.applyNavigatorDefaults(player, () -> navStuck = true);
        host.giveStartingEquipment(player);
        runLoop();
    }

    // ==================== EXECUTION ====================

    private void runLoop() {
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!provider.isSpawned(player) || !player.isOnline() || mode == Mode.DONE) {
                    cancel();
                    host.taskDone(player, this);
                    return;
                }
                Location npcLoc = host.getCurrentLocation(player);
                host.pickupNearbyItems(player, npcLoc);
                tick(npcLoc, this);
            }
        };
        task.runTaskTimer(plugin, 0L, 10L);
        host.registerTask(player, task);
    }

    private void tick(Location npcLoc, BukkitRunnable self) {
        if (breaking) return;

        // Bags full? Deliver to the chest and come back.
        if (autoDeposit && host.lootSlotsUsed(player) >= JarvisNPC.LOOT_CAPACITY - 2) {
            if (deposits.hasChest(player)) {
                host.say(player, "Bags are full, sir — running a delivery. Back shortly.");
                resumeCell = npcLoc.getBlock().getLocation();
                self.cancel();
                deposits.startDepositRun(player, deposits.getChest(player), () -> {
                    // v0.8.0: if the chest couldn't take it, don't loop forever
                    if (host.lootSlotsUsed(player) >= JarvisNPC.LOOT_CAPACITY - 2) {
                        mode = Mode.DONE;
                        host.say(player, "The chest is full and so are my bags, sir. "
                                + "Pausing the mine until there's room somewhere.");
                        return;
                    }
                    mode = Mode.RETURNING;
                    host.applyNavigatorDefaults(player, () -> navStuck = true);
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
            Location oreLoc = harvestQueue.peek();
            Block ore = oreLoc.getBlock();
            if (!host.isOre(ore.getType())
                    || npcLoc.distance(oreLoc.clone().add(0.5, 0.5, 0.5)) > REACH + 1) {
                harvestQueue.poll();
                return;
            }
            breaking = true;
            Material type = ore.getType();
            host.breakBlockProperly(player, ore, success -> {
                breaking = false;
                harvestQueue.poll();
                if (success) {
                    oresMined++;
                    host.sayQuiet(player, "Harvested " + host.formatOre(type) + " — " + oresMined + " so far.");
                    if (oresMined % 10 == 0) {
                        host.say(player, oresMined + " ores from this mine so far, sir.");
                    }
                    // Vein following: neighbors of the mined ore, if still in reach
                    queueAdjacentOres(oreLoc);
                }
            });
            return;
        }

        // Dig the current step's blocks
        if (!digQueue.isEmpty()) {
            Block toDig = digQueue.peek().getBlock();

            if (host.isPassable(toDig)) {
                digQueue.poll();
                return;
            }
            if (host.isFluid(toDig.getType())) {
                sealAndSkipSegment(toDig);
                return;
            }
            if (!host.canDig(toDig)) {
                skipSegment("Something rather solid blocks that tunnel, sir. Rerouting.");
                return;
            }
            // Seal any fluid neighbors BEFORE opening the block
            if (!sealFluidNeighbors(toDig)) {
                skipSegment("Too much lava that way, sir. Sealing it off.");
                return;
            }

            boolean wasOre = host.isOre(toDig.getType());
            breaking = true;
            host.breakBlockProperly(player, toDig, success -> {
                breaking = false;
                if (success) {
                    digQueue.poll();
                    blocksDug++;
                    if (wasOre) {
                        oresMined++;
                        host.sayQuiet(player, "Ore in the tunnel itself — " + oresMined + " so far.");
                    }
                    queueAdjacentOres(toDig.getLocation());
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

    private void beginStep(Step step, Location npcLoc) {
        // Torch the cell we're leaving
        if (step.torch && placeTorches) {
            Block here = npcLoc.getBlock();
            if (here.getType() == Material.AIR
                    && here.getRelative(BlockFace.DOWN).getType().isSolid()) {
                here.setType(Material.TORCH);
            }
        }

        for (Location dig : step.digs) {
            if (!host.isPassable(dig.getBlock())) {
                digQueue.add(dig);
            }
        }

        // Butler bridge: make sure the destination has a floor
        Block below = step.cell.clone().add(0, -1, 0).getBlock();
        if (host.isFluid(below.getType()) || !below.getType().isSolid()) {
            if (!host.isFluid(below.getType()) || sealBlock(below)) {
                if (!below.getType().isSolid()) below.setType(Material.COBBLESTONE);
            }
        }

        stepCell = step.cell;
        advanceTicks = 0;
        navStuck = false;
    }

    private void tickAdvance(Location npcLoc) {
        Location cellCenter = stepCell.clone().add(0.5, 0, 0.5);
        double horiz = Math.hypot(npcLoc.getX() - cellCenter.getX(), npcLoc.getZ() - cellCenter.getZ());
        double vert = Math.abs(npcLoc.getY() - stepCell.getY());

        if (horiz < 0.7 && vert < 1.3) {
            stepCell = null;
            advanceTicks = 0;
            return;
        }

        double distance = npcLoc.distance(cellCenter);
        if (distance > MAX_TRANSITION_DISTANCE) {
            // Shouldn't happen inside our own mine — bail politely
            finishEarly("I seem to have lost the mine, sir. Stopping here.");
            return;
        }

        advanceTicks++;
        if (!provider.isNavigating(player) || navStuck) {
            navStuck = false;
            host.navigateTo(player, cellCenter, () -> navStuck = true);
        }

        int limit = distance > 2.5 ? FAR_NUDGE_TICKS : NUDGE_TICKS;
        if (advanceTicks > limit) {
            provider.cancelNavigation(player);
            Location nudge = cellCenter.clone();
            nudge.setYaw(npcLoc.getYaw());
            provider.teleport(player, nudge);
            stepCell = null;
            advanceTicks = 0;
        }
    }

    private void tickReturning(Location npcLoc) {
        if (resumeCell == null) {
            mode = Mode.EXECUTING;
            return;
        }
        Location center = resumeCell.clone().add(0.5, 0, 0.5);
        if (npcLoc.distance(center) < 2.0) {
            resumeCell = null;
            mode = Mode.EXECUTING;
            host.sayQuiet(player, "Back to work.");
            return;
        }
        advanceTicks++;
        if (!provider.isNavigating(player) || navStuck) {
            navStuck = false;
            host.navigateTo(player, center, () -> navStuck = true);
        }
        if (advanceTicks > FAR_NUDGE_TICKS * 2) {
            provider.cancelNavigation(player);
            provider.teleport(player, center);
            advanceTicks = 0;
        }
    }

    // ==================== ORE HARVESTING ====================

    /** Queue ores adjacent to a just-cleared block (bounded so veins don't derail the plan). */
    private void queueAdjacentOres(Location cleared) {
        if (harvestQueue.size() >= 12) return;
        for (BlockFace face : new BlockFace[]{
                BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
                BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block neighbor = cleared.getBlock().getRelative(face);
            if (host.isOre(neighbor.getType())) {
                Location loc = neighbor.getLocation();
                if (!harvestQueue.contains(loc)) {
                    harvestQueue.add(loc);
                }
            }
        }
    }

    // ==================== SAFETY ====================

    /** Seal fluid neighbors of a block about to be dug. False = too much lava. */
    private boolean sealFluidNeighbors(Block about) {
        int sealed = 0;
        for (BlockFace face : new BlockFace[]{
                BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
                BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block neighbor = about.getRelative(face);
            if (host.isFluid(neighbor.getType())) {
                if (++sealed > 3) return false; // swimming in it — don't open this wall
                sealBlock(neighbor);
            }
        }
        return true;
    }

    private boolean sealBlock(Block fluid) {
        fluid.setType(Material.COBBLESTONE);
        sealedPockets++;
        return true;
    }

    private void sealAndSkipSegment(Block fluid) {
        sealBlock(fluid);
        skipSegment("Sealed off a liquid pocket, sir. Rerouting.");
    }

    /** Abandon the rest of the current tunnel segment; jump to the next one. */
    private void skipSegment(String message) {
        host.sayQuiet(player, message);
        digQueue.clear();
        stepCell = null;
        if (index == 0 || index > plan.size()) {
            finishEarly("The very first stretch is blocked, sir. Stopping here.");
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
        provider.cancelNavigation(player);
        String seals = sealedPockets > 0 ? " Sealed " + sealedPockets + " liquid pockets along the way." : "";
        host.say(player, "The mine is complete, sir. " + blocksDug + " blocks excavated, "
                + oresMined + " ores recovered." + seals + " It's lit and walkable whenever you care to visit.");
        Entertainer.celebrate(host, player);
        if (autoDeposit && deposits.hasChest(player) && host.lootSlotsUsed(player) > 0) {
            deposits.startDepositRun(player, deposits.getChest(player), () -> {});
        }
    }

    private void finishEarly(String message) {
        mode = Mode.DONE;
        provider.cancelNavigation(player);
        host.say(player, message + " (" + oresMined + " ores recovered.)");
    }
}
