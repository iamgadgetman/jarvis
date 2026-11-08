package com.gadgetman.jarvis.building;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Keeps hanging decorations above head height.
 *
 * <p>A live "cozy fish market" hung two blocks of hanging roots under every
 * azalea, so the roots reached chest height and hung in the player's face all
 * over the shop floor. The prompt asks for nothing below head height, and the
 * model ignored it. This enforces it: any hanging block whose bottom is less
 * than two blocks above the surface under it is removed. What hangs higher
 * stays, so a planter keeps its leaves and the top of its roots.
 */
public final class HangingTrimmer {

    /** How far down to look for the surface under a hanging block. */
    private static final int MAX_SCAN = 24;

    private HangingTrimmer() { }

    /**
     * Removes low hanging blocks, in place.
     *
     * @param blocks planned blocks keyed by {@code "x,y,z"}, relative to an
     *               origin at ground level (y=0 is where a player stands)
     * @return one line per block removed, e.g. {@code "hanging_roots at 3,1,4"}
     */
    public static List<String> trim(Map<String, String> blocks) {
        List<String> trimmed = new ArrayList<>();
        List<int[]> hanging = new ArrayList<>();
        for (Map.Entry<String, String> e : blocks.entrySet()) {
            if (!isHanging(e.getValue())) continue;
            String[] p = e.getKey().split(",");
            hanging.add(new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])});
        }
        for (int[] h : hanging) {
            int surface = surfaceBelow(blocks, h[0], h[1], h[2]);
            if (surface == Integer.MIN_VALUE) continue; // open drop: nobody walks there
            // 0 = at feet height, 1 = at chest/head height. 2 and up clears a player.
            if (h[1] - surface - 1 <= 1) {
                String key = h[0] + "," + h[1] + "," + h[2];
                trimmed.add(DoorwayClearer.base(blocks.get(key)) + " at " + key);
                blocks.put(key, "air");
            }
        }
        return trimmed;
    }

    /** True for a block that dangles from the one above it. */
    static boolean isHanging(String spec) {
        String s = spec.toLowerCase(Locale.ROOT);
        String b = DoorwayClearer.base(s);
        if (b.equals("hanging_roots") || b.equals("pale_hanging_moss")
                || b.startsWith("weeping_vines") || b.startsWith("cave_vines")) {
            return true;
        }
        // A chain lying on its side is a rail or a fence, not something hung.
        if (b.equals("chain") || b.endsWith("_chain")) {
            return !s.contains("axis=x") && !s.contains("axis=z");
        }
        return b.endsWith("lantern") && s.contains("hanging=true");
    }

    /** Y of the first surface under (x, y, z), or MIN_VALUE when there is none in reach. */
    private static int surfaceBelow(Map<String, String> blocks, int x, int y, int z) {
        for (int yy = y - 1; yy >= y - MAX_SCAN; yy--) {
            String spec = blocks.get(x + "," + yy + "," + z);
            if (spec == null) {
                // Anything below the origin that the plan does not touch is
                // the ground the build stands on.
                if (yy < 0) return yy;
                continue;
            }
            String b = DoorwayClearer.base(spec);
            if (b.equals("air") || b.equals("cave_air") || isHanging(spec)) continue;
            // A carpet or a pressure plate lies on the floor; the floor is under it.
            if (b.endsWith("_carpet") || b.endsWith("pressure_plate")) return yy - 1;
            return yy;
        }
        return Integer.MIN_VALUE;
    }
}
