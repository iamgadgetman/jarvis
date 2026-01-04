package com.yourname.jarvis.npc;

import com.yourname.jarvis.Jarvis;
import com.yourname.jarvis.util.DebugLogger;
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
import org.bukkit.util.BlockIterator;

import java.util.*;

public class JarvisNPC {

    private final Jarvis plugin;
    private final Map<UUID, NPC> playerNPCs = new HashMap<>();
    private final Map<UUID, BukkitRunnable> activeTasks = new HashMap<>();
    private final DebugLogger debug;

    private static final int SEARCH_RADIUS = 32;
    private static final int PICKUP_RADIUS = 8;

    private static final List<Material> ORE_PRIORITY = Arrays.asList(
            Material.ANCIENT_DEBRIS,
            Material.DEEPSLATE_EMERALD_ORE, Material.EMERALD_ORE,
            Material.DEEPSLATE_DIAMOND_ORE, Material.DIAMOND_ORE,
            Material.DEEPSLATE_GOLD_ORE, Material.GOLD_ORE,
            Material.DEEPSLATE_REDSTONE_ORE, Material.REDSTONE_ORE,
            Material.DEEPSLATE_LAPIS_ORE, Material.LAPIS_ORE,
            Material.DEEPSLATE_IRON_ORE, Material.IRON_ORE,
            Material.DEEPSLATE_COPPER_ORE, Material.COPPER_ORE,
            Material.DEEPSLATE_COAL_ORE, Material.COAL_ORE
    );

    public JarvisNPC(Jarvis plugin) {
        this.plugin = plugin;
        this.debug = plugin.getDebugLogger();
    }

    public void summon(Player player) {
        NPC existing = playerNPCs.get(player.getUniqueId());
        if (existing != null && existing.isSpawned()) {
            player.sendMessage("Jarvis: I'm already here!");
            return;
        }

        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, "Jarvis");
        npc.data().set(NPC.Metadata.PLAYER_LIST, false); // avoid join/quit spam in console
        debug.debug("Summoning NPC for " + player.getName());

        Location playerLoc = player.getLocation();
        Location spawnLoc = playerLoc.clone().add(playerLoc.getDirection().setY(0).normalize().multiply(3));

        // Ray trace down from spawn point to find solid ground (works underground)
        while (spawnLoc.getY() > playerLoc.getWorld().getMinHeight() && spawnLoc.getBlock().getType().isAir()) {
            spawnLoc.subtract(0, 1, 0);
        }
        if (spawnLoc.getBlock().getType().isAir()) {
            spawnLoc.add(0, 1, 0); // if all air, stand at player level
        } else {
            spawnLoc.add(0, 1, 0); // stand on solid block
        }

        spawnLoc.setDirection(playerLoc.toVector().subtract(spawnLoc.toVector()));

        npc.spawn(spawnLoc);
        npc.getOrAddTrait(Inventory.class);
        playerNPCs.put(player.getUniqueId(), npc);
        debug.debug("NPC spawned at " + spawnLoc);

