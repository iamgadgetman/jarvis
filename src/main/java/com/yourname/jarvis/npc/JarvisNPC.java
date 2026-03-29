package com.yourname.jarvis.npc;

import com.yourname.jarvis.Jarvis;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.ai.NavigatorParameters;
import net.citizensnpcs.api.ai.event.NavigationCompleteEvent;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.api.trait.trait.Inventory;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JarvisNPC - Manages NPC spawning, combat, and mining
 * Version: 0.0.8
 *
 * v0.0.8: Complete mining rewrite for reliable movement
 * - Simplified state machine
 * - Direct teleport-based movement (no complex pathfinding)
 * - Step-by-step navigation with verification
 * - Extensive debug output
 */
public class JarvisNPC implements Listener {

    private final Jarvis plugin;
    private final Map<UUID, NPC> playerNPCs = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitRunnable> activeTasks = new ConcurrentHashMap<>();
    private final Map<UUID, SimpleMiningState> miningStates = new ConcurrentHashMap<>();

    // Configuration
    private static final int SEARCH_RADIUS = 12;
    private static final int PICKUP_RADIUS = 6;
    private static final double REACH_DISTANCE = 4.0;
    private static final int MINING_TICK_RATE = 10; // Every 0.5 seconds
    private static final double STEP_DISTANCE = 1.5; // Move 1.5 blocks at a time

    // Debug mode - enable for detailed logging
    private boolean debugMode = true;

    // Ore priority (highest value first)
    private static final List<Material> ORE_PRIORITY = Arrays.asList(
        Material.ANCIENT_DEBRIS,
        Material.DEEPSLATE_EMERALD_ORE, Material.EMERALD_ORE,
        Material.DEEPSLATE_DIAMOND_ORE, Material.DIAMOND_ORE,
        Material.DEEPSLATE_GOLD_ORE, Material.GOLD_ORE,
        Material.DEEPSLATE_LAPIS_ORE, Material.LAPIS_ORE,
        Material.DEEPSLATE_REDSTONE_ORE, Material.REDSTONE_ORE,
        Material.DEEPSLATE_IRON_ORE, Material.IRON_ORE,
        Material.DEEPSLATE_COPPER_ORE, Material.COPPER_ORE,
        Material.DEEPSLATE_COAL_ORE, Material.COAL_ORE,
        Material.NETHER_QUARTZ_ORE, Material.NETHER_GOLD_ORE
    );

    // Keyword → ore materials mapping for targeted mining
    private static final Map<String, Set<Material>> ORE_KEYWORDS = new java.util.LinkedHashMap<>();
    static {
        ORE_KEYWORDS.put("ancient debris", Set.of(Material.ANCIENT_DEBRIS));
        ORE_KEYWORDS.put("debris",         Set.of(Material.ANCIENT_DEBRIS));
        ORE_KEYWORDS.put("netherite",      Set.of(Material.ANCIENT_DEBRIS));
        ORE_KEYWORDS.put("emerald",        Set.of(Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE));
        ORE_KEYWORDS.put("diamond",        Set.of(Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE));
        ORE_KEYWORDS.put("gold",           Set.of(Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE, Material.NETHER_GOLD_ORE));
        ORE_KEYWORDS.put("lapis",          Set.of(Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE));
        ORE_KEYWORDS.put("redstone",       Set.of(Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE));
        ORE_KEYWORDS.put("iron",           Set.of(Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE));
        ORE_KEYWORDS.put("copper",         Set.of(Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE));
        ORE_KEYWORDS.put("quartz",         Set.of(Material.NETHER_QUARTZ_ORE));
        ORE_KEYWORDS.put("coal",           Set.of(Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE));
    }

    // Items to ignore during pickup (blocks broken while navigating)
    private static final Set<Material> JUNK_DROPS = Set.of(
        Material.COBBLESTONE, Material.COBBLED_DEEPSLATE, Material.STONE,
        Material.DIRT, Material.GRAVEL, Material.SAND, Material.FLINT,
        Material.GRANITE, Material.DIORITE, Material.ANDESITE,
        Material.DEEPSLATE, Material.TUFF, Material.CALCITE,
        Material.NETHERRACK, Material.BASALT, Material.BLACKSTONE
    );

    // ==================== SIMPLE MINING STATE ====================

    /**
     * Simplified mining state - v0.0.8
     */
    private enum MiningPhase {
        IDLE,
        SEARCHING,    // Looking for ores
        MOVING,       // Walking to ore
        MINING,       // Breaking ore
        COLLECTING,   // Picking up items
        RETURNING     // Going back to player
    }

