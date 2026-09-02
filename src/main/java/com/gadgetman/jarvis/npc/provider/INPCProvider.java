package com.gadgetman.jarvis.npc.provider;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Abstraction layer for NPC backends.
 * Allows switching between Citizens and Custom NPC implementations.
 */
public interface INPCProvider {

    // ==================== LIFECYCLE ====================

    /**
     * Spawn an NPC for the given player at the specified location.
     * @param owner The player who owns this NPC
     * @param location Where to spawn the NPC
     * @param name Display name for the NPC
     */
    void spawn(Player owner, Location location, String name);

    /**
     * Despawn and remove the NPC for the given player.
     * @param owner The player whose NPC to remove
     */
    void despawn(Player owner);

    /**
     * Check if the player has a spawned NPC.
     * @param owner The player to check
     * @return true if NPC is spawned
     */
    boolean isSpawned(Player owner);

    /**
     * Check if the NPC exists (may not be spawned).
     * @param owner The player to check
     * @return true if NPC exists
     */
    boolean exists(Player owner);

    // ==================== MOVEMENT & NAVIGATION ====================

    /**
     * Teleport the NPC directly to a location.
     * @param owner The player whose NPC to teleport
     * @param location Destination location
     */
    void teleport(Player owner, Location location);

    /**
     * Navigate the NPC to a target location using pathfinding.
     * @param owner The player whose NPC to move
     * @param target Destination location
     */
    void navigateTo(Player owner, Location target);

    /**
     * Navigate the NPC to follow/chase an entity.
     * @param owner The player whose NPC to move
     * @param target Entity to follow
     * @param aggressive If true, will attack when close
     */
    void navigateTo(Player owner, Entity target, boolean aggressive);

    /**
     * Cancel any current navigation.
     * @param owner The player whose NPC to stop
     */
    void cancelNavigation(Player owner);

    /**
     * Check if the NPC is currently navigating.
     * @param owner The player to check
     * @return true if actively navigating
     */
    boolean isNavigating(Player owner);

    /**
     * Get the NPC's current location.
     * @param owner The player to check
     * @return Current location or null if not spawned
     */
    Location getCurrentLocation(Player owner);

    /**
     * Set navigation parameters.
     * @param owner The player whose NPC to configure
     * @param speed Movement speed (0.0 - 1.0)
     * @param range Maximum pathfinding range
     */
    void setNavigationParams(Player owner, float speed, double range);

    // ==================== EQUIPMENT ====================

    /**
     * Set the item held in the NPC's main hand.
     * @param owner The player whose NPC to equip
     * @param item Item to hold
     */
    void setHeldItem(Player owner, ItemStack item);

    /**
     * Get the item held in the NPC's main hand.
     * @param owner The player to check
     * @return Item in main hand or null
     */
    ItemStack getHeldItem(Player owner);

    /**
     * Set equipment in a specific slot.
     * @param owner The player whose NPC to equip
     * @param slot Equipment slot
     * @param item Item to equip
     */
    void setEquipment(Player owner, EquipmentSlot slot, ItemStack item);

    /**
     * Get equipment from a specific slot.
     * @param owner The player to check
     * @param slot Equipment slot
     * @return Item in slot or null
     */
    ItemStack getEquipment(Player owner, EquipmentSlot slot);

    // ==================== INVENTORY ====================

    /**
     * Get the NPC's inventory contents.
     * @param owner The player to check
     * @return Array of inventory contents
     */
    ItemStack[] getInventoryContents(Player owner);

    /**
     * Set the NPC's inventory contents.
     * @param owner The player whose NPC to modify
     * @param contents New inventory contents
     */
    void setInventoryContents(Player owner, ItemStack[] contents);

    /**
     * Add an item to the NPC's inventory.
     * @param owner The player whose NPC to modify
     * @param item Item to add
     * @return true if item was added successfully
     */
    boolean addToInventory(Player owner, ItemStack item);

