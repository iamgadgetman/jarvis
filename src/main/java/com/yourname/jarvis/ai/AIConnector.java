package com.yourname.jarvis.ai;

import com.yourname.jarvis.Jarvis;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class AIConnector {

    private final Jarvis plugin;
    private String provider;
    private String apiKey;
    private String model;
    private String endpoint;

    public AIConnector(Jarvis plugin) {
        this.plugin = plugin;
        reloadConfig();
    }

    public void reloadConfig() {
        this.provider = plugin.getConfig().getString("ai.provider", "openai").toLowerCase();
        if ("grok".equals(provider)) {
            this.apiKey = plugin.getConfig().getString("grok.api-key", "");
            this.model = plugin.getConfig().getString("grok.model", "grok-4");
            this.endpoint = "https://api.x.ai/v1/chat/completions";
        } else {
            this.apiKey = plugin.getConfig().getString("openai.api-key", "");
            this.model = plugin.getConfig().getString("openai.model", "gpt-3.5-turbo");
            this.endpoint = "https://api.openai.com/v1/chat/completions";
        }
        if (apiKey.isEmpty()) {
            plugin.getLogger().warning("AI API key not set for provider: " + provider);
        }
    }

    public String queryBuildPlan(String description) throws Exception {
        String prompt = "Output ONLY valid JSON, no extra text. Generate a Minecraft structure as JSON: {\"dimensions\":{\"width\":int,\"height\":int,\"length\":int},\"blocks\":[{\"x\":int,\"y\":int,\"z\":int,\"material\":\"minecraft:stone\"},...]} for: " + description;
        return sendRequest(prompt, "You are a Minecraft build planner. Output ONLY valid JSON.");
    }

    private String sendRequest(String userContent, String systemContent) throws Exception {
        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);

        JSONObject payload = new JSONObject();
        payload.put("model", model);
        payload.put("max_tokens", 2000);

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", systemContent));
        messages.put(new JSONObject().put("role", "user").put("content", userContent));
        payload.put("messages", messages);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
        }

        if (conn.getResponseCode() != 200) {
            throw new RuntimeException("HTTP error: " + conn.getResponseCode());
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) response.append(line);
            JSONObject json = new JSONObject(response.toString());
            return json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim();
        }
    }
}
