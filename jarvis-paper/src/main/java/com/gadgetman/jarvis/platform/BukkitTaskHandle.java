package com.gadgetman.jarvis.platform;

import com.gadgetman.jarvis.core.platform.Task;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Core's {@link Task} over a BukkitRunnable the Bukkit side still drives
 * itself. Two handles are equal when they wrap the same runnable, so a task
 * register keyed on core tasks can still be told "this runnable is done".
 */
public final class BukkitTaskHandle implements Task {

    private final BukkitRunnable runnable;

    public BukkitTaskHandle(BukkitRunnable runnable) {
        this.runnable = runnable;
    }

    public BukkitRunnable runnable() {
        return runnable;
    }

    @Override
    public void cancel() {
        if (!runnable.isCancelled()) {
            try {
                runnable.cancel();
            } catch (IllegalStateException ignored) {
                // never scheduled
            }
        }
    }

    @Override
    public boolean isCancelled() {
        return runnable.isCancelled();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BukkitTaskHandle other && other.runnable == runnable;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(runnable);
    }
}
