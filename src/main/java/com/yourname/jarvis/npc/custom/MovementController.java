package com.yourname.jarvis.npc.custom;

import com.yourname.jarvis.Jarvis;
import org.bukkit.Location;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

/**
 * Controls smooth movement synchronization between the PathfinderProxy and FakePlayer.
 * Uses interpolation to create natural walking animation.
 */
public class MovementController {

    private final Jarvis plugin;
    private final FakePlayer fakePlayer;
    private final PathfinderProxy pathfinderProxy;

    // Configuration
    private float interpolationFactor = 0.3f;  // How much to move towards target each tick (0.0-1.0)
    private int syncInterval = 1;               // Ticks between sync updates

    // State
    private BukkitTask syncTask;
    private boolean running = false;

    // Movement tracking for stuck detection
    private Location lastPosition;
    private int stuckTicks = 0;
    private static final int STUCK_THRESHOLD = 40;  // 2 seconds

    public MovementController(Jarvis plugin, FakePlayer fakePlayer, PathfinderProxy pathfinderProxy) {
        this.plugin = plugin;
        this.fakePlayer = fakePlayer;
        this.pathfinderProxy = pathfinderProxy;

        // Load config values
        this.interpolationFactor = (float) plugin.getConfig().getDouble("npc.custom.interpolation-factor", 0.3);
        this.syncInterval = plugin.getConfig().getInt("npc.custom.sync-interval", 1);
    }

    /**
     * Start the position synchronization loop.
     */
    public void start() {
        if (running) return;
        running = true;

        syncTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!running) {
                    cancel();
                    return;
                }
                tick();
            }
        }.runTaskTimer(plugin, 0L, syncInterval);

        plugin.getLogger().info("[MovementController] Started sync task");
    }

    /**
     * Stop the synchronization loop.
     */
    public void stop() {
        running = false;
        if (syncTask != null) {
            syncTask.cancel();
            syncTask = null;
        }
    }

    /**
     * Single tick of movement synchronization.
     */
    private void tick() {
        if (!fakePlayer.isSpawned() || !pathfinderProxy.isValid()) {
            return;
        }

        Location proxyLoc = pathfinderProxy.getLocation();
        Location currentLoc = fakePlayer.getLocation();

        if (proxyLoc == null || currentLoc == null) return;

        // Calculate interpolated position for smooth movement
        double newX = lerp(currentLoc.getX(), proxyLoc.getX(), interpolationFactor);
        double newY = lerp(currentLoc.getY(), proxyLoc.getY(), interpolationFactor);
        double newZ = lerp(currentLoc.getZ(), proxyLoc.getZ(), interpolationFactor);

        // Use proxy's rotation directly (looks more natural)
        float newYaw = proxyLoc.getYaw();
        float newPitch = proxyLoc.getPitch();

        Location newLoc = new Location(currentLoc.getWorld(), newX, newY, newZ, newYaw, newPitch);

        // Update fake player position
        fakePlayer.updatePosition(newLoc);

        // Track movement for stuck detection
        trackMovement(newLoc);
    }

    /**
     * Linear interpolation between two values.
     */
    private double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }

    /**
     * Track movement to detect if stuck.
     */
    private void trackMovement(Location newPosition) {
        if (lastPosition != null) {
            double moved = lastPosition.distance(newPosition);
            if (moved < 0.05) {
                stuckTicks++;
            } else {
                stuckTicks = Math.max(0, stuckTicks - 2);
            }
        }
        lastPosition = newPosition.clone();
    }

    /**
     * Check if the NPC appears stuck.
     */
    public boolean isStuck() {
        return stuckTicks > STUCK_THRESHOLD;
    }

    /**
     * Get how long the NPC has been stuck (in ticks).
     */
    public int getStuckTicks() {
        return stuckTicks;
    }

    /**
     * Reset stuck counter (call after teleporting or recovering).
     */
    public void resetStuckCounter() {
        stuckTicks = 0;
        lastPosition = null;
    }

    /**
     * Force sync to proxy position immediately (no interpolation).
     */
    public void forceSync() {
        if (!fakePlayer.isSpawned() || !pathfinderProxy.isValid()) return;

        Location proxyLoc = pathfinderProxy.getLocation();
        if (proxyLoc != null) {
            fakePlayer.updatePosition(proxyLoc);
            lastPosition = proxyLoc.clone();
            stuckTicks = 0;
        }
    }

    /**
     * Set interpolation factor.
     * @param factor Value between 0.0 (no movement) and 1.0 (instant teleport)
     */
    public void setInterpolationFactor(float factor) {
        this.interpolationFactor = Math.max(0.0f, Math.min(1.0f, factor));
    }

    /**
     * Set sync interval in ticks.
     */
    public void setSyncInterval(int ticks) {
        this.syncInterval = Math.max(1, ticks);

        // Restart if running
        if (running) {
            stop();
            start();
        }
    }

    /**
     * Get current interpolation factor.
     */
    public float getInterpolationFactor() {
        return interpolationFactor;
    }

    /**
     * Check if controller is running.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Get distance between fake player and proxy.
     */
    public double getDesyncDistance() {
        if (!fakePlayer.isSpawned() || !pathfinderProxy.isValid()) return 0;

        Location fakePlayerLoc = fakePlayer.getLocation();
        Location proxyLoc = pathfinderProxy.getLocation();

        if (fakePlayerLoc == null || proxyLoc == null) return 0;

        return fakePlayerLoc.distance(proxyLoc);
    }
}
