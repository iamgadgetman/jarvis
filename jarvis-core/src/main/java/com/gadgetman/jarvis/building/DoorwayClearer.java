package com.gadgetman.jarvis.building;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps every door in a planned build walkable.
 *
 * <p>A model decorating a room does not check whether its decorations land in
 * a doorway. A live "cozy fish market" hung a potted fern on a fence post at
 * chest height directly in front of the back-room door, which left the door
 * impossible to walk through. Nothing reported it; the player found it.
 *
 * <p>So after a plan is drawn, the space a person stands in on each side of
 * every door -- the block in front and behind, at feet and head height -- is
 * cleared of anything they would bump into. Only furnishings are removed.
 * Building blocks (planks, bricks, logs, glass and the like), stairs and floor
 * slabs are left alone: removing those could open a hole in a wall or knock
 * out a staircase, which is worse than a door that needs a second look.
 */
public final class DoorwayClearer {

    private static final Pattern FACING = Pattern.compile("facing=([a-z]+)");

    private DoorwayClearer() { }

    /**
     * Clears obstructions around every door, in place.
     *
     * @param blocks planned blocks keyed by {@code "x,y,z"}, values are block specs
     * @return one line per block removed, e.g. {@code "potted_fern at 7,1,6"}
     */
    public static List<String> clear(Map<String, String> blocks) {
        List<String> cleared = new ArrayList<>();
        // Copy the door positions first: clearing writes to the map.
        List<int[]> doors = new ArrayList<>();
        for (Map.Entry<String, String> e : blocks.entrySet()) {
            String spec = e.getValue().toLowerCase(Locale.ROOT);
            if (!base(spec).endsWith("_door")) continue;
            if (spec.contains("half=upper")) continue; // one check per door, from the lower half
            int[] dir = horizontalFacing(spec);
            if (dir == null) continue;
            String[] p = e.getKey().split(",");
            doors.add(new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), dir[0], dir[1]});
        }
        for (int[] d : doors) {
            for (int side : new int[]{1, -1}) {
                int x = d[0] + d[3] * side, z = d[2] + d[4] * side;
                for (int dy = 0; dy <= 1; dy++) {
                    String key = x + "," + (d[1] + dy) + "," + z;
                    String spec = blocks.get(key);
                    if (spec != null && obstructs(spec, dy)) {
                        blocks.put(key, "air");
                        cleared.add(base(spec) + " at " + key);
                    }
                }
            }
        }
        return cleared;
    }

    /** True for a furnishing that would block someone walking through. */
    static boolean obstructs(String spec, int heightAboveFloor) {
        String s = spec.toLowerCase(Locale.ROOT);
        String b = base(s);
        if (passable(b)) return false;
        if (structural(b)) return false;
        if (b.endsWith("_stairs")) return false;
        // A bottom slab at foot level is a step, not a wall.
        if (b.endsWith("_slab") && heightAboveFloor == 0 && !s.contains("type=top") && !s.contains("type=double")) {
            return false;
        }
        return true;
    }

    static boolean passable(String b) {
        return b.equals("air") || b.equals("cave_air")
                || b.endsWith("_carpet") || b.endsWith("pressure_plate")
                || b.equals("torch") || b.endsWith("_torch")
                || b.endsWith("_sign") || b.endsWith("_button") || b.equals("lever")
                || b.equals("vine") || b.equals("hanging_roots") || b.equals("glow_lichen")
                || b.endsWith("rail") || b.equals("short_grass") || b.equals("snow")
                || b.endsWith("_door"); // a second door is someone else's doorway
    }

    static boolean structural(String b) {
        return b.endsWith("_planks") || b.endsWith("bricks") || b.endsWith("_log") || b.endsWith("_wood")
                || b.endsWith("stone") || b.endsWith("cobblestone") || b.startsWith("deepslate")
                || b.endsWith("_concrete") || b.endsWith("terracotta") || b.endsWith("glass")
                || b.endsWith("glass_pane") || b.endsWith("_wall") || b.endsWith("_wool")
                || b.equals("dirt") || b.equals("sand") || b.equals("gravel") || b.endsWith("quartz_block")
                || b.equals("mud") || b.endsWith("_tiles") || b.endsWith("_pillar");
    }

    /** The x/z step of a door's horizontal facing, or null. */
    private static int[] horizontalFacing(String spec) {
        Matcher m = FACING.matcher(spec);
        if (!m.find()) return null;
        return switch (m.group(1)) {
            case "north", "south" -> new int[]{0, 1};
            case "east", "west" -> new int[]{1, 0};
            default -> null;
        };
    }

    static String base(String spec) {
        String b = spec.trim().toLowerCase(Locale.ROOT);
        int br = b.indexOf('[');
        if (br >= 0) b = b.substring(0, br);
        return b.startsWith("minecraft:") ? b.substring(10) : b;
    }
}
