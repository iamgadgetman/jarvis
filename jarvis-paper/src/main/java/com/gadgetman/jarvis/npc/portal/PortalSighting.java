package com.gadgetman.jarvis.npc.portal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A portal Jarvis has seen, and the rules for keeping a useful list of them.
 *
 * <p>A portal is not one block. A modest frame has six blocks of purple in it
 * and a large one has hundreds, and a scan finds every one of them — so the
 * first thing a memory of portals needs is to know that all of those are the
 * same portal. Sightings inside {@link #MERGE_RADIUS} of a known one update it
 * rather than joining it.
 *
 * <p>The second thing it needs is a bound. This list grows every time he walks
 * past something, forever, and it is written to disk; without a cap it is a
 * slow leak in a file that is read at every startup. Oldest goes first once the
 * list is full, on the reasoning that the portal you used this afternoon
 * matters more than one you glanced at last week.
 *
 * <p>Both rules are pure functions over coordinates, so they can be checked
 * without a world.
 */
public record PortalSighting(String world, int x, int y, int z, long seenAt) {

    /** Two hits this close are the same structure, not two portals. */
    public static final double MERGE_RADIUS = 8.0;

    /** How many he keeps per player before the oldest is forgotten. */
    public static final int DEFAULT_LIMIT = 12;

    public double distanceTo(int ox, int oy, int oz) {
        double dx = x - ox, dy = y - oy, dz = z - oz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Same world, and close enough to be part of the same frame. */
    public boolean sameStructureAs(PortalSighting other, double mergeRadius) {
        return world.equals(other.world())
                && distanceTo(other.x(), other.y(), other.z()) <= mergeRadius;
    }

    /**
     * Fold a new sighting into a list, merging with anything it belongs to and
     * dropping the oldest if that would take the list over the limit.
     *
     * @return a new list; the input is not modified
     */
    public static List<PortalSighting> remember(List<PortalSighting> known, PortalSighting seen,
                                                int limit, double mergeRadius) {
        List<PortalSighting> next = new ArrayList<>();
        boolean merged = false;
        for (PortalSighting existing : known) {
            if (!merged && existing.sameStructureAs(seen, mergeRadius)) {
                // Keep the position already on record — a frame's blocks all
                // qualify, and letting each new one nudge the coordinates makes
                // a remembered portal wander. Only the timestamp is refreshed.
                next.add(new PortalSighting(existing.world(), existing.x(), existing.y(),
                        existing.z(), Math.max(existing.seenAt(), seen.seenAt())));
                merged = true;
            } else {
                next.add(existing);
            }
        }
        if (!merged) next.add(seen);

        if (next.size() > limit) {
            next.sort(Comparator.comparingLong(PortalSighting::seenAt));
            next = new ArrayList<>(next.subList(next.size() - limit, next.size()));
        }
        return next;
    }

    /** True when this sighting would be a new portal rather than a known one. */
    public static boolean isNew(List<PortalSighting> known, PortalSighting seen, double mergeRadius) {
        for (PortalSighting existing : known) {
            if (existing.sameStructureAs(seen, mergeRadius)) return false;
        }
        return true;
    }

    /** Nearest known sighting in this world, or {@code null} if he knows none. */
    public static PortalSighting nearest(List<PortalSighting> known, String world,
                                         int x, int y, int z) {
        PortalSighting best = null;
        double bestDist = Double.MAX_VALUE;
        for (PortalSighting sighting : known) {
            if (!sighting.world().equals(world)) continue;
            double dist = sighting.distanceTo(x, y, z);
            if (dist < bestDist) {
                bestDist = dist;
                best = sighting;
            }
        }
        return best;
    }
}
