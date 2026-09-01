package com.gadgetman.jarvis.memory;

import com.gadgetman.jarvis.Jarvis;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Minimal Ollama embeddings client.
 *
 * Deliberately NOT routed through {@link com.gadgetman.jarvis.ai.AIConnector}.
 * Embeddings are called on every build request, and a fallback to a paid
 * provider would turn a free local feature into a per-build bill. If Ollama is
 * down, retrieval degrades to keyword matching instead — the memory keeps
 * working in reduced mode exactly as it does with a cloud key present.
 *
 * Health handling mirrors AIConnector's philosophy: repeated failures put the
 * client on a cooldown so a dead Ollama box costs one connection attempt every
 * few minutes rather than one per build.
 *
 * All calls block on I/O — async contexts only.
 */
public class EmbeddingClient {

    private final Jarvis plugin;

    private String endpoint = "http://localhost:11434";
    private String model = "nomic-embed-text";
    private int timeoutSeconds = 20;

    private static final int FAILURES_BEFORE_COOLDOWN = 3;
    private static final long COOLDOWN_MS = 300_000L; // 5 minutes

    private volatile int consecutiveFailures = 0;
    private volatile long cooldownUntil = 0;
    private volatile String lastError = "";

    public EmbeddingClient(Jarvis plugin) {
        this.plugin = plugin;
        reloadConfig();
    }

    public void reloadConfig() {
        // Reuse the AI connector's Ollama endpoint — one box, one setting.
        this.endpoint = plugin.getConfig().getString("ai.ollama.endpoint", "http://localhost:11434");
        this.model = plugin.getConfig().getString("memory.embedding-model", "nomic-embed-text");
        this.timeoutSeconds = plugin.getConfig().getInt("memory.embedding-timeout-seconds", 20);
        this.consecutiveFailures = 0;
        this.cooldownUntil = 0;
    }

    public boolean isAvailable() {
        return System.currentTimeMillis() > cooldownUntil;
    }

    public String getLastError() {
        return lastError;
    }

    public String getModel() {
        return model;
    }

    /**
     * Embed one string.
     *
     * @return the vector, or {@code null} if Ollama is unavailable, on cooldown,
     *         or returned something unusable. Callers fall back to keywords.
     */
    public float[] embed(String text) {
        if (text == null || text.isBlank()) return null;
        if (!isAvailable()) return null;

        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpoint + "/api/embeddings");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(timeoutSeconds * 1000);
            conn.setDoOutput(true);

            JSONObject payload = new JSONObject()
                    .put("model", model)
                    .put("prompt", text);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                recordFailure("HTTP " + code);
                return null;
            }

            StringBuilder body = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) body.append(line);
            }

            JSONArray arr = new JSONObject(body.toString()).optJSONArray("embedding");
            if (arr == null || arr.isEmpty()) {
                recordFailure("no embedding in response (is '" + model + "' pulled?)");
                return null;
            }

            float[] vec = new float[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                vec[i] = (float) arr.optDouble(i, 0.0);
            }

            recordSuccess();
            return vec;

        } catch (Exception e) {
            recordFailure(e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void recordSuccess() {
        consecutiveFailures = 0;
        cooldownUntil = 0;
        lastError = "";
    }

    private void recordFailure(String error) {
        lastError = error == null ? "unknown" : error;
        consecutiveFailures++;
        if (consecutiveFailures >= FAILURES_BEFORE_COOLDOWN) {
            cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS;
            plugin.getLogger().warning("Embeddings unavailable (" + lastError
                    + "); falling back to keyword matching for 5 minutes.");
        }
    }

    // ==================== VECTOR MATH ====================

    /** Cosine similarity, 0.0 for null, mismatched or zero-length vectors. */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return 0.0;

        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) return 0.0;

        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public static String serialize(float[] vec) {
        if (vec == null || vec.length == 0) return null;
        JSONArray arr = new JSONArray();
        for (float v : vec) arr.put(v);
        return arr.toString();
    }

    public static float[] deserialize(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JSONArray arr = new JSONArray(json);
            float[] vec = new float[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                vec[i] = (float) arr.optDouble(i, 0.0);
            }
            return vec.length == 0 ? null : vec;
        } catch (Exception e) {
            return null;
        }
    }
}
