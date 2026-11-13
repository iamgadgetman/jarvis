package com.gadgetman.jarvis.memory;

import com.gadgetman.jarvis.DatabaseManager;
import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Log;
import com.gadgetman.jarvis.core.platform.Platform;
import com.gadgetman.jarvis.core.platform.Site;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What Jarvis remembers of each player (FB-04): the conversation, condensed
 * by the light model once it grows, plus notable things that happened to
 * them. Separate from {@link ExperienceMemory}, which remembers builds.
 *
 * <p>The turns themselves are the {@code chat_interactions} rows the intent
 * pipeline has always written (they also feed the dataset export). This adds
 * {@code conversation_summaries} (one condensed paragraph per player and the
 * timestamp it covers up to) and {@code conversation_events}.
 *
 * <p>Every method here touches the database; call them off the server thread.
 */
public final class ConversationMemory {

    /** The light-tier model call that condenses a transcript. */
    public interface Summarizer {
        String summarize(String transcript) throws Exception;
    }

    /** What {@code /jarvis memory} shows. */
    public record View(String summary, List<String> events, List<String> recent) {
        public boolean isEmpty() {
            return summary.isEmpty() && events.isEmpty() && recent.isEmpty();
        }
    }

    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    /** Events brought into a prompt: the latest few, and only fairly recent ones. */
    private static final int EVENTS_IN_PROMPT = 3;
    private static final long EVENT_PROMPT_WINDOW_MS = 7 * DAY_MS;

    private final Platform platform;
    private final DatabaseManager db;
    private final Summarizer summarizer;
    private final Log log;
    /** Players with a summary call in flight, so two quick turns don't summarize twice. */
    private final Set<UUID> summarizing = ConcurrentHashMap.newKeySet();

    private volatile boolean enabled;
    private volatile int recentTurns;
    private volatile int summarizeAfter;
    private volatile int keepDays;
    private volatile boolean recordEvents;

    public ConversationMemory(Platform platform, DatabaseManager db, Summarizer summarizer) {
        this.platform = platform;
        this.db = db;
        this.summarizer = summarizer;
        this.log = platform.log();
        reload();
    }

    public void reload() {
        Config c = platform.config();
        enabled = c.getBoolean("memory.conversation.enabled", true);
        recentTurns = Math.max(0, c.getInt("memory.conversation.recent-turns", 8));
        summarizeAfter = Math.max(1, c.getInt("memory.conversation.summarize-after", 30));
        keepDays = Math.max(0, c.getInt("memory.conversation.keep-days", 90));
        recordEvents = c.getBoolean("memory.conversation.record-events", true);
    }

    public boolean isEnabled() {
        return enabled && db != null;
    }

    public boolean recordsEvents() {
        return isEnabled() && recordEvents;
    }

    // ==================== the prompt ====================

