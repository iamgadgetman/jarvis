package com.gadgetman.jarvis.npc.provider;

import com.gadgetman.jarvis.Jarvis;
import net.citizensnpcs.api.ai.tree.BehaviorStatus;
import net.citizensnpcs.api.npc.BlockBreaker;
import org.bukkit.scheduler.BukkitRunnable;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.ai.NavigatorParameters;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.api.trait.trait.Inventory;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NPC Provider implementation using Citizens plugin.
 * This extracts the Citizens-specific code from JarvisNPC.
 */
public class CitizensNPCProvider implements INPCProvider {

    /** Reach used when configuring the block breaker, matching JarvisNPC's. */
    private static final double REACH_DISTANCE = 4.5;

    /** In-flight block breaks, keyed by NPC, so a new dig supersedes the old one. */
    private final Map<UUID, BukkitRunnable> activeBreakers = new ConcurrentHashMap<>();

    private final Jarvis plugin;
    private final Map<UUID, NPC> playerNPCs = new ConcurrentHashMap<>();

    public CitizensNPCProvider(Jarvis plugin) {
        this.plugin = plugin;
    }

    // ==================== LIFECYCLE ====================

    @Override
    public void spawn(Player owner, Location location, String name) {
        NPC existing = playerNPCs.get(owner.getUniqueId());
        if (existing != null && existing.isSpawned()) {
            return; // Already spawned
        }

        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, name);
        Location spawnLoc = findSafeSpawnLocation(location);

        npc.spawn(spawnLoc);
        npc.getOrAddTrait(Inventory.class);
        npc.setProtected(true);
        playerNPCs.put(owner.getUniqueId(), npc);

        // Give starting equipment
        giveStartingEquipment(npc);