    private static class SimpleMiningState {
        MiningPhase phase = MiningPhase.IDLE;
        Block targetOre = null;
        Location targetLocation = null;
        int oresMined = 0;
        int ticksInPhase = 0;
        int stuckTicks = 0;
        Location lastLocation = null;
        long startTime = System.currentTimeMillis();

        Material targetOreType = null;
        int miningAttempts = 0;

        // Ore type filter — null means mine any ore
        Set<Material> requestedOreTypes = null;

        void transitionTo(MiningPhase newPhase) {
            phase = newPhase;
            ticksInPhase = 0;
        }

        void clearTarget() {
            targetOre = null;
            targetLocation = null;
            targetOreType = null;
            miningAttempts = 0;
        }
    }

    // ==================== CONSTRUCTOR ====================

    public JarvisNPC(Jarvis plugin) {
        this.plugin = plugin;
        this.debugMode = plugin.getConfig().getBoolean("mining.debug", true);

        // Register as listener for navigation events
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        // Start cleanup task
        startCleanupTask();

        debug("JarvisNPC initialized (v0.0.8 - simplified mining)");
    }

    private void debug(String message) {
        if (debugMode) {
            plugin.getLogger().info("[Jarvis Debug] " + message);
        }
    }

    // ==================== NPC LIFECYCLE ====================

    public void summon(Player player) {
        NPC existing = playerNPCs.get(player.getUniqueId());
        if (existing != null && existing.isSpawned()) {
            player.sendMessage(ChatColor.YELLOW + "Jarvis: I'm already here!");
            return;
        }

        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, "Jarvis");
        Location spawnLoc = findSafeSpawnLocation(player.getLocation());

        npc.spawn(spawnLoc);
        npc.getOrAddTrait(Inventory.class);
        npc.setProtected(true);
        playerNPCs.put(player.getUniqueId(), npc);

        // Give starting equipment
        giveStartingEquipment(npc);

        player.getWorld().playSound(spawnLoc, Sound.BLOCK_BELL_USE, 1.0f, 1.0f);
        player.sendMessage(ChatColor.GREEN + "Jarvis: At your service!");

