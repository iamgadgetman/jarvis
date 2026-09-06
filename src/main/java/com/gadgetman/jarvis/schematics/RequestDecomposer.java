package com.gadgetman.jarvis.schematics;

import com.gadgetman.jarvis.Jarvis;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reasoning before retrieval: what is this request actually asking for?
 *
 * <p>"Somewhere to store my loot" shares not one word with {@code storage_shed},
 * so matching the raw utterance against the library never finds it. One LIGHT
 * -tier call turns the sentence into feature tags -- {@code storage, shed,
 * small} -- and the library is matched on those instead. The paper calls this
 * reasoning-before-retrieval and measured it beating raw matching outright.
 *
 * <p>The decomposition of a given request never changes, so it is cached: in
 * memory for the session and in SQLite forever. A request asked twice costs one
 * model call, and a server whose players ask for houses all day converges on
 * costing nothing.
 *
 * <p>Threading: {@link #decompose} blocks on the model and must be called off
 * the main thread. The in-memory cache is checked first and is safe from
 * anywhere.
 */
public class RequestDecomposer {

    /** Beyond this the text is not a build request, it is an essay. */
    private static final int MAX_KEY_LENGTH = 255;
    private static final int MAX_TAGS = 6;

    private final Jarvis plugin;
    private final Map<String, List<String>> cache = new ConcurrentHashMap<>();

    private boolean enabled = true;

    public RequestDecomposer(Jarvis plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    private void loadConfig() {
        this.enabled = plugin.getConfig().getBoolean("schematics.feature-tags.enabled", true);
    }

    public void reload() {
        loadConfig();
        cache.clear();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getCachedCount() {
        return cache.size();
    }

    /**
     * Feature tags for a request, empty if the feature is off or the model
     * could not be reached. Blocks; call it off the main thread.
     *
     * <p>An empty list is never an error the caller has to handle -- it simply
     * means the raw-wording match is all there is, which is what happened
     * before this existed.
     */
    public List<String> decompose(String description) {
        if (!enabled || description == null || description.isBlank()) {
            return Collections.emptyList();
        }
        String key = normalise(description);
        if (key.isEmpty()) return Collections.emptyList();

        List<String> hit = cache.get(key);
        if (hit != null) return hit;

        List<String> stored = loadFromDb(key);
        if (stored != null) {
            cache.put(key, stored);
            return stored;
        }

        List<String> tags;
        try {
            tags = parseTags(plugin.getAIConnector().decomposeBuildRequest(description));
        } catch (Exception e) {
            // Not worth caching a failure: the box may be back by the next ask.
            plugin.getLogger().fine("Request decomposition failed: " + e.getMessage());
            return Collections.emptyList();
        }
        if (tags.isEmpty()) return Collections.emptyList();

        cache.put(key, tags);
        saveToDb(key, tags);
        plugin.getLogger().fine("Decomposed \"" + description + "\" to " + String.join(", ", tags));
        return tags;
    }

    /**
     * The cache key. Case and punctuation carry no meaning here, and collapsing
     * them means "Build me a HOUSE!" and "build me a house" are one entry.
     */
    static String normalise(String description) {
        String out = description.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return out.length() > MAX_KEY_LENGTH ? out.substring(0, MAX_KEY_LENGTH) : out;
    }

    /** Pull the tag list out of the model's JSON, discarding anything unusable. */
    static List<String> parseTags(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        try {
            JSONArray arr = new JSONObject(raw).optJSONArray("tags");
            if (arr == null) return out;
            Set<String> seen = new LinkedHashSet<>();
            for (int i = 0; i < arr.length() && seen.size() < MAX_TAGS; i++) {
                String tag = arr.optString(i, "").toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]", "");
                // Two characters is not a feature, and the scorer ignores
                // anything shorter than three anyway.
                if (tag.length() >= 3) seen.add(tag);
            }
            out.addAll(seen);
        } catch (Exception ignored) {
            // A model that did not return the shape asked for gets no tags,
            // which degrades to the raw-wording match rather than failing.
        }
        return out;
    }

    // ==================== CACHE ====================

    private List<String> loadFromDb(String key) {
        try (Connection c = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT tags FROM request_features WHERE request_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                List<String> tags = new ArrayList<>();
                for (String t : rs.getString("tags").split(",")) {
                    if (!t.isBlank()) tags.add(t.trim());
                }
                return tags.isEmpty() ? null : tags;
            }
        } catch (SQLException e) {
            plugin.getLogger().fine("Feature-tag cache read failed: " + e.getMessage());
            return null;
        }
    }

    private void saveToDb(String key, List<String> tags) {
        try (Connection c = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT OR REPLACE INTO request_features "
                     + "(request_key, tags, created_at) VALUES (?, ?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, String.join(",", tags));
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().fine("Feature-tag cache write failed: " + e.getMessage());
        }
    }
}
