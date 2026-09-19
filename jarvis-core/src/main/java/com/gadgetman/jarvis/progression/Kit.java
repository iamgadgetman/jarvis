package com.gadgetman.jarvis.progression;

import com.gadgetman.jarvis.core.world.Item;

/**
 * What marks a tool as issued to the butler rather than found or handed
 * to him. Issued gear scales with the owner's standing and is not theirs
 * to take: adapters lock it in his inventory screen, and a dismissal
 * hands over everything but it.
 */
public final class Kit {

    private Kit() {
    }

    /** The {@link Item#marker()} on issued gear. */
    public static final String MARKER = "kit";

    /** True for issued gear, by its mark, or by its name for gear issued before marks existed. */
    public static boolean isIssued(Item item) {
        if (item == null || item.isEmpty()) return false;
        if (MARKER.equals(item.marker())) return true;
        return item.displayName() != null && item.displayName().contains("Jarvis's");
    }
}
