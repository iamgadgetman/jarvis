package com.yourname.jarvis.npc.custom;

import com.yourname.jarvis.Jarvis;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fallback "fake player" implementation using a Villager entity.
 * This is used when NMS is not available.
 *
 * A future version could use NMS packets for a true player-like NPC,
 * but this allows the custom provider to work without complex build setup.
 */
public class FakePlayer {

    private final Jarvis plugin;
    private final String name;
    private final UUID uuid;

    private Villager entity;
    private Location currentLocation;
    private boolean spawned = false;

    // Track which players can see this NPC
    private final Set<UUID> viewers = ConcurrentHashMap.newKeySet();

    public FakePlayer(Jarvis plugin, PacketManager packetManager, String name) {
        this.plugin = plugin;
        this.name = name;
        this.uuid = UUID.randomUUID();
    }

    /**
     * Spawn the "fake player" (villager) at the given location.
     */
    public void spawn(Location location) {
        if (spawned) {
            despawn();
        }

        this.currentLocation = location.clone();

        entity = location.getWorld().spawn(location, Villager.class, villager -> {
            villager.setCustomName(name);
            villager.setCustomNameVisible(true);
            villager.setSilent(true);
            villager.setInvulnerable(true);
            villager.setCollidable(false);
            villager.setAI(false);  // We control movement
            villager.setPersistent(true);
            villager.setRemoveWhenFarAway(false);

            // Set profession for appearance
            villager.setProfession(Villager.Profession.TOOLSMITH);
            villager.setVillagerType(Villager.Type.PLAINS);

            // Make it look ready for action
            try {
                var speedAttr = villager.getAttribute(Attribute.valueOf("GENERIC_MOVEMENT_SPEED"));
                if (speedAttr != null) {
                    speedAttr.setBaseValue(0.3);
                }
            } catch (Exception ignored) {
                // Attribute not available in this version
            }
        });

        spawned = true;

        // Track all online players as viewers
        for (Player player : Bukkit.getOnlinePlayers()) {
            viewers.add(player.getUniqueId());
        }

        plugin.getLogger().info("[FakePlayer] Spawned villager '" + name + "' at " + formatLocation(location));
    }

    /**
     * Despawn and remove the entity.
     */
    public void despawn() {
        if (!spawned) return;

        if (entity != null && !entity.isDead()) {
            entity.remove();
        }

        entity = null;
        spawned = false;
        viewers.clear();

        plugin.getLogger().info("[FakePlayer] Despawned villager '" + name + "'");
    }

    /**
     * Show to a specific player (for compatibility).
     */
    public void showTo(Player viewer) {
        viewers.add(viewer.getUniqueId());
    }

    /**
     * Hide from a specific player (for compatibility).
     */
    public void hideFrom(Player viewer) {
        viewers.remove(viewer.getUniqueId());
    }

    /**
     * Update the entity's position.
     */
    public void updatePosition(Location newLocation) {
        if (!spawned || entity == null || entity.isDead()) return;

        currentLocation = newLocation.clone();
        entity.teleport(newLocation);
    }

    /**
     * Make the entity look at a location.
     */
    public void lookAt(Location target) {
        if (!spawned || entity == null || currentLocation == null) return;

        double dx = target.getX() - currentLocation.getX();
        double dy = target.getY() - currentLocation.getY();
        double dz = target.getZ() - currentLocation.getZ();

        double distanceXZ = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, distanceXZ));

        currentLocation.setYaw(yaw);
        currentLocation.setPitch(pitch);

        entity.setRotation(yaw, pitch);
    }

    /**
     * Play swing animation (villagers don't really swing, but we can add effects).
     */
    public void playSwingAnimation() {
        if (!spawned || entity == null) return;

        // Villagers don't have a swing animation, but we could add particles
        // For now, just a placeholder
    }

    // ==================== GETTERS ====================

    public boolean isSpawned() {
        return spawned && entity != null && !entity.isDead();
    }

    public Location getLocation() {
        if (entity != null && !entity.isDead()) {
            return entity.getLocation();
        }
        return currentLocation != null ? currentLocation.clone() : null;
    }

    public UUID getUUID() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public int getEntityId() {
        return entity != null ? entity.getEntityId() : -1;
    }

    public Entity getEntity() {
        return entity;
    }

    public Set<UUID> getViewers() {
        return Set.copyOf(viewers);
    }

    private String formatLocation(Location loc) {
        return String.format("(%.1f, %.1f, %.1f)", loc.getX(), loc.getY(), loc.getZ());
    }
}
