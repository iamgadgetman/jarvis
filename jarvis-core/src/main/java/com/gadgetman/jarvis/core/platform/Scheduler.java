package com.gadgetman.jarvis.core.platform;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The server's tick scheduler, as core needs it.
 *
 * <p>Times are in ticks, twenty to the second. {@link #async} runs off the
 * server thread and must not touch the world; come back with {@link #sync}.
 */
public interface Scheduler {

    /** Run repeatedly. The body receives its own task so it can cancel itself. */
    Task every(long delayTicks, long periodTicks, Consumer<Task> body);

    Task later(long delayTicks, Runnable body);

    /** Run on the server thread, on the next tick if called from elsewhere. */
    void sync(Runnable body);

    /** Run off the server thread. */
    void async(Runnable body);

    /** Do work off the server thread, then hand its result back on it. */
    default <T> void async(Supplier<T> work, Consumer<T> thenOnServerThread) {
        async(() -> {
            T result = work.get();
            sync(() -> thenOnServerThread.accept(result));
        });
    }

    boolean isServerThread();
}
