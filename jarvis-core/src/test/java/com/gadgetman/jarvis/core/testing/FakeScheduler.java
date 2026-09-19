package com.gadgetman.jarvis.core.testing;

import com.gadgetman.jarvis.core.platform.Scheduler;
import com.gadgetman.jarvis.core.platform.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A scheduler the test drives by hand. Nothing runs until {@link #tick} is
 * called; {@code sync} and {@code async} run inline, because there is only
 * one thread and it is the test's.
 */
public final class FakeScheduler implements Scheduler {

    private final class Job implements Task {
        final Consumer<Task> body;
        final long period;       // 0 for once
        long due;
        boolean cancelled;

        Job(Consumer<Task> body, long delay, long period) {
            this.body = body;
            this.due = now + Math.max(0, delay);
            this.period = period;
        }

        @Override public void cancel() { cancelled = true; }
        @Override public boolean isCancelled() { return cancelled; }
    }

    private long now = 0;
    private final List<Job> jobs = new ArrayList<>();

    @Override
    public Task every(long delayTicks, long periodTicks, Consumer<Task> body) {
        Job job = new Job(body, delayTicks, Math.max(1, periodTicks));
        jobs.add(job);
        return job;
    }

    @Override
    public Task later(long delayTicks, Runnable body) {
        Job job = new Job(t -> body.run(), delayTicks, 0);
        jobs.add(job);
        return job;
    }

    @Override public void sync(Runnable body) { body.run(); }
    @Override public void async(Runnable body) { body.run(); }
    @Override public boolean isServerThread() { return true; }

    public long now() {
        return now;
    }

    /** Advance one tick, running everything that is due. */
    public void tick() {
        now++;
        for (Job job : new ArrayList<>(jobs)) {
            if (job.cancelled || job.due > now) continue;
            job.body.accept(job);
            if (job.period == 0) job.cancelled = true;
            else job.due = now + job.period;
        }
        jobs.removeIf(j -> j.cancelled);
    }

    public void tick(int ticks) {
        for (int i = 0; i < ticks; i++) tick();
    }

    /** Live tasks, for asserting that something stopped. */
    public int pending() {
        return (int) jobs.stream().filter(j -> !j.cancelled).count();
    }
}
