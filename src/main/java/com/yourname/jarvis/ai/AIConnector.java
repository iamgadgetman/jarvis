package com.yourname.jarvis.ai;

import com.yourname.jarvis.Jarvis;
import org.json.JSONArray;
import org.json.JSONObject;
import org.bukkit.configuration.ConfigurationSection;
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
        ConfigurationSection ai = plugin.getConfig().getConfigurationSection("ai");
        if (ai == null) {
            plugin.getLogger().warning("Missing ai configuration section; using defaults.");
        }

        this.provider = ai != null ? ai.getString("provider", "openai").toLowerCase() : "openai";

        switch (provider) {
            case "grok" -> {
                ConfigurationSection grok = ai != null ? ai.getConfigurationSection("grok") : null;
                this.apiKey = grok != null ? grok.getString("api-key", "") : "";
                this.model = grok != null ? grok.getString("model", "grok-4") : "grok-4";
                String configuredEndpoint = grok != null ? grok.getString("endpoint") : null;
                this.endpoint = fallbackIfBlank(configuredEndpoint, "https://api.x.ai/v1/chat/completions");
            }
            case "gemini" -> {
                ConfigurationSection gemini = ai != null ? ai.getConfigurationSection("gemini") : null;
                this.apiKey = gemini != null ? gemini.getString("api-key", "") : "";
                this.model = gemini != null ? gemini.getString("model", "gemini-1.5-flash") : "gemini-1.5-flash";
                String configuredEndpoint = gemini != null ? gemini.getString("endpoint") : null;
                this.endpoint = fallbackIfBlank(configuredEndpoint,
                        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent");
            }
            default -> {
                ConfigurationSection openai = ai != null ? ai.getConfigurationSection("openai") : null;
                this.apiKey = openai != null ? openai.getString("api-key", "") : "";
                this.model = openai != null ? openai.getString("model", "gpt-3.5-turbo") : "gpt-3.5-turbo";
                String configuredEndpoint = openai != null ? openai.getString("endpoint") : null;
                this.endpoint = fallbackIfBlank(configuredEndpoint, "https://api.openai.com/v1/chat/completions");
                this.provider = "openai";
            }
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
        if ("gemini".equals(provider)) {
            return sendGeminiRequest(userContent, systemContent);
        }

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

    private String sendGeminiRequest(String userContent, String systemContent) throws Exception {
        String resolvedEndpoint = String.format(endpoint, model) + "?key=" + java.net.URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        URL url = new URL(resolvedEndpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        JSONObject payload = new JSONObject();
        JSONArray contents = new JSONArray();

        contents.put(new JSONObject()
                .put("role", "system")
                .put("parts", new JSONArray().put(new JSONObject().put("text", systemContent))));

        contents.put(new JSONObject()
                .put("role", "user")
                .put("parts", new JSONArray().put(new JSONObject().put("text", userContent))));

        payload.put("contents", contents);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
        }

        if (conn.getResponseCode() != 200) {
            String error;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String line;
                while (br != null && (line = br.readLine()) != null) response.append(line);
                error = response.toString();
            }
            throw new RuntimeException("HTTP error: " + conn.getResponseCode() + " - " + error);
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) response.append(line);
            JSONObject json = new JSONObject(response.toString());
            JSONArray candidates = json.optJSONArray("candidates");
            if (candidates == null || candidates.isEmpty()) {
                throw new RuntimeException("No candidates returned from Gemini API");
            }
            JSONObject content = candidates.getJSONObject(0).getJSONObject("content");
            JSONArray parts = content.optJSONArray("parts");
            if (parts == null || parts.isEmpty()) {
                throw new RuntimeException("No content parts returned from Gemini API");
            }
            return parts.getJSONObject(0).getString("text").trim();
        }
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isEmpty();
    }

    private String fallbackIfBlank(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }
}
