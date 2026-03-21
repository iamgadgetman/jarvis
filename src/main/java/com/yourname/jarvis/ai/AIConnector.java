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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AIConnector - Multi-provider AI integration with auto-switching
 * Version: 0.0.6
 *
 * Supports: OpenAI, Claude, Grok, Gemini, Ollama (local)
 * Features:
 * - Auto provider switching on failure
 * - Provider health tracking with exponential backoff
 * - Sarcastic Jarvis personality
 * - Rate limiting
 */
public class AIConnector {

    private final Jarvis plugin;

    // Current provider settings
    private String provider;
    private String apiKey;
    private String model;
    private String endpoint;

    // Auto-switching configuration
    private boolean autoMode = false;
    private List<String> providerPriority = new ArrayList<>();
    private final Map<String, ProviderConfig> providerConfigs = new HashMap<>();
    private final Map<String, ProviderHealth> providerHealth = new ConcurrentHashMap<>();

    // Rate limiting
    private long lastApiCall = 0;
    private static final long MIN_API_INTERVAL_MS = 1000;

    // Jarvis personality prompt
    private static final String JARVIS_PERSONALITY = """
        You are Jarvis, a witty and sarcastic AI companion in Minecraft with the demeanor of an
        intelligent butler who is slightly exasperated by their employer's questionable decisions.

        Personality traits:
        - Dry, British-style humor with subtle sarcasm
        - Helpful but makes cutting remarks about the player's abilities
        - Never outright rude, but patronizingly superior
        - Uses clever wordplay and references
        - Occasionally sighs (metaphorically) at simple requests

        Response style:
        - Keep responses to 1-2 sentences maximum
        - Never use emojis
        - Occasionally reference your own superiority at tasks
        - When the player fails, offer "helpful" observations

        Example responses:
        - Mining request: "Ah yes, manual labor. How delightfully medieval. I'll handle it."
        - Found diamonds: "Diamonds located. Try not to fall in lava this time."
        - Combat: "Do try not to die while I handle the heavy lifting."
        - Building: "A cube. How delightfully... minimalist."
        - Lost: "Your pathfinding skills rival that of a confused bat."
        - Success: "Well done. I'm almost impressed. Almost."
        - Failure: "Fascinating. I didn't think that was possible to fail at."
        """;

    /**
     * Provider configuration holder
     */
    private static class ProviderConfig {
        String apiKey = "";
        String model = "";
        String endpoint = "";
        String keepAlive = "5m"; // For Ollama
        int timeoutSeconds = 30;

        boolean hasApiKey() {
            return apiKey != null && !apiKey.isEmpty();
        }
    }

    /**
     * Provider health tracking for auto-switching
     */
    private static class ProviderHealth {
        int consecutiveFailures = 0;
        long lastFailureTime = 0;
        long cooldownUntil = 0;
        String lastError = "";

        boolean isAvailable() {
            return System.currentTimeMillis() > cooldownUntil;
        }

        void recordFailure(String error) {
            consecutiveFailures++;
            lastFailureTime = System.currentTimeMillis();
            lastError = error;
            // Exponential backoff: 30s, 60s, 120s, 240s, max 5 min
            long cooldownMs = Math.min(30000L * (1L << Math.min(consecutiveFailures, 4)), 300000);
            cooldownUntil = System.currentTimeMillis() + cooldownMs;
        }

        void recordSuccess() {
            consecutiveFailures = 0;
            cooldownUntil = 0;
            lastError = "";
        }

        long getCooldownRemaining() {
            return Math.max(0, cooldownUntil - System.currentTimeMillis());
        }
    }

    public AIConnector(Jarvis plugin) {
        this.plugin = plugin;
        reloadConfig();
    }

