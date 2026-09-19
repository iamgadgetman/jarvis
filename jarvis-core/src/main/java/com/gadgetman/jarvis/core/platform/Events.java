package com.gadgetman.jarvis.core.platform;

import com.gadgetman.jarvis.core.platform.events.Event;

import java.util.function.Consumer;

/**
 * The server's events, as core cares about them.
 *
 * <p>Handlers run on the server thread except where an event type says
 * otherwise ({@link com.gadgetman.jarvis.core.platform.events.ChatEvent} may
 * arrive async on Paper).
 */
public interface Events {

    <E extends Event> Subscription on(Class<E> type, Consumer<E> handler);
}
