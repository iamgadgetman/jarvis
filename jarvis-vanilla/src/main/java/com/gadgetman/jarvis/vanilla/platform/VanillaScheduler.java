package com.gadgetman.jarvis.vanilla.platform;

import com.gadgetman.jarvis.core.platform.Log;
import com.gadgetman.jarvis.core.platform.Scheduler;
import com.gadgetman.jarvis.core.platform.Task;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Core's {@link Scheduler} over the server tick. Timed work is run from the
 * end-of-tick hook the mod registers; async work goes to a small pool and
 * comes back through {@link MinecraftServer#execute}.
 */
public final class VanillaScheduler implements Scheduler {

    private static final class Scheduled implements Task {
        final Consumer<Task> body;
        final long period;
        long due;
        volatile boolean cancelled;

        Scheduled(Consumer<Task> body, long due, long period) {
            this.body = body;
            this.due = due;
            this.period = period;
        }

        @Override public void cancel() { cancelled = true; }
        @Override public boolean isCancelled() { return cancelled; }
    }

    private final MinecraftServer server;
    private final Log log;
    private final List<Scheduled> tasks = new CopyOnWriteArrayList<>();
    private final ExecutorService pool;
    private long tick;

    public VanillaScheduler(MinecraftServer server, Log log) {
        this.server = server;
        this.log = log;
        AtomicInteger n = new AtomicInteger();
        this.pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "jarvis-async-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** Once per server tick, on the server thread. */
    public void tick() {
        tick++;
        for (Scheduled s : tasks) {
            if (s.cancelled) {
                tasks.remove(s);
                continue;
            }
            if (tick < s.due) continue;
            try {
                s.body.accept(s);
            } catch (Exception e) {
                log.error("Scheduled task failed", e);
            }
            if (s.period > 0 && !s.cancelled) {
                s.due = tick + s.period;
            } else {
                tasks.remove(s);
            }
        }
    }

    @Override
    public Task every(long delayTicks, long periodTicks, Consumer<Task> body) {
        Scheduled s = new Scheduled(body, tick + Math.max(1, delayTicks), Math.max(1, periodTicks));
        tasks.add(s);
        return s;
    }

    @Override
    public Task later(long delayTicks, Runnable body) {
        Scheduled s = new Scheduled(t -> body.run(), tick + Math.max(1, delayTicks), 0);
        tasks.add(s);
        return s;
    }

    @Override
    public void sync(Runnable body) {
        if (server.isSameThread()) {
            body.run();
        } else {
            server.execute(body);
        }
    }

    @Override
    public void async(Runnable body) {
        pool.execute(() -> {
            try {
                body.run();
            } catch (Exception e) {
                log.error("Async task failed", e);
            }
        });
    }

    @Override
    public boolean isServerThread() {
        return server.isSameThread();
    }

    public void shutdown() {
        tasks.clear();
        pool.shutdownNow();
    }
}