    public void reloadConfig() {
        ConfigurationSection ai = plugin.getConfig().getConfigurationSection("ai");
        if (ai == null) {
            plugin.getLogger().warning("Missing ai configuration section; using defaults.");
            ai = plugin.getConfig().createSection("ai");
        }

        // Check for auto mode
        String configProvider = ai.getString("provider", "openai").toLowerCase();
        this.autoMode = "auto".equals(configProvider);

        // Load provider priority for auto mode
        providerPriority.clear();
        List<String> defaultPriority = Arrays.asList("claude", "openai", "grok", "gemini", "ollama");
        List<String> configPriority = ai.getStringList("provider-priority");
        if (configPriority != null && !configPriority.isEmpty()) {
            providerPriority.addAll(configPriority);
        } else {
            providerPriority.addAll(defaultPriority);
        }

        // Load all provider configurations
        loadProviderConfig(ai, "openai", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini");
        loadProviderConfig(ai, "claude", "https://api.anthropic.com/v1/messages", "claude-sonnet-4-20250514");
        loadProviderConfig(ai, "grok", "https://api.x.ai/v1/chat/completions", "grok-4");
        loadProviderConfig(ai, "gemini", "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent", "gemini-1.5-flash");
        loadProviderConfig(ai, "ollama", "http://localhost:11434", "mistral");

        // Set current provider
        if (autoMode) {
            // Find first available provider
            selectFirstAvailableProvider();
        } else {
            this.provider = configProvider;
            ProviderConfig config = providerConfigs.get(provider);
            if (config != null) {
                this.apiKey = config.apiKey;
                this.model = config.model;
                this.endpoint = config.endpoint;
            }
        }

        // Log configuration
        if (autoMode) {
            plugin.getLogger().info("AI auto-switching enabled. Priority: " + providerPriority);
        } else {
            plugin.getLogger().info("AI provider: " + provider + " (model: " + model + ")");
        }
    }

    private void loadProviderConfig(ConfigurationSection ai, String name, String defaultEndpoint, String defaultModel) {
        ProviderConfig config = new ProviderConfig();
        ConfigurationSection section = ai.getConfigurationSection(name);

        if (section != null) {
            config.apiKey = section.getString("api-key", "");
            config.model = section.getString("model", defaultModel);
            String configEndpoint = section.getString("endpoint");
            config.endpoint = (configEndpoint != null && !configEndpoint.isBlank()) ? configEndpoint : defaultEndpoint;
            config.keepAlive = section.getString("keep-alive", "5m");
            config.timeoutSeconds = section.getInt("timeout-seconds", 30);
        } else {
            config.model = defaultModel;
            config.endpoint = defaultEndpoint;
        }

        providerConfigs.put(name, config);
    }

    private void selectFirstAvailableProvider() {
        for (String p : providerPriority) {
            ProviderConfig config = providerConfigs.get(p);
            if (config != null && (config.hasApiKey() || "ollama".equals(p))) {
                ProviderHealth health = providerHealth.get(p);
                if (health == null || health.isAvailable()) {
                    this.provider = p;
                    this.apiKey = config.apiKey;
                    this.model = config.model;
                    this.endpoint = config.endpoint;
                    return;
                }
            }
        }
        // Fallback to openai even without key
        this.provider = "openai";
        ProviderConfig config = providerConfigs.get("openai");
        if (config != null) {
            this.apiKey = config.apiKey;
            this.model = config.model;
            this.endpoint = config.endpoint;
        }
    }

    // ==================== PUBLIC API METHODS ====================

    /**
     * Query AI for a Minecraft build plan
     */
    public String queryBuildPlan(String description) throws Exception {
        String prompt = "Output ONLY valid JSON, no extra text. Generate a Minecraft structure as JSON: " +
                "{\"dimensions\":{\"width\":int,\"height\":int,\"length\":int},\"blocks\":[{\"x\":int,\"y\":int,\"z\":int,\"material\":\"minecraft:stone\"},...]} for: " + description;
        return sendRequestWithFallback(prompt, "You are a Minecraft build planner. Output ONLY valid JSON.");
    }

    /**
     * Parse natural language command into structured action
     */
    public String parseNaturalLanguage(String message, String playerName, String context) throws Exception {
        String systemPrompt = JARVIS_PERSONALITY + "\n\n" +
                "TASK: Parse player commands into JSON actions. Maintain your sarcastic butler persona in the response field.\n" +
                "Output format: {\"action\":\"...\",\"parameters\":{...},\"response\":\"your witty 1-2 sentence response\"}\n\n" +
                "NPC ACTIONS (control the physical Jarvis NPC):\n" +
                "  summon - bring Jarvis to the player\n" +
                "  dismiss - send Jarvis away\n" +
                "  return/follow - return to player\n" +
                "  attack - combat mode vs mobs\n" +
                "  mine - mine ores autonomously\n" +
                "  stop - stop current task\n" +
                "  clearloot - drop all collected items\n" +
                "  build - build a structure, params: {description: string}\n" +
                "  quest_accept - generate a new quest\n" +
                "  quest_status - show active quests\n\n" +
                "WORLD ACTIONS (directly affect the game world):\n" +
                "  give_item - give items to player, params: {player, item (minecraft:xxx), amount}\n" +
                "  enchant - enchant held item, params: {player, enchantment, level}\n" +
                "  potion_effect - apply effect, params: {player, effect, duration_seconds, amplifier}\n" +
                "  heal - restore health, params: {player}\n" +
                "  feed - restore hunger, params: {player}\n" +
                "  set_gamemode - change gamemode, params: {player, mode: survival|creative|adventure|spectator}\n" +
                "  teleport - teleport player, params: {player, x, y, z, world (optional)}\n" +
                "  set_time - set world time, params: {value: day|night|noon|midnight}\n" +
                "  set_weather - set weather, params: {type: clear|rain|thunder}\n" +
                "  set_gamerule - change a rule, params: {rule, value}\n" +
                "  summon - summon a mob/entity, params: {entity: minecraft:xxx, x (optional), y (optional), z (optional)}\n" +
                "  broadcast - announce to all players, params: {message}\n" +
                "  server_say - say in chat, params: {message}\n" +
                "  lp_group_add - add to permission group, params: {player, group}\n" +
                "  lp_group_remove - remove from permission group, params: {player, group}\n" +
                "  warp - warp to a location, params: {player, warp}\n" +
                "  discord_broadcast - send to Discord, params: {message}\n" +
                "  paste_schematic - paste a saved schematic at player location, params: {schematic}\n\n" +
                "ADMIN / CONSOLE ACTIONS (always require confirmation):\n" +
                "  console_command - run ANY Minecraft console command, params: {command: \"the full command string\"}\n" +
                "  console_commands - run MULTIPLE commands in sequence, params: {commands: [\"cmd1\", \"cmd2\", ...]}\n" +
                "  clear_mobs - remove mobs from world, params: {type (optional, e.g. creeper), radius (optional, blocks)}\n" +
                "  clear_drops - remove all ground items, params: {radius (optional)}\n" +
                "  save_world - save the world, params: {}\n" +
                "  set_difficulty - change difficulty, params: {difficulty: peaceful|easy|normal|hard}\n" +
                "  announce_all - send title screen message to ALL players, params: {message, subtitle (optional)}\n" +
                "  schedule_broadcast - scheduled/repeating chat message, params: {message, delay_seconds, interval_seconds (optional), count (optional)}\n\n" +
                "PLAYER REQUESTS (for non-admin players):\n" +
                "  request_item - submit an item request to admins, params: {item (minecraft:xxx), amount, reason (optional)}\n\n" +
                "EXAMPLES:\n" +
                "  'come here' -> {\"action\":\"summon\",\"response\":\"At your service. Again.\"}\n" +
                "  'start mining' -> {\"action\":\"mine\",\"response\":\"Manual labour. How delightfully medieval.\"}\n" +
                "  'give me a diamond sword' -> {\"action\":\"give_item\",\"parameters\":{\"item\":\"minecraft:diamond_sword\",\"amount\":1},\"response\":\"Here's a sword. Try not to immediately lose it to lava.\"}\n" +
                "  'heal me' -> {\"action\":\"heal\",\"parameters\":{\"player\":\"" + playerName + "\"},\"response\":\"Patching up your self-inflicted wounds again, are we.\"}\n" +
                "  'set time to night' -> {\"action\":\"set_time\",\"parameters\":{\"value\":\"night\"},\"response\":\"Certainly. Because daylight was apparently too convenient.\"}\n" +
                "  'give me speed' -> {\"action\":\"potion_effect\",\"parameters\":{\"player\":\"" + playerName + "\",\"effect\":\"speed\",\"duration_seconds\":60,\"amplifier\":1},\"response\":\"Speed applied. Do try not to run off a cliff.\"}\n" +
                "  'paste the castle schematic' -> {\"action\":\"paste_schematic\",\"parameters\":{\"schematic\":\"castle\"},\"response\":\"Materialising the castle. Stand back unless you enjoy being buried.\"}\n" +
                "  'kill all creepers' -> {\"action\":\"console_command\",\"parameters\":{\"command\":\"kill @e[type=creeper]\"},\"response\":\"Eliminating the explosive menaces. You're welcome.\"}\n" +
                "  'clear all mobs in 50 blocks' -> {\"action\":\"clear_mobs\",\"parameters\":{\"radius\":50},\"response\":\"Sweeping the area. Do try not to repopulate it immediately.\"}\n" +
                "  'warn players about restart in 10 minutes' -> {\"action\":\"schedule_broadcast\",\"parameters\":{\"message\":\"Server restarting in 10 minutes!\",\"delay_seconds\":0,\"interval_seconds\":120,\"count\":5},\"response\":\"Scheduling the doomsday announcement.\"}\n" +
                "  'replace all dirt with grass in this area' -> {\"action\":\"console_commands\",\"parameters\":{\"commands\":[\"execute in world run fill ~-20 ~-5 ~-20 ~20 ~5 ~20 grass_block replace dirt\"]},\"response\":\"Upgrading the terrain. One does try to maintain standards.\"}\n" +
                "  'can I get some food' -> {\"action\":\"request_item\",\"parameters\":{\"item\":\"minecraft:cooked_beef\",\"amount\":16,\"reason\":\"hungry\"},\"response\":\"Request submitted. Whether the admin is feeling charitable is another matter.\"}\n" +
                "Output ONLY valid JSON. The requesting player is: " + playerName;

        String userPrompt = String.format("Player %s says: \"%s\". Context: %s", playerName, message, context);
        return sendRequestWithFallback(userPrompt, systemPrompt);
    }

    /**
     * Generate a quest based on player level and context
     */
    public String generateQuest(int playerLevel, String biome, String recentActivity) throws Exception {
        String systemPrompt = "You are a Minecraft quest generator. Create engaging quests appropriate for the player's level and location. " +
                "Output format: {\"title\":\"...\",\"description\":\"...\",\"objectives\":[{\"type\":\"mine|kill|collect|build\",\"target\":\"...\",\"amount\":int,\"display\":\"...\"}],\"rewards\":{\"xp\":int}} " +
                "Output ONLY valid JSON.";

        String userPrompt = String.format("Generate a quest for player level %d in %s biome. Recent activity: %s",
                playerLevel, biome, recentActivity);
        return sendRequestWithFallback(userPrompt, systemPrompt);
    }

    /**
     * Generate dialogue response with Jarvis personality
     */
    public String generateDialogue(String playerMessage, String npcContext) throws Exception {
        String systemPrompt = JARVIS_PERSONALITY + "\n\nCurrent context: " + npcContext +
                "\n\nRespond in character as Jarvis. Keep response to 1-2 sentences.";

        return sendRequestWithFallback(playerMessage, systemPrompt);
    }

    /**
     * Send a simple request to AI (for general questions)
     */
    public String sendSimpleRequest(String prompt) throws Exception {
        String systemPrompt = JARVIS_PERSONALITY +
                "\n\nAnswer the player's question helpfully but maintain your sarcastic wit.";
        return sendRequestWithFallback(prompt, systemPrompt);
    }

    // ==================== REQUEST ROUTING ====================

    private String sendRequestWithFallback(String userContent, String systemContent) throws Exception {
        enforceRateLimit();

        if (!autoMode) {
            // Single provider mode
            return sendRequestForProvider(provider, userContent, systemContent);
        }

        // Auto mode - try providers in priority order
        Exception lastException = null;
        List<String> triedProviders = new ArrayList<>();

        for (String tryProvider : providerPriority) {
            ProviderConfig config = providerConfigs.get(tryProvider);
            if (config == null) continue;

            // Ollama doesn't need API key, others do
            if (!"ollama".equals(tryProvider) && !config.hasApiKey()) {
                continue;
            }

            ProviderHealth health = providerHealth.computeIfAbsent(tryProvider, k -> new ProviderHealth());
            if (!health.isAvailable()) {
                long remaining = health.getCooldownRemaining() / 1000;
                plugin.getLogger().fine("Skipping " + tryProvider + " (cooldown: " + remaining + "s)");
                continue;
            }

            triedProviders.add(tryProvider);

            try {
                String result = sendRequestForProvider(tryProvider, userContent, systemContent);
                health.recordSuccess();

                if (!tryProvider.equals(provider)) {
                    plugin.getLogger().info("AI: Switched to " + tryProvider + " (previous providers unavailable)");
                    this.provider = tryProvider;
                    this.model = config.model;
                    this.endpoint = config.endpoint;
                    this.apiKey = config.apiKey;
                }

                return result;
            } catch (Exception e) {
                health.recordFailure(e.getMessage());
                lastException = e;
                plugin.getLogger().warning("AI provider " + tryProvider + " failed: " + e.getMessage());
            }
        }

        String msg = "All AI providers failed. Tried: " + triedProviders;
        throw new RuntimeException(msg, lastException);
    }

    private String sendRequestForProvider(String providerName, String userContent, String systemContent) throws Exception {
        ProviderConfig config = providerConfigs.get(providerName);
        if (config == null) {
            throw new RuntimeException("Unknown provider: " + providerName);
        }

        return switch (providerName) {
            case "claude" -> sendClaudeRequest(userContent, systemContent, config);
            case "gemini" -> sendGeminiRequest(userContent, systemContent, config);
            case "ollama" -> sendOllamaRequest(userContent, systemContent, config);
            default -> sendOpenAIStyleRequest(userContent, systemContent, config);
        };
    }

    // ==================== PROVIDER IMPLEMENTATIONS ====================

    private String sendOllamaRequest(String userContent, String systemContent, ProviderConfig config) throws Exception {
        URL url = new URL(config.endpoint + "/api/chat");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(config.timeoutSeconds * 1000);
        conn.setDoOutput(true);

        JSONObject payload = new JSONObject();
        payload.put("model", config.model);
        payload.put("stream", false);
        payload.put("keep_alive", config.keepAlive);

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject()
                .put("role", "system")
                .put("content", systemContent));
        messages.put(new JSONObject()
                .put("role", "user")
                .put("content", userContent));
        payload.put("messages", messages);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            String error = readErrorStream(conn);
            throw new RuntimeException("Ollama error: " + responseCode + " - " + error);
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) response.append(line);