        player.getWorld().playSound(spawnLoc, Sound.BLOCK_BELL_USE, 1.0f, 1.0f);
        player.sendMessage("Jarvis: At your service—let's make some magic.");
    }

    public void dismiss(Player player) {
        NPC npc = playerNPCs.remove(player.getUniqueId());
        if (npc != null) {
            npc.destroy();
            stopTask(player);
            debug.debug("NPC dismissed for " + player.getName());
            player.sendMessage("Jarvis: Until next time—poof!");
        }
    }

    public void returnToPlayer(Player player) {
        NPC npc = getNPC(player);
        if (npc == null) return;

        Location playerLoc = player.getLocation();
        Location target = playerLoc.clone().add(playerLoc.getDirection().setY(0).normalize().multiply(-3));

        // Ray trace down for solid ground
        while (target.getY() > playerLoc.getWorld().getMinHeight() && target.getBlock().getType().isAir()) {
            target.subtract(0, 1, 0);
        }
        target.add(0, 1, 0);

        npc.teleport(target, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
        player.sendMessage("Jarvis: Right behind you!");
        debug.debug("NPC teleported behind " + player.getName() + " to " + target);
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
                    debug.debug("NPC despawned during attack for " + player.getName());
                    cancel();
                    return;
                }

                Location npcLoc = getCurrentLocation(npc);

                Monster mob = findNearestHostileMob(npcLoc);
                if (mob != null && !mob.isDead()) {
                    npc.getNavigator().setTarget(mob, true);
                }

                pickupNearbyItems(npc);
            }
        };
        task.runTaskTimer(plugin, 0L, 10L);
        activeTasks.put(player.getUniqueId(), task);
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

    public void mine(Player player) {
        NPC npc = getNPC(player);
        if (npc == null) return;
        stopTask(player);
        debug.debug("Mine command started for " + player.getName());

        BukkitRunnable task = new BukkitRunnable() {
            private Location lastLoc;
            private int stuckTicks = 0;

            @Override
            public void run() {
                if (!npc.isSpawned()) {
                    debug.debug("NPC despawned during mining for " + player.getName());
                    cancel();
                    return;
                }

                Location npcLoc = getCurrentLocation(npc);
                debug.debug("Current NPC location: " + npcLoc.toVector());

                Block ore = findBestOre(npcLoc);
                if (ore == null) {
                    player.sendMessage("Jarvis: No ores nearby.");
                    debug.debug("No ores found around " + npcLoc);
                    cancel();
                    return;
                }

                debug.debug("Target ore " + ore.getType() + " at " + ore.getLocation());

                boolean silk = ore.getType() == Material.DEEPSLATE_EMERALD_ORE || ore.getType() == Material.EMERALD_ORE;
                equipPickaxe(npc, silk);

                Location standSpot = findApproachLocation(ore, npcLoc);
                if (standSpot == null) {
                    // Carve a space above the ore so the NPC can stand and dig
                    Block above = ore.getRelative(BlockFace.UP);
                    if (!above.getType().isAir()) {
                        above.breakNaturally();
                    }
                    standSpot = ore.getLocation().add(0.5, 1, 0.5);
                }

                ensureClearance(standSpot);
                var navigator = npc.getNavigator();
                navigator.getLocalParameters().range(SEARCH_RADIUS);
                navigator.setTarget(standSpot);
                debug.debug("Navigating to stand spot " + standSpot);

                if (!navigator.isNavigating()) {
                    navigator.setTarget(standSpot);
                    debug.debug("Navigator was idle, reissued move command.");
                }

                if (!hasLineOfSight(npc, ore)) {
                    carveLineOfSight(npc, ore);
                    debug.debug("Carving line of sight to ore...");
                }

                if (lastLoc != null && npcLoc.distanceSquared(lastLoc) < 0.06) {
                    stuckTicks++;
                    if (stuckTicks == 3) {
                        debug.debug("Navigator appears stuck. Clearing path toward stand spot...");
                        clearObstaclesToward(npcLoc, standSpot);
                        navigator.setTarget(standSpot);
                    } else if (stuckTicks >= 6) {
                        debug.debug("Still stuck. Teleporting near stand spot to resume mining.");
                        npc.teleport(standSpot, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                        stuckTicks = 0;
                    }
                } else {
                    stuckTicks = 0;
                }
                lastLoc = npcLoc.clone();

                if (npcLoc.distanceSquared(standSpot) <= 4) {
                    ItemStack tool = npc.getOrAddTrait(Equipment.class).get(Equipment.EquipmentSlot.HAND);
                    ore.breakNaturally(tool);
                    debug.debug("Breaking ore at " + ore.getLocation());
                    pickupNearbyItems(npc);
                }
            }
        };
        task.runTaskTimer(plugin, 0L, 20L);
        activeTasks.put(player.getUniqueId(), task);
    }

    private Block findBestOre(Location center) {
        Block best = null;
        int bestIndex = -1;

        for (int x = -SEARCH_RADIUS; x <= SEARCH_RADIUS; x++) {
            for (int y = -SEARCH_RADIUS; y <= SEARCH_RADIUS; y++) {
                for (int z = -SEARCH_RADIUS; z <= SEARCH_RADIUS; z++) {
                    Block b = center.clone().add(x, y, z).getBlock();
                    Material m = b.getType();
                    int index = ORE_PRIORITY.indexOf(m);
                    if (index > bestIndex) {
                        best = b;
                        bestIndex = index;
                    }
                }
            }
        }
        return best;
    }

    private void equipPickaxe(NPC npc, boolean silk) {
        ItemStack pick = new ItemStack(Material.NETHERITE_PICKAXE);
        ItemMeta meta = pick.getItemMeta();
        meta.addEnchant(Enchantment.EFFICIENCY, 5, true);
        if (silk) meta.addEnchant(Enchantment.SILK_TOUCH, 1, true);
        else meta.addEnchant(Enchantment.FORTUNE, 3, true);
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

                // Find empty or matching slot
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

    private Location findApproachLocation(Block ore, Location npcLoc) {
        // Try to find an adjacent air block with solid ground and headroom to stand on, favoring closest to NPC
        Location best = null;
        double bestDist = Double.MAX_VALUE;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                Block candidate = ore.getRelative(dx, 0, dz);
                if (isStandable(candidate)) {
                    Location loc = candidate.getLocation().add(0.5, 0, 0.5);
                    double dist = loc.distanceSquared(npcLoc);
                    if (dist < bestDist) {
                        best = loc;
                        bestDist = dist;
                    }
                }
            }
        }
        return best;
    }

    private boolean isStandable(Block block) {
        Block ground = block.getRelative(BlockFace.DOWN);
        Block head = block.getRelative(BlockFace.UP);
        return block.getType().isAir() && head.getType().isAir() && ground.getType().isSolid();
    }

    private void ensureClearance(Location standSpot) {
        Block feet = standSpot.getBlock();
        Block head = feet.getRelative(BlockFace.UP);
        if (!feet.getType().isAir()) {
            feet.breakNaturally();
        }
        if (!head.getType().isAir()) {
            head.breakNaturally();
        }
    }

    private void clearObstaclesToward(Location from, Location to) {
        BlockIterator iter = new BlockIterator(from.getWorld(), from.toVector(), to.toVector().subtract(from.toVector()), 0, (int) Math.ceil(from.distance(to)));
        while (iter.hasNext()) {
            Block b = iter.next();
            if (b.getLocation().distanceSquared(to) < 1) break;
            if (!b.getType().isAir() && b.getType().isSolid()) {
                b.breakNaturally();
                debug.debug("Cleared obstructing block " + b.getType() + " at " + b.getLocation());
                break;
            }
        }
    }

    private boolean hasLineOfSight(NPC npc, Block ore) {
        if (npc.getEntity() instanceof org.bukkit.entity.LivingEntity living) {
            return living.hasLineOfSight(ore);
        }
        return true;
    }

    private void carveLineOfSight(NPC npc, Block ore) {
        if (!(npc.getEntity() instanceof org.bukkit.entity.LivingEntity living)) return;
        Location eye = living.getEyeLocation();
        Location target = ore.getLocation().add(0.5, 0.5, 0.5);
        BlockIterator iter = new BlockIterator(eye.getWorld(), eye.toVector(), target.toVector().subtract(eye.toVector()), 0, (int) Math.ceil(eye.distance(target)));
        while (iter.hasNext()) {
            Block b = iter.next();
            if (b.equals(ore)) break;
            if (!b.getType().isAir()) {
                b.breakNaturally();
                break;
            }
        }
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
        if (task != null) task.cancel();
    }

    public void dismissAll() {
        playerNPCs.values().forEach(n -> n.destroy());
        playerNPCs.clear();
        activeTasks.values().forEach(BukkitRunnable::cancel);
        activeTasks.clear();
    }

    public NPC getNPCForPlayer(UUID uuid) {
        return playerNPCs.get(uuid);
    }
}
