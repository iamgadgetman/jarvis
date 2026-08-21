package com.yourname.jarvis.npc.custom;

import com.yourname.jarvis.Jarvis;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Zombie;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Invisible zombie that handles pathfinding for the custom NPC.
 * Uses Minecraft's built-in mob AI for natural movement.
 */
public class PathfinderProxy {

    private final Jarvis plugin;
    private Zombie proxy;
    private Location currentTarget;
    private Entity entityTarget;
    private boolean isNavigating = false;
    private double moveSpeed = 0.6;
    private double pathfindRange = 32.0;

    public PathfinderProxy(Jarvis plugin) {
        this.plugin = plugin;
    }

    /**
     * Spawn the invisible zombie at the given location.
     */
    public void spawn(Location location) {
        if (proxy != null && !proxy.isDead()) {
            proxy.remove();
        }

        proxy = location.getWorld().spawn(location, Zombie.class, zombie -> {
            // Make completely invisible
            zombie.setInvisible(true);
            zombie.setSilent(true);
            zombie.setCollidable(false);
            zombie.setInvulnerable(true);
            zombie.setGravity(true);
            zombie.setCanPickupItems(false);

            // Set as baby for smaller hitbox (optional, can remove if it causes issues)
            zombie.setBaby(false);

            // Prevent despawning
            zombie.setRemoveWhenFarAway(false);
            zombie.setPersistent(true);

            // Clear AI targets
            zombie.setTarget(null);

            // Remove vanilla AI goals - we'll control movement ourselves
            // Note: On Paper, we can use the MobGoals API
            try {
                Bukkit.getMobGoals().removeAllGoals(zombie);
            } catch (Exception e) {
                plugin.getLogger().warning("Could not clear mob goals: " + e.getMessage());
            }

            // Set movement speed using modern API
            setEntitySpeed(zombie, moveSpeed * 0.3);

            // Make it not burn in sunlight
            zombie.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 0, false, false));
        });

        plugin.getLogger().info("[PathfinderProxy] Spawned at " + formatLocation(location));
    }

    /**
     * Set movement speed on an entity using reflection for compatibility.
     */
    private void setEntitySpeed(Zombie zombie, double speed) {
        try {
            var speedAttr = zombie.getAttribute(Attribute.valueOf("GENERIC_MOVEMENT_SPEED"));
            if (speedAttr != null) {
                speedAttr.setBaseValue(speed);
            }
        } catch (Exception e) {
            // Try alternate names for different versions
            try {
                var speedAttr = zombie.getAttribute(Attribute.valueOf("MOVEMENT_SPEED"));
                if (speedAttr != null) {
                    speedAttr.setBaseValue(speed);
                }
            } catch (Exception ignored) {
                // Speed attribute not available
            }
        }
    }

    /**
     * Despawn and remove the proxy zombie.
     */
    public void despawn() {
        if (proxy != null) {
            proxy.remove();
            proxy = null;
        }
        currentTarget = null;
        entityTarget = null;
        isNavigating = false;
    }

    /**
     * Check if the proxy is spawned and valid.
     */
    public boolean isValid() {
        return proxy != null && !proxy.isDead() && proxy.isValid();
    }

    /**
     * Navigate to a specific location.
     */
    public void navigateTo(Location target) {
        if (!isValid()) return;

        this.currentTarget = target.clone();
        this.entityTarget = null;
        this.isNavigating = true;

        // Use Paper's Pathfinder API
        try {
            com.destroystokyo.paper.entity.Pathfinder pathfinder = proxy.getPathfinder();
            if (pathfinder != null) {
                pathfinder.moveTo(target, moveSpeed);
            }
        } catch (Exception e) {
            // Fallback: direct AI targeting
            plugin.getLogger().warning("[PathfinderProxy] Pathfinder API failed: " + e.getMessage());
        }
    }

    /**
     * Navigate to follow/chase an entity.
     */
    public void navigateTo(Entity target, boolean aggressive) {
        if (!isValid() || target == null) return;

        this.entityTarget = target;
        this.currentTarget = target.getLocation();
        this.isNavigating = true;

        try {
            if (aggressive && target instanceof LivingEntity living) {
                // Use Zombie's built-in target mechanism for aggressive chasing
                proxy.setTarget(living);
            } else if (target instanceof LivingEntity living) {
                // Use moveTo with LivingEntity
                com.destroystokyo.paper.entity.Pathfinder pathfinder = proxy.getPathfinder();
                if (pathfinder != null) {
                    pathfinder.moveTo(living, moveSpeed);
                }
            } else {
                // Fallback to location-based pathfinding
                com.destroystokyo.paper.entity.Pathfinder pathfinder = proxy.getPathfinder();
                if (pathfinder != null) {
                    pathfinder.moveTo(target.getLocation(), moveSpeed);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[PathfinderProxy] Entity navigation failed: " + e.getMessage());
        }
    }

    /**
     * Cancel current navigation.
     */
    public void cancelNavigation() {
        if (!isValid()) return;

        try {
            com.destroystokyo.paper.entity.Pathfinder pathfinder = proxy.getPathfinder();
            if (pathfinder != null) {
                pathfinder.stopPathfinding();
            }
            proxy.setTarget(null);
        } catch (Exception e) {
            // Ignore
        }

        currentTarget = null;
        entityTarget = null;
        isNavigating = false;
    }

    /**
     * Check if actively navigating.
     */
    public boolean isNavigating() {
        if (!isValid()) return false;

        // Check if actually moving or has a path
        try {
            com.destroystokyo.paper.entity.Pathfinder pathfinder = proxy.getPathfinder();
            if (pathfinder != null && pathfinder.hasPath()) {
                return true;
            }
        } catch (Exception e) {
            // Ignore
        }

        // Check if chasing a target
        if (proxy.getTarget() != null) {
            return true;
        }

        return isNavigating && currentTarget != null;
    }

    /**
     * Get the proxy's current location.
     */
    public Location getLocation() {
        if (!isValid()) return null;
        return proxy.getLocation();
    }

    /**
     * Teleport the proxy directly.
     */
    public void teleport(Location location) {
        if (!isValid()) return;
        proxy.teleport(location);
    }

    /**
     * Set movement speed.
     */
    public void setSpeed(float speed) {
        this.moveSpeed = speed;
        if (isValid()) {
            setEntitySpeed(proxy, speed * 0.3);
        }
    }

    /**
     * Set pathfinding range.
     */
    public void setRange(double range) {
        this.pathfindRange = range;
    }

    /**
     * Get the target location (for entity targets, returns their current location).
     */
    public Location getTargetLocation() {
        if (entityTarget != null && entityTarget.isValid()) {
            return entityTarget.getLocation();
        }
        return currentTarget;
    }

    /**
     * Update entity target tracking (call this periodically for smooth following).
     */
    public void updateTargetTracking() {
        if (!isValid()) return;

        // If following an entity, update the path periodically
        if (entityTarget != null && entityTarget.isValid()) {
            try {
                com.destroystokyo.paper.entity.Pathfinder pathfinder = proxy.getPathfinder();
                if (pathfinder != null) {
                    // Re-pathfind to current entity position
                    if (entityTarget instanceof LivingEntity living) {
                        pathfinder.moveTo(living, moveSpeed);
                    } else {
                        pathfinder.moveTo(entityTarget.getLocation(), moveSpeed);
                    }
                }
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    /**
     * Check if the proxy has reached its target.
     */
    public boolean hasReachedTarget(double margin) {
        if (!isValid() || currentTarget == null) return true;

        Location loc = proxy.getLocation();
        double distance = loc.distance(currentTarget);
        return distance <= margin;
    }

    /**
     * Get the underlying zombie entity (for advanced operations).
     */
    public Zombie getEntity() {
        return proxy;
    }

    /**
     * Get distance to current target.
     */
    public double getDistanceToTarget() {
        if (!isValid()) return -1;
        Location target = getTargetLocation();
        if (target == null) return -1;
        return proxy.getLocation().distance(target);
    }

    private String formatLocation(Location loc) {
        return String.format("(%.1f, %.1f, %.1f)", loc.getX(), loc.getY(), loc.getZ());
    }
}
