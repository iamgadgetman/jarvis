package com.gadgetman.jarvis.building;

import com.gadgetman.jarvis.DatabaseManager;
import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Log;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Platform;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * How many AI builds a player may start (FB-07): a cooldown between builds and
 * a daily budget of builds and blocks, per permission group. A player is in
 * group {@code <name>} when they have {@code jarvis.build.limit.<name>} (first
 * match in config order), otherwise {@code default}; {@code jarvis.build.bypass}
 * skips all of it. Only builds that finished spend the budget, and the counts
 * are kept in SQLite so a restart doesn't reset them. Days are server-local.
 */
public final class BuildQuota {

    public record Limit(String group, int cooldownSeconds, int dailyBuilds, int dailyBlocks) { }

    /** Today's spend for one player. */
    record Usage(int builds, int blocks, long lastBuildAt) { }

    public static final String BYPASS = "jarvis.build.bypass";

    private final DatabaseManager db;
    private final Log log;
    private final LongSupplier clock;
    private final ZoneId zone;
    private volatile Map<String, Limit> limits = new LinkedHashMap<>();

    public BuildQuota(Platform platform, DatabaseManager db, LongSupplier clock, ZoneId zone) {
        this.db = db;
        this.log = platform.log();
        this.clock = clock;
        this.zone = zone;
        reload(platform.config());
    }

    public void reload(Config cfg) {
        Map<String, Limit> m = new LinkedHashMap<>();
        for (String group : cfg.keys("build.limits")) {
            String base = "build.limits." + group + ".";
            m.put(group, new Limit(group,
                    cfg.getInt(base + "cooldown-seconds", 120),
                    cfg.getInt(base + "daily-builds", 5),
                    cfg.getInt(base + "daily-blocks", 6000)));
        }
        if (!m.containsKey("default")) m.put("default", new Limit("default", 120, 5, 6000));
        limits = m;
    }

    /** The player's group: the first configured group they have the permission for, else default. */
    public Limit limitFor(Owner player) {
        for (Limit l : limits.values()) {
            if (!l.group().equals("default") && player.hasPermission("jarvis.build.limit." + l.group())) return l;
        }
        return limits.get("default");
    }

    /**
     * Whether a new build may start now; null when it may, else the reason with
     * when it will be allowed. Database read: call off the server thread or
     * accept a short blocking read (it's one row).
     */
    public String checkStart(Owner player) {
        if (player.hasPermission(BYPASS)) return null;
        Limit l = limitFor(player);
        long now = clock.getAsLong();
        Usage u = usage(player.id(), today(now));
        long last = lastBuildAt(player.id());
        if (l.cooldownSeconds() > 0 && last > 0) {
            long readyAt = last + l.cooldownSeconds() * 1000L;
            if (now < readyAt) {
                return "Another build in " + duration(readyAt - now) + ", sir (" + l.group() + " builders wait "
                        + l.cooldownSeconds() + "s between builds).";
            }
        }
        if (l.dailyBuilds() >= 0 && u.builds() >= l.dailyBuilds()) {
            return "That's your " + l.dailyBuilds() + " builds for today, sir. The count resets at midnight, in "
                    + duration(untilMidnight(now)) + ".";
        }
        if (l.dailyBlocks() >= 0 && u.blocks() >= l.dailyBlocks()) {
            return "You've used today's " + l.dailyBlocks() + " blocks, sir. The count resets at midnight, in "
                    + duration(untilMidnight(now)) + ".";
        }
        return null;
    }

    /** Whether a plan of {@code blocks} fits what's left today; null when it does. */
    public String checkBlocks(Owner player, int blocks) {
        if (player.hasPermission(BYPASS)) return null;
        Limit l = limitFor(player);
        if (l.dailyBlocks() < 0) return null;
        int left = l.dailyBlocks() - usage(player.id(), today(clock.getAsLong())).blocks();
        if (blocks <= left) return null;
        return "That plan is " + blocks + " blocks and you have " + Math.max(0, left) + " left today, sir. "
                + "Try something smaller, or wait for midnight (" + duration(untilMidnight(clock.getAsLong())) + ").";
    }

    /** A build finished: count it and its blocks against today. */
    public void spend(UUID player, int blocks) {
        long now = clock.getAsLong();
        String day = today(now).toString();
        try (Connection c = db.getConnection()) {
            int updated;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE build_quota SET builds = builds + 1, blocks = blocks + ?, last_build_at = ? "
                            + "WHERE player_id = ? AND day = ?")) {
                ps.setInt(1, blocks);
                ps.setLong(2, now);
                ps.setString(3, player.toString());
                ps.setString(4, day);
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO build_quota (player_id, day, builds, blocks, last_build_at) VALUES (?, ?, 1, ?, ?)")) {
                    ps.setString(1, player.toString());
                    ps.setString(2, day);
                    ps.setInt(3, blocks);
                    ps.setLong(4, now);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            log.warn("Could not record a build against the quota: " + e.getMessage());
        }
    }

    Usage usage(UUID player, LocalDate day) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT builds, blocks, last_build_at FROM build_quota WHERE player_id = ? AND day = ?")) {
            ps.setString(1, player.toString());
            ps.setString(2, day.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new Usage(rs.getInt(1), rs.getInt(2), rs.getLong(3));
            }
        } catch (SQLException e) {
            log.warn("Could not read the build quota: " + e.getMessage());
        }
        return new Usage(0, 0, 0);
    }

    private long lastBuildAt(UUID player) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT MAX(last_build_at) FROM build_quota WHERE player_id = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            return 0L;
        }
    }

    private LocalDate today(long now) {
        return Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
    }

    private long untilMidnight(long now) {
        ZonedDateTime midnight = today(now).plusDays(1).atStartOfDay(zone);
        return midnight.toInstant().toEpochMilli() - now;
    }

    /** "4h 12m", "35s". */
    static String duration(long ms) {
        long s = Math.max(1, (ms + 999) / 1000);
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m " + (s % 60) + "s";
        return (m / 60) + "h " + (m % 60) + "m";
    }
}
