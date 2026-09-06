package com.gadgetman.jarvis.schematics;

import com.gadgetman.jarvis.Jarvis;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reasoning before retrieval: what is this request actually asking for?
 *
 * <p>"Somewhere to store my loot" shares not one word with {@code storage_shed},
 * so matching the raw utterance against the library never finds it. One LIGHT
 * -tier call names what the request is for -- {@link RequestFeatures}, purpose
 * {@code storage} and kind {@code shed} -- and the library is matched on that
 * instead. The paper calls this reasoning-before-retrieval and measured it
 * beating raw matching outright; measured here against llama3.2:3b it took a
 * seven-request set from 8/21 to 18/21, with no request answered worse than it
 * was without the decomposition.
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

    private final Jarvis plugin;
    private final Map<String, RequestFeatures> cache = new ConcurrentHashMap<>();

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
     * What a request is asking for, or {@link RequestFeatures#none()} if the
     * feature is off or the model could not be reached. Blocks; call it off the
     * main thread.
     *
     * <p>An empty result is never an error the caller has to handle -- it means
     * the raw-wording match is all there is, which is what happened before this
     * existed.
     */
    public RequestFeatures decompose(String description) {
        if (!enabled || description == null || description.isBlank()) {
            return RequestFeatures.none();
        }
        String key = normalise(description);
        if (key.isEmpty()) return RequestFeatures.none();

        RequestFeatures hit = cache.get(key);
        if (hit != null) return hit;

        RequestFeatures stored = loadFromDb(key);
        if (stored != null) {
            cache.put(key, stored);
            return stored;
        }

        RequestFeatures features;
        try {
            features = parseFeatures(plugin.getAIConnector().decomposeBuildRequest(description));
        } catch (Exception e) {
            // Not worth caching a failure: the box may be back by the next ask.
            plugin.getLogger().fine("Request decomposition failed: " + e.getMessage());
            return RequestFeatures.none();
        }
        if (features.isEmpty()) return RequestFeatures.none();

        cache.put(key, features);
        saveToDb(key, features);
        plugin.getLogger().fine("Decomposed \"" + description + "\" to " + features);
        return features;
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

    /** Pull the three fields out of the model's JSON, discarding anything unusable. */
    static RequestFeatures parseFeatures(String raw) {
        if (raw == null || raw.isBlank()) return RequestFeatures.none();
        try {
            JSONObject json = new JSONObject(raw);
            RequestFeatures f = new RequestFeatures(
                    json.optString("purpose", ""),
                    json.optString("kind", ""),
                    json.optString("style", ""));
            return f.isEmpty() ? RequestFeatures.none() : f;
        } catch (Exception e) {
            // A model that did not return the shape asked for gets nothing,
            // which degrades to the raw-wording match rather than failing.
            return RequestFeatures.none();
        }
    }

    // ==================== CACHE ====================

    private RequestFeatures loadFromDb(String key) {
        try (Connection c = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT tags FROM request_features WHERE request_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                // A row written by the flat-tag build that preceded this does
                // not parse into three fields, and reads as a miss rather than
                // as nonsense -- it is simply decomposed again and overwritten.
                RequestFeatures f = RequestFeatures.parse(rs.getString("tags"));
                return f.isEmpty() ? null : f;
            }
        } catch (SQLException e) {
            plugin.getLogger().fine("Feature cache read failed: " + e.getMessage());
            return null;
        }
    }

    private void saveToDb(String key, RequestFeatures features) {
        try (Connection c = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT OR REPLACE INTO request_features "
                     + "(request_key, tags, created_at) VALUES (?, ?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, features.toString());
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().fine("Feature cache write failed: " + e.getMessage());
        }
    }
}