    /**
     * The memory block for a request: the summary, recent events, then the
     * last few turns verbatim. Small local models get fewer turns.
     */
    public String contextFor(UUID player, boolean reduced) {
        if (!isEnabled()) return "";
        int turns = reduced ? Math.min(recentTurns, 3) : recentTurns;
        String summary = summary(player);
        List<String> events = events(player, EVENTS_IN_PROMPT, System.currentTimeMillis() - EVENT_PROMPT_WINDOW_MS);
        List<String[]> recent = turns == 0 ? List.of() : db.getRecentInteractions(player.toString(), turns);
        StringBuilder sb = new StringBuilder();
        if (!summary.isEmpty()) sb.append("\nEarlier with this player: ").append(summary).append("\n");
        if (!events.isEmpty()) {
            sb.append("\nLately:\n");
            for (String e : events) sb.append("  - ").append(e).append("\n");
        }
        if (!recent.isEmpty()) {
            sb.append("\nRecent conversation (oldest first):\n");
            for (String[] row : recent) {
                sb.append("  Player: ").append(trim(row[0], 160)).append("\n");
                boolean did = row[2] != null && !row[2].isEmpty() && !row[2].equals("chat");
                if (did) sb.append("  Jarvis did: ").append(row[2]);
                if (row[1] != null && !row[1].isEmpty()) {
                    sb.append(did ? " — said: " : "  Jarvis said: ").append(trim(row[1], 160));
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    // ==================== events ====================

    /** One line for something that happened to the player. */
    public void recordEvent(UUID player, String text) {
        if (!recordsEvents() || text == null || text.isBlank()) return;
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO conversation_events (player_id, text, timestamp) VALUES (?, ?, ?)")) {
            ps.setString(1, player.toString());
            ps.setString(2, trim(text.trim(), 300));
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Could not save a memory event: " + e.getMessage());
        }
    }

    /** "Died (fell from a high place) at -3044 75 3127 in world". */
    public static String deathLine(String message, Site where) {
        String why = message == null ? "" : message.trim();
        return "Died" + (why.isEmpty() ? "" : " (" + why + ")") + at(where);
    }

    /** "Went through a portal from world to world_nether, coming out at 120 64 -40". */
    public static String portalLine(Site from, Site to) {
        String a = from == null || from.world() == null ? "?" : from.world().name();
        String b = to == null || to.world() == null ? "?" : to.world().name();
        return "Went through a portal from " + a + " to " + b
                + (to == null || to.pos() == null ? "" : ", coming out at " + coords(to));
    }

    private static String at(Site s) {
        if (s == null || s.pos() == null) return "";
        return " at " + coords(s) + (s.world() == null ? "" : " in " + s.world().name());
    }

    private static String coords(Site s) {
        return (int) Math.floor(s.pos().x()) + " " + (int) Math.floor(s.pos().y()) + " " + (int) Math.floor(s.pos().z());
    }

    // ==================== summaries ====================

    /**
     * Condense the turns the summary doesn't cover yet, keeping the last
     * {@code recent-turns} verbatim, once there are more than
     * {@code summarize-after} of them. A failed model call keeps the old
     * summary and leaves the turns for next time.
     *
     * @return true when a new summary was written
     */
    public boolean summarizeIfNeeded(UUID player) {
        if (!isEnabled() || !summarizing.add(player)) return false;
        try {
            long covered = coveredUntil(player);
            List<Turn> fresh = turnsAfter(player, covered);
            if (fresh.size() <= summarizeAfter) return false;
            List<Turn> toCondense = fresh.subList(0, Math.max(0, fresh.size() - recentTurns));
            if (toCondense.isEmpty()) return false;

            String old = summary(player);
            StringBuilder t = new StringBuilder();
            if (!old.isEmpty()) t.append("Earlier notes: ").append(old).append("\n\n");
            t.append("Conversation (oldest first):\n");
            for (Turn turn : toCondense) {
                t.append("Player: ").append(trim(turn.said, 300)).append("\n");
                if (turn.reply != null && !turn.reply.isBlank()) t.append("Jarvis: ").append(trim(turn.reply, 300)).append("\n");
            }
            String summary;
            try {
                summary = summarizer.summarize(t.toString());
            } catch (Exception e) {
                log.warn("Could not condense " + player + "'s conversation (keeping the old notes): " + e.getMessage());
                return false;
            }
            summary = summary == null ? "" : summary.replaceAll("\\s+", " ").trim();
            if (summary.isEmpty()) return false;
            saveSummary(player, trim(summary, 1200), toCondense.get(toCondense.size() - 1).ts);
            return true;
        } finally {
            summarizing.remove(player);
        }
    }

    // ==================== housekeeping ====================

    /**
     * Drop raw turns and events older than {@code keep-days}. Only turns the
     * summary already covers go, so nothing is lost that wasn't condensed.
     * {@code keep-days: 0} keeps everything.
     */
    public void prune(long now) {
        if (!isEnabled() || keepDays == 0) return;
        long cutoff = now - keepDays * DAY_MS;
        try (Connection c = db.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM chat_interactions WHERE timestamp < ? AND EXISTS (SELECT 1 FROM conversation_summaries s "
                            + "WHERE s.player_id = chat_interactions.player_id AND chat_interactions.timestamp <= s.covered_until)")) {
                ps.setLong(1, cutoff);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM conversation_events WHERE timestamp < ?")) {
                ps.setLong(1, cutoff);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("Could not prune conversation memory: " + e.getMessage());
        }
    }

    /** Everything Jarvis remembers of this player, gone: turns, summary and events. */
    public void forget(UUID player) {
        if (db == null) return;
        try (Connection c = db.getConnection()) {
            for (String table : List.of("chat_interactions", "conversation_summaries", "conversation_events")) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE player_id = ?")) {
                    ps.setString(1, player.toString());
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            log.warn("Could not forget " + player + ": " + e.getMessage());
        }
    }

    /** For {@code /jarvis memory}: the summary, the latest events and the last few turns. */
    public View view(UUID player) {
        if (!isEnabled()) return new View("", List.of(), List.of());
        List<String> recent = new ArrayList<>();
        for (String[] row : db.getRecentInteractions(player.toString(), Math.max(recentTurns, 1))) {
            recent.add(trim(row[0], 120));
        }
        return new View(summary(player), events(player, 5, 0), recent);
    }

    // ==================== rows ====================

    private record Turn(long ts, String said, String reply) { }

    String summary(UUID player) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT summary FROM conversation_summaries WHERE player_id = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        } catch (SQLException e) {
            log.warn("Could not read conversation notes: " + e.getMessage());
            return "";
        }
    }

    long coveredUntil(UUID player) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT covered_until FROM conversation_summaries WHERE player_id = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            return 0L;
        }
    }

    private List<Turn> turnsAfter(UUID player, long after) {
        List<Turn> out = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT timestamp, player_message, ai_response FROM chat_interactions "
                             + "WHERE player_id = ? AND timestamp > ? ORDER BY timestamp, id")) {
            ps.setString(1, player.toString());
            ps.setLong(2, after);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Turn(rs.getLong(1), rs.getString(2), rs.getString(3)));
            }
        } catch (SQLException e) {
            log.warn("Could not read the conversation: " + e.getMessage());
        }
        return out;
    }

    private void saveSummary(UUID player, String summary, long coveredUntil) {
        try (Connection c = db.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM conversation_summaries WHERE player_id = ?")) {
                    ps.setString(1, player.toString());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO conversation_summaries (player_id, summary, covered_until, updated_at) VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, player.toString());
                    ps.setString(2, summary);
                    ps.setLong(3, coveredUntil);
                    ps.setLong(4, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            log.warn("Could not save conversation notes: " + e.getMessage());
        }
    }

    private List<String> events(UUID player, int limit, long since) {
        List<String> out = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT text FROM conversation_events WHERE player_id = ? AND timestamp >= ? "
                             + "ORDER BY timestamp DESC, id DESC LIMIT ?")) {
            ps.setString(1, player.toString());
            ps.setLong(2, since);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            log.warn("Could not read memory events: " + e.getMessage());
        }
        Collections.reverse(out);
        return out;
    }

    private static String trim(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
