package com.gadgetman.jarvis.platform;

import com.gadgetman.jarvis.memory.SituationSnapshot;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Reads the facts a {@link SituationSnapshot} needs from a Bukkit location.
 *
 * <p>Every getter here touches the world, so {@link #capture} MUST run on the
 * main thread. Callers capture before going async and carry the JSON across.
 */
public final class PaperSituation {

    private PaperSituation() {}

    /**
     * Capture the situation as a compact JSON string. Main thread only.
     *
     * @return JSON, or {@code null} if the location is unusable
     */
    public static String capture(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        World world = loc.getWorld();
        try {
            return SituationSnapshot.capture(
                    world.getEnvironment().name(),
                    loc.getBlockY(),
                    biomeName(loc),
                    isUnderground(loc),
                    world.getTime());
        } catch (Exception e) {
            // A snapshot is a nice-to-have; never let it break a build.
            return null;
        }
    }

    private static String biomeName(Location loc) {
        try {
            return loc.getBlock().getBiome().getKey().getKey();
        } catch (Throwable t) {
            // Biome moved from enum to registry interface across versions;
            // the string form is good enough for a similarity score.
            return String.valueOf(loc.getBlock().getBiome());
        }
    }

    private static boolean isUnderground(Location loc) {
        try {
            return loc.getWorld().getHighestBlockYAt(loc) > loc.getBlockY() + 2;
        } catch (Exception e) {
            return false;
        }
    }
}
