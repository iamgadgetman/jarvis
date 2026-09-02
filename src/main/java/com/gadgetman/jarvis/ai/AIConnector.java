package com.gadgetman.jarvis.ai;

import com.gadgetman.jarvis.Jarvis;
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
 * AIConnector - Multi-provider AI integration with tiered routing
 * Version: 0.3.0
 *
 * Supports: OpenAI, Claude, Grok, Gemini, Ollama (local)
 *
 * v0.3.0 — Ollama-first tiered routing:
 * - Every AI call declares a tier. LIGHT (intent parsing, banter) routes to
 *   the local Ollama box first; HEAVY (build planning) routes to the cloud
 *   provider first. Each tier has its own fallback chain.
 * - Provider health tracking with exponential-backoff cooldowns (per provider,
 *   shared across tiers).
 * - LIGHT calls get a short timeout so chat never feels laggy — if the local
 *   box is slow, the call falls through to the next provider (and ChatListener
 *   ultimately falls back to keyword matching).
 * - Ollama requests that need JSON use Ollama's structured "format":"json"
 *   mode — small local models are far more reliable when constrained.
 * - Reduced mode: when Ollama is the ONLY provider, Jarvis makes sacrifices:
 *   freeform AI build planning is off (schematics still work), and risky
 *   steward actions (console, permissions) are declined unless explicitly
 *   re-enabled in config.
 */
public class AIConnector {

    /** Workload tier: decides which provider chain serves the request. */
    public enum Tier { LIGHT, HEAVY }

    /** Output ceiling for chat, intent parsing and JSON block plans. */
    private static final int DEFAULT_MAX_TOKENS = 2000;

    /**
     * Output ceiling for build scripts. A script is prose reasoning plus code,
     * and 2000 tokens truncates one mid-function -- which reads to the parser
     * as a syntax error rather than as "ran out of room".
     */
    private static final int BUILD_SCRIPT_MAX_TOKENS = 16000;

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

    // v0.3.0 tiered routing
    private final List<String> lightRoute = new ArrayList<>();
    private final List<String> heavyRoute = new ArrayList<>();
    private boolean reducedMode = false;
    private boolean ollamaConfigured = false;
    private int lightTimeoutSeconds = 5;
    /**
     * Heavy-tier read timeout. Deliberately far above the per-provider default:
     * a build script is thousands of output tokens and takes tens of seconds,
     * where the 30s that suits chat cuts it off mid-generation. Measured live on
     * Jarvis01 -- a cottage script at claude-sonnet-5 timed out at 30s and the
     * same prompt offline returned 7,074 output tokens.
     */
    private int heavyTimeoutSeconds = 240;
    private final Map<Tier, String> lastServed = new ConcurrentHashMap<>();

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
        List<String> defaultPriority = Arrays.asList("ollama", "claude", "openai", "grok", "gemini");
        List<String> configPriority = ai.getStringList("provider-priority");
        if (configPriority != null && !configPriority.isEmpty()) {
            providerPriority.addAll(configPriority);
        } else {
            providerPriority.addAll(defaultPriority);
        }

        // Load all provider configurations
        loadProviderConfig(ai, "openai", "https://api.openai.com/v1/chat/completions", "gpt-5.6-terra");
        loadProviderConfig(ai, "claude", "https://api.anthropic.com/v1/messages", "claude-haiku-4-5");
        loadProviderConfig(ai, "grok", "https://api.x.ai/v1/chat/completions", "grok-4.6");
        loadProviderConfig(ai, "gemini", "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent", "gemini-3.7-flash");
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

        // ---- v0.3.0 tiered routing ----
        this.ollamaConfigured = ai.isConfigurationSection("ollama");
        this.lightTimeoutSeconds = ai.getInt("light-timeout-seconds", 5);
        this.heavyTimeoutSeconds = ai.getInt("heavy-timeout-seconds", 240);

        List<String> cloud = new ArrayList<>();
        for (String p : providerPriority) {
            if ("ollama".equals(p)) continue;
            ProviderConfig c = providerConfigs.get(p);
            if (c != null && c.hasApiKey()) cloud.add(p);
        }
        this.reducedMode = cloud.isEmpty() && ollamaConfigured;

        lightRoute.clear();
        heavyRoute.clear();
        List<String> cfgLight = ai.getStringList("routing.light");
        List<String> cfgHeavy = ai.getStringList("routing.heavy");

        if (!cfgLight.isEmpty()) {
            lightRoute.addAll(filterUsable(cfgLight));
        } else if (ollamaConfigured) {
            lightRoute.add("ollama");        // light work stays on the home lab
            lightRoute.addAll(cloud);
        } else if (!autoMode && isUsable(provider)) {
            lightRoute.add(provider);
        } else {
            lightRoute.addAll(cloud);
        }

