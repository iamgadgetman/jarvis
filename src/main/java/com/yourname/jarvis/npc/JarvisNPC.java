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
import org.bukkit.util.Vector;

import java.util.*;

public class JarvisNPC {

    private final Jarvis plugin;
    private final Map<UUID, NPC> playerNPCs = new HashMap<>();
    private final Map<UUID, BukkitRunnable> activeTasks = new HashMap<>();
    private final Map<UUID, MiningState> miningStates = new HashMap<>();

    private static final int SEARCH_RADIUS = 32;
    private static final int PICKUP_RADIUS = 8;
    private static final double REACH_DISTANCE = 4.5;
    private static final double MOVE_SPEED = 0.5; // Increased for smoother, less frequent updates

    // Ore priority list - REVERSED: Lowest priority first (coal), highest last (ancient debris)
    // Lower index = mine last, Higher index = mine first
    private static final List<Material> ORE_PRIORITY = Arrays.asList(
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE, Material.NETHER_GOLD_ORE,
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.ANCIENT_DEBRIS,
            Material.NETHER_QUARTZ_ORE
    );

    private static class MiningState {
        Block targetOre;
        Block currentBlockToBreak;
        int ticksStuck = 0;
        Location lastLocation;
        boolean isClimbing = false;
    }

    public JarvisNPC(Jarvis plugin) {
        this.plugin = plugin;
    }

    public void summon(Player player) {
        NPC existing = playerNPCs.get(player.getUniqueId());
        if (existing != null && existing.isSpawned()) {
            player.sendMessage("Jarvis: I'm already here!");
            return;
        }

        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, "Jarvis");

        Location playerLoc = player.getLocation();
        Location spawnLoc = playerLoc.clone().add(playerLoc.getDirection().setY(0).normalize().multiply(3));

        // Find solid ground
        while (spawnLoc.getY() > playerLoc.getWorld().getMinHeight() && spawnLoc.getBlock().getType().isAir()) {
            spawnLoc.subtract(0, 1, 0);
        }
        if (!spawnLoc.getBlock().getType().isAir()) {
            spawnLoc.add(0, 1, 0);
        }

        spawnLoc.setDirection(playerLoc.toVector().subtract(spawnLoc.toVector()));

        npc.spawn(spawnLoc);
        npc.getOrAddTrait(Inventory.class);
        npc.setProtected(true);
        playerNPCs.put(player.getUniqueId(), npc);

        player.getWorld().playSound(spawnLoc, Sound.BLOCK_BELL_USE, 1.0f, 1.0f);
        player.sendMessage("Jarvis: At your service—let's make some magic.");
        
