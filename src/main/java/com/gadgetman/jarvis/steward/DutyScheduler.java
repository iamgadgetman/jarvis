package com.gadgetman.jarvis.steward;

import com.gadgetman.jarvis.Jarvis;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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

    private final Jarvis plugin;
    private final File file;
    private final List<Duty> duties = new CopyOnWriteArrayList<>();
    private int nextId = 1;

    public DutyScheduler(Jarvis plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "duties.yml");
        load();

        // Check loop: every 20 seconds
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 200L, 400L);
    }

    // ==================== EXECUTION ====================

    private void tick() {
        long now = System.currentTimeMillis() / 1000L;
        boolean changed = false;

        for (Duty duty : duties) {
            if (!duty.due(now)) continue;

            Bukkit.getServer().broadcast(Component.text("[Jarvis] ", NamedTextColor.GOLD)
                    .append(Component.text(duty.message, NamedTextColor.WHITE)));

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

    public void showDuties(Player player) {
        if (duties.isEmpty()) {
            player.sendMessage(Component.text("Jarvis: ", NamedTextColor.GOLD)
                    .append(Component.text("No standing duties, sir. My schedule is entirely yours.",
                            NamedTextColor.WHITE)));
            return;
        }
        player.sendMessage(Component.text("— Standing duties —", NamedTextColor.GOLD));
        long now = System.currentTimeMillis() / 1000L;
        for (Duty d : duties) {
            long in = Math.max(0, d.nextRunEpochSec - now);
            String cadence = d.intervalSeconds > 0
                    ? "every " + formatDuration(d.intervalSeconds) : "once";
            player.sendMessage(Component.text("  #" + d.id + " ", NamedTextColor.YELLOW)
                    .append(Component.text("\"" + d.message + "\" ", NamedTextColor.WHITE))
                    .append(Component.text("(" + cadence + ", next in " + formatDuration(in) + ")",
                            NamedTextColor.GRAY)));
        }
        player.sendMessage(Component.text("Remove with /jarvis duty remove <id>", NamedTextColor.GRAY));
    }

    private String formatDuration(long seconds) {
        if (seconds >= 3600) return (seconds / 3600) + "h" + ((seconds % 3600) / 60 > 0 ? (seconds % 3600) / 60 + "m" : "");
        if (seconds >= 60) return (seconds / 60) + "m";
        return seconds + "s";
    }

    // ==================== PERSISTENCE ====================

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        nextId = yaml.getInt("next-id", 1);
        ConfigurationSection section = yaml.getConfigurationSection("duties");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            Duty duty = new Duty();
            duty.id = Integer.parseInt(key);
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
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("next-id", nextId);
        for (Duty d : duties) {
            String base = "duties." + d.id;
            yaml.set(base + ".message", d.message);
            yaml.set(base + ".interval-seconds", d.intervalSeconds);
            yaml.set(base + ".next-run", d.nextRunEpochSec);
            yaml.set(base + ".remaining-runs", d.remainingRuns);
            yaml.set(base + ".created-by", d.createdBy);
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save duties.yml: " + e.getMessage());
        }
    }
}
