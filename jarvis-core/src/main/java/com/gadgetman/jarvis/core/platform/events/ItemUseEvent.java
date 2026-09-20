package com.gadgetman.jarvis.core.platform.events;

import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.world.Item;

import java.util.function.Consumer;

/**
 * A player right-clicked with a marked item in hand, or on a placed block
 * that carries one's mark. Only items with a {@link Item#marker()} arrive
 * here; the controller bell is the one that matters.
 *
 * @param cancel call with true to stop the item's own use
 */
public record ItemUseEvent(Owner who, Item item, Consumer<Boolean> cancel) implements Event { }