    /**
     * Open the NPC's inventory GUI for the player.
     * @param owner The player to show inventory to
     */
    void openInventory(Player owner);

    // ==================== ANIMATIONS & VISUALS ====================

    /**
     * Play arm swing animation (for mining/attacking).
     * @param owner The player whose NPC to animate
     */
    /**
     * Break a block at vanilla speed, with the arm swing and crack overlay.
     *
     * <p>Citizens does this through a BlockBreaker, which has no equivalent in
     * a hand-rolled backend -- so it is expressed here as the behaviour wanted
     * rather than the mechanism. A backend that cannot break blocks over time
     * should break it instantly and still call {@code onDone}.
     *
     * @param toolItem      what to break it with, for drops and speed
     * @param speedModifier >1 breaks faster, <1 slower
     * @param onDone        given true when the block actually went
     */
    void breakBlock(Player owner, org.bukkit.block.Block block,
                    org.bukkit.inventory.ItemStack toolItem, double speedModifier,
                    java.util.function.Consumer<Boolean> onDone);

    /**
     * Keep the NPC afloat rather than letting it sink and wedge underwater.
     *
     * <p>Citizens has a persistent SWIM metadata flag; other backends may need
     * to implement buoyancy themselves, or no-op if they cannot.
     */
    void setSwimming(Player owner, boolean swim);

    void playSwingAnimation(Player owner);

    /**
     * Make the NPC look at a specific location.
     * @param owner The player whose NPC to rotate
     * @param target Location to look at
     */
    void lookAt(Player owner, Location target);

    /**
     * Make the NPC look at a specific entity.
     * @param owner The player whose NPC to rotate
     * @param target Entity to look at
     */
    void lookAt(Player owner, Entity target);

    /**
     * Set whether the NPC is protected from damage.
     * @param owner The player whose NPC to modify
     * @param protect true to protect from damage
     */
    void setProtected(Player owner, boolean protect);

    // ==================== ENTITY ACCESS ====================

    /**
     * Get the underlying Bukkit entity for the NPC.
     * @param owner The player to check
     * @return Entity or null if not spawned
     */
    Entity getEntity(Player owner);

    /**
     * Get the NPC's unique ID.
     * @param owner The player to check
     * @return UUID or null if not exists
     */
    UUID getNPCUUID(Player owner);

    // ==================== CLEANUP ====================

    /**
     * Clean up all NPCs managed by this provider.
     */
    void cleanup();

    /**
     * Handle a player disconnecting.
     * @param player The player who disconnected
     */
    void handlePlayerDisconnect(Player player);

    /**
     * Handle chunk unloading for an NPC.
     * @param owner The player whose NPC's chunk unloaded
     */
    void handleChunkUnload(Player owner);

    /**
     * Handle chunk loading for an NPC.
     * @param owner The player whose NPC's chunk loaded
     */
    void handleChunkLoad(Player owner);

    // ==================== PROVIDER INFO ====================

    /**
     * Get the name of this provider.
     * @return Provider name (e.g., "Citizens", "Custom")
     */
    String getProviderName();

    /**
     * Check if this provider supports a specific feature.
     * @param feature Feature to check
     * @return true if supported
     */
    boolean supportsFeature(NPCFeature feature);

    /**
     * Check if the provider is available (dependencies met).
     * @return true if provider can be used
     */
    boolean isAvailable();

    // ==================== ENUMS ====================

    enum EquipmentSlot {
        HAND,
        OFF_HAND,
        HEAD,
        CHEST,
        LEGS,
        FEET
    }

    enum NPCFeature {
        SMOOTH_MOVEMENT,      // Smooth walking animation
        NATIVE_PATHFINDING,   // Uses Minecraft's pathfinding
        SKIN_SUPPORT,         // Custom player skins
        INVENTORY_GUI,        // Interactive inventory
        CHUNK_PERSISTENCE,    // Survives chunk unload
        COLLISION             // Collides with entities
    }
}