        if (!cfgHeavy.isEmpty()) {
            heavyRoute.addAll(filterUsable(cfgHeavy));
        } else if (!cloud.isEmpty()) {
            heavyRoute.addAll(cloud);        // heavy work goes to the cloud
            if (ollamaConfigured) heavyRoute.add("ollama");
        } else if (ollamaConfigured) {
            heavyRoute.add("ollama");
        } else if (!autoMode && isUsable(provider)) {
            heavyRoute.add(provider);
        }

        // Never leave a route empty if anything at all is usable
        if (lightRoute.isEmpty() && !heavyRoute.isEmpty()) lightRoute.addAll(heavyRoute);
        if (heavyRoute.isEmpty() && !lightRoute.isEmpty()) heavyRoute.addAll(lightRoute);

        // Log configuration
        plugin.getLogger().info("AI routing — light: " + lightRoute + ", heavy: " + heavyRoute
                + (reducedMode ? " [REDUCED MODE: Ollama only]" : ""));
    }

    private boolean isUsable(String p) {
        if (p == null) return false;
        if ("ollama".equals(p)) return ollamaConfigured;
        ProviderConfig c = providerConfigs.get(p);
        return c != null && c.hasApiKey();
    }

    private List<String> filterUsable(List<String> names) {
        List<String> out = new ArrayList<>();
        for (String n : names) {
            if (isUsable(n.toLowerCase())) out.add(n.toLowerCase());
        }
        return out;
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
     * Query AI for a Minecraft build plan, with no retrieved examples.
     */
    public String queryBuildPlan(String description) throws Exception {
        return queryBuildPlan(description, "", false);
    }

    /**
     * Query AI for a Minecraft build plan, optionally primed with plans that
     * worked for similar requests on this server.
     *
     * @param memoryExamples            formatted few-shot examples, or blank for none
     * @param memoryUnlocksReducedMode  when true, freeform planning is allowed even
     *                                  in Ollama-only mode — enough retrieved examples
     *                                  close the gap that the block was there to avoid
     */
    public String queryBuildPlan(String description, String memoryExamples,
                                 boolean memoryUnlocksReducedMode) throws Exception {
        if (reducedMode && !memoryUnlocksReducedMode) {
            throw new ReducedModeException(
                    "Freeform build planning is disabled in Ollama-only mode. Use schematics instead.");
        }

        // The old one-line prompt ("Generate a Minecraft structure as JSON for: X")
        // produced flat, corner-only shapes: a local 7B model returned a 14x8x3
        // "cottage" against a declared 7x8x7, and a watchtower request ran past
        // 180s without finishing. Spelling out what a structure IS fixes both --
        // measured on qwen2.5:7b, declared dimensions then matched actual extents
        // exactly (8x9x8, 8x13x8) and the watchtower came back in 61s.
        String prompt = "Output ONLY valid JSON, no extra text. Design a complete Minecraft "
                + "structure as JSON: {\"dimensions\":{\"width\":int,\"height\":int,\"length\":int},"
                + "\"blocks\":[{\"x\":int,\"y\":int,\"z\":int,\"material\":\"minecraft:stone\"},...]}\n"
                + "Requirements:\n"
                + "- Unless the request clearly asks for something smaller, make it at least 8 blocks "
                + "wide, 8 blocks long and 4 blocks tall.\n"
                + "- List EVERY block explicitly. A wall is every block in it, not just its corners.\n"
                + "- If it is a building, include four walls, a floor, a roof, a door gap and at least "
                + "two windows.\n"
                + "- Use only real placeable block ids (minecraft:oak_planks, minecraft:bricks, "
                + "minecraft:glass). Never item ids such as minecraft:brick.\n"
                + "- Coordinates are relative to 0,0,0 and y increases upward.\n"
                + "Structure to design: " + description;

        if (memoryExamples != null && !memoryExamples.isBlank()) {
            prompt = memoryExamples + "\n" + prompt;
        }

        return sendTiered(Tier.HEAVY, prompt, "You are a Minecraft build planner. Output ONLY valid JSON.", true);
    }

    /**
     * Literal commands that need no model at all.
     *
     * <p>Ported from the `dev` branch, minus its `quest_status` entries -- that
     * action belongs to a quests package this branch does not have, and
     * emitting it would produce an action the dispatcher cannot route. Extended
     * to cover the commands this branch actually dispatches, which is most of
     * the value: the original map predated the farmer, fisherman, lumberjack,
     * lamplighter and steward.
     *
     * <p>Every value here must match a case in ChatListener's dispatcher.
     */
    private static final Map<String, String> FAST_ACTIONS = Map.ofEntries(
            Map.entry("summon", "summon"), Map.entry("come here", "summon"),
            Map.entry("come", "summon"), Map.entry("here boy", "summon"),
            Map.entry("dismiss", "dismiss"), Map.entry("go away", "dismiss"),
            Map.entry("leave", "dismiss"), Map.entry("begone", "dismiss"),
            Map.entry("follow", "follow"), Map.entry("follow me", "follow"),
            Map.entry("return", "return"), Map.entry("come back", "return"),
            Map.entry("attack", "attack"), Map.entry("fight", "attack"),
            Map.entry("guard", "guard"), Map.entry("defend me", "guard"),
            Map.entry("protect me", "guard"),
            Map.entry("watch", "watch"), Map.entry("night watch", "watch"),
            Map.entry("stand down", "stand_down"), Map.entry("at ease", "stand_down"),
            Map.entry("mine", "mine"), Map.entry("start mining", "mine"),
            Map.entry("mine here", "mine_here"), Map.entry("branch mine", "mine_here"),
            Map.entry("stop", "stop"), Map.entry("halt", "stop"),
            Map.entry("stop it", "stop"), Map.entry("cancel", "stop"),
            Map.entry("clearloot", "clearloot"), Map.entry("drop loot", "clearloot"),
            Map.entry("drop everything", "clearloot"),
            Map.entry("deposit", "deposit"), Map.entry("unload", "deposit"),
            Map.entry("loot", "loot"), Map.entry("inventory", "loot"),
            Map.entry("fish", "fish"), Map.entry("go fishing", "fish"),
            Map.entry("chop", "chop"), Map.entry("chop trees", "chop"),
            Map.entry("farm", "farm"), Map.entry("tend", "tend"),
            Map.entry("dance", "dance"), Map.entry("patrol", "patrol"),
            Map.entry("report", "report"), Map.entry("recover", "recover"),
            Map.entry("take me home", "take_home"), Map.entry("set home", "set_home"),
            Map.entry("light", "light"), Map.entry("light the area", "light"));

    private static final Set<String> FAST_ORES = Set.of(
            "diamond", "emerald", "gold", "iron", "copper", "redstone",
            "lapis", "quartz", "coal", "netherite", "ancient debris");

    /** A little variety so the canned replies don't read as canned. */
    private static final Map<String, String[]> FAST_QUIPS = Map.ofEntries(
            Map.entry("summon",     new String[]{"At your service. Again.", "You rang.", "Materialising. Try to look busy."}),
            Map.entry("dismiss",    new String[]{"Finally.", "I shall be elsewhere. Anywhere else.", "Dismissed. Do call less often."}),
            Map.entry("follow",     new String[]{"Right behind you. Regrettably.", "Following. Mind the lava."}),
            Map.entry("return",     new String[]{"Returning. Do try to stay put.", "On my way back to you."}),
            Map.entry("attack",     new String[]{"Violence. How predictable.", "Engaging. Do stand back."}),
            Map.entry("guard",      new String[]{"Guarding. Try not to need it.", "I shall keep watch over you."}),
            Map.entry("watch",      new String[]{"Taking the night watch, sir.", "The post is mine. Sleep well."}),
            Map.entry("stand_down", new String[]{"Standing down. Reluctantly.", "At ease, then."}),
            Map.entry("mine",       new String[]{"Manual labour. How delightfully medieval.", "Digging. Because you asked nicely."}),
            Map.entry("mine_here",  new String[]{"A proper mine, then. Mind the drop.", "Sinking a shaft. Do keep clear."}),
            Map.entry("stop",       new String[]{"Stopping. Second thoughts already?", "Halted.", "As you wish. Again."}),
            Map.entry("clearloot",  new String[]{"Dropping everything. Literally.", "Your hoard, unhoarded."}),
            Map.entry("deposit",    new String[]{"Off to the chest with it.", "Unloading. The bags were getting heavy."}),
            Map.entry("loot",       new String[]{"My bags, such as they are.", "Behold, the haul."}),
            Map.entry("fish",       new String[]{"Fishing. The height of ambition.", "To the water, then."}),
            Map.entry("chop",       new String[]{"Timber, presently.", "Off to fell something."}),
            Map.entry("farm",       new String[]{"To the fields.", "Farming. How pastoral."}),
            Map.entry("tend",       new String[]{"Tending the crops, sir.", "The fields shall have my attention."}),
            Map.entry("dance",      new String[]{"If I must.", "Observe. Briefly."}),
            Map.entry("patrol",     new String[]{"Patrolling. Someone has to.", "Making the rounds."}),
            Map.entry("report",     new String[]{"The briefing, sir.", "Your situation, in brief."}),
            Map.entry("recover",    new String[]{"Retrieving your things. Again.", "Off to the scene of your misfortune."}),
            Map.entry("take_home",  new String[]{"Homeward. Do keep up.", "Leading you home, sir."}),
            Map.entry("set_home",   new String[]{"Noted as home.", "This spot is now home. Ambitious."}),
            Map.entry("light",      new String[]{"Let there be light. Grudgingly.", "Lighting the place up."}));

    /** Token usage from the last provider call, for ai.log-usage. */
    private volatile String lastUsage;

    private final java.util.concurrent.atomic.AtomicInteger quipCounter =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Resolve a literal command locally, skipping the model entirely.
     *
     * <p>This is the bulk of chat traffic, and none of it needs a model. The
     * saving is latency rather than money -- the light tier goes to Ollama
     * first, which is free but takes a second or two -- so "jarvis follow"
     * answers instantly instead of after a round trip.
     *
     * @return the same JSON shape the model would produce, or null when the
     *         phrasing is anything but an exact known command
     */
    private String tryFastPath(String message) {
        if (message == null) return null;
        String m = message.toLowerCase(Locale.ROOT).trim().replaceAll("[.!?]+$", "");
        if (m.startsWith("jarvis")) m = m.substring(6).replaceAll("^[,\\s]+", "").trim();
        if (m.isEmpty()) return null;

        String action = FAST_ACTIONS.get(m);
        JSONObject parameters = new JSONObject();

        // "mine diamond" / "mine ancient debris" -- the one parameterised form.
        if (action == null && m.startsWith("mine ")) {
            String ore = m.substring(5).replaceFirst("^(some |for |me )+", "").trim();
            if (ore.equals("debris")) ore = "ancient debris";
            // Only de-pluralise if the literal form isn't already an ore --
            // "ancient debris" must not become "ancient debri".
            if (!FAST_ORES.contains(ore) && ore.endsWith("s")) {
                ore = ore.substring(0, ore.length() - 1);
            }
            if (FAST_ORES.contains(ore)) {
                action = "mine";
                parameters.put("ore", ore);
            }
        }
        if (action == null) return null;

        String[] quips = FAST_QUIPS.get(action);
        String quip = quips == null ? "Very good, sir."
                : quips[Math.floorMod(quipCounter.getAndIncrement(), quips.length)];
        if (parameters.has("ore")) {
            quip = "Off to find " + parameters.getString("ore") + ". Try to contain yourself.";
        }

        return new JSONObject()
                .put("action", action)
                .put("parameters", parameters)
                .put("response", quip)
                .toString();
    }

    /**
     * Ask the AI for a JavaScript build script.
     *
     * <p>This is the freeform build path as of v0.9.0. It replaces asking for a
     * list of every block, which does not work: a model spends its whole output
     * budget enumerating coordinates and runs out somewhere around the floor.
     * v0.8.6's best measured plan was 168 blocks -- a footprint with no walls.
     * The same cottage as {@code fill} calls is roughly a dozen lines, because
     * a wall is one call rather than four hundred coordinates.
     *
     * <p>The contract is adapted from MC-Bench, the public LLM build-off
     * benchmark, which is the strongest evidence available for what actually
     * makes models build well. Two details there are load-bearing and are kept
     * here: the model is addressed as an architect and told to weigh accents,
     * symmetry and material variety, and it must describe the design in prose
     * <em>before</em> writing code.
     *
     * @param memoryExamples formatted few-shot examples, or blank for none
     * @param previousScript a script that failed, or null on the first attempt
     * @param previousError  why it failed, fed back verbatim so the model can repair it
     */
    public String queryBuildScript(String description, String memoryExamples,
                                   String previousScript, String previousError) throws Exception {
        if (reducedMode) {
            // A 7B writes bad JavaScript. v0.8.6 measured qwen2.5-coder:7b as
            // worse than qwen2.5:7b at spatial layout, so there is no local
            // model here worth falling back to -- the JSON planner is the
            // honest reduced-mode answer.
            throw new ReducedModeException(
                    "Script building needs a cloud model. Set build.planner to json, or use schematics.");
        }

        String system = """
                You are an expert Minecraft architect and builder.

                You control a builder through two functions. Implement buildCreation; do not call it yourself.

                  fill(x1, y1, z1, x2, y2, z2, block, mode)
                      Fills the box between the two corners, inclusive. mode is optional:
                        "solid"   (default) every block in the box
                        "walls"   the four upright sides only -- no floor, no ceiling
                        "hollow"  the whole shell, INCLUDING its floor and ceiling faces
                        "outline" the twelve edges only
                      For a room, use "walls". "hollow" would pave the floor you stand on
                      and cap the room with a ceiling.
                  setBlock(x, y, z, block)
                      Places one block.
                  function buildCreation(x, y, z) { }
                      x, y, z are the build origin.

                y is the empty space a player standing at the origin occupies, and the
                ground they are standing on is the block at y-1.

                So the floor goes at y-1, replacing that ground, and EVERYTHING else --
                interior, walls, door, furniture -- starts at y. Do not put a floor at y:
                that is the air the player is standing in, and filling it buries them and
                lifts the whole building a block off the ground. Lay exactly one floor
                layer, and add no foundation beneath it unless asked.

                This means the walls of a room are `fill(..., "walls")` from y up, over a
                floor laid at y-1. Nothing else may write to the y level inside the room.

                Block ids may carry states, exactly as the /setblock command accepts them:
                  "oak_planks", "minecraft:stone_bricks", "oak_stairs[facing=north,half=bottom]",
                  "oak_log[axis=y]", "oak_slab[type=top]", "glass_pane", "air"
                Use real placeable block ids. Never item ids -- "bricks" is the block, "brick" is an item.

                Roofs are where builds most often go wrong, so follow this exactly.

                A stair's `facing` points UP the slope, toward the ridge -- the tall half of
                the block is on the side named by `facing`. So the slope that descends toward
                the north is built from stairs with facing=south, and the slope opposite it
                uses facing=north. Point them down-slope instead and the whole roof is
                inside out.

                Each course must be a full-length `fill` along the ridge axis, one block higher
                and one block inward from the course below. The FIRST course sits directly on
                top of the wall, at the wall's own line -- not outside it. Starting the roof
                beyond the wall leaves a hole between wall and roof, and placing courses
                individually leaves them standing apart like fins. This is a correct gable:

                  const mid = Math.floor((d - 1) / 2);           // d = depth in z
                  for (let i = 0; i < mid; i++) {
                    const yy = wallTop + 1 + i;
                    // close the gable triangle at each end, or the roof space is open to the sky
                    for (const ex of [x, x+w-1]) {
                      fill(ex, yy, z+i+1, ex, yy, z+d-2-i, "oak_planks");
                    }
                    // z+i descends toward north, so it faces south, back up to the ridge
                    fill(x-1, yy, z+i,       x+w, yy, z+i,       "oak_stairs[facing=south]");
                    fill(x-1, yy, z+d-1-i,   x+w, yy, z+d-1-i,   "oak_stairs[facing=north]");
                  }
                  fill(x-1, wallTop+1+mid, z+mid, x+w, wallTop+1+mid, z+mid, "oak_planks");

                The two ends of a pitched roof are open triangles until you fill them. Skip
                that and the room is open to the sky along both gables.

                which gives this profile, walls to ridge with nothing floating:

                  y=7  . . . # . . .
                  y=6  . . s . n . .
                  y=5  . s . . . n .
                  y=4  s . . . . . n     <- resting on the wall tops
                  y=3  #           #

                Blocks that hang on something. A torch, button, lever, sign or ladder is
                supported by a neighbouring block and must go in the empty space NEXT to it,
                never at the wall's own coordinate -- writing it there deletes the wall and
                leaves a hole. A wall torch is held up by the block behind it, opposite its
                facing: to light a north wall from inside a room, put a
                wall_torch[facing=south] one block SOUTH of that wall.

                Blocks that occupy two spaces. A bed is a foot and a head, and the head sits
                one block from the foot in the direction of `facing` -- facing=east means the
                head is one block EAST, not one block north. A door is two blocks stacked,
                half=lower then half=upper directly above it. Place both halves and make the
                offset agree with `facing`, or the two halves face different ways.

                Windows. Cut the opening in the wall and put the glass in it. A single
                glass_pane in a one-block hole is fine -- it is joined up to the wall around it
                after the build. Use `glass` rather than `glass_pane` when you want a flush,
                solid-looking window.

                Put windows at y+1. A player standing on the floor has their eyes at about
                y+1.6, so y+1 is the height you actually look through; y+2 sits above eye line
                and reads as a gap under the eaves. Hang wall torches at y+2, above the
                windows, so the two do not compete for the same band of wall.

                Rules:
                - Later writes win, so fill a wall and then set air over it to carve a doorway.
                - Coordinates are relative to the origin and must stay within a few dozen blocks of it.
                - Every block id must be one that really exists. Guessing by pattern is where
                  builds go wrong: it is `polished_blackstone_bricks`, not `blackstone_bricks`;
                  `bricks`, not `brick`; `stone_bricks`, not `stone_brick`. If you are unsure of
                  a decorative variant, use the plain block rather than inventing a name.
                - Ordinary JavaScript is available: loops, math, arrays, functions. Use them.
                  There is no DOM, no require, no host access, and no console.

                Write two short paragraphs first -- your influences, then how the finished build looks --
                and then the code in a single ```javascript block.
                """;

        String prompt;
        if (previousScript != null && previousError != null) {
            // Repairing beats regenerating: the design reasoning in the failed
            // attempt is usually sound and only the code broke.
            prompt = "Your previous script for \"" + description + "\" failed.\n\n"
                    + "```javascript\n" + previousScript + "\n```\n\n"
                    + "The error was:\n" + previousError + "\n\n"
                    + "Fix it and return the complete corrected script. Keep the design; "
                    + "change only what the error requires.";
        } else {
            prompt = "Build: " + description;
            if (memoryExamples != null && !memoryExamples.isBlank()) {
                prompt = memoryExamples + "\n" + prompt;
            }
        }

        return sendTiered(Tier.HEAVY, prompt, system, false, BUILD_SCRIPT_MAX_TOKENS);
    }

    /**
     * Pulls the JavaScript out of a model reply.
     *
     * <p>The prompt asks for prose and then a fenced block, so the prose has to
     * come off before the script reaches the engine. A model that skips the
     * fence still usually produces something starting at a function keyword,
     * which is worth salvaging rather than failing on.
     */
    public static String extractScript(String response) {
        if (response == null) return null;

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("```(?:javascript|js)?\\s*\\n(.*?)```", java.util.regex.Pattern.DOTALL)
                .matcher(response);
        String best = null;
        while (m.find()) {
            String block = m.group(1);
            // Take the largest fenced block: a reply sometimes shows a short
            // illustrative snippet before the real implementation.
            if (best == null || block.length() > best.length()) best = block;
        }
        if (best != null) return best.strip();

        int fn = response.indexOf("function buildCreation");
        if (fn < 0) fn = response.indexOf("async function buildCreation");
        return fn >= 0 ? response.substring(fn).strip() : null;
    }

    /** Thrown when a capability is unavailable in Ollama-only reduced mode. */
    public static class ReducedModeException extends Exception {
        public ReducedModeException(String message) { super(message); }
    }

    /**
     * Parse natural language command into structured action
     */
    public String parseNaturalLanguage(String message, String playerName, String context) throws Exception {
        // Literal commands don't need a model. This is the bulk of chat traffic.
        String fastPath = tryFastPath(message);
        if (fastPath != null) return fastPath;

        String systemPrompt = JARVIS_PERSONALITY + "\n\n" +
                "TASK: Parse player commands into JSON actions. Maintain your sarcastic butler persona in the response field.\n" +
                "Output format: {\"action\":\"...\",\"parameters\":{...},\"response\":\"your witty 1-2 sentence response\"}\n\n" +
                "NPC ACTIONS (control the physical Jarvis NPC):\n" +
                "  summon - bring Jarvis to the player\n" +
                "  dismiss - send Jarvis away\n" +
                "  return - come back to the player once\n" +
                "  follow - follow the player continuously, carrying their loot\n" +
                "  guard - bodyguard mode, params: {stance: passive|defensive|aggressive} (default defensive)\n" +
                "  watch - night watch: hold current position as a sentry, params: {stance (optional)}\n" +
                "  attack - weapons free (aggressive guard)\n" +
                "  stand_down - stop fighting, observe only\n" +
                "  mine - hunt nearby exposed ores\n" +
                "  mine_here - dig a full torch-lit branch mine at the current spot\n" +
                "  deposit - carry collected loot to the player's registered chest\n" +
                "  set_chest - register the chest the player is looking at for deposits\n" +
                "  stop - stop current task\n" +
                "  report - deliver the server status briefing (TPS, players, weather)\n" +
                "  recover - retrieve the player's death drops from where they died\n" +
                "  take_home - escort the player back to their saved home point\n" +
                "  set_home - save the player's current spot as home\n" +
                "  farm - harvest & replant the field once, params: {crop (optional): wheat|carrots|potatoes|beetroot|nether wart|melon|pumpkin}\n" +
                "  tend - stay on as a farmhand, harvesting as crops mature, params: {crop (optional)}\n" +
                "  chop - fell trees and replant saplings, params: {count (optional, default 5)}\n" +
                "  fish - fish at the nearest water\n" +
                "  dance - perform the dance\n" +
                "  patrol - walk the saved patrol circuit as a guard\n" +
                "  light - place lights on a grid to spawn-proof the area, params: {radius (optional, blocks), type (optional): torch|end_rod|lantern, spacing (optional, blocks)}\n" +
                "  clearloot - drop all collected items\n" +
                "  build - build a structure, params: {description: string}\n\n" +
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
                "  'dig a mine here' -> {\"action\":\"mine_here\",\"response\":\"One proper mine, coming up. Do admire the torchwork.\"}\n" +
                "  'follow me' -> {\"action\":\"follow\",\"response\":\"Right behind you, sir. As always.\"}\n" +
                "  'protect me' -> {\"action\":\"guard\",\"parameters\":{\"stance\":\"defensive\"},\"response\":\"At your side, sir. Nothing touches you.\"}\n" +
                "  'weapons free' -> {\"action\":\"attack\",\"response\":\"With pleasure, sir.\"}\n" +
                "  'keep watch tonight' -> {\"action\":\"watch\",\"response\":\"I shall hold this post until dawn, sir.\"}\n" +
                "  'stand down' -> {\"action\":\"stand_down\",\"response\":\"Standing down, sir. Observing only.\"}\n" +
                "  'how are things' -> {\"action\":\"report\",\"response\":\"The briefing, sir.\"}\n" +
                "  'get my stuff back' -> {\"action\":\"recover\",\"response\":\"On my way, sir. Do try to stay alive this time.\"}\n" +
                "  'take me home' -> {\"action\":\"take_home\",\"response\":\"This way, sir. I shall light the road.\"}\n" +
                "  'farm the carrots' -> {\"action\":\"farm\",\"parameters\":{\"crop\":\"carrots\"},\"response\":\"To the fields, sir. The carrots won't harvest themselves. Well — now they will.\"}\n" +
                "  'chop some wood' -> {\"action\":\"chop\",\"parameters\":{\"count\":5},\"response\":\"Timber duty, sir. Mind the falling trees.\"}\n" +
                "  'dance for me' -> {\"action\":\"dance\",\"response\":\"Very well, sir. Observe.\"}\n" +
                "  'light this place up' -> {\"action\":\"light\",\"response\":\"Illumination, sir. The rabble spawn in the dark — let's deny them the pleasure.\"}\n" +
                "  'torch the area with end rods, big radius' -> {\"action\":\"light\",\"parameters\":{\"radius\":32,\"type\":\"end_rod\"},\"response\":\"End rods it is, sir. Rather chic.\"}\n" +
                "  'put your stuff in the chest' -> {\"action\":\"deposit\",\"response\":\"Delivering the goods, sir.\"}\n" +
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
        return sendTiered(Tier.LIGHT, userPrompt, systemPrompt, true);
    }

    /**
     * v0.5.0: pick the best-matching schematic from the library for a build
     * request. Constrained choice — exactly what small local models are good
     * at, so this works in reduced mode too. Returns the schematic name, or
     * null if nothing fits.
     */
    public String pickSchematic(String description, java.util.List<String> schematicNames) throws Exception {
        if (schematicNames.isEmpty()) return null;
        String systemPrompt = "You match Minecraft build requests to schematic files. "
                + "Respond ONLY with JSON: {\"schematic\":\"<exact name from the list>\"} "
                + "or {\"schematic\":\"none\"} if nothing on the list fits the request.";
        String userPrompt = "Available schematics: " + String.join(", ", schematicNames)
                + "\nBuild request: \"" + description + "\"";
        String response = sendTiered(Tier.LIGHT, userPrompt, systemPrompt, true);
        try {
            String pick = new JSONObject(response).optString("schematic", "none").trim();
            if (pick.isEmpty() || pick.equalsIgnoreCase("none")) return null;
            for (String name : schematicNames) {
                if (name.equalsIgnoreCase(pick)) return name;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Generate dialogue response with Jarvis personality
     */
    public String generateDialogue(String playerMessage, String npcContext) throws Exception {
        String systemPrompt = JARVIS_PERSONALITY + "\n\nCurrent context: " + npcContext +
                "\n\nRespond in character as Jarvis. Keep response to 1-2 sentences.";

        return sendTiered(Tier.LIGHT, playerMessage, systemPrompt, false);
    }

    /**
     * Send a simple request to AI (for general questions)
     */
    public String sendSimpleRequest(String prompt) throws Exception {
        String systemPrompt = JARVIS_PERSONALITY +
                "\n\nAnswer the player's question helpfully but maintain your sarcastic wit.";
        return sendTiered(Tier.LIGHT, prompt, systemPrompt, false);
    }

    // ==================== REQUEST ROUTING ====================

    /**
     * Tiered request routing. LIGHT tier: short timeout, Ollama-first by
     * default. HEAVY tier: cloud-first, provider timeout. Per-provider
     * health cooldowns are shared across tiers.
     */
    private String sendTiered(Tier tier, String userContent, String systemContent, boolean jsonMode) throws Exception {
        return sendTiered(tier, userContent, systemContent, jsonMode, DEFAULT_MAX_TOKENS);
    }

    /**
     * @param maxTokens output ceiling for providers that require one. Chat and
     *                  intent parsing fit in the default; a build script does
     *                  not, and silently truncating one produces a script that
     *                  will not parse.
     */
    private String sendTiered(Tier tier, String userContent, String systemContent, boolean jsonMode,
                              int maxTokens) throws Exception {
        enforceRateLimit();

        List<String> route = tier == Tier.LIGHT ? lightRoute : heavyRoute;
        if (route.isEmpty()) {
            throw new RuntimeException("No AI providers configured. Set an API key or an ollama section in config.yml.");
        }

        int timeoutOverride = tier == Tier.LIGHT ? lightTimeoutSeconds : heavyTimeoutSeconds;
        Exception lastException = null;
        List<String> triedProviders = new ArrayList<>();

        for (String tryProvider : route) {
            ProviderHealth health = providerHealth.computeIfAbsent(tryProvider, k -> new ProviderHealth());
            if (!health.isAvailable()) {
                plugin.getLogger().fine("Skipping " + tryProvider + " (cooldown: "
                        + health.getCooldownRemaining() / 1000 + "s)");
                continue;
            }

            triedProviders.add(tryProvider);

            try {
                lastUsage = null;
                String result = sendRequestForProvider(tryProvider, userContent, systemContent, jsonMode,
                        timeoutOverride, maxTokens);
                health.recordSuccess();

                if (lastUsage != null && plugin.getConfig().getBoolean("ai.log-usage", false)) {
                    plugin.getLogger().info("AI usage [" + tier + "/" + tryProvider + "] " + lastUsage);
                }
                lastServed.put(tier, tryProvider);

                // Keep the debug getters pointing at whoever answered last
                ProviderConfig config = providerConfigs.get(tryProvider);
                this.provider = tryProvider;
                if (config != null) {
                    this.model = config.model;
                    this.endpoint = config.endpoint;
                    this.apiKey = config.apiKey;
                }
                return result;
            } catch (Exception e) {
                health.recordFailure(e.getMessage());
                lastException = e;
                plugin.getLogger().warning("AI provider " + tryProvider + " failed ("
                        + tier + " tier): " + e.getMessage());
            }
        }

        throw new RuntimeException("All AI providers failed for " + tier + " tier. Tried: " + triedProviders, lastException);
    }

    private String sendRequestForProvider(String providerName, String userContent, String systemContent,
                                          boolean jsonMode, int timeoutOverrideSeconds,
                                          int maxTokens) throws Exception {
        ProviderConfig config = providerConfigs.get(providerName);
        if (config == null) {
            throw new RuntimeException("Unknown provider: " + providerName);
        }
        int readTimeoutMs = (timeoutOverrideSeconds > 0 ? timeoutOverrideSeconds : config.timeoutSeconds) * 1000;

        return switch (providerName) {
            case "claude" -> sendClaudeRequest(userContent, systemContent, config, readTimeoutMs, maxTokens);
            case "gemini" -> sendGeminiRequest(userContent, systemContent, config, readTimeoutMs);
            case "ollama" -> sendOllamaRequest(userContent, systemContent, config, jsonMode, readTimeoutMs);
            default -> sendOpenAIStyleRequest(userContent, systemContent, config, readTimeoutMs, maxTokens);
        };
    }

    // ==================== PROVIDER IMPLEMENTATIONS ====================

    private String sendOllamaRequest(String userContent, String systemContent, ProviderConfig config,
                                     boolean jsonMode, int readTimeoutMs) throws Exception {
        URL url = new URL(config.endpoint + "/api/chat");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(readTimeoutMs);
        conn.setDoOutput(true);

        JSONObject payload = new JSONObject();
        payload.put("model", config.model);
        payload.put("stream", false);
        payload.put("keep_alive", config.keepAlive);
        if (jsonMode) {
            // Structured output: constrains small local models to valid JSON
            payload.put("format", "json");
        }

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

    private String sendClaudeRequest(String userContent, String systemContent, ProviderConfig config,
                                     int readTimeoutMs, int maxTokens) throws Exception {
        URL url = new URL(config.endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-api-key", config.apiKey);
        conn.setRequestProperty("anthropic-version", "2023-06-01");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(readTimeoutMs);
        conn.setDoOutput(true);

        JSONObject payload = new JSONObject();
        payload.put("model", config.model);
        payload.put("max_tokens", maxTokens);
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

            // Stashed rather than logged here: the tier is what makes the
            // number useful (a build is ~7k output tokens, a chat reply a
            // couple of hundred), and only sendTiered knows which one ran.
            JSONObject usage = json.optJSONObject("usage");
            lastUsage = usage == null ? null : String.format(
                    "in=%d out=%d cache_write=%d cache_read=%d",
                    usage.optInt("input_tokens"),
                    usage.optInt("output_tokens"),
                    usage.optInt("cache_creation_input_tokens"),
                    usage.optInt("cache_read_input_tokens"));

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

    private String sendOpenAIStyleRequest(String userContent, String systemContent, ProviderConfig config,
                                          int readTimeoutMs, int maxTokens) throws Exception {
        URL url = new URL(config.endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + config.apiKey);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(readTimeoutMs);
        conn.setDoOutput(true);

        JSONObject payload = new JSONObject();
        payload.put("model", config.model);
        payload.put("max_tokens", maxTokens);

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

    private String sendGeminiRequest(String userContent, String systemContent, ProviderConfig config,
                                     int readTimeoutMs) throws Exception {
        String resolvedEndpoint = String.format(config.endpoint, config.model) + "?key=" +
                java.net.URLEncoder.encode(config.apiKey, StandardCharsets.UTF_8);
        URL url = new URL(resolvedEndpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(readTimeoutMs);
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

    public boolean isReducedMode() {
        return reducedMode;
    }

    public List<String> getLightRoute() {
        return new ArrayList<>(lightRoute);
    }

    public List<String> getHeavyRoute() {
        return new ArrayList<>(heavyRoute);
    }

    /** Which provider answered the last request of this tier (null if none yet). */
    public String getLastServed(Tier tier) {
        return lastServed.get(tier);
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
