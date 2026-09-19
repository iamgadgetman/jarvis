package com.gadgetman.jarvis.core.platform.events;

import com.gadgetman.jarvis.core.platform.Owner;

import java.util.function.Consumer;

/**
 * A player said something in chat. May arrive off the server thread.
 *
 * @param cancel call with true to keep the line out of public chat
 */
public record ChatEvent(Owner who, String text, Consumer<Boolean> cancel) implements Event { }