        // Greeting animation - crouch a couple times to say hello (faster)
        new BukkitRunnable() {
            int crouchCount = 0;
            boolean isCrouching = false;
            
            @Override
            public void run() {
                if (!npc.isSpawned() || npc.getEntity() == null) {
                    cancel();
                    return;
                }
                
                if (crouchCount >= 4) { // 2 full crouch cycles (down-up-down-up)
                    cancel();
                    return;
                }
                
                // Toggle crouch/stand
                if (npc.getEntity() instanceof Player) {
                    Player npcPlayer = (Player) npc.getEntity();
                    npcPlayer.setSneaking(!isCrouching);
                    isCrouching = !isCrouching;
                    crouchCount++;
                }
            }
        }.runTaskTimer(plugin, 10L, 5L); // Start after 0.5 seconds, toggle every 0.25 seconds (faster)
    }

    public void dismiss(Player player) {
        NPC npc = playerNPCs.remove(player.getUniqueId());
        if (npc != null) {
            // Drop only inventory items, NOT equipment (tools/weapons)
            if (npc.isSpawned()) {
                Location dropLoc = npc.getEntity() != null ? npc.getEntity().getLocation() : npc.getStoredLocation();
                Inventory invTrait = npc.getOrAddTrait(Inventory.class);
                ItemStack[] contents = invTrait.getContents();
                
                // Get equipped items to exclude them
                Equipment equipTrait = npc.getOrAddTrait(Equipment.class);
                ItemStack handItem = equipTrait.get(Equipment.EquipmentSlot.HAND);
                ItemStack offHandItem = equipTrait.get(Equipment.EquipmentSlot.OFF_HAND);
                ItemStack helmet = equipTrait.get(Equipment.EquipmentSlot.HELMET);
                ItemStack chest = equipTrait.get(Equipment.EquipmentSlot.CHESTPLATE);
                ItemStack legs = equipTrait.get(Equipment.EquipmentSlot.LEGGINGS);
                ItemStack boots = equipTrait.get(Equipment.EquipmentSlot.BOOTS);
                
                int itemCount = 0;
                for (ItemStack item : contents) {
                    if (item != null && item.getType() != Material.AIR) {
                        // Don't drop if it's equipped
                        if ((handItem != null && item.isSimilar(handItem)) ||
                            (offHandItem != null && item.isSimilar(offHandItem)) ||
                            (helmet != null && item.isSimilar(helmet)) ||
                            (chest != null && item.isSimilar(chest)) ||
                            (legs != null && item.isSimilar(legs)) ||
                            (boots != null && item.isSimilar(boots))) {
                            continue; // Skip equipped items
                        }
                        
                        dropLoc.getWorld().dropItemNaturally(dropLoc, item);
                        itemCount++;
                    }
                }
                
                if (itemCount > 0) {
                    player.sendMessage("Jarvis: I've dropped " + itemCount + " item stacks for you!");
                }
            }
            
            miningStates.remove(player.getUniqueId());
            npc.destroy();
            stopTask(player);
            player.sendMessage("Jarvis: Until next time—poof!");
        }
    }

    public void returnToPlayer(Player player) {
        NPC npc = getNPC(player);
        if (npc == null) return;

        // Stop any active tasks (mining, attacking, etc.)
        stopTask(player);

        Location playerLoc = player.getLocation();
        Location target = playerLoc.clone().add(playerLoc.getDirection().setY(0).normalize().multiply(-3));

        // Find solid ground
        while (target.getY() > playerLoc.getWorld().getMinHeight() && target.getBlock().getType().isAir()) {
            target.subtract(0, 1, 0);
        }
        target.add(0, 1, 0);

        npc.teleport(target, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
        player.sendMessage("Jarvis: Right behind you!");
    }

    public void attack(Player player) {
        NPC npc = getNPC(player);
        if (npc == null) return;
        stopTask(player);

        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = sword.getItemMeta();
        meta.addEnchant(Enchantment.SHARPNESS, 5, true);
        sword.setItemMeta(meta);
        npc.getOrAddTrait(Equipment.class).set(Equipment.EquipmentSlot.HAND, sword);

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
        task.runTaskTimer(plugin, 0L, 10L);
        activeTasks.put(player.getUniqueId(), task);
        player.sendMessage("Jarvis: Switching to combat mode!");
    }

    private Monster findNearestHostileMob(Location center) {
        Monster closest = null;
        double closestDist = Double.MAX_VALUE;
        for (Entity entity : center.getNearbyEntities(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS)) {
            if (entity instanceof Monster) {
                Monster mob = (Monster) entity;
                if (!mob.isDead()) {
                    double dist = center.distance(mob.getLocation());
                    if (dist < closestDist) {
                        closest = mob;
                        closestDist = dist;
                    }
                }
            }
        }
        return closest;
    }

    public void mine(Player player) {
        NPC npc = getNPC(player);
        if (npc == null) return;
        stopTask(player);

        MiningState state = new MiningState();
        miningStates.put(player.getUniqueId(), state);

        player.sendMessage("Jarvis: Switching to mining mode!");

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

                // Pickup items continuously
                pickupNearbyItems(npc);

                // Find new target ore if needed
                if (state.targetOre == null || !isOre(state.targetOre.getType())) {
                    Block ore = findBestOre(npcLoc);
                    if (ore == null) {
                        noOreCounter++;
                        if (noOreCounter > 3) {
                            player.sendMessage("Jarvis: No ores found in range. Moving on.");
                            cancel();
                            miningStates.remove(player.getUniqueId());
                        }
                        return;
                    }
                    noOreCounter = 0;
                    state.targetOre = ore;
                    
                    // Use silk touch ONLY for deepslate emerald ore
                    boolean needsSilk = ore.getType() == Material.DEEPSLATE_EMERALD_ORE;
                    equipPickaxe(npc, needsSilk);
                }

                // Process mining with pathfinding
                processMiningWithPathfinding(npc, player, state);
            }
        };
        task.runTaskTimer(plugin, 0L, 10L);
        activeTasks.put(player.getUniqueId(), task);
    }

    private void processMiningWithPathfinding(NPC npc, Player player, MiningState state) {
        Location npcLoc = getCurrentLocation(npc);
        Location oreLoc = state.targetOre.getLocation().add(0.5, 0.5, 0.5);
        double distance = npcLoc.distance(oreLoc);

        // If within reach, mine the ore
        if (distance <= REACH_DISTANCE) {
            ItemStack tool = npc.getOrAddTrait(Equipment.class).get(Equipment.EquipmentSlot.HAND);
            
            // Cancel navigation
            npc.getNavigator().cancelNavigation();
            
            // Face the ore
            Vector direction = oreLoc.toVector().subtract(npcLoc.toVector()).normalize();
            Location lookAt = npcLoc.clone();
            lookAt.setDirection(direction);
            if (npc.getEntity() != null) {
                npc.getEntity().teleport(lookAt);
            }
            
            // Break the ore
            state.targetOre.breakNaturally(tool);
            state.targetOre = null;
            
            pickupNearbyItems(npc);
            return;
        }

        // Check for blocking blocks and create tunnel
        // Determine if we're digging vertically or horizontally
        double verticalDistance = Math.abs(oreLoc.getY() - npcLoc.getY());
        double horizontalDistance = Math.sqrt(
            Math.pow(oreLoc.getX() - npcLoc.getX(), 2) + 
            Math.pow(oreLoc.getZ() - npcLoc.getZ(), 2)
        );
        
        boolean diggingDown = (oreLoc.getY() < npcLoc.getY()) && (verticalDistance > horizontalDistance);
        
        Block blockingEyeLevel = findBlockingBlock(npcLoc, oreLoc, 0); // Eye level
        Block blockingFeetLevel = null;
        
        // Only dig 2-block tall tunnel if NOT digging straight down
        if (!diggingDown) {
            blockingFeetLevel = findBlockingBlock(npcLoc, oreLoc, -1); // Feet level
        }
        
        ItemStack tool = npc.getOrAddTrait(Equipment.class).get(Equipment.EquipmentSlot.HAND);
        boolean brokeBlock = false;
        
        // Break feet level first (if horizontal tunnel)
        if (blockingFeetLevel != null && !blockingFeetLevel.getType().isAir() && 
            blockingFeetLevel.getType().isSolid() && !isOre(blockingFeetLevel.getType())) {
            
            // Face the block
            Location blockLoc = blockingFeetLevel.getLocation().add(0.5, 0.5, 0.5);
            Vector direction = blockLoc.toVector().subtract(npcLoc.toVector()).normalize();
            Location lookAt = npcLoc.clone();
            lookAt.setDirection(direction);
            if (npc.getEntity() != null) {
                npc.getEntity().teleport(lookAt);
            }
            
            blockingFeetLevel.breakNaturally(tool);
            brokeBlock = true;
        }
        
        // Break eye level
        if (blockingEyeLevel != null && !blockingEyeLevel.getType().isAir() && 
            blockingEyeLevel.getType().isSolid() && !isOre(blockingEyeLevel.getType())) {
            
            // Face the block
            Location blockLoc = blockingEyeLevel.getLocation().add(0.5, 0.5, 0.5);
            Vector direction = blockLoc.toVector().subtract(npcLoc.toVector()).normalize();
            Location lookAt = npcLoc.clone();
            lookAt.setDirection(direction);
            if (npc.getEntity() != null) {
                npc.getEntity().teleport(lookAt);
            }
            
            blockingEyeLevel.breakNaturally(tool);
            brokeBlock = true;
        }
        
        if (brokeBlock) {
            pickupNearbyItems(npc);
            // Wait a moment after breaking blocks before trying to navigate
            return;
        }
        
        // Manual smooth movement toward ore
        if (npc.getEntity() != null) {
            Vector toOre = oreLoc.toVector().subtract(npcLoc.toVector());
            double remainingDistance = toOre.length();
            
            if (remainingDistance > 0.3) {
                // Move in small steps for smooth walking
                Vector moveDirection = toOre.normalize().multiply(0.3);
                Location targetLoc = npcLoc.clone().add(moveDirection);
                
                // Check if target location is safe
                Block targetBlock = targetLoc.getBlock();
                Block belowBlock = targetLoc.clone().subtract(0, 1, 0).getBlock();
                
                if ((targetBlock.getType().isAir() || !targetBlock.getType().isSolid()) &&
                    (belowBlock.getType().isSolid() || belowBlock.getType() == Material.AIR)) {
                    
                    // Face the direction we're moving
                    targetLoc.setDirection(moveDirection);
                    npc.getEntity().teleport(targetLoc);
                }
            }
        }
    }

    private Block findBlockingBlock(Location from, Location to, int yOffset) {
        // Adjust for eye level (0) or feet level (-1)
        Location adjustedFrom = from.clone().add(0, yOffset, 0);
        
        Vector direction = to.toVector().subtract(from.toVector()).normalize();
        double distance = from.distance(to);
        
        // Cast ray from NPC toward ore at specified height
        for (double d = 0.5; d < Math.min(distance, 3.0); d += 0.5) {
            Location check = adjustedFrom.clone().add(direction.clone().multiply(d));
            Block block = check.getBlock();
            
            // Found a blocking block
            if (!block.getType().isAir() && block.getType().isSolid() && !isOre(block.getType())) {
                return block;
            }
        }
        return null;
    }

    private Block findBestOre(Location center) {
        // PRIORITY SYSTEM:
        // 1. ANY ore within 4 blocks at similar Y level (±3 blocks vertically)
        // 2. Valuable ores within 4 blocks even if below
        // 3. Beyond 4 blocks, prefer ores at similar Y level
        // 4. Avoid mining straight down more than 10 blocks
        
        Block nearestSameLevel = null;
        double nearestSameLevelDist = Double.MAX_VALUE;
        
        Block nearestInRadius = null;
        double nearestInRadiusDist = Double.MAX_VALUE;
        
        Block bestBeyondRadius = null;
        int bestPriority = -1;
        double bestPriorityDist = Double.MAX_VALUE;

        for (int x = -SEARCH_RADIUS; x <= SEARCH_RADIUS; x++) {
            for (int y = -SEARCH_RADIUS; y <= SEARCH_RADIUS; y++) {
                for (int z = -SEARCH_RADIUS; z <= SEARCH_RADIUS; z++) {
                    Block b = center.clone().add(x, y, z).getBlock();
                    Material m = b.getType();
                    
                    if (!isOre(m)) continue;
                    
                    double dist = center.distance(b.getLocation());
                    double verticalDist = Math.abs(b.getY() - center.getY());
                    
                    // Skip ores too far below (avoid mining to bedrock)
                    if (b.getY() < center.getY() - 10) {
                        continue;
                    }
                    
                    // PRIORITY 1: Within 4 blocks AND at similar Y level (±3 blocks)
                    if (dist <= 4.0 && verticalDist <= 3.0) {
                        if (dist < nearestSameLevelDist) {
                            nearestSameLevel = b;
                            nearestSameLevelDist = dist;
                        }
                    }
                    // PRIORITY 2: Within 4 blocks (even if below)
                    else if (dist <= 4.0) {
                        if (dist < nearestInRadiusDist) {
                            nearestInRadius = b;
                            nearestInRadiusDist = dist;
                        }
                    }
                    // PRIORITY 3: Beyond 4 blocks - prefer same level + valuable
                    else {
                        int priority = ORE_PRIORITY.indexOf(m);
                        
                        // Bonus for being at similar Y level
                        if (verticalDist <= 5.0) {
                            priority += 5; // Boost priority for horizontal ores
                        }
                        
                        if (priority > bestPriority || (priority == bestPriority && dist < bestPriorityDist)) {
                            bestBeyondRadius = b;
                            bestPriority = priority;
                            bestPriorityDist = dist;
                        }
                    }
                }
            }
        }
        
        // Return in order of preference
        if (nearestSameLevel != null) return nearestSameLevel;
        if (nearestInRadius != null) return nearestInRadius;
        return bestBeyondRadius;
    }

    private boolean isOre(Material mat) {
        return ORE_PRIORITY.contains(mat);
    }

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

    private void pickupNearbyItems(NPC npc) {
        Location loc = getCurrentLocation(npc);
        Inventory invTrait = npc.getOrAddTrait(Inventory.class);
        ItemStack[] contents = invTrait.getContents();

        for (Entity e : loc.getNearbyEntities(PICKUP_RADIUS, PICKUP_RADIUS, PICKUP_RADIUS)) {
            if (e.getType() == EntityType.ITEM) {
                org.bukkit.entity.Item itemEntity = (org.bukkit.entity.Item) e;
                ItemStack drop = itemEntity.getItemStack();

                for (int i = 0; i < contents.length; i++) {
                    if (contents[i] == null) {
                        contents[i] = drop.clone();
                        invTrait.setContents(contents);
                        e.remove();
                        break;
                    } else if (contents[i].isSimilar(drop) && contents[i].getAmount() < contents[i].getMaxStackSize()) {
                        int add = Math.min(drop.getAmount(), contents[i].getMaxStackSize() - contents[i].getAmount());
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

    private Location getCurrentLocation(NPC npc) {
        if (npc.getEntity() != null) {
            return npc.getEntity().getLocation();
        }
        return npc.getStoredLocation();
    }

    public void openInventory(Player player) {
        NPC npc = getNPC(player);
        if (npc == null) {
            player.sendMessage("Jarvis: I'm not summoned yet!");
            return;
        }
        Inventory invTrait = npc.getOrAddTrait(Inventory.class);
        invTrait.openInventory(player);
    }

    private NPC getNPC(Player player) {
        NPC npc = playerNPCs.get(player.getUniqueId());
        if (npc == null || !npc.isSpawned()) {
            player.sendMessage("Jarvis: I'm not summoned yet!");
            return null;
        }
        return npc;
    }

    private void stopTask(Player player) {
        BukkitRunnable task = activeTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
        miningStates.remove(player.getUniqueId());
    }

    public void dismissAll() {
        miningStates.clear();
        playerNPCs.values().forEach(NPC::destroy);
        playerNPCs.clear();
        activeTasks.values().forEach(BukkitRunnable::cancel);
        activeTasks.clear();
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
