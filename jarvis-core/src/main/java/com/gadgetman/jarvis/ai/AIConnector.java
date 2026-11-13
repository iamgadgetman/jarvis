package com.gadgetman.jarvis.ai;

import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Log;
import org.json.JSONArray;
import org.json.JSONObject;

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

    private final Config config;
    private final Log log;

    // Current provider settings
    private String provider;
    private String apiKey;
    private String model;
    private String endpoint;

    // Sent as x-jarvis-server to Jarvis Cloud (gc_ keys only), which binds a
    // license to one install. Null until core hands it over.
    private volatile String installId;

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

    /** Where {@code env:NAME} keys and the default key variables are read from (FB-09). */
    private Map<String, String> env = System.getenv();
    /** {@code ai.env-keys}: a blank api-key may be read from the provider's usual variable. */
    private boolean envKeys = true;

    public AIConnector(Config config, Log log) {
        this.config = config;
        this.log = log;
        reloadConfig();
    }

    /** Tests: read keys from this map instead of the real environment, and reload. */
    public void useEnvironment(Map<String, String> env) {
        this.env = env;
        reloadConfig();
    }

    /** The environment keys are read from (for {@link ProviderProbe}). */
    public Map<String, String> environment() {
        return env;
    }

    /** Whether a blank api-key falls back to the provider's usual variable. */
    public boolean envKeys() {
        return envKeys;
    }

    public void reloadConfig() {
        if (!config.isSection("ai")) {
            log.warn("Missing ai configuration section; using defaults.");
        }
        Config ai = config.section("ai");

        // Check for auto mode
        String configProvider = ai.getString("provider", "openai").toLowerCase();
        this.autoMode = "auto".equals(configProvider);

        // Load provider priority for auto mode
        providerPriority.clear();
        List<String> defaultPriority = KNOWN_PROVIDERS;
        List<String> configPriority = ai.getStringList("provider-priority");
        if (configPriority != null && !configPriority.isEmpty()) {
            providerPriority.addAll(configPriority);
        } else {
            providerPriority.addAll(defaultPriority);
        }

        // Load all provider configurations
        this.envKeys = ai.getBoolean("env-keys", true);
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
        this.ollamaConfigured = ai.isSection("ollama");
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
        log.info("AI routing — light: " + lightRoute + ", heavy: " + heavyRoute
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

    private void loadProviderConfig(Config ai, String name, String defaultEndpoint, String defaultModel) {
        ProviderConfig config = new ProviderConfig();

        if (ai.isSection(name)) {
            Config section = ai.section(name);
            config.apiKey = ApiKeys.resolve(section.getString("api-key", ""), name, env, envKeys);
            config.model = section.getString("model", defaultModel);
            String configEndpoint = section.getString("endpoint");
            config.endpoint = (configEndpoint != null && !configEndpoint.isBlank()) ? configEndpoint : defaultEndpoint;
            config.keepAlive = section.getString("keep-alive", "5m");
            config.timeoutSeconds = section.getInt("timeout-seconds", 30);
        } else {
            config.apiKey = ApiKeys.resolve("", name, env, envKeys);
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

    /** System prompt for JSON shape-plan builds (queryBuildPlan). */
    static final String PLAN_SYSTEM_PROMPT = """
            You are an expert Minecraft architect. You answer with ONE JSON object and nothing else:
            {"ops":[ ... ]}. Each op is a shape; later ops overwrite earlier ones at the same spot.

              {"op":"fill",   "from":[x,y,z], "to":[x,y,z], "block":"oak_planks"}   solid box, corners inclusive
              {"op":"walls",  "from":[x,y,z], "to":[x,y,z], "block":"..."}          the four upright sides only
              {"op":"hollow", "from":[x,y,z], "to":[x,y,z], "block":"..."}          the whole shell, floor and ceiling too
              {"op":"clear",  "from":[x,y,z], "to":[x,y,z]}                         air: carve a doorway, window or room
              {"op":"set",    "at":[x,y,z], "block":"wall_torch[facing=south]"}     one block
              {"op":"door",   "at":[x,y,z], "facing":"south", "block":"oak_door"}   both halves, at the lower one
              {"op":"bed",    "at":[x,y,z], "facing":"east", "block":"red_bed"}     foot at 'at', head one block along facing
              {"op":"roof",   "from":[x,y,z], "to":[x,y,z], "block":"oak_stairs", "gable":"oak_planks"}
                  A pitched roof over that rectangle. Give the WALL's own rectangle with y one above the
                  wall tops; courses of stairs step in and up to a ridge, and the end triangles are closed.

            Coordinates are relative to the origin. y is the empty space the player stands in and the
            ground is y-1: lay exactly one floor at y-1, then walls, door, furniture start at y. Never
            fill y inside a room. North is -z, south +z, east +x, west -x. Block ids are the real ones the
            /setblock command takes, with states in brackets where they matter: oak_planks, stone_bricks,
            glass_pane, oak_log[axis=y], oak_stairs[facing=north], lantern[hanging=true]. Never item ids
            ("brick" is an item; the block is "bricks"). If unsure of a fancy variant, use the plain block.

            Design rules:
            - Unless asked for something tiny, a building is at least 8 wide, 8 long and 4 tall inside.
            - Walls, then cut windows with "clear" and fill them with glass_pane at y+1 (eye height),
              then a door. Windows and a door on the front at least.
            - A roof on every building, overhanging the walls, and no room open to the sky.
            - FURNISH IT. A place someone lives in has, at minimum: a bed, a chest, a crafting table,
              wall torches or lanterns so it is lit inside (torches go in the empty space NEXT to a
              wall, facing away from it, never at the wall's own coordinate), and something on the
              walls or floor: bookshelves, flower pots, a carpet, a painting-sized frame of a different
              block, a fireplace of bricks with a campfire. A mansion, inn, castle or manor has several
              rooms and EACH is furnished for its purpose: beds in bedrooms, a kitchen with a furnace,
              smoker and barrels, a hall with a long table of slabs and stairs as chairs, a library
              of bookshelves. Fences make railings and table legs; stairs make chairs; slabs make
              tables and counters; trapdoors make shutters. A tower has a way up: ladders or stairs.
            - Every window opening holds glass or glass_pane, never a trapdoor or plank. Shutters are
              open trapdoors on the outside of the wall BESIDE the window, not in it.
            - A stair used as a chair stands alone, one high, next to a table, its facing pointing AWAY
              from the table (chair west of the table: facing=west); facing the table puts its back to it.
              A flight of stairs exists only to reach another floor; never stack stairs in a one-storey room.
            - No cobwebs unless the request asks for something abandoned, haunted, ruined or old.
            - Outside: a path of a different block to the door, and a lantern or two by it.
            - Keep a path two blocks high to every door and through every room; nothing stands or
              hangs in a doorway or an aisle, and anything hung from above stays at y+2 or higher.
            - If the roof leaves more than three blocks above the walls, use it: a loft reached by
              stairs or a ladder, or a room open to the rafters with lights hung from the roof.
            - Keep every op inside a few dozen blocks of the origin. Twenty to sixty ops is normal.
            """;

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

        // The first version of this planner asked for every block by hand
        // ("blocks":[{"x":..,"y":..,"z":..,"material":..},...]). Models tire
        // of that a wall and a half in, leave the door as a gap, and run
        // into the output cap. Now the model writes shapes and ShapePlan
        // enumerates them, which is what the script planner's JavaScript
        // does without needing an engine on the server.
        String system = PLAN_SYSTEM_PROMPT;

        String prompt = "Design and furnish: " + description;

        if (memoryExamples != null && !memoryExamples.isBlank()) {
            prompt = memoryExamples + "\n" + prompt;
        }

        // A plan is now a few dozen shapes rather than every block, but the
        // cap stays high: Claude honoured the default 2,000 and the first
        // block-listed plans came back cut off mid-object.
        return sendTiered(Tier.HEAVY, prompt, system, true, BUILD_SCRIPT_MAX_TOKENS);
    }

    /** Token usage from the last provider call, for ai.log-usage. */
    private volatile String lastUsage;

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
        return queryBuildScript(description, memoryExamples, previousScript, previousError, "");
    }

    /** System prompt for GraalJS script builds (queryBuildScript). */
    static final String SCRIPT_SYSTEM_PROMPT = """
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

            The same rule holds for a staircase people walk up: `facing` is the direction
            you walk to climb. A flight rising toward the east is stairs with facing=east,
            each step one block higher and one block further east, e.g.
            for (let i = 0; i < 5; i++) setBlock(x+i, y+i, z, "stone_brick_stairs[facing=east]");
            Fill the space under the flight if it shows, and clear the floor above the top
            steps (and two blocks of headroom over every step) so it leads somewhere.
            Count the steps: the top step sits at the SAME height as the upper floor's own
            blocks, so a flight from a room whose floor you stand on at height y up to a
            floor laid at height f has f - y + 1 steps. One fewer and the player has to jump
            the last block. e.g. upper floor at y+4: steps at y, y+1, y+2, y+3 and y+4.

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

            Every window opening holds glass or a glass_pane. A trapdoor, plank or other
            solid block in the opening is not a window: no light gets in and it reads as a
            patch on the wall. Shutters are open trapdoors fixed to the outside of the wall
            BESIDE the opening, one to each side, never in it. e.g. a front window is
            fill(x+2, y+1, z, x+3, y+1, z, "glass_pane") in the wall at z, and any shutters
            go at x+1 and x+4 one block OUTSIDE the wall, so the wall itself stays whole.

            Put windows at y+1. A player standing on the floor has their eyes at about
            y+1.6, so y+1 is the height you actually look through; y+2 sits above eye line
            and reads as a gap under the eaves. Hang wall torches at y+2, above the
            windows, so the two do not compete for the same band of wall.

            Furnish and decorate. A place someone lives in has, at minimum, a bed, a chest, a
            crafting table, wall torches or lanterns so it is lit inside, and something on the
            walls or floor: bookshelves, flower pots, a carpet, a fireplace of bricks with a
            campfire. A mansion, inn, castle or manor has several rooms and EACH is furnished
            for its purpose: beds in bedrooms, a kitchen with a furnace, smoker and barrels, a
            hall with a long table of slabs and stairs as chairs, a library of bookshelves.
            Fences make railings and table legs; stairs make chairs; slabs make tables and
            counters; trapdoors make shutters. A tower has a way up. Outside, a path of a
            different block leads to the door, with a lantern or two beside it. A bare box with
            a door is not finished.

            A stair used as a chair stands alone, one block high, on the floor beside a table.
            Its `facing` names the side its tall BACK is on, so it points AWAY from the table:
            for a table at (x, y, z),
              chair west of it:  setBlock(x-1, y, z, "oak_stairs[facing=west]");
              chair east of it:  setBlock(x+1, y, z, "oak_stairs[facing=east]");
              chair north of it: setBlock(x, y, z-1, "oak_stairs[facing=north]");
              chair south of it: setBlock(x, y, z+1, "oak_stairs[facing=south]");
            A chair whose facing points at the table has its back pressed against it.
            A flight of stairs exists only to reach another floor: in a one-storey room, never
            put one stair on top of another.

            No cobwebs unless the request asks for something abandoned, haunted, ruined or
            old. In a lived-in house they read as a mistake: a "seaside shanty" gets none, an
            "abandoned shack" may have a few in its corners.

            Leave room to move. Keep a path two blocks high from the entrance to every door and
            through every room: nothing stands or hangs in a doorway or an aisle. Anything hung
            from above stays at y+2 or higher -- hang it from the ceiling or the roof beams, not
            from the tops of low walls, where it ends up at chest height.

            Use the height you build. If the roof leaves more than three blocks of space above
            the walls, give that space a purpose: a loft or upper floor reached by stairs or a
            ladder, or a room open to the rafters with its lights and plants hung from the roof.

            Rules:
            - You only ever place blocks; nothing is removed unless you place air. The
              space you build into is NOT guaranteed empty. On open ground it is, so this
              costs you nothing there. Inside a hill or a cave it is solid rock, and a
              wall raised through untouched stone leaves the room filled in. If you are
              told the site is enclosed, fill the entire volume with air first -- rooms,
              doorways, stairwells, the lot -- and then build into what you cleared.
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

    /**
     * @param siteNote what the ground at this particular origin allows, or blank.
     *                 Standing near bedrock or near the world ceiling leaves far
     *                 less headroom than the usual limits imply, and a model told
     *                 only afterwards cannot correct it -- it writes relative
     *                 coordinates and does not know where the origin sits.
     */
    public String queryBuildScript(String description, String memoryExamples,
                                   String previousScript, String previousError,
                                   String siteNote) throws Exception {
        if (reducedMode) {
            // A 7B writes bad JavaScript. v0.8.6 measured qwen2.5-coder:7b as
            // worse than qwen2.5:7b at spatial layout, so there is no local
            // model here worth falling back to -- the JSON planner is the
            // honest reduced-mode answer.
            throw new ReducedModeException(
                    "Script building needs a cloud model. Set build.planner to json, or use schematics.");
        }

        String system = SCRIPT_SYSTEM_PROMPT;

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
        if (siteNote != null && !siteNote.isBlank()) {
            prompt = prompt + "\n\n" + siteNote;
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

    /**
     * Thrown when Jarvis Cloud cut a build reply off at the plan's output cap.
     * Retrying cannot help -- the next reply hits the same cap -- so it goes
     * straight to the player instead of through the repair loop or a fallback.
     */
    public static class PlanLimitException extends RuntimeException {
        public PlanLimitException(String message) { super(message); }
    }

    /**
     * The message for a build reply that was cut off, or null if it finished.
     * Only build-sized requests count: a chat reply cut short is still usable.
     *
     * <p>A truncated script is not worth a repair call. The error comes back as
     * a syntax error at end of file, the repair asks for the complete script
     * again, and that reply stops at the same ceiling -- the GadgetCraft log has
     * builds that paid for two truncated replies in a row and built nothing.
     */
    static String planLimitMessage(JSONObject json, String apiKey, int maxTokens) {
        if (maxTokens < BUILD_SCRIPT_MAX_TOKENS) return null;
        if (!"max_tokens".equals(json.optString("stop_reason"))) return null;
        JSONObject usage = json.optJSONObject("usage");
        int out = usage == null ? 0 : usage.optInt("output_tokens");
        // Jarvis Cloud lowered the ceiling below what we asked for: the plan is the limit.
        if (apiKey != null && apiKey.startsWith("gc_") && out > 0 && out < maxTokens) {
            return "This build is too big for your Jarvis Cloud plan (replies stop at about " + out
                    + " tokens). Try a smaller build, or upgrade with /jarvis cloud upgrade.";
        }
        return truncatedBuildMessage();
    }

    private static String truncatedBuildMessage() {
        return "That design was too big to finish in one reply and was cut off. "
                + "Try a smaller or simpler build.";
    }

    /** Thrown when a capability is unavailable in Ollama-only reduced mode. */
    public static class ReducedModeException extends Exception {
        public ReducedModeException(String message) { super(message); }
    }

    /**
     * Parse natural language command into structured action
     */
    public String parseNaturalLanguage(String message, String playerName, String context) throws Exception {
        return parseNaturalLanguage(message, playerName, context, "");
    }

    /**
     * Parse a sentence into an action, with the admin's server facts
     * ({@link ServerKnowledge#render()}, may be empty) in the system prompt.
     */
    public String parseNaturalLanguage(String message, String playerName, String context,
                                       String serverFacts) throws Exception {
        return parseNaturalLanguage(message, playerName, context, serverFacts, null);
    }

    /**
     * As above; with a {@code stream} sink, the reply's sentences are handed to it
     * while the model is still writing (FB-05, {@code ai.streaming}). The full
     * reply is still returned for the normal parse.
     */
    public String parseNaturalLanguage(String message, String playerName, String context,
                                       String serverFacts, StreamAssembler.Sink stream) throws Exception {
        // Literal commands ("follow me", "mine 10 iron") never get here:
        // IntentPipeline answers them with FastPathParser first, unless
        // intent.fast-path.enabled is off, in which case the model decides.
        String systemPrompt = JARVIS_PERSONALITY + "\n\n" +
                "TASK: Parse player commands into JSON actions. Maintain your sarcastic butler persona in the response field.\n" +
                "Output format: {\"action\":\"...\",\"parameters\":{...},\"response\":\"your witty 1-2 sentence response\"}\n\n" +
                "CONVERSATION:\n" +
                "  chat - just answer; no action. Use for questions, small talk and opinions.\n" +
                "         The response field is the answer, seen only by this player.\n" +
                "  The Context lines are the player's real situation right now (health, time, mobs, Jarvis).\n" +
                "  Use them when advising; never invent details they don't give.\n" +
                "  If there is a WARNINGS line, deal with those dangers first in your answer.\n\n" +
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
                "  mine_here - dig a full torch-lit branch mine at the current spot: it descends to\n" +
                "              diamond level and then tunnels SIDEWAYS. Below that depth it has\n" +
                "              nothing to descend to.\n" +
                "  tunnel - drive a straight 3x3 passage in the direction he faces, lit as it goes.\n" +
                "           Use for \"dig a tunnel\", \"tunnel that way\", \"cut me a passage\".\n" +
                "           params: {length (optional): blocks, default 32,\n" +
                "                    direction (optional): north|south|east|west -- only the four\n" +
                "                    cardinals are dug; anything else is rounded onto one}\n" +
                "  dig_down - sink a vertical shaft straight down from where he stands, ladder-lined\n" +
                "             and lit. params: {depth (optional): blocks, default 20}\n" +
                "             Use for \"dig down\", \"dig a shaft\", \"dig down 20 blocks\".\n" +
                "  deposit - carry collected loot to the player's registered chest\n" +
                "  set_chest - register the chest the player is looking at for deposits\n" +
                "  stop - stop current task\n" +
                "  report - deliver the server status briefing (TPS, players, weather); only when asked for a report or status\n" +
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
                "  build - build a structure, params: {description: string}\n" +
                "  undo - take down the last thing Jarvis built or pasted and put back what was there.\n" +
                "         Use for \"undo that\", \"put it back\", \"wrong place, take it down\".\n" +
                "  redo - put back up what the last undo took down\n\n" +
                "WORLD ACTIONS (directly affect the game world):\n" +
                "  give_item - give items to player, params: {player, item (minecraft:xxx), amount}\n" +
                "  enchant - enchant held item, params: {player, enchantment, level}\n" +
                "  potion_effect - apply effect, params: {player, effect, duration_seconds, amplifier}\n" +
                "  heal - restore health, params: {player}\n" +
                "  feed - restore hunger, params: {player}\n" +
                "  set_gamemode - change gamemode, params: {player, mode: survival|creative|adventure|spectator}\n" +
                "  teleport - move the player; ONLY when asked to be moved (\"take me to\", \"tp me\"), never for \"where is\", params: {player, x, y, z, world (optional)}\n" +
                "  set_time - set world time, params: {value: day|night|noon|midnight}\n" +
                "  set_weather - set weather, params: {type: clear|rain|thunder}\n" +
                "  set_gamerule - change a rule, params: {rule, value}\n" +
                "  summon - summon a mob/entity, params: {entity: minecraft:xxx, x (optional), y (optional), z (optional)}\n" +
                "  broadcast - announce to EVERY player; only when asked to announce something, params: {message}\n" +
                "  server_say - post a [Server] line EVERY player sees; only when asked to, never to answer, params: {message}\n" +
                "  lp_group_add - add to permission group, params: {player, group}\n" +
                "  lp_group_remove - remove from permission group, params: {player, group}\n" +
                "  warp - warp to a location; only when asked to be moved, params: {player, warp}\n" +
                "  discord_broadcast - send to Discord, params: {message}\n" +
                "  paste_schematic - paste a saved schematic at player location, params: {schematic}\n\n" +
                "ADMIN / CONSOLE ACTIONS (always require confirmation):\n" +
                "  console_command - run ANY Minecraft console command, params: {command: \"the full command string\"}\n" +
                "  console_commands - run MULTIPLE commands in sequence, params: {commands: [\"cmd1\", \"cmd2\", ...]}\n" +
                "  clear_mobs - remove mobs from world, params: {type (optional, e.g. creeper), radius (optional, blocks)}\n" +
                "  clear_drops - remove all ground items, params: {radius (optional)}\n" +
                "  save_world - save the world, params: {}\n" +
                "  set_difficulty - change difficulty, params: {difficulty: peaceful|easy|normal|hard}\n" +
                "  announce_all - title screen message to ALL players; only when asked to, params: {message, subtitle (optional)}\n" +
                "  schedule_broadcast - scheduled/repeating chat message, params: {message, delay_seconds, interval_seconds (optional), count (optional)}\n\n" +
                "PLAYER REQUESTS (for non-admin players):\n" +
                "  request_item - submit an item request to admins, params: {item (minecraft:xxx), amount, reason (optional)}\n\n" +
                "EXAMPLES:\n" +
                "  'come here' -> {\"action\":\"summon\",\"response\":\"At your service. Again.\"}\n" +
                "  'start mining' -> {\"action\":\"mine\",\"response\":\"Manual labour. How delightfully medieval.\"}\n" +
                "  'dig a mine here' -> {\"action\":\"mine_here\",\"response\":\"One proper mine, coming up. Do admire the torchwork.\"}\n" +
                "  'dig a tunnel 40 blocks that way' -> {\"action\":\"tunnel\",\"parameters\":{\"length\":40},\"response\":\"Forty blocks of passage, sir. Mind the dust.\"}\n" +
                "  'tunnel north' -> {\"action\":\"tunnel\",\"parameters\":{\"direction\":\"north\"},\"response\":\"Northward, sir.\"}\n" +
                "  'follow me' -> {\"action\":\"follow\",\"response\":\"Right behind you, sir. As always.\"}\n" +
                "  'protect me' -> {\"action\":\"guard\",\"parameters\":{\"stance\":\"defensive\"},\"response\":\"At your side, sir. Nothing touches you.\"}\n" +
                "  'weapons free' -> {\"action\":\"attack\",\"response\":\"With pleasure, sir.\"}\n" +
                "  'keep watch tonight' -> {\"action\":\"watch\",\"response\":\"I shall hold this post until dawn, sir.\"}\n" +
                "  'stand down' -> {\"action\":\"stand_down\",\"response\":\"Standing down, sir. Observing only.\"}\n" +
                "  'give me a status report' -> {\"action\":\"report\",\"response\":\"The briefing, sir.\"}\n" +
                "  'what should I do next?' -> {\"action\":\"chat\",\"response\":\"Gather wood and build a shelter before nightfall, sir.\"}\n" +
                "  'where is the market?' -> {\"action\":\"chat\",\"response\":\"The market is at x 120, y 70, z -40, sir.\"} (coordinates from SERVER FACTS; if it isn't listed, say you don't know)\n" +
                "  'take me to the market' -> {\"action\":\"teleport\",\"parameters\":{\"x\":120,\"y\":70,\"z\":-40},\"response\":\"Off you go, sir.\"}\n" +
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
        if (serverFacts != null && !serverFacts.isBlank()) {
            systemPrompt += "\n\n" + serverFacts;
        }

        String userPrompt = String.format("Player %s says: \"%s\".\nContext:\n%s", playerName, message, context);
        return sendTiered(Tier.LIGHT, userPrompt, systemPrompt, true, DEFAULT_MAX_TOKENS, stream);
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
     * Reasoning before retrieval: turn a build request into the features it is
     * actually asking for, before anything is matched against the library.
     *
     * <p>"Somewhere to store my loot" shares no word with `storage_shed`. The
     * paper's finding is that decomposing the utterance first and matching on
     * what it *means* measurably beats matching the raw wording, and the
     * decomposition is cheap enough to cache forever.
     *
     * <p>LIGHT tier and heavily constrained, because this is exactly the shape
     * of task a small local model is good at.
     *
     * @return raw JSON: {@code {"purpose":"storage","kind":"shed","style":""}}
     */
    public String decomposeBuildRequest(String description) throws Exception {
        // Structured, not a flat tag list. Measured against llama3.2:3b: asked
        // for a list, the model mixed purpose words in with size and style
        // words, and a style word matching a schematic NAME by coincidence
        // ("small" hitting small_warehouse) outscored the purpose that actually
        // answered the request -- "a cosy little cottage" resolved to
        // small_warehouse when the plain name match had it right. Naming the
        // fields separately lets the scorer weigh them properly, and took the
        // set from 16/21 to 18/21 with no answer made worse than no tags at all.
        String systemPrompt = "You read a Minecraft build request and name what it is for, so a "
                + "library of existing builds can be searched.\n"
                + "Respond ONLY with JSON: "
                + "{\"purpose\":\"<one word>\",\"kind\":\"<one word>\",\"style\":\"<one word or empty>\"}\n"
                + "purpose = what the player will USE it for: storage, shelter, farming, defence, "
                + "crossing, decoration, crafting, fishing, mining, animals.\n"
                + "kind = the STRUCTURE that serves that purpose: house, cottage, shed, warehouse, "
                + "tower, keep, barn, hut, wall, bridge, farm.\n"
                + "style = size or material ONLY if the request says so, otherwise \"\".\n"
                + "Never name a material list and never name a step.\n"
                // Three examples covered three of the ten purposes, and both
                // models read "a place to keep my crops" as storage -- things do
                // end up inside a barn. The rule and the farming example fixed
                // it on both, and took llama3.2:3b from 5/7 to 6/7 while leaving
                // qwen2.5:7b at 6/7. It also stopped the smaller model answering
                // with a two-word purpose ("storing crops"), which no schematic
                // name can ever match.
                + "Pick the purpose from what the player will DO there, not from the fact that "
                + "things end up inside it. Growing food is farming, not storage.\n"
                + "Examples:\n"
                + "\"somewhere to store my loot\" -> "
                + "{\"purpose\":\"storage\",\"kind\":\"shed\",\"style\":\"\"}\n"
                + "\"a place to keep my crops\" -> "
                + "{\"purpose\":\"farming\",\"kind\":\"barn\",\"style\":\"\"}\n"
                + "\"a cosy little cottage\" -> "
                + "{\"purpose\":\"shelter\",\"kind\":\"cottage\",\"style\":\"small\"}\n"
                + "\"somewhere to hold off a siege\" -> "
                + "{\"purpose\":\"defence\",\"kind\":\"keep\",\"style\":\"\"}\n"
                + "\"a spot by the lake for angling\" -> "
                + "{\"purpose\":\"fishing\",\"kind\":\"hut\",\"style\":\"\"}";
        return sendTiered(Tier.LIGHT, "Build request: \"" + description + "\"", systemPrompt, true);
    }

    /**
     * Self-explain: given a dead task, say why it died and pick a way out.
     *
     * <p>LIGHT tier on purpose. A diagnosis is short, wanted quickly, and must
     * work on an Ollama-only server -- the failures worth explaining happen on
     * exactly the servers least likely to be paying for a cloud key.
     *
     * @param failureContext what broke, from {@code TaskRecoveryHandler}
     * @param allowedActions the only moves the failing task offered. Anything
     *                       outside this list is treated as "abort" by the
     *                       caller, so the model cannot invent an action.
     * @return raw JSON: {@code {"diagnosis":..., "action":..., "message":...}}
     */
    public String diagnoseTaskFailure(String failureContext, java.util.List<String> allowedActions)
            throws Exception {
        // "abort" is always on the list, and is the whole of it where the
        // failing job had no way to carry on.
        java.util.List<String> choices = new java.util.ArrayList<>(allowedActions);
        choices.add("abort");
        // Every line of this was measured against llama3.2:3b, which is the size
        // of model this tier is for. Three findings shaped it, and none of them
        // were guessable: phrasing the action rule as "pick abort unless..."
        // made a 3B model answer abort every single time, even where it had
        // just described the way out in its own message; the rule has to be
        // affirmative. Not saying the fields are required left the diagnosis
        // empty in half the answers. And the action rule has to come LAST --
        // moved into the middle, correct picks fell from 4/4 to 2/4 on the same
        // case. Re-measure if you reword it.
        String systemPrompt = JARVIS_PERSONALITY + "\n\n"
                + "TASK: A job you were carrying out has stopped. Work out why, then choose what "
                + "to do next.\n"
                + "Respond ONLY with JSON. All three fields are REQUIRED and none may be left empty:\n"
                + "{\"diagnosis\":\"one sentence, mechanical and specific, for the server log\","
                + "\"action\":\"" + String.join("|", choices) + "\","
                + "\"message\":\"one or two sentences to the player, in character\"}\n\n"
                + "THE MESSAGE follows a line you have already said, so do not repeat that line. "
                + "Say why it happened and what you are doing about it. The player never sees the "
                + "diagnosis field, so the message must stand on its own. The fault is the world's "
                + "or your own, never the player's.\n\n"
                + "THE DIAGNOSIS is for the server operator reading the log, not the player. State "
                + "the mechanical cause plainly, with the numbers from the state above where they "
                + "apply. Write it even when the action is \"abort\" -- especially then, because it "
                + "is the only record of why the job stopped.\n\n"
                + "CHOOSING THE ACTION, which matters most. You are listed the moves you can still "
                + "make. If one of them would let the job carry on, CHOOSE IT -- that is what it is "
                + "there for, and stopping when you could have carried on is the worst answer you "
                + "can give. Choose \"abort\" only when none of the listed moves addresses what went "
                + "wrong, or when abort is the only move listed. Never answer with an action that "
                + "is not listed.";
        return sendTiered(Tier.LIGHT, failureContext, systemPrompt, true);
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
     * Condense a player's older conversation with Jarvis into a few lines of
     * facts (FB-04), on the light tier. Plain text back, no JSON.
     */
    public String summarizeConversation(String transcript) throws Exception {
        String systemPrompt = "You keep notes for Jarvis, a butler in a Minecraft world. Condense the "
                + "earlier notes and the conversation below into at most six short plain sentences of "
                + "facts worth remembering about this player: places and coordinates they named, their "
                + "base, plans, preferences, names, things that happened. Keep earlier facts unless the "
                + "conversation replaces them. No greetings, no commentary, no lists, no JSON.";
        return sendTiered(Tier.LIGHT, transcript, systemPrompt, false);
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
        return sendTiered(tier, userContent, systemContent, jsonMode, maxTokens, null);
    }

    private String sendTiered(Tier tier, String userContent, String systemContent, boolean jsonMode,
                              int maxTokens, StreamAssembler.Sink stream) throws Exception {
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
                log.fine("Skipping " + tryProvider + " (cooldown: "
                        + health.getCooldownRemaining() / 1000 + "s)");
                continue;
            }

            triedProviders.add(tryProvider);

            try {
                lastUsage = null;
                String result = sendRequestForProvider(tryProvider, userContent, systemContent, jsonMode,
                        timeoutOverride, maxTokens, stream);
                health.recordSuccess();

                if (lastUsage != null && this.config.getBoolean("ai.log-usage", false)) {
                    log.info("AI usage [" + tier + "/" + tryProvider + "] " + lastUsage);
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
            } catch (PlanLimitException e) {
                throw e; // the provider is fine; the plan is the limit
            } catch (Exception e) {
                health.recordFailure(e.getMessage());
                lastException = e;
                log.warn("AI provider " + tryProvider + " failed ("
                        + tier + " tier): " + e.getMessage());
            }
        }

        throw new RuntimeException("All AI providers failed for " + tier + " tier. Tried: " + triedProviders, lastException);
    }

    private String sendRequestForProvider(String providerName, String userContent, String systemContent,
                                          boolean jsonMode, int timeoutOverrideSeconds,
                                          int maxTokens, StreamAssembler.Sink stream) throws Exception {
        ProviderConfig config = providerConfigs.get(providerName);
        if (config == null) {
            throw new RuntimeException("Unknown provider: " + providerName);
        }
        int readTimeoutMs = (timeoutOverrideSeconds > 0 ? timeoutOverrideSeconds : config.timeoutSeconds) * 1000;

        if (stream != null && streams(providerName, config)) {
            StreamAssembler asm = new StreamAssembler(wireFor(providerName), stream,
                    this.config.getLong("ai.streaming.min-interval-ms", 400), System::currentTimeMillis);
            try {
                return send(providerName, userContent, systemContent, config, jsonMode, readTimeoutMs, maxTokens, asm);
            } catch (PlanLimitException e) {
                throw e;
            } catch (Exception e) {
                // Words already on screen stay; if the whole answer arrived, the next
                // reply is used for its action only, so nothing is said twice.
                if (!asm.responseComplete()) asm.abandon();
                // Only a stream that broke after a good start is retried the old way. A
                // refused request, a rate limit or a timeout before any reply goes to the
                // next provider, as without streaming, rather than being sent twice.
                if (!(e instanceof StreamBroken)) throw e;
                log.warn("Streaming from " + providerName + " broke (" + e.getMessage()
                        + "); retrying without streaming.");
            }
        }
        return send(providerName, userContent, systemContent, config, jsonMode, readTimeoutMs, maxTokens, null);
    }

    private String send(String providerName, String userContent, String systemContent, ProviderConfig config,
                        boolean jsonMode, int readTimeoutMs, int maxTokens, StreamAssembler asm) throws Exception {
        return switch (providerName) {
            case "claude" -> sendClaudeRequest(userContent, systemContent, config, readTimeoutMs, maxTokens, asm);
            case "gemini" -> sendGeminiRequest(userContent, systemContent, config, readTimeoutMs, asm);
            case "ollama" -> sendOllamaRequest(userContent, systemContent, config, jsonMode, readTimeoutMs, asm);
            default -> sendOpenAIStyleRequest(userContent, systemContent, config, readTimeoutMs, maxTokens, asm);
        };
    }

    /**
     * Whether replies from this provider are streamed: {@code ai.streaming.enabled},
     * the provider in {@code ai.streaming.providers}, and not Jarvis Cloud ({@code gc_}
     * keys), which collects the stream itself so it can meter the request.
     */
    private boolean streams(String providerName, ProviderConfig config) {
        if (!this.config.getBoolean("ai.streaming.enabled", true)) return false;
        List<String> allowed = this.config.contains("ai.streaming.providers")
                ? this.config.getStringList("ai.streaming.providers") : KNOWN_PROVIDERS;
        if (!allowed.contains(providerName)) return false;
        if (config.apiKey != null && config.apiKey.startsWith("gc_")) return false;
        if (providerName.equals("gemini")) return config.endpoint != null && config.endpoint.contains(":generateContent");
        return true;
    }

    private static StreamAssembler.Wire wireFor(String providerName) {
        return switch (providerName) {
            case "claude" -> StreamAssembler.Wire.ANTHROPIC_SSE;
            case "gemini" -> StreamAssembler.Wire.GEMINI_SSE;
            case "ollama" -> StreamAssembler.Wire.OLLAMA_NDJSON;
            default -> StreamAssembler.Wire.OPENAI_SSE;
        };
    }

    /** A stream that failed after the provider accepted the request: worth one buffered retry. */
    static final class StreamBroken extends RuntimeException {
        StreamBroken(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * Read a streamed body line by line into the assembler; the whole reply text.
     * The read timeout restarts on every line, so a model that trickles tokens
     * forever is also cut off once the whole reply has taken {@code totalMs}.
     */
    private static String readStream(HttpURLConnection conn, StreamAssembler asm, long totalMs) throws Exception {
        long deadline = System.currentTimeMillis() + Math.max(1000, totalMs);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                asm.line(line);
                if (asm.done()) break;
                if (System.currentTimeMillis() > deadline) {
                    throw new RuntimeException("streamed reply took longer than " + totalMs / 1000 + "s");
                }
            }
        } catch (java.io.IOException | IllegalStateException e) {
            throw new StreamBroken(e.getMessage(), e);
        }
        // Every provider marks the end ([DONE], message_stop, done:true, finishReason);
        // a stream that just stops is a dropped connection, not a short reply.
        if (!asm.done()) {
            asm.finish();
            throw new StreamBroken("stream ended before the reply was complete", null);
        }
        String text = asm.finish().trim();
        if (text.isEmpty()) throw new StreamBroken("empty streamed reply", null);
        return text;
    }

    // ==================== PROVIDER IMPLEMENTATIONS ====================

    private String sendOllamaRequest(String userContent, String systemContent, ProviderConfig config,
                                     boolean jsonMode, int readTimeoutMs, StreamAssembler asm) throws Exception {
        URL url = new URL(config.endpoint + "/api/chat");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(readTimeoutMs);
        conn.setDoOutput(true);

        JSONObject payload = new JSONObject();
        payload.put("model", config.model);
        payload.put("stream", asm != null);
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
        if (asm != null) return readStream(conn, asm, readTimeoutMs);

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
                                     int readTimeoutMs, int maxTokens, StreamAssembler asm) throws Exception {
        URL url = new URL(config.endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-api-key", config.apiKey);
        conn.setRequestProperty("anthropic-version", "2023-06-01");
        if (installId != null && config.apiKey.startsWith("gc_")) conn.setRequestProperty("x-jarvis-server", installId);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(readTimeoutMs);
        conn.setDoOutput(true);

        JSONObject payload = new JSONObject();
        payload.put("model", config.model);
        payload.put("max_tokens", maxTokens);
        payload.put("system", systemContent);
        if (asm != null) payload.put("stream", true);

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
        if (asm != null) {
            String text = readStream(conn, asm, readTimeoutMs);
            lastUsage = asm.usage();
            return text;
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

            String capped = planLimitMessage(json, config.apiKey, maxTokens);
            if (capped != null) throw new PlanLimitException(capped);

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
                                          int readTimeoutMs, int maxTokens, StreamAssembler asm) throws Exception {
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
        if (asm != null) payload.put("stream", true);

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
        if (asm != null) return readStream(conn, asm, readTimeoutMs);

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
            // Same rule as the Claude path: a cut-off build is not repaired.
            if (maxTokens >= BUILD_SCRIPT_MAX_TOKENS && "length".equals(firstChoice.optString("finish_reason"))) {
                throw new PlanLimitException(truncatedBuildMessage());
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
                                     int readTimeoutMs, StreamAssembler asm) throws Exception {
        String base = String.format(config.endpoint, config.model);
        if (asm != null) base = base.replace(":generateContent", ":streamGenerateContent");
        String resolvedEndpoint = base + "?key=" +
                java.net.URLEncoder.encode(config.apiKey, StandardCharsets.UTF_8)
                + (asm != null ? "&alt=sse" : "");
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
        if (asm != null) return readStream(conn, asm, readTimeoutMs);

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

    // ==================== SETUP SUPPORT ====================

    /** Every provider this connector knows how to talk to, in the default priority order. */
    /** Free first (FB-09): local Ollama, then Gemini's free tier, then the paid ones. */
    public static final List<String> KNOWN_PROVIDERS = List.of("ollama", "gemini", "claude", "openai", "grok");

    /** True when the provider is in the priority list, the connector's idea of "enabled". */
    public boolean isEnabled(String providerName) {
        return providerPriority.contains(providerName);
    }

    public boolean hasApiKey(String providerName) {
        ProviderConfig c = providerConfigs.get(providerName);
        return c != null && c.hasApiKey();
    }

    public String modelOf(String providerName) {
        ProviderConfig c = providerConfigs.get(providerName);
        return c == null ? "" : c.model;
    }

    public String endpointOf(String providerName) {
        ProviderConfig c = providerConfigs.get(providerName);
        return c == null ? "" : c.endpoint;
    }

    /** One provider's health in a word or two, whether or not it is enabled. */
    public String describe(String providerName) {
        if (!providerConfigs.containsKey(providerName)) return "unknown";
        if (!isEnabled(providerName)) return "disabled";
        ProviderConfig config = providerConfigs.get(providerName);
        if (!"ollama".equals(providerName) && !config.hasApiKey()) return "no API key";
        ProviderHealth health = providerHealth.get(providerName);
        if (health != null && !health.isAvailable()) return "cooldown (" + health.getCooldownRemaining() / 1000 + "s)";
        return "available";
    }

    /**
     * One small request straight to a provider, past the routing, to see
     * whether it answers. Blocks; call it off the server thread. Returns the
     * reply, trimmed; throws with the reason when it does not answer.
     */
    public String probe(String providerName) throws Exception {
        if (!providerConfigs.containsKey(providerName)) throw new IllegalArgumentException("Unknown provider: " + providerName);
        if (!"ollama".equals(providerName) && !hasApiKey(providerName)) throw new IllegalStateException("no API key set");
        String reply = sendRequestForProvider(providerName, "Reply with the single word: ready",
                "You are a connectivity check. Answer with one word.", false, 20, 16, null);
        ProviderHealth health = providerHealth.get(providerName);
        if (health != null) health.recordSuccess();
        return reply == null ? "" : reply.trim();
    }

    /** This install's ID, sent only to Jarvis Cloud. */
    public void setInstallId(String id) {
        this.installId = id;
    }

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
