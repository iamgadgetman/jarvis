package com.gadgetman.jarvis.core.platform.events;

import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Site;
import com.gadgetman.jarvis.core.world.Item;

import java.util.List;

/**
 * A player died.
 *
 * @param drops    what fell, empty when the inventory is kept
 * @param keptInventory true when the keepInventory rule held the items back
 * @param message  the death message, or null
 */
public record DeathEvent(Owner who, Site where, List<Item> drops, boolean keptInventory,
                         String message) implements Event { }
