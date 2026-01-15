package com.yourname.jarvis.npc;

import com.yourname.jarvis.Jarvis;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.api.trait.trait.Inventory;
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
import java.util.logging.Level;

/**
 * JarvisNPC - Manages NPC spawning, combat, and intelligent mining
 * Version: 0.0.5
 * 
 * Key features:
 * - Smart mining with exposed ore priority
 * - Dirt pillar climbing system
 * - Intelligent pathfinding
 * - Combat mode for hostile mobs
 */
public class JarvisNPC {

    private final Jarvis plugin;
    private final Map<UUID, NPC> playerNPCs = new HashMap<>();
    private final Map<UUID, BukkitRunnable> activeTasks = new HashMap<>();
    private final Map<UUID, MiningState> miningStates = new HashMap<>();

    // Mining configuration constants
    private static final int SEARCH_RADIUS = 16;           // Reduced from 32 - stay closer
    private static final int PICKUP_RADIUS = 8;
    private static final double REACH_DISTANCE = 4.5;
    private static final double MOVE_SPEED = 0.25;         // Smooth movement speed
    private static final int MINING_TICK_RATE = 5;         // Check every 5 ticks
    private static final int COMBAT_TICK_RATE = 10;        // Check every 10 ticks
    private static final int CLIMB_HEIGHT_THRESHOLD = 2;   // Climb if >2 blocks up
    private static final int MAX_PILLAR_HEIGHT = 8;        // Max blocks to pillar up
    
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
     * Mining state tracker with cleanup capability
     */
    private static class MiningState {
        Block targetOre;
        List<Block> pillarBlocks = new ArrayList<>();    // Dirt pillar for climbing
        Block currentBlockToBreak;
        int ticksStuck = 0;
        Location lastLocation;
        int oresMined = 0;
        
        void reset() {
            targetOre = null;
            currentBlockToBreak = null;
            ticksStuck = 0;
        }
    }

