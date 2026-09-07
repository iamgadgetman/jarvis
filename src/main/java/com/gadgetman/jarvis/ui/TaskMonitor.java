package com.gadgetman.jarvis.ui;

import com.gadgetman.jarvis.Jarvis;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TaskMonitor — what Jarvis is doing, and what he'll do next.
 *
 * <p>Two features that want the same heartbeat:
 *
 * <ul>
 *   <li>A boss bar showing the current task, so a long job reports itself
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

    private final Jarvis plugin;
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<String>> queues = new ConcurrentHashMap<>();
    private final Map<UUID, Long> idleSince = new ConcurrentHashMap<>();

    private final boolean barEnabled;
    private final boolean queueEnabled;
    private BukkitRunnable heartbeat;

    public TaskMonitor(Jarvis plugin) {
        this.plugin       = plugin;
        this.barEnabled   = plugin.getConfig().getBoolean("ui.task-bar", true);
        this.queueEnabled = plugin.getConfig().getBoolean("ui.task-queue", true);
    }

    public void start() {
        if (!barEnabled && !queueEnabled) return;
        heartbeat = new BukkitRunnable() {
            @Override public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    tick(player);
                }
            }
        };
        heartbeat.runTaskTimer(plugin, 20L, 10L);   // twice a second
    }

    public void shutdown() {
        if (heartbeat != null) {
            heartbeat.cancel();
            heartbeat = null;
        }
        bars.values().forEach(BossBar::removeAll);
        bars.clear();
        queues.clear();
    }

    // ==================== HEARTBEAT ====================

    private void tick(Player player) {
        UUID id = player.getUniqueId();
        var npc = plugin.getJarvisNPC();
        String task = npc.getNPCForPlayer(id) == null ? null : npc.describeCurrentTask(id);

        if (barEnabled) updateBar(player, task);
        if (queueEnabled) advanceQueue(player, task);
    }

    private void updateBar(Player player, String task) {
        UUID id = player.getUniqueId();

        if (task == null) {
            BossBar bar = bars.remove(id);
            if (bar != null) bar.removeAll();
            return;
        }

        int queued = queueSize(id);
        String title = ChatColor.AQUA + task
                + (queued > 0 ? ChatColor.GRAY + "  (+" + queued + " queued)" : "");

        double fill = lootFullness(player);
        if (fill >= 0) {
            title += ChatColor.DARK_GRAY + "  ·  " + ChatColor.WHITE
                    + Math.round(fill * 100) + "% full";
        }

        final String barTitle = title;
        BossBar bar = bars.computeIfAbsent(id, k -> {
            BossBar b = Bukkit.createBossBar(barTitle, BarColor.BLUE, BarStyle.SEGMENTED_10);
            b.addPlayer(player);
            return b;
        });
        bar.setTitle(barTitle);
        bar.setProgress(fill < 0 ? 1.0 : Math.max(0, Math.min(1, fill)));
        // Turns amber as his pockets fill, so "he's about to head back" is
        // something you see rather than something you get told.
        bar.setColor(fill >= 0.9 ? BarColor.YELLOW : BarColor.BLUE);
    }

    /** @return 0..1 how full Jarvis's inventory is, or -1 if unknown */
    private double lootFullness(Player player) {
        try {
            NPC npc = plugin.getJarvisNPC().getNPCForPlayer(player.getUniqueId());
            if (npc == null) return -1;
            int used = plugin.getJarvisNPC().lootSlotsUsedPublic(npc);
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

    public void enqueue(Player player, String command) {
        queueFor(player.getUniqueId()).addLast(command);
        player.sendMessage(ChatColor.AQUA + "Jarvis: " + ChatColor.WHITE
                + "Noted — that's " + queueSize(player.getUniqueId()) + " in hand, sir.");
    }

    public void clearQueue(Player player) {
        queueFor(player.getUniqueId()).clear();
        player.sendMessage(ChatColor.GRAY + "Jarvis: Consider the list torn up, sir.");
    }

    /**
     * Start the next order once the current one is done.
     *
     * <p>A task must look finished for two consecutive checks before the queue
     * moves on. Some tasks pass briefly through an idle-looking state while
     * they re-target — one tick of "no task" is not the same as being done,
     * and without this the queue would eat its whole list in a second.
     */
    private void advanceQueue(Player player, String task) {
        UUID id = player.getUniqueId();
        Deque<String> queue = queues.get(id);
        if (queue == null || queue.isEmpty()) {
            idleSince.remove(id);
            return;
        }

        if (task != null) {
            idleSince.remove(id);
            return;
        }
        if (plugin.getJarvisNPC().getNPCForPlayer(id) == null) return;   // not summoned; hold the list

        long now = System.currentTimeMillis();
        Long since = idleSince.putIfAbsent(id, now);
        if (since == null) return;                       // first idle sighting
        if (now - since < 750) return;                   // let it settle

        idleSince.remove(id);
        String next = queue.pollFirst();
        if (next == null) return;

        player.sendMessage(ChatColor.AQUA + "Jarvis: " + ChatColor.WHITE + "Next: " + next);
        player.performCommand(next);
    }
}
