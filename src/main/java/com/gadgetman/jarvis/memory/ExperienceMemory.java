package com.gadgetman.jarvis.memory;

import com.gadgetman.jarvis.Jarvis;
import org.bukkit.scheduler.BukkitRunnable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Experience memory — the store and the retrieval engine.
 *
 * Records how past builds turned out, then injects the plans that worked into
 * the prompt for a similar new request. Retrieval is two-stage: match on
 * request-text similarity, then re-rank on how much the world matched
 * ({@link SituationSnapshot}). Only positive outcomes are ever retrieved.
 *
 * Once enough successes have accumulated, freeform build planning is unlocked
 * in Ollama-only reduced mode — the examples do the work the bigger model used
 * to. That gate is what makes this more than logging.
 *
 * Threading: every DB and embedding call is off the main thread. A hot cache of
 * positive experiences is held in memory so retrieval scoring never touches
 * SQLite mid-request.
 */
public class ExperienceMemory {

    public static final String TASK_BUILD_FREEFORM = "build_freeform";

    private static final int CACHE_SIZE = 200;
    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
            "a", "an", "the", "me", "my", "some", "please", "can", "you", "build",
            "make", "create", "for", "with", "of", "and", "to", "in", "on", "at", "it"));

    private final Jarvis plugin;
    private final EmbeddingClient embeddings;

    // Positive experiences only — the retrievable set.
    private final List<BuildExperience> cache = new CopyOnWriteArrayList<>();
    // playerId -> {experienceId, createdAt} for the most recent recorded success.
    private final Map<UUID, long[]> lastSuccess = new ConcurrentHashMap<>();

    private boolean enabled = true;
    private int maxExamples = 3;
    private int minSuccessesForReducedMode = 20;
    private int negativeWindowMinutes = 10;
    private double minTextRelevance = 0.55;
    private double minKeywordRelevance = 0.15;
    private int maxPlanChars = 1200;

    public ExperienceMemory(Jarvis plugin) {
        this.plugin = plugin;
        this.embeddings = new EmbeddingClient(plugin);
        loadConfig();

        if (enabled) {
            loadCacheAsync();
        } else {
            plugin.getLogger().info("Experience memory disabled in config.");
        }
    }

    private void loadConfig() {
        this.enabled = plugin.getConfig().getBoolean("memory.enabled", true);
        this.maxExamples = plugin.getConfig().getInt("memory.max-examples-in-prompt", 3);
        this.minSuccessesForReducedMode =
                plugin.getConfig().getInt("memory.min-successes-for-reduced-mode-builds", 20);
        this.negativeWindowMinutes =
                plugin.getConfig().getInt("memory.negative-signal-window-minutes", 10);
        this.minTextRelevance = plugin.getConfig().getDouble("memory.min-text-relevance", 0.55);
        this.minKeywordRelevance = plugin.getConfig().getDouble("memory.min-keyword-relevance", 0.15);
        this.maxPlanChars = plugin.getConfig().getInt("memory.max-plan-chars", 1200);
    }

    public void reload() {
        loadConfig();
        embeddings.reloadConfig();
        cache.clear();
        lastSuccess.clear();
        if (enabled) loadCacheAsync();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getSuccessCount() {
        return cache.size();
    }

    public EmbeddingClient getEmbeddingClient() {
        return embeddings;
    }

    /**
     * True once enough successes exist to plan freeform builds from examples
     * alone. This is what lets an Ollama-only server do freeform building.
     */
    public boolean isReducedModeBuildUnlocked() {
        return enabled && cache.size() >= minSuccessesForReducedMode;
    }

    // ==================== RECORDING ====================

    /**
     * Persist an experience. Returns immediately; the insert and the embedding
     * both happen off the main thread.
     */
    public void record(BuildExperience experience) {
        if (!enabled || experience == null) return;

        new BukkitRunnable() {
            @Override
            public void run() {
                // Only positives are ever retrieved, so only positives are worth
                // paying an embedding call for.
                if (experience.getOutcome().isPositive()) {
                    experience.setEmbedding(embeddings.embed(experience.getRequestText()));
                }
                if (insert(experience) && experience.getOutcome().isPositive()) {
                    addToCache(experience);
                    if (experience.getPlayerId() != null) {
                        lastSuccess.put(experience.getPlayerId(),
                                new long[]{experience.getId(), experience.getCreatedAt()});
                    }
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * Demote one specific experience to UNDONE.
     *
     * Preferred over {@link #markRecentBuildUndone}: it targets the build that
     * was actually reverted rather than whichever success is newest, so undoing
     * an older build out of order demotes the right row. Falls back to the
     * by-player search when the id has not landed yet (the insert is async).
     */
    public void markUndone(BuildExperience experience) {
        if (!enabled || experience == null) return;

        long id = experience.getId();
        if (id <= 0) {
            markRecentBuildUndone(experience.getPlayerId());
            return;
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                if (updateOutcome(id, BuildExperience.Outcome.UNDONE)) {
                    experience.setOutcome(BuildExperience.Outcome.UNDONE);
                    cache.removeIf(e -> e.getId() == id);
                    if (experience.getPlayerId() != null) {
                        long[] recent = lastSuccess.get(experience.getPlayerId());
                        if (recent != null && recent[0] == id) {
                            lastSuccess.remove(experience.getPlayerId());
                        }
                    }
                    plugin.getLogger().fine("Experience " + id + " demoted to UNDONE.");
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    private boolean updateOutcome(long id, BuildExperience.Outcome outcome) {
        try (Connection c = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE build_experiences SET outcome = ?, outcome_signal = ? WHERE id = ?")) {
            ps.setString(1, outcome.name());
            ps.setDouble(2, outcome.signal());
            ps.setLong(3, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to update experience outcome: " + ex.getMessage());
            return false;
        }
    }

    /**
     * Demote this player's most recent success to UNDONE, if it happened inside
     * the negative-signal window. A player reverting a build minutes after it
     * finished is the clearest "that plan was wrong" signal available without
     * asking them.
     */
    public void markRecentBuildUndone(UUID playerId) {
        if (!enabled || playerId == null) return;

        long cutoff = System.currentTimeMillis() - (negativeWindowMinutes * 60_000L);
        long[] recent = lastSuccess.get(playerId);
        long knownId = (recent != null && recent[1] >= cutoff) ? recent[0] : -1;

        new BukkitRunnable() {
            @Override
            public void run() {
                long demoted = demote(playerId, knownId, cutoff);
                if (demoted >= 0) {
                    cache.removeIf(e -> e.getId() == demoted);
                    lastSuccess.remove(playerId);
                    plugin.getLogger().fine("Experience " + demoted + " demoted to UNDONE.");
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    // ==================== RETRIEVAL ====================

    /**
     * Format the most relevant successful plans for prompt injection.
     * Blocking (it may embed the query) — async contexts only.
     *
     * @return prompt text, or an empty string when there is nothing worth adding
     */
    public String retrieveExamples(String requestText, String situationJson) {
        if (!enabled || cache.isEmpty() || requestText == null || requestText.isBlank()) {
            return "";
        }

        float[] queryVec = embeddings.embed(requestText);
        Set<String> queryTokens = queryVec == null ? tokenize(requestText) : null;

        List<Scored> scored = new ArrayList<>();
        for (BuildExperience e : cache) {
            double textScore;
            double gate;

            if (queryVec != null && e.hasEmbedding()) {
                textScore = EmbeddingClient.cosine(queryVec, e.getEmbedding());
                gate = minTextRelevance;
            } else {
                // Ollama down, or a row predating embeddings. Jaccard is on a
                // completely different scale, so it needs its own threshold.
                textScore = jaccard(queryTokens != null ? queryTokens : tokenize(requestText),
                        tokenize(e.getRequestText()));
                gate = minKeywordRelevance;
            }

            // Gate on the text score ALONE, before blending. The situation term
            // is a large constant offset — with a same-situation match it adds a
            // flat 0.3 — so a floor applied to the blended score cannot reject
            // anything. Measured on nomic-embed-text, unrelated text scores
            // 0.34-0.42 and a genuinely similar request scores ~0.86, so the
            // discrimination has to happen here or not at all.
            if (textScore < gate) continue;

            double situationScore = SituationSnapshot.similarity(situationJson, e.getSituation());
            scored.add(new Scored(e, (textScore * 0.7) + (situationScore * 0.3)));
        }

        if (scored.isEmpty()) return "";
        scored.sort((a, b) -> Double.compare(b.score, a.score));

        StringBuilder sb = new StringBuilder();
        sb.append("Here are plans that worked for similar requests on this server. ")
          .append("Use them as a guide for style, scale and materials; do not copy them verbatim.\n\n");

        int n = Math.min(maxExamples, scored.size());
        for (int i = 0; i < n; i++) {
            BuildExperience e = scored.get(i).experience;
            sb.append("Example ").append(i + 1).append(":\n")
              .append("  Request: ").append(e.getRequestText()).append('\n')
              .append("  Situation: ").append(SituationSnapshot.describe(e.getSituation())).append('\n')
              .append("  Plan: ").append(truncate(e.getPlan())).append("\n\n");
        }

        return sb.toString();
    }

    private String truncate(String plan) {
        if (plan == null) return "(not recorded)";
        if (plan.length() <= maxPlanChars) return plan;
        return plan.substring(0, maxPlanChars) + "... (truncated)";
    }

    private static class Scored {
        final BuildExperience experience;
        final double score;

        Scored(BuildExperience experience, double score) {
            this.experience = experience;
            this.score = score;
        }
    }

    // ==================== KEYWORD FALLBACK ====================

    static Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null) return tokens;
        for (String raw : text.toLowerCase().split("[^a-z0-9]+")) {
            if (raw.length() > 1 && !STOPWORDS.contains(raw)) tokens.add(raw);
        }
        return tokens;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        if (intersection.isEmpty()) return 0.0;
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    // ==================== PERSISTENCE ====================

    private void addToCache(BuildExperience e) {
        cache.add(0, e);
        while (cache.size() > CACHE_SIZE) {
            cache.remove(cache.size() - 1);
        }
    }

    private boolean insert(BuildExperience e) {
        String sql = "INSERT INTO build_experiences "
                + "(player_id, task_type, request_text, situation, plan, outcome, outcome_signal, "
                + "provider, embedding, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection c = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            ps.setString(1, e.getPlayerId() == null ? null : e.getPlayerId().toString());
            ps.setString(2, e.getTaskType());
            ps.setString(3, e.getRequestText());
            ps.setString(4, e.getSituation());
            ps.setString(5, e.getPlan());
            ps.setString(6, e.getOutcome().name());
            ps.setDouble(7, e.getOutcomeSignal());
            ps.setString(8, e.getProvider());
            ps.setString(9, EmbeddingClient.serialize(e.getEmbedding()));
            ps.setLong(10, e.getCreatedAt());
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) e.setId(keys.getLong(1));
            }
            return true;

        } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to record build experience: " + ex.getMessage());
            return false;
        }
    }

    /** @return the demoted row id, or -1 if nothing qualified */
    private long demote(UUID playerId, long knownId, long cutoff) {
        try (Connection c = plugin.getDatabaseManager().getConnection()) {
            long targetId = knownId;

            if (targetId < 0) {
                // Cache miss (server restarted since the build) — find it in the DB.
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id FROM build_experiences WHERE player_id = ? AND outcome = 'SUCCESS' "
                        + "AND created_at >= ? ORDER BY created_at DESC LIMIT 1")) {
                    ps.setString(1, playerId.toString());
                    ps.setLong(2, cutoff);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) return -1;
                        targetId = rs.getLong(1);
                    }
                }
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE build_experiences SET outcome = ?, outcome_signal = ? WHERE id = ?")) {
                ps.setString(1, BuildExperience.Outcome.UNDONE.name());
                ps.setDouble(2, BuildExperience.Outcome.UNDONE.signal());
                ps.setLong(3, targetId);
                return ps.executeUpdate() > 0 ? targetId : -1;
            }

        } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to demote undone build: " + ex.getMessage());
            return -1;
        }
    }

    private void loadCacheAsync() {
        new BukkitRunnable() {
            @Override
            public void run() {
                List<BuildExperience> loaded = loadPositives();
                cache.clear();
                cache.addAll(loaded);

                int missing = 0;
                for (BuildExperience e : loaded) {
                    if (!e.hasEmbedding()) missing++;
                }

                plugin.getLogger().info("Experience memory: " + loaded.size()
                        + " successful builds loaded"
                        + (missing > 0 ? " (" + missing + " awaiting embeddings)" : "")
                        + (isReducedModeBuildUnlocked()
                            ? " — reduced-mode freeform builds UNLOCKED"
                            : " — " + Math.max(0, minSuccessesForReducedMode - loaded.size())
                              + " more to unlock reduced-mode freeform builds"));

                if (missing > 0) backfillEmbeddings(loaded);
            }
        }.runTaskAsynchronously(plugin);
    }

    private List<BuildExperience> loadPositives() {
        List<BuildExperience> out = new ArrayList<>();
        String sql = "SELECT id, player_id, task_type, request_text, situation, plan, outcome, "
                + "provider, embedding, created_at FROM build_experiences "
                + "WHERE outcome = 'SUCCESS' ORDER BY created_at DESC LIMIT " + CACHE_SIZE;

        try (Connection c = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                UUID playerId = null;
                String rawId = rs.getString("player_id");
                if (rawId != null) {
                    try {
                        playerId = UUID.fromString(rawId);
                    } catch (IllegalArgumentException ignored) {
                        // Row written by an older build; the id is only used for undo.
                    }
                }

                BuildExperience e = new BuildExperience(
                        playerId,
                        rs.getString("task_type"),
                        rs.getString("request_text"),
                        rs.getString("situation"),
                        rs.getString("plan"),
                        BuildExperience.Outcome.parse(rs.getString("outcome")),
                        rs.getString("provider"),
                        rs.getLong("created_at"));
                e.setId(rs.getLong("id"));
                e.setEmbedding(EmbeddingClient.deserialize(rs.getString("embedding")));
                out.add(e);
            }

        } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to load experience memory: " + ex.getMessage());
        }
        return out;
    }

    /**
     * Fill in vectors for rows written while Ollama was down. Already async;
     * gives up as soon as the client goes on cooldown so a dead box costs one
     * attempt, not one per row.
     */
    private void backfillEmbeddings(List<BuildExperience> experiences) {
        int done = 0;
        for (BuildExperience e : experiences) {
            if (e.hasEmbedding()) continue;
            if (!embeddings.isAvailable()) break;

            float[] vec = embeddings.embed(e.getRequestText());
            if (vec == null) break;

            e.setEmbedding(vec);
            try (Connection c = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "UPDATE build_experiences SET embedding = ? WHERE id = ?")) {
                ps.setString(1, EmbeddingClient.serialize(vec));
                ps.setLong(2, e.getId());
                ps.executeUpdate();
                done++;
            } catch (SQLException ex) {
                plugin.getLogger().warning("Embedding backfill failed: " + ex.getMessage());
                return;
            }
        }
        if (done > 0) {
            plugin.getLogger().info("Experience memory: backfilled " + done + " embeddings.");
        }
    }
}
