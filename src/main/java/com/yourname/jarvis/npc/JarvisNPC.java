package com.yourname.jarvis.npc;

import com.yourname.jarvis.Jarvis;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.ai.NavigatorParameters;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.api.trait.trait.Inventory;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

// Phase 3: Denizen and WorldGuard integration
import org.bukkit.plugin.Plugin;

/**
 * JarvisNPC - Manages NPC spawning, combat, and intelligent mining
 * Version: 0.0.6
 * 
 * Key features:
 * - Smart mining with exposed ore priority
 * - Dirt pillar climbing system
 * - Torch placement while mining
 * - Branch mining patterns
 * - Vein mining detection
 * - Statistics tracking
 * - Intelligent pathfinding
 * - Combat mode for hostile mobs
 */
public class JarvisNPC {

    private final Jarvis plugin;
    private final Map<UUID, NPC> playerNPCs = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitRunnable> activeTasks = new ConcurrentHashMap<>();
    private final Map<UUID, MiningState> miningStates = new ConcurrentHashMap<>();
    private final Map<UUID, BranchMiningState> branchMiningStates = new ConcurrentHashMap<>();
    private final Map<UUID, Location> lastTorchPlaced = new ConcurrentHashMap<>();

    // Mining configuration constants
    private static final int SEARCH_RADIUS = 16;
    private static final int PICKUP_RADIUS = 8;
    private static final double REACH_DISTANCE = 4.5;
    private static final double MOVE_SPEED = 0.25;
    private static final int MINING_TICK_RATE = 5;
    private static final int COMBAT_TICK_RATE = 10;
    private static final int CLIMB_HEIGHT_THRESHOLD = 2;
    private static final int MAX_PILLAR_HEIGHT = 8;
    private static final int VEIN_SEARCH_RADIUS = 3;
    private static final int MAX_VEIN_SIZE = 64;
    
    // Configuration from config.yml
    private final int torchSpacing;
    private final boolean placeTorches;
    private final boolean torchOnFloor;
    private final boolean enableVeinMining;

    // Phase 1: Safety and stuck recovery configuration
    private final boolean avoidLava;
    private final boolean avoidWater;
    private final boolean avoidFire;
    private final int minYLevel;
    private final int stuckTeleportThreshold;
    private final boolean stuckMineAround;
    private final boolean useNavigator;

    // Phase 3: Denizen and WorldGuard integration
    private boolean denizenEnabled = false;
    private boolean worldGuardEnabled = false;
    private Plugin denizenPlugin = null;
    private Plugin worldGuardPlugin = null;
    
    // Debug mode - set true for detailed logging
    private static final boolean DEBUG = false;

    // Ore priority list (highest value first)
    private static final List<Material> ORE_PRIORITY = Arrays.asList(
            Material.ANCIENT_DEBRIS,
            Material.DEEPSLATE_EMERALD_ORE, Material.EMERALD_ORE,
            Material.DEEPSLATE_DIAMOND_ORE, Material.DIAMOND_ORE,
            Material.DEEPSLATE_GOLD_ORE, Material.GOLD_ORE, Material.NETHER_GOLD_ORE,
            Material.DEEPSLATE_REDSTONE_ORE, Material.REDSTONE_ORE,
            Material.DEEPSLATE_LAPIS_ORE, Material.LAPIS_ORE,
            Material.DEEPSLATE_IRON_ORE, Material.IRON_ORE,
            Material.DEEPSLATE_COPPER_ORE, Material.COPPER_ORE,
            Material.DEEPSLATE_COAL_ORE, Material.COAL_ORE,
            Material.NETHER_QUARTZ_ORE
    );

    // ========== PHASE 2: STATE MACHINE ENUMS ==========

    /**
     * Mining phases for state machine (Phase 2)
     */
    private enum MiningPhase {
        IDLE,           // Not doing anything
        SEARCHING,      // Looking for ore clusters
        PLANNING,       // Calculating path to cluster
        MOVING,         // Following path segment
        MINING,         // Breaking ore block
        COLLECTING,     // Picking up drops
        STUCK,          // Recovery mode
        RETREATING,     // Moving away from danger
        COMPLETE        // Finished mining session
    }

    /**
     * Ore cluster - groups nearby ores for efficient mining (Phase 2)
     */
    private static class OreCluster {
        List<Block> ores = new ArrayList<>();
        Location center;
        int totalValue = 0;
        double distanceFromNpc;

        void calculateCenter() {
            if (ores.isEmpty()) return;
            double x = 0, y = 0, z = 0;
            for (Block ore : ores) {
                x += ore.getX();
                y += ore.getY();
                z += ore.getZ();
            }
            int size = ores.size();
            center = new Location(ores.get(0).getWorld(), x / size, y / size, z / size);
        }

        void addOre(Block ore, int priority) {
            ores.add(ore);
            totalValue += (20 - priority); // Higher priority = higher value
            calculateCenter();
        }

        Block getNextOre(Location from) {
            if (ores.isEmpty()) return null;
            // Return closest ore in cluster
            return ores.stream()
                .min((a, b) -> Double.compare(
                    a.getLocation().distance(from),
                    b.getLocation().distance(from)))
                .orElse(null);
        }

        void removeOre(Block ore) {
            ores.remove(ore);
            calculateCenter();
        }

        boolean isEmpty() {
            return ores.isEmpty();
        }

        int size() {
            return ores.size();
        }
    }

    /**
     * Path segment for navigation (Phase 2)
     */
    private static class PathSegment {
        Location target;
        boolean requiresMining;
        Block blockToBreak;
        int attempts = 0;
        static final int MAX_ATTEMPTS = 10;

        PathSegment(Location target) {
            this.target = target;
            this.requiresMining = false;
        }

        PathSegment(Location target, Block blockToBreak) {
            this.target = target;
            this.blockToBreak = blockToBreak;
            this.requiresMining = true;
        }

        boolean hasExceededAttempts() {
            return attempts >= MAX_ATTEMPTS;
        }
    }

    /**
     * Enhanced mining state with state machine (Phase 2)
     */
    private static class MiningState {
        // Current state
        MiningPhase phase = MiningPhase.IDLE;
        MiningPhase previousPhase = MiningPhase.IDLE;

        // Target tracking
        Block targetOre;
        OreCluster currentCluster;
        Queue<PathSegment> pathSegments = new LinkedList<>();

        // Progress tracking
        int oresMined = 0;
        int blocksCleared = 0;
        long sessionStartTime;

        // Stuck detection
        int ticksStuck = 0;
        int ticksInPhase = 0;
        Location lastLocation;

        // Mining verification (v0.0.6)
        boolean awaitingBreakConfirmation = false;
        long breakStartTime = 0;
        int miningAttempts = 0;
        Material lastTargetType = null;

        // Navigation verification (v0.0.6)
        boolean awaitingNavigation = false;
        long navigationStartTime = 0;

        // Metrics tracking (v0.0.6)
        int oresFound = 0;
        int oresActuallyMined = 0;
        int navigationAttempts = 0;
        int navigationSuccesses = 0;
        int blockBreakAttempts = 0;
        int blockBreakSuccesses = 0;

        // Legacy support
        List<Block> pillarBlocks = new ArrayList<>();
        Block currentBlockToBreak;
        Set<Block> currentVein = new HashSet<>();
        boolean miningVein = false;

        void transitionTo(MiningPhase newPhase) {
            previousPhase = phase;
            phase = newPhase;
            ticksInPhase = 0;
            // Reset verification flags on phase transition
            awaitingBreakConfirmation = false;
            awaitingNavigation = false;
        }

        void reset() {
            targetOre = null;
            currentBlockToBreak = null;
            ticksStuck = 0;
            currentVein.clear();
            miningVein = false;
            pathSegments.clear();
            miningAttempts = 0;
            awaitingBreakConfirmation = false;
            awaitingNavigation = false;
            transitionTo(MiningPhase.SEARCHING);
        }

        void fullReset() {
            reset();
            currentCluster = null;
            transitionTo(MiningPhase.IDLE);
        }

        // Get success rate for debugging
        double getMiningSuccessRate() {
            return blockBreakAttempts > 0 ?
                (double) blockBreakSuccesses / blockBreakAttempts * 100 : 0;
        }

        double getNavigationSuccessRate() {
            return navigationAttempts > 0 ?
                (double) navigationSuccesses / navigationAttempts * 100 : 0;
        }
    }

    /**
     * Branch mining state tracker
     */
    private static class BranchMiningState {
        Location startLocation;
        Vector mainDirection;
        int currentBranch = 0;
        int maxBranches;
        int branchLength;
        int branchSpacing;
        int tunnelHeight;
        boolean isActive = false;
        Location currentTarget;
        int blocksInCurrentBranch = 0;
        int totalBlocksMined = 0;
        
        void reset() {
            currentTarget = null;
            blocksInCurrentBranch = 0;
        }
    }

    public JarvisNPC(Jarvis plugin) {
        this.plugin = plugin;

        // Load configuration
        this.torchSpacing = plugin.getConfig().getInt("mining.torch-spacing", 8);
        this.placeTorches = plugin.getConfig().getBoolean("mining.place-torches", false);
        this.torchOnFloor = plugin.getConfig().getBoolean("mining.torch-on-floor", true);
        this.enableVeinMining = plugin.getConfig().getBoolean("mining.enable-vein-mining", true);

        // Phase 1: Safety and stuck recovery
        this.avoidLava = plugin.getConfig().getBoolean("mining.safety.avoid-lava", true);
        this.avoidWater = plugin.getConfig().getBoolean("mining.safety.avoid-water", true);
        this.avoidFire = plugin.getConfig().getBoolean("mining.safety.avoid-fire", true);
        this.minYLevel = plugin.getConfig().getInt("mining.safety.min-y-level", -60);
        this.stuckTeleportThreshold = plugin.getConfig().getInt("mining.stuck-recovery.teleport-threshold", 60);
        this.stuckMineAround = plugin.getConfig().getBoolean("mining.stuck-recovery.mine-around", true);
        this.useNavigator = plugin.getConfig().getBoolean("mining.use-navigator", true);

        // Phase 3: Initialize Denizen and WorldGuard hooks
        initializeDenizenHook();
        initializeWorldGuardHook();

        // Phase 6: Start cleanup task for memory leak prevention
        startCleanupTask();
    }

    /**
     * Phase 6: Periodic cleanup task to prevent memory leaks
     * Runs every 5 minutes to clean up stale entries
     */
    private void startCleanupTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                int cleaned = 0;

                // Clean up lastTorchPlaced entries for offline players
                Iterator<UUID> torchIterator = lastTorchPlaced.keySet().iterator();
                while (torchIterator.hasNext()) {
                    UUID playerId = torchIterator.next();
                    if (plugin.getServer().getPlayer(playerId) == null) {
                        torchIterator.remove();
                        cleaned++;
                    }
                }

