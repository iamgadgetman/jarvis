package com.gadgetman.jarvis.core.platform.events;

import com.gadgetman.jarvis.core.platform.Owner;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Someone right-clicked a butler. {@code ownerId} is whose butler; the
 * clicker may be someone else.
 *
 * @param cancel call with true to stop the platform's own handling
 */
public record ButlerInteractEvent(UUID ownerId, Owner clicker, boolean sneaking, Consumer<Boolean> cancel)
        implements Event { }
