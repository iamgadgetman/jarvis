package com.gadgetman.jarvis.ui;

import com.gadgetman.jarvis.JarvisCore;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Platform;
import com.gadgetman.jarvis.core.platform.Task;
import com.gadgetman.jarvis.core.text.Colors;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What Jarvis is doing, and what he'll do next.
 *
 * <p>Two features that want the same heartbeat:
 *
 * <ul>
 *   <li>A progress bar showing the current task, so a long job reports itself
 *       without filling the chat box. The bar's fill is how full his pockets
 *       are, which is the number that actually matters mid-mine — he turns for
 *       home at {@code mining.auto-return-threshold}.</li>
 *   <li>A queue, so orders can be lined up instead of issued one at a time
 *       while you stand and wait.</li>
 * </ul>
 *
 * <p>Both work by watching {@code describeCurrentTask()} rather than by
 * hooking task completion. Nothing in the task classes has to call back, which
 * means none of them had to change and none can forget to. The cost is that
 * the queue advances within a tick or two of a task ending rather than
 * instantly — imperceptible, and worth it for not threading a completion
 * contract through ten task types.
 */
public class TaskMonitor {

    private final JarvisCore core;
    private final Platform platform;
    private final Map<UUID, Deque<String>> queues = new ConcurrentHashMap<>();
    private final Map<UUID, Long> idleSince = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> showing = new ConcurrentHashMap<>();

    private final boolean barEnabled;
    private final boolean queueEnabled;
    private Task heartbeat;

    public TaskMonitor(JarvisCore core) {
        this.core = core;
        this.platform = core.platform();
        this.barEnabled   = platform.config().getBoolean("ui.task-bar", true);
        this.queueEnabled = platform.config().getBoolean("ui.task-queue", true);
    }

    public void start() {
        if (!barEnabled && !queueEnabled) return;
        heartbeat = platform.scheduler().every(20L, 10L, self -> {   // twice a second
            for (Owner player : platform.players().online()) {
                tick(player);
            }
        });
    }

    public void shutdown() {
        if (heartbeat != null) {
            heartbeat.cancel();
            heartbeat = null;
        }
        for (UUID id : showing.keySet()) {
            platform.players().byId(id).ifPresent(platform.ui()::hideProgressBar);
        }
        showing.clear();
        queues.clear();
    }

    // ==================== HEARTBEAT ====================

    private void tick(Owner player) {
        UUID id = player.id();
        var npc = core.butlers();
        String task = npc.exists(player) ? npc.describeCurrentTask(id) : null;

        if (barEnabled) updateBar(player, task);
        if (queueEnabled) advanceQueue(player, task);
    }

    private void updateBar(Owner player, String task) {
        UUID id = player.id();

        if (task == null) {
            if (showing.remove(id) != null) platform.ui().hideProgressBar(player);
            return;
        }

        int queued = queueSize(id);
        String title = Colors.AQUA + task
                + (queued > 0 ? Colors.GRAY + "  (+" + queued + " queued)" : "");

        double fill = lootFullness(player);
        if (fill >= 0) {
            title += Colors.DARK_GRAY + "  ·  " + Colors.WHITE + Math.round(fill * 100) + "% full";
        }

        showing.put(id, Boolean.TRUE);
        // Turns amber as his pockets fill, so "he's about to head back" is
        // something you see rather than something you get told.
        platform.ui().progressBar(player, title,
                fill < 0 ? 1.0 : Math.max(0, Math.min(1, fill)), fill >= 0.9);
    }

    /** @return 0..1 how full Jarvis's inventory is, or -1 if unknown */
    private double lootFullness(Owner player) {
        try {
            if (!core.butlers().exists(player)) return -1;
            int used = core.butlers().lootSlotsUsed(player);
            return used / 27.0;
        } catch (Exception e) {
            return -1;
        }
    }

    // ==================== QUEUE ====================

    private Deque<String> queueFor(UUID id) {
        return queues.computeIfAbsent(id, k -> new ArrayDeque<>());
    }

    public int queueSize(UUID id) {
        Deque<String> q = queues.get(id);
        return q == null ? 0 : q.size();
    }

    public List<String> queued(UUID id) {
        return new ArrayList<>(queueFor(id));
    }

    /** Line up "/jarvis {@code order}" for when he is free. */
    public void enqueue(Owner player, String order) {
        queueFor(player.id()).addLast(order);
        player.message(Colors.AQUA + "Jarvis: " + Colors.WHITE
                + "Noted — that's " + queueSize(player.id()) + " in hand, sir.");
    }

    public void clearQueue(Owner player) {
        queueFor(player.id()).clear();
        player.message(Colors.GRAY + "Jarvis: Consider the list torn up, sir.");
    }

    /**
     * Start the next order once the current one is done.
     *
     * <p>A task must look finished for two consecutive checks before the queue
     * moves on. Some tasks pass briefly through an idle-looking state while
     * they re-target — one tick of "no task" is not the same as being done,
     * and without this the queue would eat its whole list in a second.
     */
    private void advanceQueue(Owner player, String task) {
        UUID id = player.id();
        Deque<String> queue = queues.get(id);
        if (queue == null || queue.isEmpty()) {
            idleSince.remove(id);
            return;
        }

        if (task != null) {
            idleSince.remove(id);
            return;
        }
        if (!core.butlers().exists(player)) return;   // not summoned; hold the list

        long now = System.currentTimeMillis();
        Long since = idleSince.putIfAbsent(id, now);
        if (since == null) return;                       // first idle sighting
        if (now - since < 750) return;                   // let it settle

        idleSince.remove(id);
        String next = queue.pollFirst();
        if (next == null) return;

        player.message(Colors.AQUA + "Jarvis: " + Colors.WHITE + "Next: /jarvis " + next);
        core.commands().jarvis(player, Optional.of(player),
                Arrays.asList(next.trim().split("\\s+")), false);
    }
}
