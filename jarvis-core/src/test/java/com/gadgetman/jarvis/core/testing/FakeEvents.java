package com.gadgetman.jarvis.core.testing;

import com.gadgetman.jarvis.core.platform.Events;
import com.gadgetman.jarvis.core.platform.Subscription;
import com.gadgetman.jarvis.core.platform.events.Event;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Events the test raises itself. */
public final class FakeEvents implements Events {

    private final Map<Class<?>, List<Consumer<Object>>> handlers = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <E extends Event> Subscription on(Class<E> type, Consumer<E> handler) {
        Consumer<Object> h = o -> handler.accept((E) o);
        handlers.computeIfAbsent(type, k -> new ArrayList<>()).add(h);
        return () -> {
            List<Consumer<Object>> list = handlers.get(type);
            if (list != null) list.remove(h);
        };
    }

    public void publish(Event event) {
        List<Consumer<Object>> list = handlers.get(event.getClass());
        if (list == null) return;
        for (Consumer<Object> h : new ArrayList<>(list)) h.accept(event);
    }

    public boolean hasHandlers(Class<?> type) {
        List<Consumer<Object>> list = handlers.get(type);
        return list != null && !list.isEmpty();
    }
}