                // Clean up stale NPC entries for disconnected players
                Iterator<Map.Entry<UUID, NPC>> npcIterator = playerNPCs.entrySet().iterator();
                while (npcIterator.hasNext()) {
                    Map.Entry<UUID, NPC> entry = npcIterator.next();
                    UUID playerId = entry.getKey();
                    NPC npc = entry.getValue();

                    // If player is offline and NPC still exists
                    if (plugin.getServer().getPlayer(playerId) == null) {
                        // Check if NPC is orphaned (spawned but player offline for a while)
                        if (npc != null && npc.isSpawned()) {
                            // Save inventory before destroying
                            try {
                                Inventory invTrait = npc.getOrAddTrait(Inventory.class);
                                ItemStack[] contents = invTrait.getContents();
                                plugin.getDatabaseManager().saveNpcInventory(playerId, contents);
                            } catch (Exception e) {
                                plugin.getLogger().warning("Failed to save inventory during cleanup: " + e.getMessage());
                            }

                            npc.destroy();
                            debugLog("Cleaned up orphaned NPC for offline player: " + playerId);
                        }
                        npcIterator.remove();
                        cleaned++;
                    }
                }

                // Clean up mining states for offline players
                miningStates.keySet().removeIf(playerId -> plugin.getServer().getPlayer(playerId) == null);

                // Clean up branch mining states for offline players
                branchMiningStates.keySet().removeIf(playerId -> plugin.getServer().getPlayer(playerId) == null);

                // Clean up active tasks for offline players
                Iterator<Map.Entry<UUID, BukkitRunnable>> taskIterator = activeTasks.entrySet().iterator();
                while (taskIterator.hasNext()) {
                    Map.Entry<UUID, BukkitRunnable> entry = taskIterator.next();
                    if (plugin.getServer().getPlayer(entry.getKey()) == null) {
                        entry.getValue().cancel();
                        taskIterator.remove();
                        cleaned++;
                    }
                }

