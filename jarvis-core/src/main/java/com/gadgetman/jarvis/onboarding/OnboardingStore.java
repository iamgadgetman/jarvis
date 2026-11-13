package com.gadgetman.jarvis.onboarding;

import com.gadgetman.jarvis.DatabaseManager;
import com.gadgetman.jarvis.core.platform.Log;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code onboarding_state} table (FB-11): one row per player Jarvis has
 * welcomed. The row, not the platform's "has played before", decides whether
 * a join is a first join, because that flag comes back when player data is
 * wiped. Small blocking reads and writes: call off the server thread.
 */
public final class OnboardingStore {

    /**
     * One player's row.
     *
     * @param welcomedAt when Jarvis welcomed them (epoch ms)
     * @param step       the tutorial step they are on, 1-based; 0 = never started
     * @param active     whether the tutorial is running for them now
     * @param doneAt     when they finished it, or 0
     */
    public record State(long welcomedAt, int step, boolean active, long doneAt) {
        public boolean done() { return doneAt > 0; }
        public State withStep(int s, boolean a) { return new State(welcomedAt, s, a, doneAt); }
        public State finished(long at) { return new State(welcomedAt, step, false, at); }
    }

    private final DatabaseManager db;
    private final Log log;

    public OnboardingStore(DatabaseManager db, Log log) {
        this.db = db;
        this.log = log;
    }

    public Optional<State> load(UUID player) {
        if (db == null) return Optional.empty();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT welcomed_at, tutorial_step, tutorial_active, tutorial_done_at "
                             + "FROM onboarding_state WHERE player_id = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new State(rs.getLong(1), rs.getInt(2), rs.getInt(3) != 0, rs.getLong(4)));
                }
            }
        } catch (SQLException e) {
            log.warn("Could not read onboarding state: " + e.getMessage());
        }
        return Optional.empty();
    }

    /** Insert or replace the player's row. */
    public void save(UUID player, State s) {
        if (db == null) return;
        try (Connection c = db.getConnection()) {
            int updated;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE onboarding_state SET welcomed_at = ?, tutorial_step = ?, tutorial_active = ?, "
                            + "tutorial_done_at = ? WHERE player_id = ?")) {
                bind(ps, s);
                ps.setString(5, player.toString());
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO onboarding_state (welcomed_at, tutorial_step, tutorial_active, "
                                + "tutorial_done_at, player_id) VALUES (?, ?, ?, ?, ?)")) {
                    bind(ps, s);
                    ps.setString(5, player.toString());
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            log.warn("Could not save onboarding state: " + e.getMessage());
        }
    }

    /** Forget the player entirely: their next join is a first join. */
    public void delete(UUID player) {
        if (db == null) return;
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM onboarding_state WHERE player_id = ?")) {
            ps.setString(1, player.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Could not reset onboarding state: " + e.getMessage());
        }
    }

    private static void bind(PreparedStatement ps, State s) throws SQLException {
        ps.setLong(1, s.welcomedAt());
        ps.setInt(2, s.step());
        ps.setInt(3, s.active() ? 1 : 0);
        ps.setLong(4, s.doneAt());
    }
}