        debug("Jarvis spawned for " + player.getName() + " at " + formatLoc(spawnLoc));
    }

    public void dismiss(Player player) {
        UUID playerId = player.getUniqueId();
        NPC npc = playerNPCs.remove(playerId);
        if (npc == null) {
            player.sendMessage(ChatColor.RED + "Jarvis: I'm not summoned yet!");
            return;
        }

        // Stop any active task
        stopTask(player);

        // Drop inventory items
        dropInventoryItems(npc);

        // Clean up mining state
        miningStates.remove(playerId);

        npc.destroy();
        player.sendMessage(ChatColor.GRAY + "Jarvis: Until next time!");

        debug("Jarvis dismissed for " + player.getName());
    }

    public void handlePlayerDisconnect(Player player) {
        UUID playerId = player.getUniqueId();
        NPC npc = playerNPCs.remove(playerId);

        if (npc == null) return;

        stopTask(player);

        if (npc.isSpawned()) {
            dropInventoryItems(npc);
        }

        miningStates.remove(playerId);
        npc.destroy();

        debug("Cleaned up NPC for disconnected player: " + player.getName());
    }

    // ==================== MINING - SIMPLIFIED v0.0.8 ====================

    public void mine(Player player, String[] args) {
        // Parse optional ore type keyword from args (e.g. "diamond", "iron ore")
        Set<Material> oreFilter = null;
        if (args.length > 0) {
            String keyword = String.join(" ", args).toLowerCase().trim();
            for (Map.Entry<String, Set<Material>> entry : ORE_KEYWORDS.entrySet()) {
                if (keyword.contains(entry.getKey())) {
                    oreFilter = entry.getValue();
                    break;
                }
            }
        }
        mine(player, oreFilter);
    }

    public void mine(Player player) {
        mine(player, (Set<Material>) null);
    }

    private void mine(Player player, Set<Material> oreFilter) {
        NPC npc = getNPC(player);
        if (npc == null) {
            player.sendMessage(ChatColor.RED + "Jarvis: Summon me first!");
            return;
        }

        stopTask(player);

        SimpleMiningState state = new SimpleMiningState();
        state.requestedOreTypes = oreFilter;
        state.transitionTo(MiningPhase.SEARCHING);
        miningStates.put(player.getUniqueId(), state);

        if (oreFilter != null) {
            // Build a readable name from the first ore in the filter
            String oreName = oreFilter.iterator().next().name()
                .replace("DEEPSLATE_", "").replace("_ORE", "").replace("_", " ").toLowerCase();
            player.sendMessage(ChatColor.GOLD + "Jarvis: Hunting for " + oreName + "!");
        } else {
            player.sendMessage(ChatColor.GOLD + "Jarvis: Mining mode activated! (v0.0.9)");
        }

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!npc.isSpawned() || !player.isOnline()) {
                    cancel();
                    miningStates.remove(player.getUniqueId());
                    return;
                }

                Location npcLoc = getCurrentLocation(npc);
                state.ticksInPhase++;

                // Always try to pick up items
                pickupNearbyItems(npc, npcLoc);

                // Stuck detection
                if (state.lastLocation != null) {
                    double moved = npcLoc.distance(state.lastLocation);
                    if (moved < 0.1) {
                        state.stuckTicks++;
                    } else {
                        state.stuckTicks = 0;
                    }
                }
                state.lastLocation = npcLoc.clone();

                // State machine
                switch (state.phase) {
                    case SEARCHING -> processSearching(npc, player, state, npcLoc);
                    case MOVING -> processMoving(npc, player, state, npcLoc);
                    case MINING -> processMining(npc, player, state, npcLoc);
                    case COLLECTING -> processCollecting(npc, player, state, npcLoc);
                    case RETURNING -> processReturning(npc, player, state, npcLoc);
                }

                // Stuck recovery - after 60 ticks (3 seconds) of no movement
                if (state.stuckTicks > 60 && state.phase != MiningPhase.MINING) {
                    debug("STUCK for " + state.stuckTicks + " ticks in phase " + state.phase);
                    handleStuck(npc, player, state, npcLoc);
                }
            }
        };

        task.runTaskTimer(plugin, 0L, MINING_TICK_RATE);
        activeTasks.put(player.getUniqueId(), task);

        debug("Mining task started for " + player.getName());
    }

    /**
     * SEARCHING - Find the nearest ore
     */
    private void processSearching(NPC npc, Player player, SimpleMiningState state, Location npcLoc) {
        debug("SEARCHING phase, tick " + state.ticksInPhase);

        Block ore = findNearestOre(npcLoc, state.requestedOreTypes);

        if (ore == null) {
            if (state.ticksInPhase > 5) {
                String suffix = state.requestedOreTypes != null ? " No more of that type nearby." : "";
                player.sendMessage(ChatColor.YELLOW + "Jarvis: No ores found nearby. Mined " + state.oresMined + " ores total." + suffix);
                stopTask(player);
                miningStates.remove(player.getUniqueId());
            }
            return;
        }

        state.targetOre = ore;
        state.targetLocation = ore.getLocation().add(0.5, 0.5, 0.5);

        player.sendMessage(ChatColor.AQUA + "Jarvis: Found " + formatOre(ore.getType()) + "!");
        debug("Found ore: " + ore.getType() + " at " + formatLoc(ore.getLocation()));

        state.transitionTo(MiningPhase.MOVING);
    }

    /**
     * MOVING - Walk towards the ore using step-by-step movement
     * v0.0.8: Uses teleportation for reliable movement
     */
    private void processMoving(NPC npc, Player player, SimpleMiningState state, Location npcLoc) {
        if (state.targetLocation == null) {
            state.transitionTo(MiningPhase.SEARCHING);
            return;
        }

        double distance = npcLoc.distance(state.targetLocation);
        debug("MOVING phase, distance=" + String.format("%.1f", distance) + ", tick=" + state.ticksInPhase);

        // Close enough to mine
        if (distance <= REACH_DISTANCE) {
            debug("Within reach, transitioning to MINING");
            state.transitionTo(MiningPhase.MINING);
            return;
        }

        // Try to move closer using Citizens Navigator
        Navigator nav = npc.getNavigator();
        if (nav != null) {
            if (!nav.isNavigating()) {
                // Start navigation
                NavigatorParameters params = nav.getLocalParameters();
                params.baseSpeed(0.6f);
                params.distanceMargin(2.0);
                params.range(SEARCH_RADIUS * 2);

                nav.setTarget(state.targetLocation);
                debug("Started navigation to " + formatLoc(state.targetLocation));
            } else if (state.stuckTicks > 20) {
                // Navigator is active but we haven't moved — cancel so it restarts next tick
                debug("Navigator stuck, cancelling for restart");
                nav.cancelNavigation();
            }
        }

        // Fallback: If stuck for too long, teleport step-by-step
        if (state.stuckTicks > 20) {
            // Clear path by breaking blocks in the way
            Block blockInFront = getBlockInFront(npcLoc, state.targetLocation);
            if (blockInFront != null && blockInFront.getType().isSolid() && !isOre(blockInFront.getType())) {
                debug("Breaking blocking block: " + blockInFront.getType());
                blockInFront.breakNaturally(getOrRestoreTool(npc));
                state.stuckTicks = 0;
                return;
            }

            // If no block to break, teleport closer
            if (state.stuckTicks > 40) {
                // Cancel navigator before teleporting so it doesn't fight the position change
                if (nav != null) nav.cancelNavigation();
                Location stepLoc = getStepTowards(npcLoc, state.targetLocation, STEP_DISTANCE);
                if (stepLoc != null && isSafeToStand(stepLoc)) {
                    npc.teleport(stepLoc, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                    debug("Teleported step towards ore: " + formatLoc(stepLoc));
                    state.stuckTicks = 0;
                }
            }
        }

        // Timeout - ore is unreachable
        if (state.ticksInPhase > 100) {
            player.sendMessage(ChatColor.YELLOW + "Jarvis: Can't reach that ore, finding another...");
            state.targetOre = null;
            state.targetLocation = null;
            state.transitionTo(MiningPhase.SEARCHING);
        }
    }

    /**
     * MINING - Break the ore block.
     * breakNaturally() is synchronous: the block is AIR immediately after the call.
     * We check success on the same tick, retry up to 3 times if somehow it failed.
     */
    private void processMining(NPC npc, Player player, SimpleMiningState state, Location npcLoc) {
        debug("MINING phase, tick=" + state.ticksInPhase);

        // Ore already gone (e.g. broken by player between ticks) — snapshot type once
        if (state.targetOre == null) {
            debug("Target ore reference is null, going to COLLECTING");
            state.transitionTo(MiningPhase.COLLECTING);
            return;
        }
        Material snapshotType = state.targetOre.getType();
        if (!isOre(snapshotType)) {
            debug("Target ore already gone (" + snapshotType + "), going to COLLECTING");
            state.transitionTo(MiningPhase.COLLECTING);
            return;
        }

        Location oreLoc = state.targetOre.getLocation().clone().add(0.5, 0.5, 0.5);
        double distance = npcLoc.distance(oreLoc);

        if (distance > REACH_DISTANCE + 1) {
            debug("Too far to mine (" + String.format("%.1f", distance) + "), back to MOVING");
            state.transitionTo(MiningPhase.MOVING);
            return;
        }

        faceLocation(npc, oreLoc);

        // Re-verify immediately before breaking — another player/plugin could have changed it
        if (!isOre(state.targetOre.getType())) {
            debug("Ore disappeared between check and break, going to COLLECTING");
            state.transitionTo(MiningPhase.COLLECTING);
            return;
        }

        state.targetOreType = snapshotType;
        debug("Breaking " + state.targetOreType + " (attempt " + (state.miningAttempts + 1) + ")");
        state.targetOre.breakNaturally(getOrRestoreTool(npc));

        // breakNaturally() is synchronous — block is AIR right now if it succeeded
        boolean broke = state.targetOre.getType() == Material.AIR
                || !isOre(state.targetOre.getType());

        if (broke) {
            state.oresMined++;
            player.sendMessage(ChatColor.GREEN + "Jarvis: Mined " + formatOre(state.targetOreType)
                    + "! (Total: " + state.oresMined + ")");
            debug("Break confirmed: " + state.targetOreType + ", total=" + state.oresMined);
            state.clearTarget();
            state.transitionTo(MiningPhase.COLLECTING);
        } else {
            // Block didn't break — protected or indestructible
            state.miningAttempts++;
            debug("Break failed (attempt " + state.miningAttempts + ")");
            if (state.miningAttempts >= 3) {
                player.sendMessage(ChatColor.YELLOW + "Jarvis: Can't break that "
                        + formatOre(state.targetOreType) + ", moving on.");
                state.clearTarget();
                state.transitionTo(MiningPhase.SEARCHING);
            }
            // else: try again next tick
        }
    }

    /**
     * COLLECTING - Pick up dropped items
     */
    private void processCollecting(NPC npc, Player player, SimpleMiningState state, Location npcLoc) {
        debug("COLLECTING phase, tick " + state.ticksInPhase);

        // Wait a bit for items to spawn
        if (state.ticksInPhase < 3) {
            return;
        }

        // Pick up any items
        pickupNearbyItems(npc, npcLoc);

        // Go back to searching for more ores
        state.transitionTo(MiningPhase.SEARCHING);
    }

    /**
     * RETURNING - Go back to player
     */
    private void processReturning(NPC npc, Player player, SimpleMiningState state, Location npcLoc) {
        Location playerLoc = player.getLocation();
        double distance = npcLoc.distance(playerLoc);

        if (distance < 5) {
            player.sendMessage(ChatColor.GREEN + "Jarvis: I'm back! Mined " + state.oresMined + " ores.");
            stopTask(player);
            miningStates.remove(player.getUniqueId());
            return;
        }

        // Teleport towards player
        Location stepLoc = getStepTowards(npcLoc, playerLoc, STEP_DISTANCE * 2);
        if (stepLoc != null) {
            npc.teleport(stepLoc, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
    }

    /**
     * Handle being stuck
     */
    private void handleStuck(NPC npc, Player player, SimpleMiningState state, Location npcLoc) {
        debug("Handling stuck state");

        // Try to clear surrounding blocks
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block block = npcLoc.getBlock().getRelative(face);
            if (block.getType().isSolid() && !isOre(block.getType()) && block.getType().getHardness() >= 0) {
                block.breakNaturally(getOrRestoreTool(npc));
                debug("Cleared blocking block: " + block.getType());
                state.stuckTicks = 0;
                return;
            }
        }

        // If we can't clear blocks, try teleporting to player
        if (state.stuckTicks > 100) {
            player.sendMessage(ChatColor.YELLOW + "Jarvis: I'm stuck! Coming back to you.");
            Location safeLoc = findSafeSpawnLocation(player.getLocation());
            npc.teleport(safeLoc, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
            state.stuckTicks = 0;
            state.targetOre = null;
            state.targetLocation = null;
            state.transitionTo(MiningPhase.SEARCHING);
        }
    }

    // ==================== ORE FINDING ====================

    private Block findNearestOre(Location center, Set<Material> filter) {
        Block nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        int nearestPriority = Integer.MAX_VALUE;

        // Use getBlockAt() to avoid allocating a Location object per iteration
        org.bukkit.World world = center.getWorld();
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();

        for (int x = -SEARCH_RADIUS; x <= SEARCH_RADIUS; x++) {
            for (int y = -SEARCH_RADIUS; y <= SEARCH_RADIUS; y++) {
                for (int z = -SEARCH_RADIUS; z <= SEARCH_RADIUS; z++) {
                    Block block = world.getBlockAt(cx + x, cy + y, cz + z);
                    Material type = block.getType();

                    // Apply ore type filter
                    if (filter != null) {
                        if (!filter.contains(type)) continue;
                    } else {
                        if (!isOre(type)) continue;
                    }

                    int priority = ORE_PRIORITY.indexOf(type);
                    if (priority < 0) priority = 999;

                    // Use squared distance — avoids sqrt and no Location object needed
                    double distSq = (x * x) + (y * y) + (z * z);

                    // Prefer higher priority ores, then closer ores
                    if (priority < nearestPriority || (priority == nearestPriority && distSq < nearestDistSq)) {
                        nearest = block;
                        nearestDistSq = distSq;
                        nearestPriority = priority;
                    }
                }
            }
        }

        return nearest;
    }

    private boolean isOre(Material type) {
        String name = type.name();
        return name.contains("_ORE") || type == Material.ANCIENT_DEBRIS;
    }

    // ==================== MOVEMENT HELPERS ====================

    private Block getBlockInFront(Location from, Location to) {
        Vector direction = to.toVector().subtract(from.toVector()).normalize();
        Location checkLoc = from.clone().add(direction.multiply(1.0));

        // Check at eye level and feet level
        Block atFeet = checkLoc.getBlock();
        Block atHead = checkLoc.clone().add(0, 1, 0).getBlock();

        if (atFeet.getType().isSolid()) return atFeet;
        if (atHead.getType().isSolid()) return atHead;

        return null;
    }

    private Location getStepTowards(Location from, Location to, double stepSize) {
        Vector direction = to.toVector().subtract(from.toVector());
        double distance = direction.length();

        if (distance < stepSize) {
            return to.clone();
        }

        direction.normalize().multiply(stepSize);
        Location step = from.clone().add(direction);

        // Ensure we land on solid ground
        Block ground = step.getBlock().getRelative(BlockFace.DOWN);
        if (!ground.getType().isSolid()) {
            // Find ground below
            for (int y = 0; y < 5; y++) {
                Block check = step.clone().add(0, -y, 0).getBlock();
                if (check.getRelative(BlockFace.DOWN).getType().isSolid()) {
                    return check.getLocation().add(0.5, 0, 0.5);
                }
            }
            return null; // No safe ground found
        }

        return step;
    }

    private boolean isSafeToStand(Location loc) {
        Block feet = loc.getBlock();
        Block head = feet.getRelative(BlockFace.UP);
        Block ground = feet.getRelative(BlockFace.DOWN);

        // Need solid ground
        if (!ground.getType().isSolid()) return false;

        // Need air for body
        if (feet.getType().isSolid()) return false;
        if (head.getType().isSolid()) return false;

        // Avoid hazards under feet
        Material groundType = ground.getType();
        if (groundType == Material.LAVA || groundType == Material.FIRE ||
            groundType == Material.MAGMA_BLOCK || groundType == Material.CACTUS) {
            return false;
        }

        // Avoid water or lava at body level
        Material feetType = feet.getType();
        Material headType = head.getType();
        if (feetType == Material.WATER || feetType == Material.LAVA ||
            headType == Material.WATER || headType == Material.LAVA) {
            return false;
        }

        return true;
    }

    private void faceLocation(NPC npc, Location target) {
        Location npcLoc = getCurrentLocation(npc);
        Vector direction = target.toVector().subtract(npcLoc.toVector());

        float yaw = (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
        float pitch = (float) Math.toDegrees(-Math.atan2(direction.getY(),
            Math.sqrt(direction.getX() * direction.getX() + direction.getZ() * direction.getZ())));

        npcLoc.setYaw(yaw);
        npcLoc.setPitch(pitch);

        if (npc.getEntity() != null) {
            npc.getEntity().teleport(npcLoc);
        }
    }

    // ==================== ITEM PICKUP ====================

    private void pickupNearbyItems(NPC npc, Location npcLoc) {
        if (npc.getEntity() == null) return;

        Inventory invTrait = npc.getOrAddTrait(Inventory.class);

        for (Entity entity : npc.getEntity().getNearbyEntities(PICKUP_RADIUS, PICKUP_RADIUS, PICKUP_RADIUS)) {
            if (entity instanceof Item item) {
                ItemStack stack = item.getItemStack();

                // Skip worthless navigation debris
                if (JUNK_DROPS.contains(stack.getType())) continue;

                // Add to inventory
                ItemStack[] contents = invTrait.getContents();
                boolean added = false;

                for (int i = 0; i < contents.length; i++) {
                    if (contents[i] == null) {
                        contents[i] = stack.clone();
                        added = true;
                        break;
                    } else if (contents[i].isSimilar(stack) &&
                               contents[i].getAmount() < contents[i].getMaxStackSize()) {
                        int canAdd = contents[i].getMaxStackSize() - contents[i].getAmount();
                        int toAdd = Math.min(canAdd, stack.getAmount());
                        contents[i].setAmount(contents[i].getAmount() + toAdd);
                        added = true;
                        break;
                    }
                }

                if (added) {
                    invTrait.setContents(contents);
                    item.remove();
                    npcLoc.getWorld().playSound(npcLoc, Sound.ENTITY_ITEM_PICKUP, 0.3f, 1.2f);
                }
            }
        }
    }

    // ==================== UTILITY METHODS ====================

    /**
     * Returns the tool in Jarvis's hand, restoring starting equipment if it is missing.
     * Prevents null being passed to breakNaturally() which silently fails.
     */
    private ItemStack getOrRestoreTool(NPC npc) {
        ItemStack tool = npc.getOrAddTrait(Equipment.class).get(Equipment.EquipmentSlot.HAND);
        if (tool == null) {
            debug("Tool missing — restoring starting equipment");
            giveStartingEquipment(npc);
            tool = npc.getOrAddTrait(Equipment.class).get(Equipment.EquipmentSlot.HAND);
        }
        return tool;
    }

    public NPC getNPC(Player player) {
        return playerNPCs.get(player.getUniqueId());
    }

    private Location getCurrentLocation(NPC npc) {
        if (npc.getEntity() != null) {
            return npc.getEntity().getLocation();
        }
        return npc.getStoredLocation();
    }

    private Location findSafeSpawnLocation(Location center) {
        // Try locations around the center
        for (int dx = 0; dx <= 3; dx++) {
            for (int dz = 0; dz <= 3; dz++) {
                for (int dir = 0; dir < 4; dir++) {
                    int x = (dir == 0 || dir == 2) ? dx : -dx;
                    int z = (dir == 0 || dir == 1) ? dz : -dz;

                    Location check = center.clone().add(x, 0, z);
                    if (isSafeToStand(check)) {
                        return check;
                    }
                }
            }
        }
        return center; // Fallback
    }

    private void giveStartingEquipment(NPC npc) {
        Equipment equipment = npc.getOrAddTrait(Equipment.class);

        // Diamond pickaxe with Fortune III
        ItemStack pickaxe = new ItemStack(Material.DIAMOND_PICKAXE);
        pickaxe.addUnsafeEnchantment(Enchantment.FORTUNE, 3);
        equipment.set(Equipment.EquipmentSlot.HAND, pickaxe);

        // Some dirt for climbing
        Inventory inv = npc.getOrAddTrait(Inventory.class);
        ItemStack[] contents = inv.getContents();
        contents[0] = new ItemStack(Material.DIRT, 32);
        inv.setContents(contents);
    }

    private void dropInventoryItems(NPC npc) {
        if (!npc.isSpawned()) return;

        Location dropLoc = getCurrentLocation(npc);
        Inventory invTrait = npc.getOrAddTrait(Inventory.class);
        ItemStack[] contents = invTrait.getContents();

        for (ItemStack item : contents) {
            if (item != null && item.getType() != Material.AIR) {
                // Don't drop starting equipment
                if (item.getType() == Material.DIAMOND_PICKAXE ||
                    item.getType() == Material.DIRT) {
                    continue;
                }
                dropLoc.getWorld().dropItemNaturally(dropLoc, item.clone());
            }
        }
    }

    public void stopTask(Player player) {
        BukkitRunnable task = activeTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }

        NPC npc = getNPC(player);
        if (npc != null && npc.getNavigator() != null) {
            npc.getNavigator().cancelNavigation();
        }
    }

    public void stop(Player player) {
        stopTask(player);
        miningStates.remove(player.getUniqueId());
        player.sendMessage(ChatColor.GRAY + "Jarvis: Stopping current task.");
    }

    private String formatLoc(Location loc) {
        return String.format("(%.1f, %.1f, %.1f)", loc.getX(), loc.getY(), loc.getZ());
    }

    private String formatOre(Material ore) {
        String name = ore.name().replace("DEEPSLATE_", "").replace("_ORE", "").replace("_", " ");
        return name.substring(0, 1).toUpperCase() + name.substring(1).toLowerCase();
    }

    // ==================== COMBAT MODE ====================

    public void attack(Player player) {
        NPC npc = getNPC(player);
        if (npc == null) {
            player.sendMessage(ChatColor.RED + "Jarvis: Summon me first!");
            return;
        }

        stopTask(player);

        player.sendMessage(ChatColor.RED + "Jarvis: Combat mode engaged!");

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!npc.isSpawned() || !player.isOnline()) {
                    cancel();
                    return;
                }

                Location npcLoc = getCurrentLocation(npc);

                // Find nearest hostile mob
                Monster target = null;
                double nearestDist = 16;

                for (Entity entity : npc.getEntity().getNearbyEntities(16, 16, 16)) {
                    if (entity instanceof Monster monster) {
                        double dist = npcLoc.distance(monster.getLocation());
                        if (dist < nearestDist) {
                            target = monster;
                            nearestDist = dist;
                        }
                    }
                }

                if (target != null) {
                    // Move towards and attack
                    if (nearestDist > 2) {
                        Navigator nav = npc.getNavigator();
                        if (nav != null && !nav.isNavigating()) {
                            nav.setTarget(target, true);
                        }
                    } else {
                        // Attack
                        target.damage(6.0, npc.getEntity());
                        npcLoc.getWorld().playSound(npcLoc, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1f);
                    }
                }

                // Also pick up items
                pickupNearbyItems(npc, npcLoc);
            }
        };

        task.runTaskTimer(plugin, 0L, 10L);
        activeTasks.put(player.getUniqueId(), task);
    }

    /**
     * Battle another player (PvP training)
     */
    public void battle(Player player, Player target) {
        NPC npc = getNPC(player);
        if (npc == null) {
            player.sendMessage(ChatColor.RED + "Jarvis: Summon me first!");
            return;
        }

        stopTask(player);

        player.sendMessage(ChatColor.RED + "Jarvis: Engaging " + target.getName() + " in battle!");
        target.sendMessage(ChatColor.YELLOW + "Jarvis (" + player.getName() + "'s companion) is challenging you!");

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!npc.isSpawned() || !player.isOnline() || !target.isOnline()) {
                    player.sendMessage(ChatColor.YELLOW + "Jarvis: Battle ended.");
                    cancel();
                    return;
                }

                Location npcLoc = getCurrentLocation(npc);
                Location targetLoc = target.getLocation();
                double distance = npcLoc.distance(targetLoc);

                if (distance > 2) {
                    // Move towards target
                    Navigator nav = npc.getNavigator();
                    if (nav != null && !nav.isNavigating()) {
                        nav.setTarget(target, true);
                    }
                } else {
                    // Attack
                    target.damage(4.0, npc.getEntity());
                    npcLoc.getWorld().playSound(npcLoc, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1f);
                }
            }
        };

        task.runTaskTimer(plugin, 0L, 20L);
        activeTasks.put(player.getUniqueId(), task);
    }

    // ==================== OTHER COMMANDS ====================

    public void returnToPlayer(Player player) {
        NPC npc = getNPC(player);
        if (npc == null) return;

        stopTask(player);

        Location safeLoc = findSafeSpawnLocation(player.getLocation());
        npc.teleport(safeLoc, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);

        player.sendMessage(ChatColor.GREEN + "Jarvis: Right behind you!");
    }

    public void openInventory(Player player) {
        NPC npc = getNPC(player);
        if (npc == null) {
            player.sendMessage(ChatColor.RED + "Jarvis: I'm not summoned yet!");
            return;
        }

        Inventory invTrait = npc.getOrAddTrait(Inventory.class);
        invTrait.openInventory(player);
    }

    public void clearInventory(Player player) {
        NPC npc = getNPC(player);
        if (npc == null) {
            player.sendMessage(ChatColor.RED + "Jarvis: I'm not summoned yet!");
            return;
        }

        Inventory invTrait = npc.getOrAddTrait(Inventory.class);
        Location dropLoc = getCurrentLocation(npc);

        // Drop non-equipment items
        ItemStack[] contents = invTrait.getContents();
        int dropped = 0;

        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null && item.getType() != Material.AIR &&
                item.getType() != Material.DIAMOND_PICKAXE && item.getType() != Material.DIRT) {
                dropLoc.getWorld().dropItemNaturally(dropLoc, item.clone());
                contents[i] = null;
                dropped += item.getAmount();
            }
        }

        invTrait.setContents(contents);

        // Restore starting equipment
        giveStartingEquipment(npc);

        player.sendMessage(ChatColor.GREEN + "Jarvis: Dropped " + dropped + " items.");
    }

    // ==================== BRANCH MINING (Simplified) ====================

    public void startBranchMining(Player player) {
        // For now, just use regular mining
        player.sendMessage(ChatColor.YELLOW + "Jarvis: Using smart mining mode.");
        mine(player);
    }

    // ==================== STATUS & INFO ====================

    public int getActiveNpcCount() {
        return playerNPCs.size();
    }

    public int getActiveTaskCount() {
        return activeTasks.size();
    }

    public NPC getNPCForPlayer(UUID uuid) {
        return playerNPCs.get(uuid);
    }

    public void dismissAll() {
        for (NPC npc : playerNPCs.values()) {
            if (npc.isSpawned()) {
                dropInventoryItems(npc);
            }
            npc.destroy();
        }
        playerNPCs.clear();
        activeTasks.values().forEach(BukkitRunnable::cancel);
        activeTasks.clear();
        miningStates.clear();
    }

    // ==================== CLEANUP ====================

    private void startCleanupTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                // Clean up NPCs for offline players
                Iterator<Map.Entry<UUID, NPC>> it = playerNPCs.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<UUID, NPC> entry = it.next();
                    if (plugin.getServer().getPlayer(entry.getKey()) == null) {
                        NPC npc = entry.getValue();
                        if (npc.isSpawned()) {
                            dropInventoryItems(npc);
                        }
                        npc.destroy();
                        it.remove();
                        activeTasks.remove(entry.getKey());
                        miningStates.remove(entry.getKey());
                    }
                }
            }
        }.runTaskTimer(plugin, 6000L, 6000L); // Every 5 minutes
    }
}