                if (cleaned > 0) {
                    debugLog("Cleanup task removed " + cleaned + " stale entries");
                }
            }
        }.runTaskTimer(plugin, 6000L, 6000L); // Run every 5 minutes (6000 ticks)
    }

    // ========== PHASE 3: DENIZEN INTEGRATION ==========

    /**
     * Initialize Denizen plugin hook for scripted behaviors
     */
    private void initializeDenizenHook() {
        denizenPlugin = plugin.getServer().getPluginManager().getPlugin("Denizen");
        if (denizenPlugin != null && denizenPlugin.isEnabled()) {
            denizenEnabled = true;
            plugin.getLogger().info("Denizen integration enabled - scripted behaviors available");
        } else {
            denizenEnabled = false;
            debugLog("Denizen not found - scripted behaviors disabled");
        }
    }

    /**
     * Run a Denizen script for the NPC
     * Scripts should be placed in plugins/Denizen/scripts/jarvis/
     * Example: jarvis_mining_start.dsc, jarvis_ore_found.dsc
     */
    public void runDenizenScript(Player player, String scriptName, Map<String, Object> context) {
        if (!denizenEnabled || denizenPlugin == null) {
            debugLog("Denizen not enabled, skipping script: " + scriptName);
            return;
        }

        try {
            // Use Denizen's command system to run scripts
            // Format: /ex run <script> def:<definitions>
            StringBuilder command = new StringBuilder("ex run jarvis_" + scriptName);

            // Add context as definitions
            if (context != null && !context.isEmpty()) {
                command.append(" def:");
                boolean first = true;
                for (Map.Entry<String, Object> entry : context.entrySet()) {
                    if (!first) command.append("|");
                    command.append(entry.getKey()).append("=").append(entry.getValue());
                    first = false;
                }
            }

            // Dispatch command from console
            plugin.getServer().dispatchCommand(
                plugin.getServer().getConsoleSender(),
                command.toString()
            );

            debugLog("Ran Denizen script: " + scriptName);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to run Denizen script " + scriptName + ": " + e.getMessage());
        }
    }

    /**
     * Trigger Denizen event for custom handling
     */
    public void triggerDenizenEvent(String eventName, Player player, NPC npc, Map<String, Object> data) {
        if (!denizenEnabled) return;

        // Build context for Denizen
        Map<String, Object> context = new HashMap<>();
        context.put("player", player.getName());
        context.put("npc_id", npc.getId());
        if (data != null) {
            context.putAll(data);
        }

        // Events Jarvis can trigger:
        // - jarvis_mining_start, jarvis_mining_complete
        // - jarvis_ore_found, jarvis_vein_found
        // - jarvis_stuck, jarvis_danger_detected
        // - jarvis_inventory_full, jarvis_returned
        runDenizenScript(player, eventName, context);
    }

    // ========== PHASE 3: WORLDGUARD INTEGRATION ==========

    /**
     * Initialize WorldGuard plugin hook for region checking
     */
    private void initializeWorldGuardHook() {
        worldGuardPlugin = plugin.getServer().getPluginManager().getPlugin("WorldGuard");
        if (worldGuardPlugin != null && worldGuardPlugin.isEnabled()) {
            worldGuardEnabled = true;
            plugin.getLogger().info("WorldGuard integration enabled - region protection active");
        } else {
            worldGuardEnabled = false;
            debugLog("WorldGuard not found - region protection disabled");
        }
    }

    /**
     * Check if mining is allowed at a location using WorldGuard
     */
    public boolean canMineAt(Player player, Location location) {
        if (!worldGuardEnabled || worldGuardPlugin == null) {
            return true; // No WorldGuard, allow all
        }

        try {
            // Use WorldGuard API to check block-break flag
            // This uses reflection to avoid hard dependency
            Class<?> worldGuardClass = Class.forName("com.sk89q.worldguard.WorldGuard");
            Object instance = worldGuardClass.getMethod("getInstance").invoke(null);
            Object platform = instance.getClass().getMethod("getPlatform").invoke(instance);
            Object regionContainer = platform.getClass().getMethod("getRegionContainer").invoke(platform);

            // Get the query object
            Class<?> bukkitAdapterClass = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            Object weWorld = bukkitAdapterClass.getMethod("adapt", org.bukkit.World.class)
                .invoke(null, location.getWorld());
            Object query = regionContainer.getClass().getMethod("createQuery").invoke(regionContainer);

            // Create location for WorldGuard
            Object weLocation = Class.forName("com.sk89q.worldedit.math.BlockVector3")
                .getMethod("at", int.class, int.class, int.class)
                .invoke(null, location.getBlockX(), location.getBlockY(), location.getBlockZ());

            // Get wrapped player
            Object wePlayer = bukkitAdapterClass.getMethod("adapt", Player.class).invoke(null, player);

            // Check BLOCK_BREAK flag
            Class<?> flagsClass = Class.forName("com.sk89q.worldguard.protection.flags.Flags");
            Object blockBreakFlag = flagsClass.getField("BLOCK_BREAK").get(null);

            // testState(location, player, flag)
            Object result = query.getClass().getMethod("testState",
                Class.forName("com.sk89q.worldedit.util.Location"),
                Class.forName("com.sk89q.worldguard.LocalPlayer"),
                Class.forName("com.sk89q.worldguard.protection.flags.StateFlag"))
                .invoke(query,
                    createWorldEditLocation(weWorld, weLocation),
                    wePlayer,
                    blockBreakFlag);

            return (Boolean) result;

        } catch (ClassNotFoundException e) {
            // WorldGuard classes not found, allow mining
            debugLog("WorldGuard API classes not found");
            return true;
        } catch (Exception e) {
            // Error checking, log and allow (fail open)
            debugLog("WorldGuard check error: " + e.getMessage());
            return true;
        }
    }

    /**
     * Helper to create WorldEdit location
     */
    private Object createWorldEditLocation(Object world, Object blockVector) {
        try {
            Class<?> locationClass = Class.forName("com.sk89q.worldedit.util.Location");
            return locationClass.getConstructor(
                Class.forName("com.sk89q.worldedit.world.World"),
                Class.forName("com.sk89q.worldedit.math.Vector3")
            ).newInstance(world, blockVector);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Check if NPC can enter a region
     */
    public boolean canEnterRegion(Player player, Location location) {
        if (!worldGuardEnabled) return true;

        try {
            // Check ENTRY flag for the location
            Class<?> worldGuardClass = Class.forName("com.sk89q.worldguard.WorldGuard");
            Object instance = worldGuardClass.getMethod("getInstance").invoke(null);
            Object platform = instance.getClass().getMethod("getPlatform").invoke(instance);
            Object regionContainer = platform.getClass().getMethod("getRegionContainer").invoke(platform);

            Class<?> bukkitAdapterClass = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            Object weWorld = bukkitAdapterClass.getMethod("adapt", org.bukkit.World.class)
                .invoke(null, location.getWorld());

            Object regionManager = regionContainer.getClass()
                .getMethod("get", Class.forName("com.sk89q.worldedit.world.World"))
                .invoke(regionContainer, weWorld);

            if (regionManager == null) return true;

            // Get applicable regions at location
            Object blockVector = Class.forName("com.sk89q.worldedit.math.BlockVector3")
                .getMethod("at", int.class, int.class, int.class)
                .invoke(null, location.getBlockX(), location.getBlockY(), location.getBlockZ());

            Object regions = regionManager.getClass()
                .getMethod("getApplicableRegions", Class.forName("com.sk89q.worldedit.math.BlockVector3"))
                .invoke(regionManager, blockVector);

            // Check if any region denies entry
            // For simplicity, we check if there are protected regions
            int size = (int) regions.getClass().getMethod("size").invoke(regions);
            if (size == 0) return true;

            // If there are regions, check membership
            Object wePlayer = bukkitAdapterClass.getMethod("adapt", Player.class).invoke(null, player);
            Object result = regions.getClass().getMethod("isMemberOfAll",
                Class.forName("com.sk89q.worldguard.LocalPlayer"))
                .invoke(regions, wePlayer);

            return (Boolean) result;

        } catch (Exception e) {
            debugLog("WorldGuard entry check error: " + e.getMessage());
            return true;
        }
    }

    /**
     * Get the name of the region at a location (for display purposes)
     */
    public String getRegionNameAt(Location location) {
        if (!worldGuardEnabled) return null;

        try {
            Class<?> worldGuardClass = Class.forName("com.sk89q.worldguard.WorldGuard");
            Object instance = worldGuardClass.getMethod("getInstance").invoke(null);
            Object platform = instance.getClass().getMethod("getPlatform").invoke(instance);
            Object regionContainer = platform.getClass().getMethod("getRegionContainer").invoke(platform);

            Class<?> bukkitAdapterClass = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            Object weWorld = bukkitAdapterClass.getMethod("adapt", org.bukkit.World.class)
                .invoke(null, location.getWorld());

            Object regionManager = regionContainer.getClass()
                .getMethod("get", Class.forName("com.sk89q.worldedit.world.World"))
                .invoke(regionContainer, weWorld);

            if (regionManager == null) return null;

            Object blockVector = Class.forName("com.sk89q.worldedit.math.BlockVector3")
                .getMethod("at", int.class, int.class, int.class)
                .invoke(null, location.getBlockX(), location.getBlockY(), location.getBlockZ());

            Object regions = regionManager.getClass()
                .getMethod("getApplicableRegions", Class.forName("com.sk89q.worldedit.math.BlockVector3"))
                .invoke(regionManager, blockVector);

            // Get first region name
            Object iterator = regions.getClass().getMethod("iterator").invoke(regions);
            if ((Boolean) iterator.getClass().getMethod("hasNext").invoke(iterator)) {
                Object region = iterator.getClass().getMethod("next").invoke(iterator);
                return (String) region.getClass().getMethod("getId").invoke(region);
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isDenizenEnabled() {
        return denizenEnabled;
    }

    public boolean isWorldGuardEnabled() {
        return worldGuardEnabled;
    }

    // ========== NPC LIFECYCLE ==========

    public void summon(Player player) {
        NPC existing = playerNPCs.get(player.getUniqueId());
        if (existing != null && existing.isSpawned()) {
            player.sendMessage("§eJarvis: I'm already here!");
            return;
        }

        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, "Jarvis");
        Location spawnLoc = findSafeSpawnLocation(player.getLocation());

        npc.spawn(spawnLoc);
        npc.getOrAddTrait(Inventory.class);
        npc.setProtected(true);
        playerNPCs.put(player.getUniqueId(), npc);

        // Check for saved inventory from previous session
        UUID playerId = player.getUniqueId();
        ItemStack[] savedInventory = plugin.getDatabaseManager().loadNpcInventory(playerId);

        if (savedInventory != null && savedInventory.length > 0) {
            // Restore saved inventory
            Inventory invTrait = npc.getOrAddTrait(Inventory.class);
            invTrait.setContents(savedInventory);
            plugin.getDatabaseManager().clearSavedInventory(playerId);
            player.sendMessage("§aJarvis: At your service—I've got your items from last time!");
            debugLog("Restored saved inventory for " + player.getName());
        } else {
            // Give Jarvis starting equipment
            giveStartingEquipment(npc);
            player.sendMessage("§aJarvis: At your service—let's make some magic.");
        }

        player.getWorld().playSound(spawnLoc, Sound.BLOCK_BELL_USE, 1.0f, 1.0f);

        debugLog("Jarvis spawned for " + player.getName() + " at " + spawnLoc);
    }

    public void dismiss(Player player) {
        UUID playerId = player.getUniqueId();
        NPC npc = playerNPCs.remove(playerId);
        if (npc == null) {
            player.sendMessage("§cJarvis: I'm not summoned yet!");
            return;
        }

        // Clean up any mining state
        MiningState state = miningStates.remove(playerId);
        if (state != null) {
            cleanupPillarBlocks(state);
        }

        // Clean up branch mining state
        branchMiningStates.remove(playerId);
        lastTorchPlaced.remove(playerId);

        // Save inventory to database instead of dropping
        try {
            Inventory invTrait = npc.getOrAddTrait(Inventory.class);
            ItemStack[] contents = invTrait.getContents();
            plugin.getDatabaseManager().saveNpcInventory(playerId, contents);
            debugLog("Saved NPC inventory for " + player.getName());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save NPC inventory: " + e.getMessage());
            // Fallback: drop items if save fails
            dropInventoryItems(npc);
        }

        npc.destroy();
        stopTask(player);
        player.sendMessage("§7Jarvis: Until next time—your items are safe with me!");

        debugLog("Jarvis dismissed for " + player.getName());
    }

    public void returnToPlayer(Player player) {
        NPC npc = getNPC(player);
        if (npc == null) return;

        Location target = findSafeSpawnLocation(player.getLocation());
        npc.teleport(target, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
        
        stopTask(player);
        player.sendMessage("§aJarvis: Right behind you!");
        
        debugLog("Jarvis returned to " + player.getName());
    }

    // ========== COMBAT MODE ==========

    public void attack(Player player) {
        NPC npc = getNPC(player);
        if (npc == null) return;
        stopTask(player);

        equipWeapon(npc);

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!npc.isSpawned()) {
                    cancel();
                    return;
                }

                Location npcLoc = getCurrentLocation(npc);
                Monster mob = findNearestHostileMob(npcLoc);
                
                if (mob != null && !mob.isDead()) {
                    npc.getNavigator().setTarget(mob, true);
                } else {
                    npc.getNavigator().cancelNavigation();
                }

                pickupNearbyItems(npc);
            }
        };
        task.runTaskTimer(plugin, 0L, COMBAT_TICK_RATE);
        activeTasks.put(player.getUniqueId(), task);
        player.sendMessage("§cJarvis: Switching to combat mode!");
        
        debugLog("Jarvis entered combat mode for " + player.getName());
    }

    public void battle(Player owner, Player target) {
        NPC npc = getNPC(owner);
        if (npc == null) return;
        stopTask(owner);

        equipWeapon(npc);
        npc.getNavigator().setTarget(target, true);
        
        owner.sendMessage("§cJarvis: Engaging " + target.getName() + "!");
        debugLog("Jarvis in battle mode: " + owner.getName() + " vs " + target.getName());
    }

    // ========== PHASE 2: STATE MACHINE MINING ==========

    public void mine(Player player, String[] args) {
        mine(player);
    }

    public void mine(Player player) {
        NPC npc = getNPC(player);
        if (npc == null) return;
        stopTask(player);

        MiningState state = new MiningState();
        state.sessionStartTime = System.currentTimeMillis();
        state.transitionTo(MiningPhase.SEARCHING);
        miningStates.put(player.getUniqueId(), state);

        player.sendMessage("§6Jarvis: Switching to mining mode!");
        player.sendMessage("§7Using smart ore clustering and pathfinding");
        if (worldGuardEnabled) {
            player.sendMessage("§7WorldGuard protection active");
        }
        if (denizenEnabled) {
            player.sendMessage("§7Denizen scripting available");
        }

        // Phase 3: Trigger Denizen mining_start event
        if (denizenEnabled) {
            Map<String, Object> eventData = new HashMap<>();
            eventData.put("location_x", npc.getStoredLocation().getBlockX());
            eventData.put("location_y", npc.getStoredLocation().getBlockY());
            eventData.put("location_z", npc.getStoredLocation().getBlockZ());
            triggerDenizenEvent("mining_start", player, npc, eventData);
        }

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!npc.isSpawned()) {
                    cancel();
                    miningStates.remove(player.getUniqueId());
                    return;
                }

                Location npcLoc = getCurrentLocation(npc);
                state.ticksInPhase++;

                // Always pickup items
                pickupNearbyItems(npc);

                // Torch placement (if enabled)
                tryPlaceTorch(npc, player, npcLoc);

                // Stuck detection (applies to all phases except IDLE/COMPLETE)
                if (state.phase != MiningPhase.IDLE && state.phase != MiningPhase.COMPLETE) {
                    if (state.lastLocation != null && state.lastLocation.distance(npcLoc) < 0.1) {
                        state.ticksStuck++;
                    } else {
                        state.ticksStuck = 0;
                    }
                    state.lastLocation = npcLoc.clone();
                }

                // State machine - process current phase
                switch (state.phase) {
                    case SEARCHING:
                        processSearching(npc, player, state, npcLoc);
                        break;

                    case PLANNING:
                        processPlanning(npc, player, state, npcLoc);
                        break;

                    case MOVING:
                        processMoving(npc, player, state, npcLoc);
                        break;

                    case MINING:
                        processMiningPhase(npc, player, state, npcLoc);
                        break;

                    case COLLECTING:
                        processCollecting(npc, player, state, npcLoc);
                        break;

                    case STUCK:
                        processStuck(npc, player, state, npcLoc);
                        break;

                    case RETREATING:
                        processRetreating(npc, player, state, npcLoc);
                        break;

                    case COMPLETE:
                        player.sendMessage("§eJarvis: Mining complete! Mined " + state.oresMined + " ores.");

                        // Phase 3: Trigger Denizen mining_complete event
                        if (denizenEnabled) {
                            Map<String, Object> completeData = new HashMap<>();
                            completeData.put("ores_mined", state.oresMined);
                            completeData.put("blocks_cleared", state.blocksCleared);
                            long duration = (System.currentTimeMillis() - state.sessionStartTime) / 1000;
                            completeData.put("duration_seconds", duration);
                            triggerDenizenEvent("mining_complete", player, npc, completeData);
                        }

                        cancel();
                        miningStates.remove(player.getUniqueId());
                        cleanupPillarBlocks(state);
                        break;

                    default:
                        state.transitionTo(MiningPhase.SEARCHING);
                }

                // Global stuck check - transition to STUCK phase
                if (state.ticksStuck >= 30 && state.phase != MiningPhase.STUCK && state.phase != MiningPhase.RETREATING) {
                    debugLog("Transitioning to STUCK phase after " + state.ticksStuck + " ticks");
                    state.transitionTo(MiningPhase.STUCK);
                }

                // Danger check - transition to RETREATING
                if (isDangerNearby(npcLoc) && state.phase != MiningPhase.RETREATING) {
                    player.sendMessage(ChatColor.RED + "Jarvis: Danger detected!");
                    state.transitionTo(MiningPhase.RETREATING);
                }
            }
        };
        task.runTaskTimer(plugin, 0L, MINING_TICK_RATE);
        activeTasks.put(player.getUniqueId(), task);

        debugLog("Jarvis entered state machine mining mode for " + player.getName());
    }

    // ========== PHASE 2: STATE HANDLERS ==========

    /**
     * SEARCHING phase - Find ore clusters
     */
    private void processSearching(NPC npc, Player player, MiningState state, Location npcLoc) {
        debugLog("Phase: SEARCHING");

        // Find ore clusters instead of single ores
        OreCluster cluster = findBestOreCluster(npcLoc);

        if (cluster == null || cluster.isEmpty()) {
            state.ticksInPhase++;
            if (state.ticksInPhase > 10) {
                // No ores found after searching
                state.transitionTo(MiningPhase.COMPLETE);
            }
            return;
        }

        state.currentCluster = cluster;
        player.sendMessage(ChatColor.AQUA + "Jarvis: Found ore cluster with " + cluster.size() + " ores!");
        debugLog("Found cluster with " + cluster.size() + " ores, center at " + cluster.center);

        // Phase 3: Trigger Denizen event for cluster found
        if (denizenEnabled) {
            Map<String, Object> eventData = new HashMap<>();
            eventData.put("cluster_size", cluster.size());
            eventData.put("cluster_value", cluster.totalValue);
            eventData.put("distance", cluster.distanceFromNpc);
            triggerDenizenEvent("cluster_found", player, npc, eventData);
        }

        state.transitionTo(MiningPhase.PLANNING);
    }

    /**
     * PLANNING phase - Calculate path to cluster
     */
    private void processPlanning(NPC npc, Player player, MiningState state, Location npcLoc) {
        debugLog("Phase: PLANNING");

        if (state.currentCluster == null || state.currentCluster.isEmpty()) {
            state.transitionTo(MiningPhase.SEARCHING);
            return;
        }

        // Get next ore from cluster
        Block nextOre = state.currentCluster.getNextOre(npcLoc);
        if (nextOre == null) {
            state.transitionTo(MiningPhase.SEARCHING);
            return;
        }

        state.targetOre = nextOre;

        // Calculate path segments to ore
        state.pathSegments.clear();
        calculatePathSegments(npcLoc, nextOre.getLocation().add(0.5, 0.5, 0.5), state);

        // Equip appropriate pickaxe
        boolean needsSilk = nextOre.getType() == Material.DEEPSLATE_EMERALD_ORE ||
                           nextOre.getType() == Material.EMERALD_ORE;
        equipPickaxe(npc, needsSilk);

        debugLog("Planned path with " + state.pathSegments.size() + " segments to " + nextOre.getType());

        if (state.pathSegments.isEmpty()) {
            // Already at ore, go straight to mining
            state.transitionTo(MiningPhase.MINING);
        } else {
            state.transitionTo(MiningPhase.MOVING);
        }
    }

    /**
     * MOVING phase - Follow path segments
     * v0.0.6: Added navigation verification - wait for movement to complete
     */
    private void processMoving(NPC npc, Player player, MiningState state, Location npcLoc) {
        debugLog("Phase: MOVING, segments remaining: " + state.pathSegments.size());

        // v0.0.6: Check if we're still navigating - don't interrupt
        Navigator nav = npc.getNavigator();
        if (nav != null && nav.isNavigating()) {
            // Still moving, check for timeout
            if (state.awaitingNavigation) {
                long elapsed = System.currentTimeMillis() - state.navigationStartTime;
                if (elapsed > 10000) { // 10 second navigation timeout
                    debugLog("Navigation timeout after " + elapsed + "ms");
                    nav.cancelNavigation();
                    state.awaitingNavigation = false;
                    state.ticksStuck += 5;
                }
            }
            return; // Let navigation continue
        }

        // Navigation completed or not started
        if (state.awaitingNavigation) {
            // Navigation just completed
            state.awaitingNavigation = false;
            state.navigationSuccesses++;
            state.ticksStuck = 0;
            debugLog("Navigation completed successfully");
        }

        if (state.pathSegments.isEmpty()) {
            // Reached destination, start mining
            state.transitionTo(MiningPhase.MINING);
            return;
        }

        PathSegment segment = state.pathSegments.peek();
        if (segment == null) {
            state.transitionTo(MiningPhase.MINING);
            return;
        }

        double distance = npcLoc.distance(segment.target);

        // Check if we've reached this segment
        if (distance < 1.5) {
            state.pathSegments.poll(); // Remove completed segment
            state.ticksStuck = 0; // Reset stuck counter on progress
            debugLog("Reached path segment, " + state.pathSegments.size() + " remaining");
            return;
        }

        // Handle blocking blocks
        if (segment.requiresMining && segment.blockToBreak != null) {
            Block block = segment.blockToBreak;
            if (block.getType().isSolid() && !block.getType().isAir()) {
                faceLocation(npc, block.getLocation().add(0.5, 0.5, 0.5));
                ItemStack tool = npc.getOrAddTrait(Equipment.class).get(Equipment.EquipmentSlot.HAND);

                Material blockType = block.getType();
                block.breakNaturally(tool);
                state.blocksCleared++;
                segment.attempts++;

                // v0.0.6: Verify block actually broke
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (block.getType() == Material.AIR || block.getType() != blockType) {
                            debugLog("Path block cleared: " + blockType);
                        } else {
                            debugLog("Path block failed to break: " + blockType);
                            state.ticksStuck++;
                        }
                    }
                }.runTaskLater(plugin, 5L);

                if (segment.hasExceededAttempts()) {
                    state.pathSegments.poll(); // Skip this segment
                }
                return;
            }
        }

        // Navigate to segment target
        if (isSafeLocation(segment.target)) {
            // Phase 3: Check WorldGuard before entering region
            if (!canEnterRegion(player, segment.target)) {
                String regionName = getRegionNameAt(segment.target);
                player.sendMessage(ChatColor.YELLOW + "Jarvis: Can't enter protected region" +
                    (regionName != null ? ": " + regionName : ""));
                state.pathSegments.poll(); // Skip this segment
                return;
            }

            // v0.0.6: Track navigation start
            state.awaitingNavigation = true;
            state.navigationStartTime = System.currentTimeMillis();
            state.navigationAttempts++;

            navigateToLocation(npc, segment.target);
            debugLog("Started navigation to segment, distance: " + String.format("%.1f", distance));
        } else {
            // Unsafe location, skip segment
            debugLog("Skipping unsafe segment");
            state.pathSegments.poll();
        }

        segment.attempts++;
        if (segment.hasExceededAttempts()) {
            debugLog("Segment exceeded max attempts, skipping");
            state.pathSegments.poll();
        }
    }

    /**
     * MINING phase - Break the target ore
     * v0.0.6: Added block break verification - confirms blocks actually break
     */
    private void processMiningPhase(NPC npc, Player player, MiningState state, Location npcLoc) {
        debugLog("Phase: MINING, awaiting confirmation: " + state.awaitingBreakConfirmation);

        // v0.0.6: If waiting for break confirmation, check status
        if (state.awaitingBreakConfirmation) {
            long elapsed = System.currentTimeMillis() - state.breakStartTime;
            Block block = state.targetOre;

            // Check if block actually broke
            if (block == null || block.getType() == Material.AIR ||
                (state.lastTargetType != null && block.getType() != state.lastTargetType)) {
                // SUCCESS - block broke!
                state.awaitingBreakConfirmation = false;
                state.oresActuallyMined++;
                state.blockBreakSuccesses++;
                state.miningAttempts = 0;

                debugLog("VERIFIED: Mined " + state.lastTargetType + " (actual: " + state.oresActuallyMined + ")");

                // Remove from cluster
                if (state.currentCluster != null) {
                    state.currentCluster.removeOre(state.targetOre);
                }

                state.targetOre = null;
                state.lastTargetType = null;
                state.transitionTo(MiningPhase.COLLECTING);
                return;
            }

            // Calculate expected dig time
            int expectedDigTimeMs = calculateDigTime(block, npc.getOrAddTrait(Equipment.class).get(Equipment.EquipmentSlot.HAND));

            // Check for timeout (dig time + generous buffer)
            if (elapsed > expectedDigTimeMs + 3000) {
                // TIMEOUT - block didn't break
                state.awaitingBreakConfirmation = false;
                state.miningAttempts++;

                debugLog("Mining timeout after " + elapsed + "ms (expected: " + expectedDigTimeMs + "ms), attempt " + state.miningAttempts);

                if (state.miningAttempts >= 5) {
                    // Give up on this ore after 5 attempts
                    player.sendMessage(ChatColor.YELLOW + "Jarvis: This ore won't break. Finding another...");
                    if (state.currentCluster != null) {
                        state.currentCluster.removeOre(state.targetOre);
                    }
                    state.targetOre = null;
                    state.miningAttempts = 0;
                    state.ticksStuck += 10;
                    state.transitionTo(MiningPhase.SEARCHING);
                }
                // Otherwise, will retry mining on next tick
            }
            return; // Still waiting
        }

        if (state.targetOre == null || !isOre(state.targetOre.getType())) {
            // Ore already mined or invalid
            if (state.currentCluster != null) {
                state.currentCluster.removeOre(state.targetOre);
            }
            state.transitionTo(MiningPhase.COLLECTING);
            return;
        }

        Location oreLoc = state.targetOre.getLocation().add(0.5, 0.5, 0.5);
        double distance = npcLoc.distance(oreLoc);

        if (distance > REACH_DISTANCE) {
            // Too far, need to move closer
            debugLog("Too far to mine (" + String.format("%.1f", distance) + " blocks), replanning");
            state.transitionTo(MiningPhase.PLANNING);
            return;
        }

        // Phase 3: Check WorldGuard permissions before mining
        if (!canMineAt(player, state.targetOre.getLocation())) {
            String regionName = getRegionNameAt(state.targetOre.getLocation());
            if (regionName != null) {
                player.sendMessage(ChatColor.RED + "Jarvis: Can't mine here - protected region: " + regionName);
            } else {
                player.sendMessage(ChatColor.RED + "Jarvis: Can't mine here - protected area!");
            }
            // Skip this ore
            if (state.currentCluster != null) {
                state.currentCluster.removeOre(state.targetOre);
            }
            state.targetOre = null;
            state.transitionTo(MiningPhase.SEARCHING);
            return;
        }

        // Face and mine the ore
        faceLocation(npc, oreLoc);
        ItemStack tool = npc.getOrAddTrait(Equipment.class).get(Equipment.EquipmentSlot.HAND);
        Material oreType = state.targetOre.getType();

        // v0.0.6: Record what we're mining and start verification
        state.lastTargetType = oreType;
        state.awaitingBreakConfirmation = true;
        state.breakStartTime = System.currentTimeMillis();
        state.blockBreakAttempts++;
        state.oresMined++; // Attempted count (for backwards compatibility)

        // Actually break the block
        state.targetOre.breakNaturally(tool);

        debugLog("Attempting to mine " + oreType + " at " + oreLoc.toVector() +
                 " (attempt " + (state.miningAttempts + 1) + ")");

        // Phase 3: Trigger Denizen event for ore mined
        if (denizenEnabled) {
            Map<String, Object> eventData = new HashMap<>();
            eventData.put("ore_type", oreType.name());
            eventData.put("total_mined", state.oresMined);
            eventData.put("x", oreLoc.getBlockX());
            eventData.put("y", oreLoc.getBlockY());
            eventData.put("z", oreLoc.getBlockZ());
            triggerDenizenEvent("ore_mined", player, npc, eventData);
        }
    }

    /**
     * Calculate expected dig time in milliseconds based on tool and block
     * v0.0.6: Used for mining verification timeout
     */
    private int calculateDigTime(Block block, ItemStack tool) {
        if (block == null) return 1000;

        Material blockType = block.getType();
        float hardness = blockType.getHardness();

        // Unbreakable blocks
        if (hardness < 0) return Integer.MAX_VALUE;

        // Base dig time in seconds
        float baseTime = hardness * 1.5f;

        // Tool effectiveness
        float multiplier = 1.0f;
        if (tool != null) {
            Material toolType = tool.getType();

            // Check if correct tool
            if (isPickaxe(toolType) && requiresPickaxe(blockType)) {
                multiplier = getToolSpeed(toolType);

                // Efficiency enchantment
                int efficiency = tool.getEnchantmentLevel(Enchantment.EFFICIENCY);
                if (efficiency > 0) {
                    multiplier += (efficiency * efficiency + 1);
                }
            }
        }

        // Calculate final time in milliseconds
        int timeMs = (int) ((baseTime / multiplier) * 1000);

        // Minimum 50ms, maximum 30 seconds
        return Math.max(50, Math.min(timeMs, 30000));
    }

    private boolean isPickaxe(Material mat) {
        if (mat == null) return false;
        String name = mat.name();
        return name.endsWith("_PICKAXE");
    }

    private boolean requiresPickaxe(Material mat) {
        String name = mat.name();
        return name.contains("ORE") || name.contains("STONE") || name.contains("DEEPSLATE") ||
               name.equals("OBSIDIAN") || name.equals("ANCIENT_DEBRIS");
    }

    private float getToolSpeed(Material tool) {
        if (tool == null) return 1.0f;
        String name = tool.name();
        if (name.startsWith("NETHERITE_")) return 9.0f;
        if (name.startsWith("DIAMOND_")) return 8.0f;
        if (name.startsWith("IRON_")) return 6.0f;
        if (name.startsWith("STONE_")) return 4.0f;
        if (name.startsWith("GOLDEN_")) return 12.0f;
        if (name.startsWith("WOODEN_")) return 2.0f;
        return 1.0f;
    }

    /**
     * COLLECTING phase - Pick up drops
     */
    private void processCollecting(NPC npc, Player player, MiningState state, Location npcLoc) {
        debugLog("Phase: COLLECTING");

        pickupNearbyItems(npc);

        // Short delay for items to spawn
        if (state.ticksInPhase < 3) {
            return;
        }

        // Check if cluster has more ores
        if (state.currentCluster != null && !state.currentCluster.isEmpty()) {
            state.transitionTo(MiningPhase.PLANNING);
        } else {
            // Cluster exhausted, search for new one
            state.transitionTo(MiningPhase.SEARCHING);
        }
    }

    /**
     * STUCK phase - Recovery logic
     */
    private void processStuck(NPC npc, Player player, MiningState state, Location npcLoc) {
        debugLog("Phase: STUCK, ticks: " + state.ticksInPhase);

        if (state.ticksInPhase == 1) {
            // First: Try mining around
            if (stuckMineAround) {
                player.sendMessage(ChatColor.YELLOW + "Jarvis: Stuck, clearing space...");
                mineAroundWhenStuck(npc, npcLoc);
            }
        } else if (state.ticksInPhase == 10) {
            // Second: Reset current target
            player.sendMessage(ChatColor.YELLOW + "Jarvis: Finding different path...");
            state.pathSegments.clear();
            if (state.currentCluster != null && state.targetOre != null) {
                state.currentCluster.removeOre(state.targetOre);
            }
            state.targetOre = null;
        } else if (state.ticksInPhase >= 20) {
            // Last resort: Teleport to player
            player.sendMessage(ChatColor.YELLOW + "Jarvis: Coming back to you!");
            Location safeLoc = findSafeSpawnLocation(player.getLocation());
            npc.teleport(safeLoc, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
            cleanupPillarBlocks(state);
            state.currentCluster = null;
            state.ticksStuck = 0;
        }

        // Check if unstuck
        if (state.ticksStuck == 0) {
            state.transitionTo(MiningPhase.SEARCHING);
        }
    }

    /**
     * RETREATING phase - Escape from danger
     */
    private void processRetreating(NPC npc, Player player, MiningState state, Location npcLoc) {
        debugLog("Phase: RETREATING");

        retreatToSafety(npc, player, npcLoc);

        // Check if safe now
        if (!isDangerNearby(npcLoc)) {
            state.transitionTo(MiningPhase.SEARCHING);
        } else if (state.ticksInPhase > 20) {
            // Teleport to player if can't escape
            Location safeLoc = findSafeSpawnLocation(player.getLocation());
            npc.teleport(safeLoc, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
            state.transitionTo(MiningPhase.SEARCHING);
        }
    }

    // ========== PHASE 2: ORE CLUSTERING ==========

    /**
     * Find the best ore cluster to mine (Phase 2)
     */
    private OreCluster findBestOreCluster(Location center) {
        List<OreCluster> clusters = new ArrayList<>();
        Set<Block> assignedOres = new HashSet<>();

        // Find all ores in range
        List<OreInfo> allOres = new ArrayList<>();
        for (int x = -SEARCH_RADIUS; x <= SEARCH_RADIUS; x++) {
            for (int y = -SEARCH_RADIUS; y <= SEARCH_RADIUS; y++) {
                for (int z = -SEARCH_RADIUS; z <= SEARCH_RADIUS; z++) {
                    Block block = center.clone().add(x, y, z).getBlock();
                    Material material = block.getType();

                    if (!isOre(material)) continue;

                    double distance = center.distance(block.getLocation().add(0.5, 0.5, 0.5));
                    if (distance > SEARCH_RADIUS) continue;

                    int priority = ORE_PRIORITY.indexOf(material);
                    if (priority < 0) priority = ORE_PRIORITY.size();

                    allOres.add(new OreInfo(block, distance, true, priority));
                }
            }
        }

        if (allOres.isEmpty()) return null;

        // Cluster nearby ores together (within 5 blocks of each other)
        double clusterRadius = 5.0;

        for (OreInfo oreInfo : allOres) {
            if (assignedOres.contains(oreInfo.block)) continue;

            // Start new cluster
            OreCluster cluster = new OreCluster();
            cluster.addOre(oreInfo.block, oreInfo.valuePriority);
            assignedOres.add(oreInfo.block);

            // Find nearby ores to add to cluster
            for (OreInfo other : allOres) {
                if (assignedOres.contains(other.block)) continue;

                double dist = oreInfo.block.getLocation().distance(other.block.getLocation());
                if (dist <= clusterRadius) {
                    cluster.addOre(other.block, other.valuePriority);
                    assignedOres.add(other.block);
                }
            }

            cluster.distanceFromNpc = center.distance(cluster.center);
            clusters.add(cluster);
        }

        if (clusters.isEmpty()) return null;

        // Sort clusters by value/distance ratio (prefer high value, close clusters)
        clusters.sort((a, b) -> {
            double scoreA = a.totalValue / (a.distanceFromNpc + 1);
            double scoreB = b.totalValue / (b.distanceFromNpc + 1);
            return Double.compare(scoreB, scoreA); // Higher score first
        });

        return clusters.get(0);
    }

    // ========== PHASE 2: PATH SEGMENTATION ==========

    /**
     * Calculate path segments from current location to target (Phase 2)
     */
    private void calculatePathSegments(Location from, Location to, MiningState state) {
        double distance = from.distance(to);

        // If close enough, no segments needed
        if (distance <= REACH_DISTANCE) {
            return;
        }

        Vector direction = to.toVector().subtract(from.toVector()).normalize();
        double segmentLength = 2.0; // 2 blocks per segment

        Location current = from.clone();
        int maxSegments = 20;
        int segments = 0;

        while (current.distance(to) > REACH_DISTANCE && segments < maxSegments) {
            Location nextPoint = current.clone().add(direction.clone().multiply(segmentLength));

            // Check for blocking blocks along the way
            Block blockingBlock = findBlockingBlock(current, nextPoint);

            if (blockingBlock != null && blockingBlock.getType().isSolid()) {
                // Need to break this block
                state.pathSegments.add(new PathSegment(
                    blockingBlock.getLocation().add(0.5, 0.5, 0.5),
                    blockingBlock
                ));
            } else {
                // Clear path, just move
                state.pathSegments.add(new PathSegment(nextPoint));
            }

            current = nextPoint;
            segments++;
        }

        debugLog("Calculated " + state.pathSegments.size() + " path segments");
    }

    // ========== BRANCH MINING MODE ==========

    public void startBranchMining(Player player) {
        NPC npc = getNPC(player);
        if (npc == null) return;
        
        stopTask(player);
        
        // Initialize branch mining state
        BranchMiningState state = new BranchMiningState();
        state.startLocation = getCurrentLocation(npc).clone();
        state.mainDirection = npc.getEntity().getLocation().getDirection().setY(0).normalize();
        state.branchLength = plugin.getConfig().getInt("mining.branch-length", 16);
        state.branchSpacing = plugin.getConfig().getInt("mining.branch-spacing", 3);
        state.tunnelHeight = plugin.getConfig().getInt("mining.tunnel-height", 2);
        state.maxBranches = plugin.getConfig().getInt("mining.max-branches", 10);
        state.isActive = true;
        
        branchMiningStates.put(player.getUniqueId(), state);
        
        player.sendMessage(ChatColor.GOLD + "Jarvis: Starting branch mining pattern!");
        player.sendMessage(ChatColor.GRAY + "Spacing: " + state.branchSpacing + 
            " | Length: " + state.branchLength + " | Height: " + state.tunnelHeight);
        
        equipPickaxe(npc, false);
        
        // Start task
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!npc.isSpawned() || !state.isActive) {
                    cancel();
                    branchMiningStates.remove(player.getUniqueId());

                    player.sendMessage(ChatColor.GOLD + "Jarvis: Branch mining complete!");
                    player.sendMessage(ChatColor.GRAY + "Mined " + state.totalBlocksMined + " blocks total");
                    return;
                }
                
                processBranchMining(npc, player, state);
            }
        };
        
        task.runTaskTimer(plugin, 0L, MINING_TICK_RATE);
        activeTasks.put(player.getUniqueId(), task);
    }

    private void processBranchMining(NPC npc, Player player, BranchMiningState state) {
        Location npcLoc = getCurrentLocation(npc);
        
        // Place torches periodically
        tryPlaceTorch(npc, player, npcLoc);
        
        // Pick up items
        pickupNearbyItems(npc);
        
        // Calculate next target if needed
        if (state.currentTarget == null || npcLoc.distance(state.currentTarget) < 1.0) {
            
            // Check if current branch is complete
            if (state.blocksInCurrentBranch >= state.branchLength) {
                // Move to next branch
                state.currentBranch++;
                state.blocksInCurrentBranch = 0;
                
                if (state.currentBranch >= state.maxBranches) {
                    state.isActive = false;
                    return;
                }
                
                player.sendMessage(ChatColor.GRAY + "Branch " + (state.currentBranch + 1) + 
                    " of " + state.maxBranches);
                
                // Calculate start of next branch
                // Go back to main tunnel
                Vector perpendicular = new Vector(-state.mainDirection.getZ(), 0, state.mainDirection.getX());
                Location branchStart = state.startLocation.clone()
                    .add(state.mainDirection.clone().multiply(state.branchSpacing * (state.currentBranch + 1)))
                    .add(perpendicular.multiply((state.currentBranch % 2 == 0) ? 1 : -1));
                
                state.currentTarget = branchStart;
                state.blocksInCurrentBranch = 0;
                
                return;
            }
            
            // Calculate next block in branch
            Vector branchDirection;
            if (state.currentBranch % 2 == 0) {
                branchDirection = new Vector(-state.mainDirection.getZ(), 0, state.mainDirection.getX());
            } else {
                branchDirection = new Vector(state.mainDirection.getZ(), 0, -state.mainDirection.getX());
            }
            
            Location nextBlock = npcLoc.clone().add(branchDirection);
            state.currentTarget = nextBlock;
        }
        
        // Mine blocks to create tunnel
        mineBlocksForTunnel(npc, state, npcLoc);
    }

    private void mineBlocksForTunnel(NPC npc, BranchMiningState state, Location npcLoc) {
        // Mine blocks at eye level and feet level for tunnel
        for (int yOffset = 0; yOffset < state.tunnelHeight; yOffset++) {
            Location blockLoc = state.currentTarget.clone().add(0, yOffset, 0);
            Block block = blockLoc.getBlock();
            
            if (!block.getType().isAir() && block.getType().isSolid()) {
                ItemStack tool = npc.getOrAddTrait(Equipment.class).get(Equipment.EquipmentSlot.HAND);
                faceLocation(npc, blockLoc.add(0.5, 0.5, 0.5));
                block.breakNaturally(tool);
                state.totalBlocksMined++;
                pickupNearbyItems(npc);
            }
        }
        
        state.blocksInCurrentBranch++;
        
        // Move Jarvis forward
        Vector direction = state.currentTarget.toVector().subtract(npcLoc.toVector()).normalize();
        Location newLoc = npcLoc.clone().add(direction.multiply(0.5));
        newLoc.setDirection(npcLoc.getDirection());
        npc.getEntity().teleport(newLoc);
    }

    // ========== VEIN MINING ==========

    private void processVeinMining(NPC npc, Player player, MiningState state) {
        if (state.currentVein.isEmpty()) {
            state.miningVein = false;
            state.reset();
            return;
        }
        
        Location npcLoc = getCurrentLocation(npc);
        
        // Find closest block in vein
        Block closest = null;
        double closestDist = Double.MAX_VALUE;
        
        for (Block block : state.currentVein) {
            if (!isOre(block.getType())) {
                continue;
            }
            double dist = npcLoc.distance(block.getLocation());
            if (dist < closestDist) {
                closestDist = dist;
                closest = block;
            }
        }
        
        if (closest == null) {
            state.miningVein = false;
            state.currentVein.clear();
            player.sendMessage(ChatColor.GREEN + "Jarvis: Vein complete!");
            return;
        }
        
        state.targetOre = closest;
        
        // Mine the ore
        if (closestDist <= REACH_DISTANCE) {
            ItemStack tool = npc.getOrAddTrait(Equipment.class).get(Equipment.EquipmentSlot.HAND);
            faceLocation(npc, closest.getLocation().add(0.5, 0.5, 0.5));
            closest.breakNaturally(tool);
            state.oresMined++;
            state.currentVein.remove(closest);
            pickupNearbyItems(npc);
            
            debugLog("Mined vein ore: " + closest.getType() + " (" + state.currentVein.size() + " remaining)");
        } else {
            // Move towards ore
            moveTowardsLocation(npc, npcLoc, closest.getLocation().add(0.5, 0.5, 0.5));
        }
    }

    private Set<Block> detectVein(Block startOre) {
        Set<Block> vein = new HashSet<>();
        Set<Block> checked = new HashSet<>();
        Queue<Block> toCheck = new LinkedList<>();
        
        toCheck.add(startOre);
        Material oreType = startOre.getType();
        
        while (!toCheck.isEmpty() && vein.size() < MAX_VEIN_SIZE) {
            Block current = toCheck.poll();
            
            if (checked.contains(current)) continue;
            checked.add(current);
            
            if (current.getType() == oreType) {
                vein.add(current);
                
                // Check adjacent blocks
                for (int x = -1; x <= 1; x++) {
                    for (int y = -1; y <= 1; y++) {
                        for (int z = -1; z <= 1; z++) {
                            if (x == 0 && y == 0 && z == 0) continue;
                            
                            Block adjacent = current.getRelative(x, y, z);
                            if (!checked.contains(adjacent) && adjacent.getType() == oreType) {
                                toCheck.add(adjacent);
                            }
                        }
                    }
                }
            }
        }
        
        return vein;
    }

    // ========== TORCH PLACEMENT ==========

    private void tryPlaceTorch(NPC npc, Player player, Location currentLoc) {
        if (!placeTorches) return;
        
        UUID playerId = player.getUniqueId();
        Location lastTorch = lastTorchPlaced.get(playerId);
        
        // Check distance since last torch
        if (lastTorch != null && lastTorch.distance(currentLoc) < torchSpacing) {
            return;
        }
        
        // Check if Jarvis has torches in inventory
        Inventory inv = npc.getOrAddTrait(Inventory.class);
        ItemStack[] contents = inv.getContents();
        
        int torchSlot = -1;
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null && contents[i].getType() == Material.TORCH) {
                torchSlot = i;
                break;
            }
        }
        
        if (torchSlot == -1) {
            // Out of torches - warn player once
            if (lastTorch == null) { // Only warn once
                if (plugin.getConfig().getBoolean("mining.visual-feedback.inventory-warnings", true)) {
                    player.sendMessage(ChatColor.YELLOW + "Jarvis: Out of torches!");
                }
            }
            return;
        }
        
        // Determine torch location
        Location torchLoc = currentLoc.clone();
        if (torchOnFloor) {
            torchLoc.subtract(0, 1, 0);
        }
        
        Block torchBlock = torchLoc.getBlock();
        
        // Place torch if location is valid
        if (torchBlock.getType().isAir() || !torchBlock.getType().isSolid()) {
            torchBlock.setType(Material.TORCH);
            
            // Remove one torch from inventory
            ItemStack torchStack = contents[torchSlot];
            torchStack.setAmount(torchStack.getAmount() - 1);
            if (torchStack.getAmount() <= 0) {
                contents[torchSlot] = null;
            }
            inv.setContents(contents);
            
            // Remember location
            lastTorchPlaced.put(playerId, torchLoc);
            
            if (plugin.getConfig().getBoolean("mining.visual-feedback.chat-messages", true)) {
                player.sendMessage(ChatColor.GRAY + "🔥 Torch placed");
            }
            
            debugLog("Placed torch at " + torchLoc);
        }
    }

    // ========== PHASE 1: DANGER DETECTION & STUCK RECOVERY ==========

    /**
     * Check if there are dangerous blocks nearby (lava, fire, etc.)
     */
    private boolean isDangerNearby(Location loc) {
        int checkRadius = 2;
        for (int x = -checkRadius; x <= checkRadius; x++) {
            for (int y = -1; y <= 2; y++) {
                for (int z = -checkRadius; z <= checkRadius; z++) {
                    Block block = loc.clone().add(x, y, z).getBlock();
                    Material type = block.getType();

                    if (avoidLava && (type == Material.LAVA)) {
                        debugLog("Danger: Lava detected at " + block.getLocation());
                        return true;
                    }
                    if (avoidFire && type == Material.FIRE) {
                        debugLog("Danger: Fire detected at " + block.getLocation());
                        return true;
                    }
                }
            }
        }

        // Check Y level
        if (loc.getY() < minYLevel) {
            debugLog("Danger: Below minimum Y level");
            return true;
        }

        return false;
    }

    /**
     * Check if a specific location is safe to move to
     */
    private boolean isSafeLocation(Location loc) {
        Block block = loc.getBlock();
        Block below = block.getRelative(BlockFace.DOWN);
        Block above = block.getRelative(BlockFace.UP);

        // Must have solid ground
        if (!below.getType().isSolid()) return false;

        // Must have space for NPC
        if (!block.getType().isAir() && block.getType() != Material.CAVE_AIR) return false;
        if (!above.getType().isAir() && above.getType() != Material.CAVE_AIR) return false;

        // Check for danger blocks
        Material belowType = below.getType();
        if (avoidLava && belowType == Material.LAVA) return false;
        if (avoidFire && belowType == Material.FIRE) return false;
        if (belowType == Material.MAGMA_BLOCK) return false;
        if (belowType == Material.CACTUS) return false;

        return true;
    }

    /**
     * Retreat to a safe location when danger is detected
     */
    private void retreatToSafety(NPC npc, Player player, Location currentLoc) {
        // Try to find safe spot in expanding radius
        for (int radius = 2; radius <= 8; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    Location check = currentLoc.clone().add(x, 1, z);
                    if (isSafeLocation(check) && !isDangerNearby(check)) {
                        debugLog("Retreating to safe location: " + check);

                        if (useNavigator) {
                            npc.getNavigator().setTarget(check);
                        } else {
                            npc.teleport(check, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                        }
                        return;
                    }
                }
            }
        }

        // No safe spot found, teleport to player
        Location safeLoc = findSafeSpawnLocation(player.getLocation());
        npc.teleport(safeLoc, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
        player.sendMessage(ChatColor.YELLOW + "Jarvis: Had to teleport back - too dangerous!");
    }

    /**
     * Mine blocks in immediate vicinity when stuck
     */
    private void mineAroundWhenStuck(NPC npc, Location npcLoc) {
        ItemStack tool = npc.getOrAddTrait(Equipment.class).get(Equipment.EquipmentSlot.HAND);
        int mined = 0;

        // Mine blocks at NPC level and one above (2-high clearance)
        for (int y = 0; y <= 1; y++) {
            for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                Block target = npcLoc.getBlock().getRelative(face).getRelative(BlockFace.UP, y);

                if (target.getType().isSolid() && !isOre(target.getType())) {
                    // Don't mine bedrock or other unbreakables
                    if (target.getType() == Material.BEDROCK ||
                        target.getType() == Material.BARRIER ||
                        target.getType() == Material.END_PORTAL_FRAME) {
                        continue;
                    }

                    target.breakNaturally(tool);
                    mined++;
                }
            }
        }

        if (mined > 0) {
            debugLog("Mined " + mined + " blocks while stuck");
            pickupNearbyItems(npc);
        }
    }

    /**
     * Navigate to target using Citizens Navigator (Phase 1 improvement)
     */
    private void navigateToLocation(NPC npc, Location target) {
        if (useNavigator && npc.getNavigator() != null) {
            Navigator nav = npc.getNavigator();

            // Configure navigator parameters
            NavigatorParameters params = nav.getLocalParameters();
            params.distanceMargin(1.5);
            params.avoidWater(avoidWater);
            // StuckAction: teleport NPC to target when stuck
            params.stuckAction((npcRef, navigator) -> {
                if (navigator.getTargetAsLocation() != null) {
                    npcRef.teleport(navigator.getTargetAsLocation(),
                        org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                }
                return false; // Don't cancel navigation
            });

            // Set target
            if (!nav.isNavigating() || nav.getTargetAsLocation() == null ||
                nav.getTargetAsLocation().distance(target) > 2) {
                nav.setTarget(target);
            }
        } else {
            // Fallback to old method if navigator disabled
            Location npcLoc = getCurrentLocation(npc);
            moveTowardsLocation(npc, npcLoc, target);
        }
    }

    // ========== MINING LOGIC ==========

    private void processMining(NPC npc, Player player, MiningState state) {
        Location npcLoc = getCurrentLocation(npc);
        Location oreLoc = state.targetOre.getLocation().add(0.5, 0.5, 0.5);
        double distance = npcLoc.distance(oreLoc);

        // If within reach, mine the ore
        if (distance <= REACH_DISTANCE) {
            ItemStack tool = npc.getOrAddTrait(Equipment.class).get(Equipment.EquipmentSlot.HAND);
            
            faceLocation(npc, oreLoc);
            
            // Check for vein mining
            if (enableVeinMining && !state.miningVein) {
                Set<Block> vein = detectVein(state.targetOre);
                if (vein.size() > 1) {
                    state.currentVein = vein;
                    state.miningVein = true;
                    player.sendMessage(ChatColor.AQUA + "Jarvis: Found " + 
                        state.targetOre.getType().toString().replace("_", " ").toLowerCase() + 
                        " vein! (" + vein.size() + " blocks)");
                    debugLog("Detected vein of size " + vein.size());
                }
            }
            
            state.targetOre.breakNaturally(tool);
            state.oresMined++;
            debugLog("Mined " + state.targetOre.getType() + " (total: " + state.oresMined + ")");
            
            if (!state.miningVein) {
                state.targetOre = null;
                state.currentBlockToBreak = null;
                cleanupPillarBlocks(state);
            }
            
            pickupNearbyItems(npc);
            return;
        }

        // Calculate path to ore
        Vector toOre = oreLoc.toVector().subtract(npcLoc.toVector());
        double heightDiff = toOre.getY();
        
        // Check if we need to climb
        if (heightDiff > CLIMB_HEIGHT_THRESHOLD && distance > 3) {
            if (!climbTowardsOre(npc, state, npcLoc, oreLoc)) {
                findAndBreakBlockingBlock(npc, state, npcLoc, oreLoc);
            }
            return;
        }

        // Find and break blocking blocks
        Block blockingBlock = findBlockingBlock(npcLoc, oreLoc);
        if (blockingBlock != null && !blockingBlock.getType().isAir()) {
            if (state.currentBlockToBreak == null || !state.currentBlockToBreak.equals(blockingBlock)) {
                state.currentBlockToBreak = blockingBlock;
                debugLog("Breaking blocking block: " + blockingBlock.getType());
            }

            faceLocation(npc, blockingBlock.getLocation().add(0.5, 0.5, 0.5));
            
            ItemStack tool = npc.getOrAddTrait(Equipment.class).get(Equipment.EquipmentSlot.HAND);
            blockingBlock.breakNaturally(tool);
            pickupNearbyItems(npc);
        } else {
            // Phase 1: Check if destination is safe before moving
            if (isSafeLocation(oreLoc.getBlock().getLocation())) {
                navigateToLocation(npc, oreLoc);
            } else {
                debugLog("Target ore location is unsafe, skipping");
                state.reset();
            }
        }
    }

    private boolean climbTowardsOre(NPC npc, MiningState state, Location npcLoc, Location targetLoc) {
        if (!hasDirtInInventory(npc)) {
            giveDirt(npc);
        }

        if (state.pillarBlocks.size() >= MAX_PILLAR_HEIGHT) {
            debugLog("Pillar max height reached");
            return false;
        }

        Block currentBlock = npcLoc.getBlock();
        Block aboveBlock = currentBlock.getRelative(BlockFace.UP);
        
        if (aboveBlock.getType().isAir()) {
            aboveBlock.setType(Material.DIRT);
            state.pillarBlocks.add(aboveBlock);
            debugLog("Placed dirt pillar block at " + aboveBlock.getLocation());
            
            Location newLoc = aboveBlock.getLocation().add(0.5, 0, 0.5);
            newLoc.setDirection(npcLoc.getDirection());
            npc.getEntity().teleport(newLoc);
            
            return true;
        }
        
        return false;
    }

    private void findAndBreakBlockingBlock(NPC npc, MiningState state, Location from, Location to) {
        Block blockingBlock = findBlockingBlock(from, to);
        if (blockingBlock != null && !blockingBlock.getType().isAir()) {
            if (state.currentBlockToBreak == null || !state.currentBlockToBreak.equals(blockingBlock)) {
                state.currentBlockToBreak = blockingBlock;
                debugLog("Breaking blocking block: " + blockingBlock.getType());
            }

            faceLocation(npc, blockingBlock.getLocation().add(0.5, 0.5, 0.5));
            
            ItemStack tool = npc.getOrAddTrait(Equipment.class).get(Equipment.EquipmentSlot.HAND);
            blockingBlock.breakNaturally(tool);
            pickupNearbyItems(npc);
        }
    }

    private void moveTowardsLocation(NPC npc, Location from, Location to) {
        Vector direction = to.toVector().subtract(from.toVector()).normalize();
        Vector moveVector = direction.multiply(MOVE_SPEED);
        Location newLoc = from.clone().add(moveVector);
        
        Block groundBlock = newLoc.getBlock().getRelative(BlockFace.DOWN);
        if (groundBlock.getType().isAir()) {
            debugLog("No ground below, not moving");
            return;
        }
        
        if (newLoc.getBlock().getType().isAir()) {
            newLoc.setDirection(from.getDirection());
            npc.getEntity().teleport(newLoc);
        }
    }

    private Block findBlockingBlock(Location from, Location to) {
        Vector direction = to.toVector().subtract(from.toVector()).normalize();
        double distance = from.distance(to);
        
        for (double d = 0.5; d < Math.min(distance, REACH_DISTANCE + 1); d += 0.5) {
            Location check = from.clone().add(direction.clone().multiply(d));
            Block block = check.getBlock();
            
            if (!block.getType().isAir() && 
                !block.equals(to.getBlock()) &&
                block.getType().isSolid()) {
                return block;
            }
        }
        return null;
    }

    private static class OreInfo implements Comparable<OreInfo> {
        Block block;
        double distance;
        boolean isExposed;
        int valuePriority;
        
        OreInfo(Block block, double distance, boolean isExposed, int valuePriority) {
            this.block = block;
            this.distance = distance;
            this.isExposed = isExposed;
            this.valuePriority = valuePriority;
        }
        
        @Override
        public int compareTo(OreInfo other) {
            if (this.isExposed != other.isExposed) {
                return this.isExposed ? -1 : 1;
            }
            
            if (this.valuePriority != other.valuePriority) {
                return Integer.compare(this.valuePriority, other.valuePriority);
            }
            
            return Double.compare(this.distance, other.distance);
        }
    }

    private OreInfo findBestOre(Location center) {
        List<OreInfo> ores = new ArrayList<>();

        for (int x = -SEARCH_RADIUS; x <= SEARCH_RADIUS; x++) {
            for (int y = -SEARCH_RADIUS; y <= SEARCH_RADIUS; y++) {
                for (int z = -SEARCH_RADIUS; z <= SEARCH_RADIUS; z++) {
                    Block block = center.clone().add(x, y, z).getBlock();
                    Material material = block.getType();
                    
                    if (!isOre(material)) continue;
                    
                    double distance = center.distance(block.getLocation().add(0.5, 0.5, 0.5));
                    if (distance > SEARCH_RADIUS) continue;
                    
                    int valuePriority = ORE_PRIORITY.indexOf(material);
                    boolean isExposed = isOreExposed(center, block.getLocation().add(0.5, 0.5, 0.5));
                    
                    ores.add(new OreInfo(block, distance, isExposed, valuePriority));
                }
            }
        }

        if (ores.isEmpty()) return null;
        
        Collections.sort(ores);
        return ores.get(0);
    }

    private boolean isOreExposed(Location from, Location oreLoc) {
        Vector direction = oreLoc.toVector().subtract(from.toVector());
        double distance = from.distance(oreLoc);
        
        if (distance > SEARCH_RADIUS) return false;
        
        RayTraceResult result = from.getWorld().rayTraceBlocks(
            from, 
            direction.normalize(), 
            distance,
            org.bukkit.FluidCollisionMode.NEVER,
            true
        );
        
        if (result != null && result.getHitBlock() != null) {
            Block hitBlock = result.getHitBlock();
            Location hitLoc = hitBlock.getLocation();
            Location checkLoc = oreLoc.clone().subtract(0.5, 0.5, 0.5).getBlock().getLocation();
            return hitLoc.equals(checkLoc);
        }
        
        return false;
    }

    private boolean isOre(Material mat) {
        return ORE_PRIORITY.contains(mat);
    }

    // ========== HELPER FUNCTIONS ==========

    private void equipPickaxe(NPC npc, boolean silk) {
        ItemStack pick = new ItemStack(Material.NETHERITE_PICKAXE);
        ItemMeta meta = pick.getItemMeta();
        meta.addEnchant(Enchantment.EFFICIENCY, 5, true);
        meta.addEnchant(Enchantment.UNBREAKING, 3, true);
        if (silk) {
            meta.addEnchant(Enchantment.SILK_TOUCH, 1, true);
        } else {
            meta.addEnchant(Enchantment.FORTUNE, 3, true);
        }
        pick.setItemMeta(meta);
        npc.getOrAddTrait(Equipment.class).set(Equipment.EquipmentSlot.HAND, pick);
    }

    private void equipWeapon(NPC npc) {
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = sword.getItemMeta();
        meta.addEnchant(Enchantment.SHARPNESS, 5, true);
        sword.setItemMeta(meta);
        npc.getOrAddTrait(Equipment.class).set(Equipment.EquipmentSlot.HAND, sword);
    }

    private void giveStartingEquipment(NPC npc) {
        Inventory invTrait = npc.getOrAddTrait(Inventory.class);
        ItemStack[] contents = invTrait.getContents();
        
        // Give dirt for climbing
        int dirtSlots = 0;
        for (int i = 0; i < contents.length && dirtSlots < 3; i++) {
            if (contents[i] == null) {
                contents[i] = new ItemStack(Material.DIRT, 64);
                dirtSlots++;
            }
        }
        
        // Give torches if torch placement enabled
        if (placeTorches) {
            int torchSlots = 0;
            for (int i = 0; i < contents.length && torchSlots < 2; i++) {
                if (contents[i] == null) {
                    contents[i] = new ItemStack(Material.TORCH, 64);
                    torchSlots++;
                }
            }
        }
        
        invTrait.setContents(contents);
    }

    private boolean hasDirtInInventory(NPC npc) {
        Inventory invTrait = npc.getOrAddTrait(Inventory.class);
        ItemStack[] contents = invTrait.getContents();
        
        for (ItemStack item : contents) {
            if (item != null && item.getType() == Material.DIRT) {
                return true;
            }
        }
        return false;
    }

    private void giveDirt(NPC npc) {
        Inventory invTrait = npc.getOrAddTrait(Inventory.class);
        ItemStack[] contents = invTrait.getContents();
        
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] == null) {
                contents[i] = new ItemStack(Material.DIRT, 64);
                invTrait.setContents(contents);
                debugLog("Gave Jarvis more dirt");
                return;
            }
        }
    }

    private void cleanupPillarBlocks(MiningState state) {
        if (state == null) return;
        
        for (Block block : state.pillarBlocks) {
            if (block.getType() == Material.DIRT) {
                block.setType(Material.AIR);
            }
        }
        
        if (!state.pillarBlocks.isEmpty()) {
            debugLog("Cleaned up " + state.pillarBlocks.size() + " pillar blocks");
        }
        
        state.pillarBlocks.clear();
    }

    private void faceLocation(NPC npc, Location target) {
        Location npcLoc = getCurrentLocation(npc);
        Vector direction = target.toVector().subtract(npcLoc.toVector()).normalize();
        Location lookAt = npcLoc.clone();
        lookAt.setDirection(direction);
        npc.getEntity().teleport(lookAt);
    }

    private void pickupNearbyItems(NPC npc) {
        Location loc = getCurrentLocation(npc);
        Inventory invTrait = npc.getOrAddTrait(Inventory.class);
        ItemStack[] contents = invTrait.getContents();

        for (Entity e : loc.getNearbyEntities((double)PICKUP_RADIUS, (double)PICKUP_RADIUS, (double)PICKUP_RADIUS)) {
            if (e.getType() == EntityType.ITEM) {
                org.bukkit.entity.Item itemEntity = (org.bukkit.entity.Item) e;
                ItemStack drop = itemEntity.getItemStack();

                if (drop.getType() == Material.DIRT) {
                    continue;
                }

                for (int i = 0; i < contents.length; i++) {
                    if (contents[i] == null) {
                        contents[i] = drop.clone();
                        invTrait.setContents(contents);
                        e.remove();
                        break;
                    } else if (contents[i].isSimilar(drop) && 
                              contents[i].getAmount() < contents[i].getMaxStackSize()) {
                        int add = Math.min(drop.getAmount(), 
                                         contents[i].getMaxStackSize() - contents[i].getAmount());
                        contents[i].setAmount(contents[i].getAmount() + add);
                        drop.setAmount(drop.getAmount() - add);
                        invTrait.setContents(contents);
                        if (drop.getAmount() <= 0) {
                            e.remove();
                            break;
                        }
                    }
                }
            }
        }
    }

    private void dropInventoryItems(NPC npc) {
        Inventory invTrait = npc.getOrAddTrait(Inventory.class);
        ItemStack[] contents = invTrait.getContents();
        Location dropLoc = getCurrentLocation(npc);
        
        Equipment equipment = npc.getOrAddTrait(Equipment.class);
        ItemStack handItem = equipment.get(Equipment.EquipmentSlot.HAND);
        
        for (ItemStack item : contents) {
            if (item != null && !item.isSimilar(handItem)) {
                dropLoc.getWorld().dropItem(dropLoc, item);
            }
        }
    }

    private Monster findNearestHostileMob(Location center) {
        Monster closest = null;
        double closestDist = Double.MAX_VALUE;
        for (Entity entity : center.getNearbyEntities((double)SEARCH_RADIUS, (double)SEARCH_RADIUS, (double)SEARCH_RADIUS)) {
            if (entity instanceof Monster mob && !mob.isDead()) {
                double dist = center.distance(mob.getLocation());
                if (dist < closestDist) {
                    closest = mob;
                    closestDist = dist;
                }
            }
        }
        return closest;
    }

    private Location findSafeSpawnLocation(Location playerLoc) {
        Location spawnLoc = playerLoc.clone().add(
            playerLoc.getDirection().setY(0).normalize().multiply(3)
        );

        while (spawnLoc.getY() > playerLoc.getWorld().getMinHeight() && 
               spawnLoc.getBlock().getType().isAir()) {
            spawnLoc.subtract(0, 1, 0);
        }
        
        if (!spawnLoc.getBlock().getType().isAir()) {
            spawnLoc.add(0, 1, 0);
        }

        spawnLoc.setDirection(playerLoc.toVector().subtract(spawnLoc.toVector()));
        return spawnLoc;
    }

    private Location getCurrentLocation(NPC npc) {
        if (npc.getEntity() != null) {
            return npc.getEntity().getLocation();
        }
        return npc.getStoredLocation();
    }

    private NPC getNPC(Player player) {
        NPC npc = playerNPCs.get(player.getUniqueId());
        if (npc == null || !npc.isSpawned()) {
            player.sendMessage("§cJarvis: I'm not summoned yet!");
            return null;
        }
        return npc;
    }

    public void stop(Player player) {
        stopTask(player);
        player.sendMessage("§7Jarvis: Task stopped.");
    }
    
    private void stopTask(Player player) {
        BukkitRunnable task = activeTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
        
        MiningState state = miningStates.remove(player.getUniqueId());
        if (state != null) {
            cleanupPillarBlocks(state);
        }
        
        branchMiningStates.remove(player.getUniqueId());
        lastTorchPlaced.remove(player.getUniqueId());
    }

    private void debugLog(String message) {
        if (DEBUG) {
            plugin.getLogger().log(Level.INFO, "[JarvisNPC] " + message);
        }
    }

    // ========== PUBLIC API ==========

    public void openInventory(Player player) {
        NPC npc = getNPC(player);
        if (npc == null) {
            player.sendMessage("§cJarvis: I'm not summoned yet!");
            return;
        }
        Inventory invTrait = npc.getOrAddTrait(Inventory.class);
        invTrait.openInventory(player);
    }

    /**
     * Clear inventory - drop all non-equipment items at NPC location
     * and restore starting equipment
     */
    public void clearInventory(Player player) {
        NPC npc = getNPC(player);
        if (npc == null) {
            player.sendMessage("§cJarvis: I'm not summoned yet!");
            return;
        }

        Inventory invTrait = npc.getOrAddTrait(Inventory.class);
        ItemStack[] contents = invTrait.getContents();
        Location dropLoc = getCurrentLocation(npc);

        int droppedCount = 0;

        // Drop all items except dirt and torches (starting equipment)
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null) continue;

            Material type = item.getType();

            // Keep dirt and torches as starting equipment
            if (type == Material.DIRT || type == Material.TORCH) {
                continue;
            }

            // Drop the item
            dropLoc.getWorld().dropItemNaturally(dropLoc, item.clone());
            droppedCount += item.getAmount();
            contents[i] = null;
        }

        // Apply cleared contents
        invTrait.setContents(contents);

        // Ensure starting equipment is present
        giveStartingEquipment(npc);

        if (droppedCount > 0) {
            player.sendMessage("§aJarvis: Dropped " + droppedCount + " items. Ready for a fresh start!");
            debugLog("Cleared " + droppedCount + " items from inventory for " + player.getName());
        } else {
            player.sendMessage("§eJarvis: No items to drop - inventory is already clear.");
        }
    }

    public void dismissAll() {
        for (MiningState state : miningStates.values()) {
            cleanupPillarBlocks(state);
        }
        miningStates.clear();
        branchMiningStates.clear();
        lastTorchPlaced.clear();
        
        playerNPCs.values().forEach(NPC::destroy);
        playerNPCs.clear();
        activeTasks.values().forEach(BukkitRunnable::cancel);
        activeTasks.clear();
        
        debugLog("All NPCs dismissed");
    }

    public NPC getNPCForPlayer(UUID uuid) {
        return playerNPCs.get(uuid);
    }

    /**
     * Handle player disconnect - save inventory and cleanup
     * Called by PlayerConnectionListener when player quits
     */
    public void handlePlayerDisconnect(Player player) {
        UUID playerId = player.getUniqueId();
        NPC npc = playerNPCs.remove(playerId);

        if (npc == null) {
            debugLog("No NPC to cleanup for disconnected player: " + player.getName());
            return;
        }

        // Save inventory to database before destroying
        if (npc.isSpawned()) {
            try {
                Inventory invTrait = npc.getOrAddTrait(Inventory.class);
                ItemStack[] contents = invTrait.getContents();
                plugin.getDatabaseManager().saveNpcInventory(playerId, contents);
                debugLog("Saved NPC inventory for " + player.getName() + " to database");
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to save NPC inventory for " + player.getName() + ": " + e.getMessage());
            }
        }

        // Clean up mining state and pillar blocks
        MiningState state = miningStates.remove(playerId);
        if (state != null) {
            cleanupPillarBlocks(state);
        }

        // Clean up branch mining state
        branchMiningStates.remove(playerId);
        lastTorchPlaced.remove(playerId);

        // Stop active tasks
        BukkitRunnable task = activeTasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }

        // Destroy NPC without dropping items (they're saved to DB)
        npc.destroy();

        debugLog("Cleaned up NPC for disconnected player: " + player.getName());
    }

    /**
     * Check if player has saved inventory from previous session
     */
    public boolean hasSavedInventory(UUID playerId) {
        return plugin.getDatabaseManager().hasSavedInventory(playerId);
    }

    public int getActiveNpcCount() {
        return (int) playerNPCs.values().stream().filter(NPC::isSpawned).count();
    }

    public int getActiveTaskCount() {
        return activeTasks.size();
    }
}
