package com.gadgetman.jarvis.npc;

/**
 * Compass headings for the tunnel command.
 *
 * <p>Only the four cardinals are dug. A 3x3 bore on a diagonal does not tile:
 * consecutive steps meet at a corner rather than a face, so the walls come out
 * with gaps in them. Rather than refuse an intercardinal outright, anything
 * off-axis is rounded to the nearest cardinal and the caller is told which —
 * "sse" is a perfectly clear intention, and south is what it should dig.
 *
 * <p>Minecraft's axes: +Z is south, -Z north, +X east, -X west.
 */
final class Compass {

    private Compass() { }

    /** Parsed heading plus whether the request had to be rounded onto an axis. */
    record Heading(int dx, int dz, String requested, boolean rounded) {
        String name() { return Compass.name(dx, dz); }
    }

    /** @return the heading, or null if the text names no direction at all */
    static Heading parse(String text) {
        if (text == null) return null;
        String key = text.trim().toLowerCase().replaceAll("[^a-z]", "");
        if (key.isEmpty()) return null;

        Double bearing = bearingOf(key);
        if (bearing == null) return null;

        // Snap to the nearest quarter turn.
        int quarter = (int) Math.round(bearing / 90.0) % 4;
        boolean rounded = Math.abs(bearing - quarter * 90.0) > 0.01
                       && Math.abs(bearing - quarter * 90.0) < 359.99;

        return switch (quarter) {
            case 0  -> new Heading(0, -1, key, rounded);   // north
            case 1  -> new Heading(1, 0, key, rounded);    // east
            case 2  -> new Heading(0, 1, key, rounded);    // south
            default -> new Heading(-1, 0, key, rounded);   // west
        };
    }

    /** Compass bearing in degrees for a name, or null if unrecognised. */
    private static Double bearingOf(String key) {
        return switch (key) {
            case "n", "north"                     -> 0.0;
            case "nne", "northnortheast"          -> 22.5;
            case "ne", "northeast"                -> 45.0;
            case "ene", "eastnortheast"           -> 67.5;
            case "e", "east"                      -> 90.0;
            case "ese", "eastsoutheast"           -> 112.5;
            case "se", "southeast"                -> 135.0;
            case "sse", "southsoutheast"          -> 157.5;
            case "s", "south"                     -> 180.0;
            case "ssw", "southsouthwest"          -> 202.5;
            case "sw", "southwest"                -> 225.0;
            case "wsw", "westsouthwest"           -> 247.5;
            case "w", "west"                      -> 270.0;
            case "wnw", "westnorthwest"           -> 292.5;
            case "nw", "northwest"                -> 315.0;
            case "nnw", "northnorthwest"          -> 337.5;
            default                               -> null;
        };
    }

    static String name(int dx, int dz) {
        if (dz < 0) return "north";
        if (dz > 0) return "south";
        if (dx > 0) return "east";
        if (dx < 0) return "west";
        return "nowhere";
    }
}
