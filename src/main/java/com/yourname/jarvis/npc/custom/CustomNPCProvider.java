package com.yourname.jarvis.npc.custom;

import com.yourname.jarvis.Jarvis;
import com.yourname.jarvis.npc.provider.INPCProvider;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom NPC Provider that doesn't require Citizens.
 * Uses packet-based fake players and invisible zombie proxies for pathfinding.
 */
public class CustomNPCProvider implements INPCProvider, Listener {

    private final Jarvis plugin;
    private final PacketManager packetManager;

    // Per-player NPC data
    private final Map<UUID, CustomNPC> npcs = new ConcurrentHashMap<>();

    /**
     * Container for all components of a custom NPC.
     */
    private static class CustomNPC {
        final FakePlayer fakePlayer;
        final PathfinderProxy pathfinder;
        final MovementController movementController;
        final NPCEquipment equipment;
        final NPCInventory inventory;

        CustomNPC(FakePlayer fakePlayer, PathfinderProxy pathfinder,
                  MovementController movementController, NPCEquipment equipment, NPCInventory inventory) {
            this.fakePlayer = fakePlayer;
            this.pathfinder = pathfinder;
            this.movementController = movementController;
            this.equipment = equipment;
            this.inventory = inventory;
        }
    }

    public CustomNPCProvider(Jarvis plugin) {
        this.plugin = plugin;
        this.packetManager = new PacketManager(plugin);

        // Register listener for player joins (to show existing NPCs)
        Bukkit.getPluginManager().registerEvents(this, plugin);

        plugin.getLogger().info("[CustomNPCProvider] Initialized");
    }

    // ==================== LIFECYCLE ====================

    @Override
    public void spawn(Player owner, Location location, String name) {
        UUID ownerId = owner.getUniqueId();

        // Despawn existing if present
        if (npcs.containsKey(ownerId)) {
            despawn(owner);
        }

        Location safeLoc = findSafeSpawnLocation(location);

        // Create all components
        FakePlayer fakePlayer = new FakePlayer(plugin, packetManager, name);
        PathfinderProxy pathfinder = new PathfinderProxy(plugin);
        MovementController movementController = new MovementController(plugin, fakePlayer, pathfinder);
        NPCEquipment equipment = new NPCEquipment(plugin, packetManager, fakePlayer);
        NPCInventory inventory = new NPCInventory(plugin, name);

        // Spawn entities
        fakePlayer.spawn(safeLoc);
        pathfinder.spawn(safeLoc);

        // Give starting equipment
        ItemStack pickaxe = new ItemStack(Material.DIAMOND_PICKAXE);
        equipment.setHeldItem(pickaxe);

        // Start movement sync
        movementController.start();

        // Store NPC data
        npcs.put(ownerId, new CustomNPC(fakePlayer, pathfinder, movementController, equipment, inventory));

        // Play sound
        safeLoc.getWorld().playSound(safeLoc, Sound.BLOCK_BELL_USE, 1.0f, 1.0f);

        plugin.getLogger().info("[CustomNPCProvider] Spawned NPC for " + owner.getName());
    }

    @Override
    public void despawn(Player owner) {
        CustomNPC npc = npcs.remove(owner.getUniqueId());
        if (npc == null) return;

        // Drop inventory items
        dropInventoryItems(npc);

        // Stop and cleanup all components
        npc.movementController.stop();
        npc.fakePlayer.despawn();
        npc.pathfinder.despawn();
        npc.inventory.cleanup();

        plugin.getLogger().info("[CustomNPCProvider] Despawned NPC for " + owner.getName());
    }

    @Override
    public boolean isSpawned(Player owner) {
        CustomNPC npc = npcs.get(owner.getUniqueId());
        return npc != null && npc.fakePlayer.isSpawned();
    }

    @Override
    public boolean exists(Player owner) {
        return npcs.containsKey(owner.getUniqueId());
    }

    // ==================== MOVEMENT & NAVIGATION ====================

    @Override
    public void teleport(Player owner, Location location) {
        CustomNPC npc = npcs.get(owner.getUniqueId());
        if (npc == null) return;

        npc.pathfinder.teleport(location);
        npc.movementController.forceSync();
    }

    @Override
    public void navigateTo(Player owner, Location target) {
        CustomNPC npc = npcs.get(owner.getUniqueId());
        if (npc == null) return;

        npc.pathfinder.navigateTo(target);
    }