        owner.getWorld().playSound(spawnLoc, Sound.BLOCK_BELL_USE, 1.0f, 1.0f);
    }

    @Override
    public void despawn(Player owner) {
        NPC npc = playerNPCs.remove(owner.getUniqueId());
        if (npc == null) return;

        if (npc.isSpawned()) {
            dropInventoryItems(npc);
        }
        npc.destroy();
    }

    @Override
    public boolean isSpawned(Player owner) {
        NPC npc = playerNPCs.get(owner.getUniqueId());
        return npc != null && npc.isSpawned();
    }

    @Override
    public boolean exists(Player owner) {
        return playerNPCs.containsKey(owner.getUniqueId());
    }

    // ==================== MOVEMENT & NAVIGATION ====================

    @Override
    public void teleport(Player owner, Location location) {
        NPC npc = playerNPCs.get(owner.getUniqueId());
        if (npc != null && npc.isSpawned()) {
            npc.teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
    }

    @Override
    public void navigateTo(Player owner, Location target) {
        NPC npc = playerNPCs.get(owner.getUniqueId());
        if (npc == null || !npc.isSpawned()) return;

        Navigator nav = npc.getNavigator();
        if (nav != null) {
            nav.setTarget(target);
        }
    }

    @Override
    public void navigateTo(Player owner, Entity target, boolean aggressive) {
        NPC npc = playerNPCs.get(owner.getUniqueId());
        if (npc == null || !npc.isSpawned()) return;

        Navigator nav = npc.getNavigator();
        if (nav != null) {
            nav.setTarget(target, aggressive);
        }
    }

    @Override
    public void cancelNavigation(Player owner) {
        NPC npc = playerNPCs.get(owner.getUniqueId());
        if (npc != null && npc.getNavigator() != null) {
            npc.getNavigator().cancelNavigation();
        }
    }

    @Override
    public boolean isNavigating(Player owner) {
        NPC npc = playerNPCs.get(owner.getUniqueId());
        if (npc == null) return false;
        Navigator nav = npc.getNavigator();
        return nav != null && nav.isNavigating();
    }

    @Override
    public Location getCurrentLocation(Player owner) {
        NPC npc = playerNPCs.get(owner.getUniqueId());
        if (npc == null) return null;
        if (npc.getEntity() != null) {
            return npc.getEntity().getLocation();
        }
        return npc.getStoredLocation();
    }

    @Override
    public void setNavigationParams(Player owner, float speed, double range) {
        NPC npc = playerNPCs.get(owner.getUniqueId());
        if (npc == null) return;

        Navigator nav = npc.getNavigator();
        if (nav != null) {
            NavigatorParameters params = nav.getLocalParameters();
            params.baseSpeed(speed);
            params.range((float) range);
        }
    }

    // ==================== EQUIPMENT ====================

    @Override
    public void setHeldItem(Player owner, ItemStack item) {
        NPC npc = playerNPCs.get(owner.getUniqueId());
        if (npc == null) return;

        Equipment equipment = npc.getOrAddTrait(Equipment.class);
        equipment.set(Equipment.EquipmentSlot.HAND, item);
    }

    @Override
    public ItemStack getHeldItem(Player owner) {
        NPC npc = playerNPCs.get(owner.getUniqueId());
        if (npc == null) return null;

        Equipment equipment = npc.getOrAddTrait(Equipment.class);
        return equipment.get(Equipment.EquipmentSlot.HAND);
    }

    @Override
    public void setEquipment(Player owner, EquipmentSlot slot, ItemStack item) {
        NPC npc = playerNPCs.get(owner.getUniqueId());
        if (npc == null) return;

        Equipment equipment = npc.getOrAddTrait(Equipment.class);
        equipment.set(toCitizensSlot(slot), item);
    }

    @Override
    public ItemStack getEquipment(Player owner, EquipmentSlot slot) {
        NPC npc = playerNPCs.get(owner.getUniqueId());
        if (npc == null) return null;

        Equipment equipment = npc.getOrAddTrait(Equipment.class);
        return equipment.get(toCitizensSlot(slot));
    }

    private Equipment.EquipmentSlot toCitizensSlot(EquipmentSlot slot) {
        return switch (slot) {
            case HAND -> Equipment.EquipmentSlot.HAND;
            case OFF_HAND -> Equipment.EquipmentSlot.OFF_HAND;
            case HEAD -> Equipment.EquipmentSlot.HELMET;
            case CHEST -> Equipment.EquipmentSlot.CHESTPLATE;
            case LEGS -> Equipment.EquipmentSlot.LEGGINGS;
            case FEET -> Equipment.EquipmentSlot.BOOTS;
        };
    }

    // ==================== INVENTORY ====================

    @Override
    public ItemStack[] getInventoryContents(Player owner) {
        NPC npc = playerNPCs.get(owner.getUniqueId());
        if (npc == null) return new ItemStack[0];

        Inventory inv = npc.getOrAddTrait(Inventory.class);
        return inv.getContents();
    }

    @Override
    public void setInventoryContents(Player owner, ItemStack[] contents) {
        NPC npc = playerNPCs.get(owner.getUniqueId());
        if (npc == null) return;

        Inventory inv = npc.getOrAddTrait(Inventory.class);
        inv.setContents(contents);
    }

    @Override
    public boolean addToInventory(Player owner, ItemStack item) {
        NPC npc = playerNPCs.get(owner.getUniqueId());
        if (npc == null) return false;

        Inventory invTrait = npc.getOrAddTrait(Inventory.class);
        ItemStack[] contents = invTrait.getContents();

        // Try to stack with existing items first
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null && contents[i].isSimilar(item)) {
                int canAdd = contents[i].getMaxStackSize() - contents[i].getAmount();
                if (canAdd > 0) {
                    int toAdd = Math.min(canAdd, item.getAmount());
                    contents[i].setAmount(contents[i].getAmount() + toAdd);
                    item.setAmount(item.getAmount() - toAdd);
                    if (item.getAmount() <= 0) {
                        invTrait.setContents(contents);
                        return true;
                    }
                }
            }
        }

        // Find empty slot
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] == null || contents[i].getType() == Material.AIR) {
                contents[i] = item.clone();
                invTrait.setContents(contents);
                return true;
            }
        }

        return false; // Inventory full
    }

    @Override
    public void openInventory(Player owner) {
        NPC npc = playerNPCs.get(owner.getUniqueId());
        if (npc == null) return;

        Inventory invTrait = npc.getOrAddTrait(Inventory.class);
        invTrait.openInventory(owner);
    }

    // ==================== ANIMATIONS & VISUALS ====================

    @Override
    public void playSwingAnimation(Player owner) {
        NPC npc = playerNPCs.get(owner.getUniqueId());
        if (npc == null || !npc.isSpawned()) return;

        Entity entity = npc.getEntity();
        if (entity instanceof LivingEntity living) {
            living.swingMainHand();
        }
    }

    @Override
    public void lookAt(Player owner, Location target) {
        NPC npc = playerNPCs.get(owner.getUniqueId());
        if (npc == null || !npc.isSpawned() || npc.getEntity() == null) return;

        Location npcLoc = npc.getEntity().getLocation();
        Vector direction = target.toVector().subtract(npcLoc.toVector());

        float yaw = (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
        float pitch = (float) Math.toDegrees(-Math.atan2(direction.getY(),
                Math.sqrt(direction.getX() * direction.getX() + direction.getZ() * direction.getZ())));

        npcLoc.setYaw(yaw);
        npcLoc.setPitch(pitch);
        npc.getEntity().teleport(npcLoc);
    }

    @Override
    public void lookAt(Player owner, Entity target) {
        if (target != null) {
            lookAt(owner, target.getLocation());
        }
    }

    @Override
    public void setProtected(Player owner, boolean protect) {
        NPC npc = playerNPCs.get(owner.getUniqueId());
        if (npc != null) {
            npc.setProtected(protect);
        }
    }

    // ==================== ENTITY ACCESS ====================

    @Override
    public Entity getEntity(Player owner) {
        NPC npc = playerNPCs.get(owner.getUniqueId());
        return npc != null ? npc.getEntity() : null;
    }

    /**
     * Vanilla-speed block breaking, with arm swing and crack overlay.
     *
     * <p>Moved here from JarvisNPC in the provider port: it was the one place
     * a Citizens BlockBreaker was reached for directly, and it is exactly the
     * kind of mechanism a different backend would have to solve its own way.
     */
    @Override
    public void breakBlock(Player owner, Block block, ItemStack toolItem,
                           double speedModifier, java.util.function.Consumer<Boolean> onDone) {
        NPC npc = getCitizensNPC(owner);
        if (npc == null || !npc.isSpawned()) {
            onDone.accept(false);
            return;
        }
        npc.faceLocation(block.getLocation().add(0.5, 0.5, 0.5));

        BlockBreaker.BlockBreakerConfiguration cfg = new BlockBreaker.BlockBreakerConfiguration();
        cfg.item(toolItem);
        cfg.radius(REACH_DISTANCE + 1.5);
        // Citizens: damage-per-tick is MULTIPLIED by this — higher = faster.
        cfg.blockStrengthModifier((float) speedModifier);

        BlockBreaker breaker = npc.getBlockBreaker(block, cfg);
        Material expected = block.getType();

        if (breaker == null || !breaker.shouldExecute()) {
            // Can't run the breaker (block already gone etc.) — fall back to instant
            boolean ok = block.breakNaturally(toolItem);
            onDone.accept(ok || block.getType() == Material.AIR);
            return;
        }

        UUID npcId = npc.getUniqueId();
        BukkitRunnable breakTask = new BukkitRunnable() {
            int safety = 0;
            @Override
            public void run() {
                // Superseded or stopped externally (v0.8.0: /jarvis stop mid-dig)
                if (activeBreakers.get(npcId) != this) {
                    breaker.reset();
                    cancel();
                    return;
                }
                if (!npc.isSpawned() || ++safety > 600) { // 30s hard cap per block
                    activeBreakers.remove(npcId, this);
                    breaker.reset();
                    cancel();
                    onDone.accept(false);
                    return;
                }
                BehaviorStatus status = breaker.run();
                if (status == BehaviorStatus.RUNNING) return;

                activeBreakers.remove(npcId, this);
                breaker.reset();
                cancel();

                // Belt and braces: if the breaker finished but the block survived,
                // finish the job so the state machine never wedges.
                if (block.getType() == expected && expected != Material.AIR) {
                    block.breakNaturally(toolItem);
                }
                onDone.accept(block.getType() != expected);
            }
        };
        activeBreakers.put(npcId, breakTask);
        breakTask.runTaskTimer(plugin, 1L, 1L);
    }

    /** Cancels an in-flight break for this NPC, if any. */
    public void cancelBreaking(UUID npcId) {
        BukkitRunnable task = activeBreakers.remove(npcId);
        if (task != null) task.cancel();
    }

    @Override
    public void setSwimming(Player owner, boolean swim) {
        NPC npc = getCitizensNPC(owner);
        if (npc != null) npc.data().setPersistent(NPC.Metadata.SWIM, swim);
    }

    @Override
    public UUID getNPCUUID(Player owner) {
        NPC npc = playerNPCs.get(owner.getUniqueId());
        return npc != null ? npc.getUniqueId() : null;
    }

    /**
     * Get the raw Citizens NPC object (for advanced operations).
     */
    public NPC getCitizensNPC(Player owner) {
        return playerNPCs.get(owner.getUniqueId());
    }

    // ==================== CLEANUP ====================

    @Override
    public void cleanup() {
        for (NPC npc : playerNPCs.values()) {
            if (npc.isSpawned()) {
                dropInventoryItems(npc);
            }
            npc.destroy();
        }
        playerNPCs.clear();
    }

    @Override
    public void handlePlayerDisconnect(Player player) {
        NPC npc = playerNPCs.remove(player.getUniqueId());
        if (npc == null) return;

        if (npc.isSpawned()) {
            dropInventoryItems(npc);
        }
        npc.destroy();
    }

    @Override
    public void handleChunkUnload(Player owner) {
        // Citizens handles this automatically
    }

    @Override
    public void handleChunkLoad(Player owner) {
        // Citizens handles this automatically
    }

    // ==================== PROVIDER INFO ====================

    @Override
    public String getProviderName() {
        return "Citizens";
    }

    @Override
    public boolean supportsFeature(NPCFeature feature) {
        return switch (feature) {
            case SMOOTH_MOVEMENT -> false; // Citizens uses teleport-based movement
            case NATIVE_PATHFINDING -> true;
            case SKIN_SUPPORT -> true;
            case INVENTORY_GUI -> true;
            case CHUNK_PERSISTENCE -> true;
            case COLLISION -> false;
        };
    }

    @Override
    public boolean isAvailable() {
        return Bukkit.getPluginManager().getPlugin("Citizens") != null;
    }

    // ==================== HELPER METHODS ====================

    private Location findSafeSpawnLocation(Location center) {
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
        return center;
    }

    private boolean isSafeToStand(Location loc) {
        Block feet = loc.getBlock();
        Block head = feet.getRelative(BlockFace.UP);
        Block ground = feet.getRelative(BlockFace.DOWN);

        if (!ground.getType().isSolid()) return false;
        if (feet.getType().isSolid()) return false;
        if (head.getType().isSolid()) return false;

        Material groundType = ground.getType();
        if (groundType == Material.LAVA || groundType == Material.FIRE ||
            groundType == Material.MAGMA_BLOCK || groundType == Material.CACTUS) {
            return false;
        }

        return true;
    }

    private void giveStartingEquipment(NPC npc) {
        Equipment equipment = npc.getOrAddTrait(Equipment.class);
        equipment.set(Equipment.EquipmentSlot.HAND, new ItemStack(Material.DIAMOND_PICKAXE));

        Inventory inv = npc.getOrAddTrait(Inventory.class);
        ItemStack[] contents = inv.getContents();
        contents[0] = new ItemStack(Material.DIRT, 32);
        inv.setContents(contents);
    }

    private void dropInventoryItems(NPC npc) {
        if (!npc.isSpawned()) return;

        Location dropLoc = npc.getEntity().getLocation();
        Inventory invTrait = npc.getOrAddTrait(Inventory.class);
        ItemStack[] contents = invTrait.getContents();

        for (ItemStack item : contents) {
            if (item != null && item.getType() != Material.AIR &&
                item.getType() != Material.DIAMOND_PICKAXE && item.getType() != Material.DIRT) {
                dropLoc.getWorld().dropItemNaturally(dropLoc, item.clone());
            }
        }
    }
}
