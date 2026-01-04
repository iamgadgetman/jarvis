package com.yourname.jarvis.ai;

import com.yourname.jarvis.Jarvis;
import com.yourname.jarvis.util.DebugLogger;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

public class AIConnector {

    private final Jarvis plugin;
    private String provider;
    private String apiKey;
    private String model;
    private String endpoint;
    private boolean keyPresent;

    public AIConnector(Jarvis plugin) {
        this.plugin = plugin;
        reloadConfig();
    }

    public void reloadConfig() {
        DebugLogger debug = plugin.getDebugLogger();
        this.provider = plugin.getConfig().getString("ai.provider", "openai").toLowerCase(Locale.ROOT);
        switch (provider) {
            case "grok" -> {
                this.apiKey = resolveKey("ai.grok.api-key", "GROK_API_KEY");
                this.model = plugin.getConfig().getString("ai.grok.model", "grok-4");
                this.endpoint = "https://api.x.ai/v1/chat/completions";
            }
            case "gemini" -> {
                this.apiKey = resolveKey("ai.gemini.api-key", "GEMINI_API_KEY");
                this.model = plugin.getConfig().getString("ai.gemini.model", "gemini-1.5-pro");
                this.endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;
            }
            default -> {
                this.provider = "openai";
                this.apiKey = resolveKey("ai.openai.api-key", "OPENAI_API_KEY");
                this.model = plugin.getConfig().getString("ai.openai.model", "gpt-3.5-turbo");
                this.endpoint = "https://api.openai.com/v1/chat/completions";
            }
        }
        keyPresent = apiKey != null && !apiKey.isBlank();
        if (!keyPresent) {
            plugin.getLogger().warning("AI API key not set for provider: " + provider + " (check config ai." + provider + ".api-key or environment variable)");
        } else if (debug != null) {
            debug.debug("AI provider=" + provider + " model=" + model + " endpoint=" + endpoint + " keyLen=" + apiKey.length());
        }
    }

    public String queryBuildPlan(String description) throws Exception {
        String prompt = "Output ONLY valid JSON, no extra text. Generate a Minecraft structure as JSON: {\"dimensions\":{\"width\":int,\"height\":int,\"length\":int},\"blocks\":[{\"x\":int,\"y\":int,\"z\":int,\"material\":\"minecraft:stone\"},...]} for: " + description;
        return sendRequest(prompt, "You are a Minecraft build planner. Output ONLY valid JSON.");
    }

    public String getProviderName() {
        return provider;
    }

    public String getModelName() {
        return model;
    }

    public boolean isApiKeyPresent() {
        return keyPresent;
    }

    public String simpleChat(String prompt) throws Exception {
        return sendRequest(prompt, "You are a helpful assistant for the Jarvis Minecraft plugin.");
    }

    private String sendRequest(String userContent, String systemContent) throws Exception {
        return switch (provider) {
            case "gemini" -> sendGeminiRequest(userContent, systemContent);
            case "grok", "openai" -> sendOpenAIStyleRequest(userContent, systemContent);
            default -> throw new IllegalStateException("Unsupported AI provider: " + provider);
        };
    }

    private String sendOpenAIStyleRequest(String userContent, String systemContent) throws Exception {
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
        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        JSONObject payload = new JSONObject();
        JSONArray contents = new JSONArray();

        JSONObject systemObj = new JSONObject();
        systemObj.put("role", "system");
        systemObj.put("parts", new JSONArray().put(new JSONObject().put("text", systemContent)));

        JSONObject userObj = new JSONObject();
        userObj.put("role", "user");
        userObj.put("parts", new JSONArray().put(new JSONObject().put("text", userContent)));

        contents.put(systemObj);
        contents.put(userObj);
        payload.put("contents", contents);

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
            JSONArray candidates = json.optJSONArray("candidates");
            if (candidates == null || candidates.isEmpty()) {
                throw new RuntimeException("No candidates returned from Gemini");
            }
            JSONObject first = candidates.getJSONObject(0);
            JSONArray parts = first.getJSONObject("content").optJSONArray("parts");
            if (parts == null || parts.isEmpty()) {
                throw new RuntimeException("No content parts returned from Gemini");
            }
            return parts.getJSONObject(0).getString("text").trim();
        }
    }

    private String resolveKey(String configPath, String envVar) {
        // Accept multiple spellings to avoid silently missing user-provided keys
        List<String> aliases = List.of(
                configPath,
                configPath.replace("-", ""),
                configPath.replace("-", "").replace(".", "-"),
                configPath.replace("api-key", "apiKey"),
                configPath.replace("api-key", "apikey")
        );

        for (String path : aliases) {
            String key = plugin.getConfig().getString(path, "");
            if (key != null && !key.isBlank()) {
                plugin.getLogger().info("Loaded API key for " + provider + " from config path: " + path);
                return key.trim();
            }
        }

        String env = System.getenv(envVar);
        if (env != null && !env.isBlank()) {
            plugin.getLogger().info("Using " + envVar + " from environment for " + provider + " API key.");
            return env.trim();
        }
        return "";
    }
}
