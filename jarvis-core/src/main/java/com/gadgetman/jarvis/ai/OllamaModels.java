package com.gadgetman.jarvis.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** The models an Ollama server has pulled, from its {@code /api/tags}. Blocks; call off the server thread. */
public final class OllamaModels {

    private OllamaModels() {
    }

    public static List<String> list(String endpoint, int timeoutMs) throws IOException {
        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        HttpURLConnection conn = (HttpURLConnection) new URL(base + "/api/tags").openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        int code = conn.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code + " from " + base);
        try (InputStream in = conn.getInputStream()) {
            JSONObject body = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            JSONArray models = body.optJSONArray("models");
            List<String> out = new ArrayList<>();
            if (models != null) {
                for (int i = 0; i < models.length(); i++) {
                    String name = models.getJSONObject(i).optString("name", "");
                    if (!name.isEmpty()) out.add(name);
                }
            }
            return out;
        }
    }
}