    @Override
    public void navigateTo(Player owner, Entity target, boolean aggressive) {
        CustomNPC npc = npcs.get(owner.getUniqueId());
        if (npc == null) return;

        npc.pathfinder.navigateTo(target, aggressive);
    }

    @Override
    public void cancelNavigation(Player owner) {
        CustomNPC npc = npcs.get(owner.getUniqueId());
        if (npc == null) return;

        npc.pathfinder.cancelNavigation();
    }

    @Override
    public boolean isNavigating(Player owner) {
        CustomNPC npc = npcs.get(owner.getUniqueId());
        return npc != null && npc.pathfinder.isNavigating();
    }

    @Override
    public Location getCurrentLocation(Player owner) {
        CustomNPC npc = npcs.get(owner.getUniqueId());
        if (npc == null) return null;
        return npc.fakePlayer.getLocation();
    }

    @Override
    public void setNavigationParams(Player owner, float speed, double range) {
        CustomNPC npc = npcs.get(owner.getUniqueId());
        if (npc == null) return;

        npc.pathfinder.setSpeed(speed);
        npc.pathfinder.setRange(range);
    }

    // ==================== EQUIPMENT ====================

    @Override
    public void setHeldItem(Player owner, ItemStack item) {
        CustomNPC npc = npcs.get(owner.getUniqueId());
        if (npc != null) {
            npc.equipment.setHeldItem(item);
        }
    }

    @Override
    public ItemStack getHeldItem(Player owner) {
        CustomNPC npc = npcs.get(owner.getUniqueId());
        return npc != null ? npc.equipment.getHeldItem() : null;
    }

    @Override
    public void setEquipment(Player owner, EquipmentSlot slot, ItemStack item) {
        CustomNPC npc = npcs.get(owner.getUniqueId());
        if (npc != null) {
            npc.equipment.setEquipment(slot, item);
        }
    }

    @Override
    public ItemStack getEquipment(Player owner, EquipmentSlot slot) {
        CustomNPC npc = npcs.get(owner.getUniqueId());
        return npc != null ? npc.equipment.getEquipment(slot) : null;
    }

    // ==================== INVENTORY ====================

    @Override
    public ItemStack[] getInventoryContents(Player owner) {
        CustomNPC npc = npcs.get(owner.getUniqueId());
        return npc != null ? npc.inventory.getContents() : new ItemStack[0];
    }

    @Override
    public void setInventoryContents(Player owner, ItemStack[] contents) {
        CustomNPC npc = npcs.get(owner.getUniqueId());
        if (npc != null) {
            npc.inventory.setContents(contents);
        }
    }

    @Override
    public boolean addToInventory(Player owner, ItemStack item) {
        CustomNPC npc = npcs.get(owner.getUniqueId());
        return npc != null && npc.inventory.addItem(item);
    }

    @Override
    public void openInventory(Player owner) {
        CustomNPC npc = npcs.get(owner.getUniqueId());
        if (npc != null) {
            npc.inventory.openFor(owner);
        }
    }

    // ==================== ANIMATIONS & VISUALS ====================

    @Override
    public void playSwingAnimation(Player owner) {
        CustomNPC npc = npcs.get(owner.getUniqueId());
        if (npc != null) {
            npc.fakePlayer.playSwingAnimation();
        }
    }

    @Override
    public void lookAt(Player owner, Location target) {
        CustomNPC npc = npcs.get(owner.getUniqueId());
        if (npc != null) {
            npc.fakePlayer.lookAt(target);
        }
    }

    @Override
    public void lookAt(Player owner, Entity target) {
        if (target != null) {
            lookAt(owner, target.getLocation().add(0, target.getHeight() / 2, 0));
        }
    }

    @Override
    public void setProtected(Player owner, boolean protect) {
        CustomNPC npc = npcs.get(owner.getUniqueId());
        if (npc != null && npc.pathfinder.isValid()) {
            npc.pathfinder.getEntity().setInvulnerable(protect);
        }
    }

    // ==================== ENTITY ACCESS ====================

    @Override
    public Entity getEntity(Player owner) {
        CustomNPC npc = npcs.get(owner.getUniqueId());
        // Return the pathfinder proxy since that's the actual entity in the world
        return npc != null ? npc.pathfinder.getEntity() : null;
    }

    @Override
    public UUID getNPCUUID(Player owner) {
        CustomNPC npc = npcs.get(owner.getUniqueId());
        return npc != null ? npc.fakePlayer.getUUID() : null;
    }

