package com.gadgetman.jarvis.core.ui;

import com.gadgetman.jarvis.core.world.Item;

import java.util.Map;

/**
 * A chest-style menu as core describes it: a title, a number of nine-slot
 * rows, an item in some of the slots, and what to do when one is clicked.
 * The adapter draws it and reports the clicks; nothing here knows how.
 *
 * @param filler the item drawn in every empty slot, or null to leave them empty
 */
public record Menu(String title, int rows, Map<Integer, MenuItem> slots, Item filler) {

    public Menu {
        slots = Map.copyOf(slots);
    }

    public int size() {
        return rows * 9;
    }

    /** The entry at a slot, or null when the slot is empty or filler. */
    public MenuItem at(int slot) {
        return slots.get(slot);
    }
}
