package com.yourname.jarvis.npc;

import com.yourname.jarvis.Jarvis;
import com.yourname.jarvis.npc.provider.INPCProvider;
import com.yourname.jarvis.npc.custom.CustomNPCProvider;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
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
    private final INPCProvider provider;
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

        void transitionTo(MiningPhase newPhase) {
            phase = newPhase;
            ticksInPhase = 0;
        }
    }

    // ==================== CONSTRUCTOR ====================

    public JarvisNPC(Jarvis plugin, INPCProvider provider) {
        this.plugin = plugin;
        this.provider = provider;
        this.debugMode = plugin.getConfig().getBoolean("mining.debug", true);

        // Register as listener for navigation events
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        // Start cleanup task
        startCleanupTask();

        debug("JarvisNPC initialized (v0.0.9 - provider-based) using " + provider.getProviderName());
    }

    private void debug(String message) {
        if (debugMode) {
            plugin.getLogger().info("[Jarvis Debug] " + message);
        }
    }

    // ==================== NPC LIFECYCLE ====================

    public void summon(Player player) {
        if (provider.isSpawned(player)) {
            player.sendMessage(ChatColor.YELLOW + "Jarvis: I'm already here!");
            return;
        }

        Location spawnLoc = findSafeSpawnLocation(player.getLocation());
        provider.spawn(player, spawnLoc, "Jarvis");

        player.sendMessage(ChatColor.GREEN + "Jarvis: At your service!");

        debug("Jarvis spawned for " + player.getName() + " at " + formatLoc(spawnLoc));
    }

    public void dismiss(Player player) {
        if (!provider.isSpawned(player)) {
            player.sendMessage(ChatColor.RED + "Jarvis: I'm not summoned yet!");
            return;
        }

        // Stop any active task
        stopTask(player);

        // Clean up mining state
        miningStates.remove(player.getUniqueId());

        // Despawn via provider (handles inventory drop)
        provider.despawn(player);

        player.sendMessage(ChatColor.GRAY + "Jarvis: Until next time!");

        debug("Jarvis dismissed for " + player.getName());
    }

    public void handlePlayerDisconnect(Player player) {
        stopTask(player);
        miningStates.remove(player.getUniqueId());
        provider.handlePlayerDisconnect(player);

        debug("Cleaned up NPC for disconnected player: " + player.getName());
    }

    // ==================== MINING - SIMPLIFIED v0.0.8 ====================

    public void mine(Player player, String[] args) {
        mine(player);
    }

    public void mine(Player player) {
        if (!provider.isSpawned(player)) {
            player.sendMessage(ChatColor.RED + "Jarvis: Summon me first!");
            return;
        }

        stopTask(player);

        SimpleMiningState state = new SimpleMiningState();
        state.transitionTo(MiningPhase.SEARCHING);
        miningStates.put(player.getUniqueId(), state);

        player.sendMessage(ChatColor.GOLD + "Jarvis: Mining mode activated! (v0.0.9 - " + provider.getProviderName() + ")");

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!provider.isSpawned(player) || !player.isOnline()) {
                    cancel();
                    miningStates.remove(player.getUniqueId());
                    return;
                }

                Location npcLoc = provider.getCurrentLocation(player);
                if (npcLoc == null) {
                    cancel();
                    return;
                }

                state.ticksInPhase++;

                // Always try to pick up items
                pickupNearbyItems(player, npcLoc);

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
                    case SEARCHING -> processSearching(player, state, npcLoc);
                    case MOVING -> processMoving(player, state, npcLoc);
                    case MINING -> processMining(player, state, npcLoc);
                    case COLLECTING -> processCollecting(player, state, npcLoc);
                    case RETURNING -> processReturning(player, state, npcLoc);
                }

                // Stuck recovery - after 60 ticks (3 seconds) of no movement
                if (state.stuckTicks > 60 && state.phase != MiningPhase.MINING) {
                    debug("STUCK for " + state.stuckTicks + " ticks in phase " + state.phase);
                    handleStuck(player, state, npcLoc);
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
    private void processSearching(Player player, SimpleMiningState state, Location npcLoc) {
        debug("SEARCHING phase, tick " + state.ticksInPhase);

        Block ore = findNearestOre(npcLoc);

        if (ore == null) {
            if (state.ticksInPhase > 5) {
                player.sendMessage(ChatColor.YELLOW + "Jarvis: No ores found nearby. Mined " + state.oresMined + " ores total.");
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
     * MOVING - Walk towards the ore using provider's navigation
     * v0.0.9: Uses provider for smooth movement
     */
    private void processMoving(Player player, SimpleMiningState state, Location npcLoc) {
        if (state.targetLocation == null) {
            state.transitionTo(MiningPhase.SEARCHING);
            return;
        }

        double distance = npcLoc.distance(state.targetLocation);
        debug("MOVING phase, distance=" + String.format("%.1f", distance) + ", tick=" + state.ticksInPhase);

        // Close enough to mine
        if (distance <= REACH_DISTANCE) {
            debug("Within reach, transitioning to MINING");
            provider.cancelNavigation(player);
            state.transitionTo(MiningPhase.MINING);
            return;
        }

        // Navigate using provider (uses smooth movement on custom provider)
        if (!provider.isNavigating(player)) {
            provider.setNavigationParams(player, 0.6f, SEARCH_RADIUS * 2);
            provider.navigateTo(player, state.targetLocation);
            debug("Started navigation to " + formatLoc(state.targetLocation));
        } else {
            // Dynamic target update - re-issue navigation periodically (like attack mode)
            if (state.ticksInPhase % 10 == 0) {
                provider.navigateTo(player, state.targetLocation);
            }
        }

        // Fallback: If stuck for too long, try recovery
        if (state.stuckTicks > 20) {
            // Clear path by breaking blocks in the way
            Block blockInFront = getBlockInFront(npcLoc, state.targetLocation);
            if (blockInFront != null && blockInFront.getType().isSolid() && !isOre(blockInFront.getType())) {
                debug("Breaking blocking block: " + blockInFront.getType());
                ItemStack tool = provider.getHeldItem(player);
                blockInFront.breakNaturally(tool);
                provider.playSwingAnimation(player);
                state.stuckTicks = 0;
                return;
            }

            // If no block to break, teleport closer
            if (state.stuckTicks > 40) {
                Location stepLoc = getStepTowards(npcLoc, state.targetLocation, STEP_DISTANCE);
                if (stepLoc != null && isSafeToStand(stepLoc)) {
                    provider.teleport(player, stepLoc);
                    debug("Teleported step towards ore: " + formatLoc(stepLoc));
                    state.stuckTicks = 0;
                }
            }
        }

        // Timeout - ore is unreachable
        if (state.ticksInPhase > 100) {
            player.sendMessage(ChatColor.YELLOW + "Jarvis: Can't reach that ore, finding another...");
            provider.cancelNavigation(player);
            state.targetOre = null;
            state.targetLocation = null;
            state.transitionTo(MiningPhase.SEARCHING);
        }
    }

    /**
     * MINING - Break the ore block
     */
    private void processMining(Player player, SimpleMiningState state, Location npcLoc) {
        debug("MINING phase, tick " + state.ticksInPhase);

        if (state.targetOre == null || !isOre(state.targetOre.getType())) {
            debug("Target ore is gone or not an ore anymore");
            state.transitionTo(MiningPhase.COLLECTING);
            return;
        }

        Location oreLoc = state.targetOre.getLocation().add(0.5, 0.5, 0.5);
        double distance = npcLoc.distance(oreLoc);

        if (distance > REACH_DISTANCE + 1) {
            debug("Too far to mine, going back to MOVING");
            state.transitionTo(MiningPhase.MOVING);
            return;
        }

        // Face the ore
        provider.lookAt(player, oreLoc);

        // Play swing animation
        provider.playSwingAnimation(player);

        // Break the ore
        ItemStack tool = provider.getHeldItem(player);
        Material oreType = state.targetOre.getType();

        state.targetOre.breakNaturally(tool);
        state.oresMined++;

        player.sendMessage(ChatColor.GREEN + "Jarvis: Mined " + formatOre(oreType) + "! (Total: " + state.oresMined + ")");
        debug("Mined " + oreType + ", total=" + state.oresMined);

        state.targetOre = null;
        state.targetLocation = null;
        state.transitionTo(MiningPhase.COLLECTING);
    }

    /**
     * COLLECTING - Pick up dropped items
     */
    private void processCollecting(Player player, SimpleMiningState state, Location npcLoc) {
        debug("COLLECTING phase, tick " + state.ticksInPhase);

        // Wait a bit for items to spawn
        if (state.ticksInPhase < 3) {
            return;
        }

        // Pick up any items
        pickupNearbyItems(player, npcLoc);

        // Go back to searching for more ores
        state.transitionTo(MiningPhase.SEARCHING);
    }

    /**
     * RETURNING - Go back to player
     */
    private void processReturning(Player player, SimpleMiningState state, Location npcLoc) {
        Location playerLoc = player.getLocation();
        double distance = npcLoc.distance(playerLoc);

        if (distance < 5) {
            player.sendMessage(ChatColor.GREEN + "Jarvis: I'm back! Mined " + state.oresMined + " ores.");
            stopTask(player);
            miningStates.remove(player.getUniqueId());
            return;
        }

        // Navigate towards player
        if (!provider.isNavigating(player)) {
            provider.navigateTo(player, playerLoc);
        }
    }

    /**
     * Handle being stuck
     */
    private void handleStuck(Player player, SimpleMiningState state, Location npcLoc) {
        debug("Handling stuck state");

        // Try to clear surrounding blocks
        ItemStack tool = provider.getHeldItem(player);
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block block = npcLoc.getBlock().getRelative(face);
            if (block.getType().isSolid() && !isOre(block.getType()) && block.getType().getHardness() >= 0) {
                block.breakNaturally(tool);
                provider.playSwingAnimation(player);
                debug("Cleared blocking block: " + block.getType());
                state.stuckTicks = 0;
                return;
            }
        }

        // If we can't clear blocks, try teleporting to player
        if (state.stuckTicks > 100) {
            player.sendMessage(ChatColor.YELLOW + "Jarvis: I'm stuck! Coming back to you.");
            Location safeLoc = findSafeSpawnLocation(player.getLocation());
            provider.teleport(player, safeLoc);
            state.stuckTicks = 0;
            state.targetOre = null;
            state.targetLocation = null;
            state.transitionTo(MiningPhase.SEARCHING);
        }
    }

    // ==================== ORE FINDING ====================

    private Block findNearestOre(Location center) {
        Block nearest = null;
        double nearestDist = Double.MAX_VALUE;
        int nearestPriority = Integer.MAX_VALUE;

        for (int x = -SEARCH_RADIUS; x <= SEARCH_RADIUS; x++) {
            for (int y = -SEARCH_RADIUS; y <= SEARCH_RADIUS; y++) {
                for (int z = -SEARCH_RADIUS; z <= SEARCH_RADIUS; z++) {
                    Block block = center.clone().add(x, y, z).getBlock();
                    Material type = block.getType();

                    if (!isOre(type)) continue;

                    int priority = ORE_PRIORITY.indexOf(type);
                    if (priority < 0) priority = 999;

                    double dist = center.distance(block.getLocation());

                    // Prefer higher priority ores, then closer ores
                    if (priority < nearestPriority || (priority == nearestPriority && dist < nearestDist)) {
                        nearest = block;
                        nearestDist = dist;
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

        // Avoid hazards
        Material groundType = ground.getType();
        if (groundType == Material.LAVA || groundType == Material.FIRE ||
            groundType == Material.MAGMA_BLOCK || groundType == Material.CACTUS) {
            return false;
        }

        return true;
    }

    // ==================== ITEM PICKUP ====================

    private void pickupNearbyItems(Player owner, Location npcLoc) {
        Entity npcEntity = provider.getEntity(owner);
        if (npcEntity == null) return;

        for (Entity entity : npcEntity.getNearbyEntities(PICKUP_RADIUS, PICKUP_RADIUS, PICKUP_RADIUS)) {
            if (entity instanceof Item item) {
                ItemStack stack = item.getItemStack();

                // Add to inventory via provider
                if (provider.addToInventory(owner, stack)) {
                    item.remove();
                    npcLoc.getWorld().playSound(npcLoc, Sound.ENTITY_ITEM_PICKUP, 0.3f, 1.2f);
                }
            }
        }
    }

    // ==================== UTILITY METHODS ====================

    /**
     * Check if a player has an NPC spawned.
     */
    public boolean hasNPC(Player player) {
        return provider.isSpawned(player);
    }

    /**
     * Get the NPC entity for a player (for backwards compatibility).
     */
    public Entity getNPCEntity(Player player) {
        return provider.getEntity(player);
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

    public void stopTask(Player player) {
        BukkitRunnable task = activeTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }

        provider.cancelNavigation(player);
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
        if (!provider.isSpawned(player)) {
            player.sendMessage(ChatColor.RED + "Jarvis: Summon me first!");
            return;
        }

        stopTask(player);

        player.sendMessage(ChatColor.RED + "Jarvis: Combat mode engaged!");

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!provider.isSpawned(player) || !player.isOnline()) {
                    cancel();
                    return;
                }

                Location npcLoc = provider.getCurrentLocation(player);
                if (npcLoc == null) {
                    cancel();
                    return;
                }

                Entity npcEntity = provider.getEntity(player);
                if (npcEntity == null) {
                    cancel();
                    return;
                }

                // Find nearest hostile mob
                Monster target = null;
                double nearestDist = 16;

                for (Entity entity : npcEntity.getNearbyEntities(16, 16, 16)) {
                    if (entity instanceof Monster monster) {
                        double dist = npcLoc.distance(monster.getLocation());
                        if (dist < nearestDist) {
                            target = monster;
                            nearestDist = dist;
                        }
                    }
                }

                if (target != null) {
                    // Move towards and attack (using dynamic target tracking)
                    if (nearestDist > 2) {
                        provider.navigateTo(player, target, true);
                    } else {
                        // Attack
                        target.damage(6.0, npcEntity);
                        provider.playSwingAnimation(player);
                        npcLoc.getWorld().playSound(npcLoc, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1f);
                    }
                }

                // Also pick up items
                pickupNearbyItems(player, npcLoc);
            }
        };

        task.runTaskTimer(plugin, 0L, 10L);
        activeTasks.put(player.getUniqueId(), task);
    }

    /**
     * Battle another player (PvP training)
     */
    public void battle(Player player, Player target) {
        if (!provider.isSpawned(player)) {
            player.sendMessage(ChatColor.RED + "Jarvis: Summon me first!");
            return;
        }

        stopTask(player);

        player.sendMessage(ChatColor.RED + "Jarvis: Engaging " + target.getName() + " in battle!");
        target.sendMessage(ChatColor.YELLOW + "Jarvis (" + player.getName() + "'s companion) is challenging you!");

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!provider.isSpawned(player) || !player.isOnline() || !target.isOnline()) {
                    player.sendMessage(ChatColor.YELLOW + "Jarvis: Battle ended.");
                    cancel();
                    return;
                }

                Location npcLoc = provider.getCurrentLocation(player);
                if (npcLoc == null) {
                    cancel();
                    return;
                }

                Location targetLoc = target.getLocation();
                double distance = npcLoc.distance(targetLoc);

                if (distance > 2) {
                    // Move towards target (dynamic tracking)
                    provider.navigateTo(player, target, true);
                } else {
                    // Attack
                    Entity npcEntity = provider.getEntity(player);
                    if (npcEntity != null) {
                        target.damage(4.0, npcEntity);
                        provider.playSwingAnimation(player);
                        npcLoc.getWorld().playSound(npcLoc, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1f);
                    }
                }
            }
        };

        task.runTaskTimer(plugin, 0L, 20L);
        activeTasks.put(player.getUniqueId(), task);
    }

    // ==================== OTHER COMMANDS ====================

    public void returnToPlayer(Player player) {
        if (!provider.isSpawned(player)) return;

        stopTask(player);

        Location safeLoc = findSafeSpawnLocation(player.getLocation());
        provider.teleport(player, safeLoc);

        player.sendMessage(ChatColor.GREEN + "Jarvis: Right behind you!");
    }

    public void openInventory(Player player) {
        if (!provider.isSpawned(player)) {
            player.sendMessage(ChatColor.RED + "Jarvis: I'm not summoned yet!");
            return;
        }

        provider.openInventory(player);
    }

    public void clearInventory(Player player) {
        if (!provider.isSpawned(player)) {
            player.sendMessage(ChatColor.RED + "Jarvis: I'm not summoned yet!");
            return;
        }

        Location dropLoc = provider.getCurrentLocation(player);
        if (dropLoc == null) return;

        // Drop non-equipment items
        ItemStack[] contents = provider.getInventoryContents(player);
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

        provider.setInventoryContents(player, contents);

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
        // Count active tasks as a proxy for active NPCs
        return activeTasks.size();
    }

    public int getActiveTaskCount() {
        return activeTasks.size();
    }

    /**
     * Check if player has an NPC via provider.
     */
    public boolean playerHasNPC(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return player != null && provider.isSpawned(player);
    }

    /**
     * Get NPC entity for a player (for backwards compatibility).
     * Returns the underlying entity from the provider.
     */
    public Entity getNPCForPlayer(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return null;
        return provider.getEntity(player);
    }

    public void dismissAll() {
        // Cancel all tasks
        activeTasks.values().forEach(BukkitRunnable::cancel);
        activeTasks.clear();
        miningStates.clear();

        // Let provider handle cleanup
        provider.cleanup();
    }

    // ==================== CLEANUP ====================

    private void startCleanupTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                // Clean up tasks for offline players
                Iterator<Map.Entry<UUID, BukkitRunnable>> it = activeTasks.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<UUID, BukkitRunnable> entry = it.next();
                    Player player = plugin.getServer().getPlayer(entry.getKey());
                    if (player == null) {
                        entry.getValue().cancel();
                        it.remove();
                        miningStates.remove(entry.getKey());
                    }
                }
            }
        }.runTaskTimer(plugin, 6000L, 6000L); // Every 5 minutes
    }

    /**
     * Get the NPC provider (for advanced operations).
     */
    public INPCProvider getProvider() {
        return provider;
    }
}