    // ==================== CLEANUP ====================

    @Override
    public void cleanup() {
        for (UUID ownerId : npcs.keySet()) {
            Player owner = Bukkit.getPlayer(ownerId);
            if (owner != null) {
                despawn(owner);
            } else {
                // Player offline, cleanup directly
                CustomNPC npc = npcs.remove(ownerId);
                if (npc != null) {
                    npc.movementController.stop();
                    npc.fakePlayer.despawn();
                    npc.pathfinder.despawn();
                    npc.inventory.cleanup();
                }
            }
        }
        npcs.clear();

        // Unregister listener
        HandlerList.unregisterAll(this);
    }

    @Override
    public void handlePlayerDisconnect(Player player) {
        despawn(player);
    }

    @Override
    public void handleChunkUnload(Player owner) {
        // Custom NPCs use packets, so chunk unload doesn't affect them directly
        // The pathfinder proxy might despawn, but we can respawn it
        CustomNPC npc = npcs.get(owner.getUniqueId());
        if (npc != null && !npc.pathfinder.isValid()) {
            Location loc = npc.fakePlayer.getLocation();
            if (loc != null && loc.getChunk().isLoaded()) {
                npc.pathfinder.spawn(loc);
            }
        }
    }

    @Override
    public void handleChunkLoad(Player owner) {
        // Respawn pathfinder if needed
        handleChunkUnload(owner);
    }

    // ==================== PROVIDER INFO ====================

    @Override
    public String getProviderName() {
        return "Custom";
    }

    @Override
    public boolean supportsFeature(NPCFeature feature) {
        return switch (feature) {
            case SMOOTH_MOVEMENT -> true;      // We use interpolation
            case NATIVE_PATHFINDING -> true;   // Uses zombie pathfinding
            case SKIN_SUPPORT -> false;        // Not implemented yet
            case INVENTORY_GUI -> true;
            case CHUNK_PERSISTENCE -> false;   // Packet-based, doesn't persist
            case COLLISION -> false;           // Fake players don't collide
        };
    }

    @Override
    public boolean isAvailable() {
        return true; // Always available - no external dependencies
    }

    // ==================== EVENT HANDLERS ====================

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Show all existing NPCs to the joining player
        Player joiner = event.getPlayer();

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (CustomNPC npc : npcs.values()) {
                if (npc.fakePlayer.isSpawned()) {
                    npc.fakePlayer.showTo(joiner);
                    npc.equipment.sendAllEquipment(joiner);
                }
            }
        }, 20L); // 1 second delay to ensure client is ready
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
        return groundType != Material.LAVA && groundType != Material.FIRE &&
               groundType != Material.MAGMA_BLOCK && groundType != Material.CACTUS;
    }

    private void dropInventoryItems(CustomNPC npc) {
        Location dropLoc = npc.fakePlayer.getLocation();
        if (dropLoc == null) return;

        ItemStack[] contents = npc.inventory.getContents();
        for (ItemStack item : contents) {
            if (item != null && item.getType() != Material.AIR &&
                item.getType() != Material.DIAMOND_PICKAXE && item.getType() != Material.DIRT) {
                dropLoc.getWorld().dropItemNaturally(dropLoc, item.clone());
            }
        }
    }

    // ==================== CUSTOM PROVIDER METHODS ====================

    /**
     * Get the movement controller for an NPC (for stuck detection).
     */
    public MovementController getMovementController(Player owner) {
        CustomNPC npc = npcs.get(owner.getUniqueId());
        return npc != null ? npc.movementController : null;
    }

    /**
     * Get the pathfinder proxy for an NPC (for advanced control).
     */
    public PathfinderProxy getPathfinderProxy(Player owner) {
        CustomNPC npc = npcs.get(owner.getUniqueId());
        return npc != null ? npc.pathfinder : null;
    }

    /**
     * Check if NPC is stuck.
     */
    public boolean isStuck(Player owner) {
        CustomNPC npc = npcs.get(owner.getUniqueId());
        return npc != null && npc.movementController.isStuck();
    }

    /**
     * Force position sync (after teleport/recovery).
     */
    public void forceSync(Player owner) {
        CustomNPC npc = npcs.get(owner.getUniqueId());
        if (npc != null) {
            npc.movementController.forceSync();
            npc.movementController.resetStuckCounter();
        }
    }
}