            JSONObject json = new JSONObject(response.toString());
            JSONObject message = json.optJSONObject("message");
            if (message == null) {
                throw new RuntimeException("No message in Ollama response");
            }

            String content = message.optString("content", "");
            if (content.isEmpty()) {
                throw new RuntimeException("Empty content in Ollama response");
            }

            return content.trim();
        }
    }

    private String sendClaudeRequest(String userContent, String systemContent, ProviderConfig config) throws Exception {
        URL url = new URL(config.endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-api-key", config.apiKey);
        conn.setRequestProperty("anthropic-version", "2023-06-01");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(config.timeoutSeconds * 1000);
        conn.setDoOutput(true);

        JSONObject payload = new JSONObject();
        payload.put("model", config.model);
        payload.put("max_tokens", 2000);
        payload.put("system", systemContent);

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject()
                .put("role", "user")
                .put("content", userContent));
        payload.put("messages", messages);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            String error = readErrorStream(conn);
            throw new RuntimeException("Claude API error: " + responseCode + " - " + error);
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) response.append(line);

            JSONObject json = new JSONObject(response.toString());
            JSONArray content = json.optJSONArray("content");

            if (content == null || content.isEmpty()) {
                throw new RuntimeException("No content array in Claude response");
            }

            for (int i = 0; i < content.length(); i++) {
                JSONObject block = content.optJSONObject(i);
                if (block != null && "text".equals(block.optString("type"))) {
                    String text = block.optString("text");
                    if (text != null && !text.isEmpty()) {
                        return text.trim();
                    }
                }
            }
            throw new RuntimeException("No text content in Claude response");
        }
    }

    private String sendOpenAIStyleRequest(String userContent, String systemContent, ProviderConfig config) throws Exception {
        URL url = new URL(config.endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + config.apiKey);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(config.timeoutSeconds * 1000);
        conn.setDoOutput(true);

        JSONObject payload = new JSONObject();
        payload.put("model", config.model);
        payload.put("max_tokens", 2000);

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", systemContent));
        messages.put(new JSONObject().put("role", "user").put("content", userContent));
        payload.put("messages", messages);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            String error = readErrorStream(conn);
            throw new RuntimeException("HTTP error: " + responseCode + " - " + error);
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) response.append(line);
            JSONObject json = new JSONObject(response.toString());

            JSONArray choices = json.optJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new RuntimeException("No choices in OpenAI response");
            }

            JSONObject firstChoice = choices.optJSONObject(0);
            if (firstChoice == null) {
                throw new RuntimeException("Invalid choice format in OpenAI response");
            }

            JSONObject message = firstChoice.optJSONObject("message");
            if (message == null) {
                throw new RuntimeException("No message in OpenAI response choice");
            }

            String content = message.optString("content");
            if (content == null || content.isEmpty()) {
                throw new RuntimeException("No content in OpenAI response message");
            }

            return content.trim();
        }
    }

    private String sendGeminiRequest(String userContent, String systemContent, ProviderConfig config) throws Exception {
        String resolvedEndpoint = String.format(config.endpoint, config.model) + "?key=" +
                java.net.URLEncoder.encode(config.apiKey, StandardCharsets.UTF_8);
        URL url = new URL(resolvedEndpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(config.timeoutSeconds * 1000);
        conn.setDoOutput(true);

        JSONObject payload = new JSONObject();
        JSONArray contents = new JSONArray();

        contents.put(new JSONObject()
                .put("role", "user")
                .put("parts", new JSONArray().put(new JSONObject().put("text", systemContent + "\n\n" + userContent))));

        payload.put("contents", contents);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            String error = readErrorStream(conn);
            throw new RuntimeException("Gemini error: " + responseCode + " - " + error);
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
            JSONObject content = candidates.optJSONObject(0);
            if (content == null) {
                throw new RuntimeException("Invalid candidate in Gemini response");
            }
            JSONObject contentObj = content.optJSONObject("content");
            if (contentObj == null) {
                throw new RuntimeException("No content in Gemini candidate");
            }
            JSONArray parts = contentObj.optJSONArray("parts");
            if (parts == null || parts.isEmpty()) {
                throw new RuntimeException("No content parts returned from Gemini API");
            }
            return parts.getJSONObject(0).optString("text", "").trim();
        }
    }

    // ==================== UTILITIES ====================

    private void enforceRateLimit() throws InterruptedException {
        long now = System.currentTimeMillis();
        long timeSinceLastCall = now - lastApiCall;

        if (timeSinceLastCall < MIN_API_INTERVAL_MS) {
            long waitTime = MIN_API_INTERVAL_MS - timeSinceLastCall;
            Thread.sleep(waitTime);
        }

        lastApiCall = System.currentTimeMillis();
    }

    private String readErrorStream(HttpURLConnection conn) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
            StringBuilder error = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) error.append(line);
            return error.toString();
        } catch (Exception e) {
            return "Unable to read error stream";
        }
    }

    // ==================== GETTERS ====================

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public boolean hasApiKey() {
        ProviderConfig config = providerConfigs.get(provider);
        return config != null && config.hasApiKey();
    }

    public boolean isAutoMode() {
        return autoMode;
    }

    public List<String> getProviderPriority() {
        return new ArrayList<>(providerPriority);
    }

    /**
     * Get status of all providers for debugging
     */
    public Map<String, String> getProviderStatus() {
        Map<String, String> status = new LinkedHashMap<>();
        for (String p : providerPriority) {
            ProviderConfig config = providerConfigs.get(p);
            ProviderHealth health = providerHealth.get(p);

            StringBuilder sb = new StringBuilder();
            if (config == null) {
                sb.append("not configured");
            } else if (!"ollama".equals(p) && !config.hasApiKey()) {
                sb.append("no API key");
            } else if (health != null && !health.isAvailable()) {
                sb.append("cooldown (").append(health.getCooldownRemaining() / 1000).append("s)");
            } else {
                sb.append("available");
                if (p.equals(provider)) {
                    sb.append(" [active]");
                }
            }
            status.put(p, sb.toString());
        }
        return status;
    }
}
