package com.gadgetman.jarvis.steward.remarks;

import com.gadgetman.jarvis.Jarvis;
import com.gadgetman.jarvis.steward.remarks.RemarkDoctrine.Remark;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Idle commentary (v0.15.0) — he notices things.
 *
 * <p>Until now he spoke when spoken to, when a task ended, and when something
 * was about to explode behind you. He never simply <i>noticed</i> anything.
 * This is a remark every few minutes while he is summoned and standing about:
 * what you are carrying, how deep you have got, what the weather is doing.
 *
 * <p>Three things shape the implementation more than the words do:
 *
 * <ul>
 *   <li><b>It costs nothing.</b> No model call, no event handlers, no storage.
 *       Everything said here comes off a getter — see {@link Observer}. This is
 *       the first thing he would do on a timer rather than on your order, and a
 *       paid call every ninety seconds per player, forever, is a standing bill
 *       for a cosmetic feature. So the lines are written, not generated.</li>
 *   <li><b>The annoyance budget is small and the failure is silent.</b> Nobody
 *       reports that the butler is tiresome; they turn him off. Hence: off by
 *       default, a generous cooldown, {@code /jarvis quiet} to mute him without
 *       touching the config, and cooldowns keyed on
 *       {@link RemarkSubject the subject} rather than the wording.</li>
 *   <li><b>He does not remark on what he cannot see.</b> An NPC forty blocks
 *       away discussing your inventory is unsettling rather than charming, so
 *       this reuses the same judgement the charm monitor makes — same world,
 *       close by — rather than inventing a second one.</li>
 * </ul>
 *
 * <p>Text only, deliberately. What is charming to read every few minutes is
 * grating to hear, and the spoken cadence would have to be far slower than the
 * written one; that is a separate decision from this one.
 */
public class Remarks {

    private final Jarvis plugin;
    private final Random random = new Random();

    /** When he last said anything at all to this player. */
    private final Map<UUID, Long> lastRemark = new ConcurrentHashMap<>();
    /** When he last said anything about a given subject, per player. */
    private final Map<UUID, Map<RemarkSubject, Long>> lastBySubject = new ConcurrentHashMap<>();
    /** Players who have told him to keep it to himself. */
    private final Set<UUID> muted = ConcurrentHashMap.newKeySet();

    private BukkitTask task;

    private boolean enabled;
    private long intervalTicks;
    private long quietMs;
    private long subjectCooldownMs;
    private double maxDistance;
    private boolean requireIdle;

    public Remarks(Jarvis plugin) {
        this.plugin = plugin;
        readConfig();
    }

    private void readConfig() {
        var config = plugin.getConfig();
        // steward.charm is the master switch for this whole family — waves,
        // greetings, idle glances and now remarks.
        this.enabled = config.getBoolean("steward.charm", true)
                && config.getBoolean("steward.remarks.enabled", false);
        this.intervalTicks = Math.max(20L, config.getLong("steward.remarks.interval-seconds", 90L) * 20L);
        this.quietMs = config.getLong("steward.remarks.quiet-seconds", 180L) * 1000L;
        this.subjectCooldownMs = config.getLong("steward.remarks.subject-cooldown-seconds", 900L) * 1000L;
        this.maxDistance = config.getDouble("steward.remarks.max-distance", 10.0);
        this.requireIdle = config.getBoolean("steward.remarks.require-idle", true);
    }

    /** Begin observing. A no-op when the feature is off. */
    public void start() {
        if (!enabled || plugin.getJarvisNPC() == null) return;
        task = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    considerRemarking(player);
                }
            }
        }.runTaskTimer(plugin, intervalTicks, intervalTicks);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Re-read the config and restart the timer; the interval only takes on a restart. */
    public void reload() {
        shutdown();
        readConfig();
        start();
    }

    /** Drop a player's cooldowns. Mute survives — it was a decision, not state. */
    public void forget(Player player) {
        UUID id = player.getUniqueId();
        lastRemark.remove(id);
        lastBySubject.remove(id);
    }

    /** Toggle the mute for this player; returns true if he is now muted. */
    public boolean toggleMute(Player player) {
        UUID id = player.getUniqueId();
        if (muted.remove(id)) return false;
        muted.add(id);
        return true;
    }

    public boolean isMuted(Player player) {
        return muted.contains(player.getUniqueId());
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ==================== THE LOOP ====================

    private void considerRemarking(Player player) {
        UUID id = player.getUniqueId();
        if (muted.contains(id)) return;

        // Summoned, in this world, and close enough to have noticed.
        if (plugin.getJarvisNPC().distanceToOwner(player) > maxDistance) return;

        // "Standing about" is the whole premise. A butler halfway down a mine
        // shaft on your orders is working, not making conversation.
        if (requireIdle && plugin.getJarvisNPC().describeCurrentTask(id) != null) return;

        long now = System.currentTimeMillis();
        Long last = lastRemark.get(id);
        if (last != null && now - last < quietMs) return;

        Observation observation = Observer.observe(player);
        if (observation == null) return;

        Remark remark = RemarkDoctrine.choose(observation, recentSubjects(id, now), random.nextInt(64));
        if (remark == null) return;   // the usual outcome, and the point

        lastRemark.put(id, now);
        lastBySubject.computeIfAbsent(id, k -> new EnumMap<>(RemarkSubject.class))
                .put(remark.subject(), now);
        plugin.getJarvisNPC().speakTo(player, remark.line());
    }

    /** Subjects still inside their cooldown for this player. */
    private Set<RemarkSubject> recentSubjects(UUID id, long now) {
        Map<RemarkSubject, Long> spoken = lastBySubject.get(id);
        if (spoken == null || spoken.isEmpty()) return Set.of();
        Set<RemarkSubject> recent = new HashSet<>();
        for (Map.Entry<RemarkSubject, Long> entry : spoken.entrySet()) {
            if (now - entry.getValue() < subjectCooldownMs) recent.add(entry.getKey());
        }
        return recent;
    }
}
