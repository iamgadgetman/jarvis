package com.gadgetman.jarvis.steward;

import com.gadgetman.jarvis.core.config.YamlConfig;
import com.gadgetman.jarvis.core.config.YamlFiles;
import com.gadgetman.jarvis.core.platform.Audience;
import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Platform;
import com.gadgetman.jarvis.core.platform.Task;
import com.gadgetman.jarvis.core.text.Colors;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * DutyScheduler (v0.5.0) - Jarvis's standing duties.
 *
 * Persistent scheduled tasks — currently repeating broadcasts ("the server
 * restarts at midnight", "market day every morning"). Duties live in
 * duties.yml, survive restarts, and fire on a 20-second check loop.
 */
public class DutyScheduler {

    public static class Duty {
        public int id;
        public String message;
        public long intervalSeconds;    // 0 = one-shot
        public long nextRunEpochSec;
        public int remainingRuns = -1;  // -1 = repeat forever
        public String createdBy;

        boolean due(long nowSec) {
            return nowSec >= nextRunEpochSec;
        }
    }

    private final Platform platform;
    private final Path file;
    private final List<Duty> duties = new CopyOnWriteArrayList<>();
    private int nextId = 1;
    private final Task loop;

    public DutyScheduler(Platform platform) {
        this.platform = platform;
        this.file = platform.dataDir().resolve("duties.yml");
        load();

        // Check loop: every 20 seconds
        this.loop = platform.scheduler().every(200L, 400L, t -> tick());
    }

    public void shutdown() {
        loop.cancel();
    }

    // ==================== EXECUTION ====================

    private void tick() {
        long now = System.currentTimeMillis() / 1000L;
        boolean changed = false;

        for (Duty duty : duties) {
            if (!duty.due(now)) continue;

            platform.players().broadcast(Colors.GOLD + "[Jarvis] " + Colors.WHITE + duty.message);

            if (duty.remainingRuns > 0) {
                duty.remainingRuns--;
            }
            if (duty.intervalSeconds > 0 && duty.remainingRuns != 0) {
                duty.nextRunEpochSec = now + duty.intervalSeconds;
            } else {
                duties.remove(duty);
            }
            changed = true;
        }

        if (changed) save();
    }

    // ==================== MANAGEMENT ====================

    public Duty addBroadcast(String message, long delaySeconds, long intervalSeconds, String createdBy) {
        return addBroadcast(message, delaySeconds, intervalSeconds, -1, createdBy);
    }

    public Duty addBroadcast(String message, long delaySeconds, long intervalSeconds,
                             int repeatCount, String createdBy) {
        Duty duty = new Duty();
        duty.id = nextId++;
        duty.message = message;
        duty.intervalSeconds = Math.max(0, intervalSeconds);
        duty.remainingRuns = repeatCount;
        duty.nextRunEpochSec = System.currentTimeMillis() / 1000L + Math.max(0, delaySeconds);
        duty.createdBy = createdBy;
        duties.add(duty);
        save();
        return duty;
    }

    public boolean remove(int id) {
        boolean removed = duties.removeIf(d -> d.id == id);
        if (removed) save();
        return removed;
    }

    public List<Duty> list() {
        return new ArrayList<>(duties);
    }

    public int count() {
        return duties.size();
    }

    public void showDuties(Audience player) {
        if (duties.isEmpty()) {
            player.message(Colors.jarvis("No standing duties, sir. My schedule is entirely yours."));
            return;
        }
        player.message(Colors.GOLD + "— Standing duties —");
        long now = System.currentTimeMillis() / 1000L;
        for (Duty d : duties) {
            long in = Math.max(0, d.nextRunEpochSec - now);
            String cadence = d.intervalSeconds > 0
                    ? "every " + formatDuration(d.intervalSeconds) : "once";
            player.message(Colors.YELLOW + "  #" + d.id + " "
                    + Colors.WHITE + "\"" + d.message + "\" "
                    + Colors.GRAY + "(" + cadence + ", next in " + formatDuration(in) + ")");
        }
        player.message(Colors.GRAY + "Remove with /jarvis duty remove <id>");
    }

    private String formatDuration(long seconds) {
        if (seconds >= 3600) return (seconds / 3600) + "h" + ((seconds % 3600) / 60 > 0 ? (seconds % 3600) / 60 + "m" : "");
        if (seconds >= 60) return (seconds / 60) + "m";
        return seconds + "s";
    }

    // ==================== PERSISTENCE ====================

    private void load() {
        Config yaml;
        try {
            yaml = YamlConfig.load(file);
        } catch (IOException e) {
            platform.log().warn("Could not read duties.yml: " + e.getMessage());
            return;
        }
        nextId = yaml.getInt("next-id", 1);
        if (!yaml.isSection("duties")) return;
        Config section = yaml.section("duties");
        for (String key : section.keys()) {
            Duty duty = new Duty();
            try {
                duty.id = Integer.parseInt(key);
            } catch (NumberFormatException e) {
                continue;
            }
            duty.message = section.getString(key + ".message", "");
            duty.intervalSeconds = section.getLong(key + ".interval-seconds", 0);
            duty.nextRunEpochSec = section.getLong(key + ".next-run", 0);
            duty.remainingRuns = section.getInt(key + ".remaining-runs", -1);
            duty.createdBy = section.getString(key + ".created-by", "unknown");
            if (!duty.message.isEmpty()) {
                duties.add(duty);
            }
        }
    }

    public void save() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("next-id", nextId);
        Map<String, Object> all = new LinkedHashMap<>();
        for (Duty d : duties) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("message", d.message);
            one.put("interval-seconds", d.intervalSeconds);
            one.put("next-run", d.nextRunEpochSec);
            one.put("remaining-runs", d.remainingRuns);
            one.put("created-by", d.createdBy);
            all.put(String.valueOf(d.id), one);
        }
        root.put("duties", all);
        try {
            YamlFiles.write(file, root);
        } catch (IOException e) {
            platform.log().warn("Could not save duties.yml: " + e.getMessage());
        }
    }
}
