package com.gadgetman.jarvis.core.platform;

import com.gadgetman.jarvis.core.world.Item;

import java.util.List;

/** A chest, barrel or similar block with an inventory. */
public interface Container {

    List<Item> contents();

    /** Add what fits. Returns what did not. */
    List<Item> add(List<Item> items);

    int freeSlots();

    int size();
}
