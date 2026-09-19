package com.gadgetman.jarvis.memory;

import org.json.JSONObject;

/**
 * Ground-truth world state at the moment a build was requested.
 *
 * "Build me a house" underground at y=12 needs a different plan than the same
 * words on a plains surface, so a memory keyed on the request text alone
 * retrieves the wrong examples. Retrieval matches request similarity first,
 * then re-ranks on this.
 *
 * <p>Reading the world is the adapter's job (on Paper, {@code PaperSituation}
 * does it on the main thread); this class only builds, compares and describes
 * the JSON, so it can run anywhere.
 */
public final class SituationSnapshot {

    private SituationSnapshot() {}

    /**
     * Build a snapshot from facts the adapter has already read.
     *
     * @param dimension the world's environment name, as the adapter reports it
     * @param y         block height of the request
     * @param biome     biome key, for a tiebreak on similarity
     * @param underground whether the spot is enclosed under terrain
     * @param time      world time in ticks, for day or night
     * @return JSON, never null
     */
    public static String capture(String dimension, int y, String biome, boolean underground, long time) {
        JSONObject json = new JSONObject();
        json.put("dimension", dimension);
        json.put("y", y);
        json.put("biome", biome);
        json.put("underground", underground);
        json.put("time", time < 12300 ? "day" : "night");
        return json.toString();
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
