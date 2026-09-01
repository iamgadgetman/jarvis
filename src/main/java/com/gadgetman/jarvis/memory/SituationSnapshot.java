package com.gadgetman.jarvis.memory;

import org.bukkit.Location;
import org.bukkit.World;
import org.json.JSONObject;

/**
 * Ground-truth world state at the moment a build was requested.
 *
 * "Build me a house" underground at y=12 needs a different plan than the same
 * words on a plains surface, so a memory keyed on the request text alone
 * retrieves the wrong examples. Retrieval matches request similarity first,
 * then re-ranks on this.
 *
 * Every getter here touches the world, so {@link #capture} MUST run on the main
 * thread. Callers capture before going async and carry the JSON across.
 */
public final class SituationSnapshot {

    private SituationSnapshot() {}

    /**
     * Capture the situation as a compact JSON string. Main thread only.
     *
     * @return JSON, or {@code null} if the location is unusable
     */
    public static String capture(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;

        World world = loc.getWorld();
        JSONObject json = new JSONObject();

        try {
            json.put("dimension", world.getEnvironment().name());
            json.put("y", loc.getBlockY());
            json.put("biome", biomeName(loc));
            json.put("underground", isUnderground(loc));
            json.put("time", world.getTime() < 12300 ? "day" : "night");
        } catch (Exception e) {
            // A snapshot is a nice-to-have; never let it break a build.
            return null;
        }

        return json.toString();
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

    /**
     * Similarity between two captured situations, 0.0–1.0.
     *
     * Weighted the way it actually matters to a build plan: the dimension and
     * whether you are enclosed dominate, the biome is a tiebreaker. Y-level is
     * deliberately not scored directly — "underground" already carries it, and
     * a raw y delta would swamp the signal.
     *
     * Malformed or missing JSON scores a neutral 0.5 rather than throwing, so a
     * pre-situation row still ranks on its request text.
     */
    public static double similarity(String situationA, String situationB) {
        if (situationA == null || situationB == null) return 0.5;

        JSONObject a;
        JSONObject b;
        try {
            a = new JSONObject(situationA);
            b = new JSONObject(situationB);
        } catch (Exception e) {
            return 0.5;
        }

        double score = 0.0;

        String dimA = a.optString("dimension", "");
        String dimB = b.optString("dimension", "");
        if (!dimA.isEmpty() && dimA.equals(dimB)) score += 0.4;

        if (a.has("underground") && b.has("underground")
                && a.optBoolean("underground") == b.optBoolean("underground")) {
            score += 0.4;
        }

        String biomeA = a.optString("biome", "");
        String biomeB = b.optString("biome", "");
        if (!biomeA.isEmpty() && biomeA.equals(biomeB)) score += 0.2;

        return score;
    }

    /** Short human-readable form for prompt injection. */
    public static String describe(String situationJson) {
        if (situationJson == null) return "unknown";
        try {
            JSONObject s = new JSONObject(situationJson);
            return s.optString("dimension", "?").toLowerCase()
                    + ", " + s.optString("biome", "?")
                    + ", y=" + s.optInt("y", 0)
                    + (s.optBoolean("underground") ? ", underground" : ", surface");
        } catch (Exception e) {
            return "unknown";
        }
    }
}
