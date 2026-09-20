package com.gadgetman.jarvis.platform;

import com.gadgetman.jarvis.core.platform.Scheduler;
import com.gadgetman.jarvis.core.platform.Task;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.function.Consumer;

/** Core's {@link Scheduler} over the Bukkit scheduler. */
public final class PaperScheduler implements Scheduler {

    private final JavaPlugin plugin;

    public PaperScheduler(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * One handle per runnable, handed both to the body and to the caller, so
     * a task register can match "this task is done" against what it stored.
     */
    @Override
    public Task every(long delayTicks, long periodTicks, Consumer<Task> body) {
        final Task[] handle = new Task[1];
        BukkitRunnable runnable = new BukkitRunnable() {
            @Override public void run() { body.accept(handle[0]); }
        };
        handle[0] = new BukkitTaskHandle(runnable);
        runnable.runTaskTimer(plugin, delayTicks, periodTicks);
        return handle[0];
    }

    @Override
    public Task later(long delayTicks, Runnable body) {
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, body, delayTicks);
        return wrap(task);
    }

    @Override
    public void sync(Runnable body) {
        if (Bukkit.isPrimaryThread()) {
            body.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, body);
        }
    }

    @Override
    public void async(Runnable body) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, body);
    }

    @Override
    public boolean isServerThread() {
        return Bukkit.isPrimaryThread();
    }

    private static Task wrap(BukkitTask t) {
        return new Task() {
            @Override public void cancel() { t.cancel(); }
            @Override public boolean isCancelled() { return t.isCancelled(); }
        };
    }
}
