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
    private static final double MOVE_SPEED = 0.3;

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
    }

    public void dismiss(Player player) {
        NPC npc = playerNPCs.remove(player.getUniqueId());
        if (npc != null) {
            miningStates.remove(player.getUniqueId());
            npc.destroy();
            stopTask(player);
            player.sendMessage("Jarvis: Until next time—poof!");
        }
    }

    public void returnToPlayer(Player player) {
        NPC npc = getNPC(player);
        if (npc == null) return;

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

                // Check if stuck (not moving)
                if (state.lastLocation != null && state.lastLocation.distance(npcLoc) < 0.1) {
                    state.ticksStuck++;
                    if (state.ticksStuck > 10) {
                        // Reset if stuck for too long
                        state.currentBlockToBreak = null;
                        state.ticksStuck = 0;
                    }
                } else {
                    state.ticksStuck = 0;
                }
                state.lastLocation = npcLoc.clone();

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
                    state.currentBlockToBreak = null;
                    
                    // Use silk touch ONLY for deepslate emerald ore
                    boolean needsSilk = ore.getType() == Material.DEEPSLATE_EMERALD_ORE;
                    equipPickaxe(npc, needsSilk);
                }

                // Process mining
                processMining(npc, player, state);
            }
        };
        task.runTaskTimer(plugin, 0L, 5L);
        activeTasks.put(player.getUniqueId(), task);
    }

    private void processMining(NPC npc, Player player, MiningState state) {
        Location npcLoc = getCurrentLocation(npc);
        Location oreLoc = state.targetOre.getLocation().add(0.5, 0.5, 0.5);
        double distance = npcLoc.distance(oreLoc);

        // If within reach, mine the ore
        if (distance <= REACH_DISTANCE) {
            ItemStack tool = npc.getOrAddTrait(Equipment.class).get(Equipment.EquipmentSlot.HAND);
            
            // Face the ore
            Vector direction = oreLoc.toVector().subtract(npcLoc.toVector()).normalize();
            Location lookAt = npcLoc.clone();
            lookAt.setDirection(direction);
            npc.getEntity().teleport(lookAt);
            
            // Break the ore
            state.targetOre.breakNaturally(tool);
            state.targetOre = null;
            state.currentBlockToBreak = null;
            state.isClimbing = false;
            
            pickupNearbyItems(npc);
            return;
        }

        // Use Citizens' pathfinding for navigation
        npc.getNavigator().setTarget(oreLoc, false);
        
        // Find and break blocking blocks
        Block blockingBlock = findBlockingBlock(npcLoc, oreLoc);
        if (blockingBlock != null && !blockingBlock.getType().isAir() && 
            !blockingBlock.equals(npcLoc.getBlock())) {
            
            if (state.currentBlockToBreak == null || !state.currentBlockToBreak.equals(blockingBlock)) {
                state.currentBlockToBreak = blockingBlock;
            }

            // Face and break the blocking block
            Location blockLoc = blockingBlock.getLocation().add(0.5, 0.5, 0.5);
            Vector direction = blockLoc.toVector().subtract(npcLoc.toVector()).normalize();
            Location lookAt = npcLoc.clone();
            lookAt.setDirection(direction);
            npc.getEntity().teleport(lookAt);

            ItemStack tool = npc.getOrAddTrait(Equipment.class).get(Equipment.EquipmentSlot.HAND);
            blockingBlock.breakNaturally(tool);
            pickupNearbyItems(npc);
        }
    }

    private Block findBlockingBlock(Location from, Location to) {
        Vector direction = to.toVector().subtract(from.toVector()).normalize();
        double distance = from.distance(to);
        
        // Cast ray from NPC toward ore
        for (double d = 0.5; d < Math.min(distance, 5.0); d += 0.5) {
            Location check = from.clone().add(direction.clone().multiply(d));
            Block block = check.getBlock();
            
            if (!block.getType().isAir() && block.getType().isSolid() && !isOre(block.getType())) {
                return block;
            }
        }
        return null;
    }

    private Block findBestOre(Location center) {
        Block best = null;
        int bestPriority = -1;
        double closestDist = Double.MAX_VALUE;

        for (int x = -SEARCH_RADIUS; x <= SEARCH_RADIUS; x++) {
            for (int y = -SEARCH_RADIUS; y <= SEARCH_RADIUS; y++) {
                for (int z = -SEARCH_RADIUS; z <= SEARCH_RADIUS; z++) {
                    Block b = center.clone().add(x, y, z).getBlock();
                    Material m = b.getType();
                    int priority = ORE_PRIORITY.indexOf(m);
                    
                    if (priority != -1) {
                        double dist = center.distance(b.getLocation());
                        
                        // Prefer HIGHER priority ores (higher index = more valuable)
                        // Changed from > to < for correct priority ordering
                        if (priority > bestPriority || (priority == bestPriority && dist < closestDist)) {
                            best = b;
                            bestPriority = priority;
                            closestDist = dist;
                        }
                    }
                }
            }
        }
        return best;
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
