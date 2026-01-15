package com.yourname.jarvis.npc;

import com.yourname.jarvis.Jarvis;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.CitizensAPI;
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
    private final Map<UUID, BukkitRunnable> activeTasks = new HashMap<>();
    private final Map<UUID, MiningState> miningStates = new HashMap<>();
    private final Map<UUID, BranchMiningState> branchMiningStates = new HashMap<>();
    private final Map<UUID, Location> lastTorchPlaced = new HashMap<>();

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

    /**
     * Mining state tracker with cleanup capability and vein tracking
     */
    private static class MiningState {
        Block targetOre;
        List<Block> pillarBlocks = new ArrayList<>();
        Block currentBlockToBreak;
        int ticksStuck = 0;
        Location lastLocation;
        int oresMined = 0;
        Set<Block> currentVein = new HashSet<>();
        boolean miningVein = false;
        
        void reset() {
            targetOre = null;
            currentBlockToBreak = null;
            ticksStuck = 0;
            currentVein.clear();
            miningVein = false;
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
        this.placeTorches = plugin.getConfig().getBoolean("mining.place-torches", true);
        this.torchOnFloor = plugin.getConfig().getBoolean("mining.torch-on-floor", true);
        this.enableVeinMining = plugin.getConfig().getBoolean("mining.enable-vein-mining", true);
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

        // Give Jarvis starting equipment
        giveStartingEquipment(npc);

        player.getWorld().playSound(spawnLoc, Sound.BLOCK_BELL_USE, 1.0f, 1.0f);
        player.sendMessage("§aJarvis: At your service—let's make some magic.");

        debugLog("Jarvis spawned for " + player.getName() + " at " + spawnLoc);
    }

    public void dismiss(Player player) {
        NPC npc = playerNPCs.remove(player.getUniqueId());
        if (npc == null) {
            player.sendMessage("§cJarvis: I'm not summoned yet!");
            return;
        }

        // Clean up any mining state
        MiningState state = miningStates.remove(player.getUniqueId());
        if (state != null) {
            cleanupPillarBlocks(state);
        }
        
        // Clean up branch mining state
        branchMiningStates.remove(player.getUniqueId());
        lastTorchPlaced.remove(player.getUniqueId());
        
        // Drop inventory but keep equipment
        dropInventoryItems(npc);
        
        npc.destroy();
        stopTask(player);
        player.sendMessage("§7Jarvis: Until next time—poof!");
        
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

    // ========== SMART MINING MODE ==========

    public void mine(Player player, String[] args) {
        mine(player);
    }

    public void mine(Player player) {
        NPC npc = getNPC(player);
        if (npc == null) return;
        stopTask(player);

        MiningState state = new MiningState();
        miningStates.put(player.getUniqueId(), state);

        player.sendMessage("§6Jarvis: Switching to mining mode!");
        if (enableVeinMining) {
            player.sendMessage("§7Vein mining enabled");
        }

        BukkitRunnable task = new BukkitRunnable() {
            private int noOreCounter = 0;

            @Override
            public void run() {
                if (!npc.isSpawned()) {
                    cancel();
                    miningStates.remove(player.getUniqueId());
                    return;
                }

                Location npcLoc = getCurrentLocation(npc);

                // Always pickup items
                pickupNearbyItems(npc);
                
                // Try to place torch
                tryPlaceTorch(npc, player, npcLoc);

                // Check if stuck
                if (state.lastLocation != null && state.lastLocation.distance(npcLoc) < 0.1) {
                    state.ticksStuck++;
                    if (state.ticksStuck > 20) {
                        debugLog("Jarvis stuck, resetting target");
                        state.reset();
                    }
                } else {
                    state.ticksStuck = 0;
                }
                state.lastLocation = npcLoc.clone();

                // Handle vein mining
                if (state.miningVein && !state.currentVein.isEmpty()) {
                    processVeinMining(npc, player, state);
                    return;
                }

                // Find new target ore if needed
                if (state.targetOre == null || !isOre(state.targetOre.getType())) {
                    OreInfo oreInfo = findBestOre(npcLoc);
                    if (oreInfo == null) {
                        noOreCounter++;
                        if (noOreCounter > 5) {
                            player.sendMessage("§eJarvis: No more ores nearby. Mined " + state.oresMined + " ores!");

                            cancel();
                            miningStates.remove(player.getUniqueId());
                            cleanupPillarBlocks(state);
                        }
                        return;
                    }
                    noOreCounter = 0;
                    state.targetOre = oreInfo.block;
                    state.currentBlockToBreak = null;
                    
                    boolean needsSilk = oreInfo.block.getType() == Material.DEEPSLATE_EMERALD_ORE || 
                                       oreInfo.block.getType() == Material.EMERALD_ORE;
                    equipPickaxe(npc, needsSilk);
                    
                    debugLog("New target: " + oreInfo.block.getType() + 
                            " at " + oreInfo.block.getLocation() + 
                            " (exposed: " + oreInfo.isExposed + 
                            ", distance: " + String.format("%.1f", oreInfo.distance) + ")");
                }

                // Process mining
                processMining(npc, player, state);
            }
        };
        task.runTaskTimer(plugin, 0L, MINING_TICK_RATE);
        activeTasks.put(player.getUniqueId(), task);
        
        debugLog("Jarvis entered mining mode for " + player.getName());
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
            moveTowardsLocation(npc, npcLoc, oreLoc);
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

    public int getActiveNpcCount() {
        return (int) playerNPCs.values().stream().filter(NPC::isSpawned).count();
    }

    public int getActiveTaskCount() {
        return activeTasks.size();
    }
}