    public JarvisNPC(Jarvis plugin) {
        this.plugin = plugin;
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

    /**
     * Battle another player (PvP mode)
     */
    public void battle(Player owner, Player target) {
        NPC npc = getNPC(owner);
        if (npc == null) return;
        stopTask(owner);

        equipWeapon(npc);
        
        // Set navigator to target the player
        npc.getNavigator().setTarget(target, true);
        
        owner.sendMessage("§cJarvis: Engaging " + target.getName() + "!");
        debugLog("Jarvis in battle mode: " + owner.getName() + " vs " + target.getName());
    }

    // ========== SMART MINING MODE ==========

    /**
     * Mine with optional arguments (for command compatibility)
     */
    public void mine(Player player, String[] args) {
        // For now, ignore args and use default mining
        // Future: could add modes like "mine deep", "mine exposed", etc.
        mine(player);
    }

    public void mine(Player player) {
        NPC npc = getNPC(player);
        if (npc == null) return;
        stopTask(player);

        MiningState state = new MiningState();
        miningStates.put(player.getUniqueId(), state);

        player.sendMessage("§6Jarvis: Switching to mining mode!");

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

                // Check if stuck (not moving)
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

    /**
     * Main mining logic - handles movement, climbing, and breaking blocks
     */
    private void processMining(NPC npc, Player player, MiningState state) {
        Location npcLoc = getCurrentLocation(npc);
        Location oreLoc = state.targetOre.getLocation().add(0.5, 0.5, 0.5);
        double distance = npcLoc.distance(oreLoc);

        // If within reach, mine the ore
        if (distance <= REACH_DISTANCE) {
            ItemStack tool = npc.getOrAddTrait(Equipment.class).get(Equipment.EquipmentSlot.HAND);
            
            // Face the ore
            faceLocation(npc, oreLoc);
            
            // Break the ore
            state.targetOre.breakNaturally(tool);
            state.oresMined++;
            debugLog("Mined " + state.targetOre.getType() + " (total: " + state.oresMined + ")");
            
            state.targetOre = null;
            state.currentBlockToBreak = null;
            
            // Clean up pillar after mining
            cleanupPillarBlocks(state);
            
            pickupNearbyItems(npc);
            return;
        }

        // Calculate path to ore
        Vector toOre = oreLoc.toVector().subtract(npcLoc.toVector());
        double heightDiff = toOre.getY();
        
        // Check if we need to climb
        if (heightDiff > CLIMB_HEIGHT_THRESHOLD && distance > 3) {
            if (!climbTowardsOre(npc, state, npcLoc, oreLoc)) {
                // Can't climb further, try to find blocking blocks
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
            // Move toward ore
            moveTowardsLocation(npc, npcLoc, oreLoc);
        }
    }

    /**
     * Climb using dirt blocks to reach ore above
     */
    private boolean climbTowardsOre(NPC npc, MiningState state, Location npcLoc, Location targetLoc) {
        // Check if we have dirt
        if (!hasDirtInInventory(npc)) {
            giveDirt(npc);
        }

        // Don't build pillar too high
        if (state.pillarBlocks.size() >= MAX_PILLAR_HEIGHT) {
            debugLog("Pillar max height reached");
            return false;
        }

        Block currentBlock = npcLoc.getBlock();
        Block aboveBlock = currentBlock.getRelative(BlockFace.UP);
        
        // Place dirt above if air
        if (aboveBlock.getType().isAir()) {
            aboveBlock.setType(Material.DIRT);
            state.pillarBlocks.add(aboveBlock);
            debugLog("Placed dirt pillar block at " + aboveBlock.getLocation());
            
            // Teleport NPC up onto the dirt
            Location newLoc = aboveBlock.getLocation().add(0.5, 0, 0.5);
            newLoc.setDirection(npcLoc.getDirection());
            npc.getEntity().teleport(newLoc);
            
            return true;
        }
        
        return false;
    }

    /**
     * Find and break block that's blocking path to ore
     */
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

    /**
     * Move NPC towards a location smoothly
     */
    private void moveTowardsLocation(NPC npc, Location from, Location to) {
        Vector direction = to.toVector().subtract(from.toVector()).normalize();
        Vector moveVector = direction.multiply(MOVE_SPEED);
        Location newLoc = from.clone().add(moveVector);
        
        // Check if ground exists below, place dirt if not
        Block groundBlock = newLoc.getBlock().getRelative(BlockFace.DOWN);
        if (groundBlock.getType().isAir()) {
            // Don't move into air without ground
            debugLog("No ground below, not moving");
            return;
        }
        
        // Teleport to new location if safe
        if (newLoc.getBlock().getType().isAir()) {
            newLoc.setDirection(from.getDirection());
            npc.getEntity().teleport(newLoc);
        }
    }

    /**
     * Find blocking block using raycast
     */
    private Block findBlockingBlock(Location from, Location to) {
        Vector direction = to.toVector().subtract(from.toVector()).normalize();
        double distance = from.distance(to);
        
        // Cast ray from NPC toward ore
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

    /**
     * Ore information with priority data
     */
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
            // Priority 1: Exposed ores
            if (this.isExposed != other.isExposed) {
                return this.isExposed ? -1 : 1;
            }
            
            // Priority 2: Higher value ores
            if (this.valuePriority != other.valuePriority) {
                return Integer.compare(this.valuePriority, other.valuePriority);
            }
            
            // Priority 3: Closer ores
            return Double.compare(this.distance, other.distance);
        }
    }

    /**
     * Find best ore using smart priority system
     * Priority: Exposed > Value > Distance
     */
    private OreInfo findBestOre(Location center) {
        List<OreInfo> ores = new ArrayList<>();

        // Scan for ores in reduced radius
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
        
        // Sort by priority and return best
        Collections.sort(ores);
        OreInfo best = ores.get(0);
        
        return best;
    }

    /**
     * Check if ore is exposed (can see it directly)
     */
    private boolean isOreExposed(Location from, Location oreLoc) {
        Vector direction = oreLoc.toVector().subtract(from.toVector());
        double distance = from.distance(oreLoc);
        
        if (distance > SEARCH_RADIUS) return false;
        
        // Use Bukkit's raytrace
        RayTraceResult result = from.getWorld().rayTraceBlocks(
            from, 
            direction.normalize(), 
            distance,
            org.bukkit.FluidCollisionMode.NEVER,
            true
        );
        
        // If raytrace hits the ore block directly, it's exposed
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
        for (int i = 0; i < contents.length && i < 3; i++) {
            if (contents[i] == null) {
                contents[i] = new ItemStack(Material.DIRT, 64);
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
        
        // Find empty slot and add dirt
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

        for (Entity e : loc.getNearbyEntities(PICKUP_RADIUS, PICKUP_RADIUS, PICKUP_RADIUS)) {
            if (e.getType() == EntityType.ITEM) {
                org.bukkit.entity.Item itemEntity = (org.bukkit.entity.Item) e;
                ItemStack drop = itemEntity.getItemStack();

                // Skip dirt pickup - we manage it separately
                if (drop.getType() == Material.DIRT) {
                    continue;
                }

                // Add to inventory
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
        
        // Get equipped items to exclude
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
        for (Entity entity : center.getNearbyEntities(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS)) {
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

        // Find solid ground
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
        
        // Clean up mining state
        MiningState state = miningStates.remove(player.getUniqueId());
        if (state != null) {
            cleanupPillarBlocks(state);
        }
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
        // Clean up all pillar blocks
        for (MiningState state : miningStates.values()) {
            cleanupPillarBlocks(state);
        }
        miningStates.clear();
        
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
