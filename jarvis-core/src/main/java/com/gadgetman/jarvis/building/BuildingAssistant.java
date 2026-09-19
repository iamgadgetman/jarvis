package com.gadgetman.jarvis.building;

import com.gadgetman.jarvis.DatabaseManager;
import com.gadgetman.jarvis.ai.AIConnector;
import com.gadgetman.jarvis.ai.ModelJson;
import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Log;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Platform;
import com.gadgetman.jarvis.core.platform.Task;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.text.Colors;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.BlockState;
import com.gadgetman.jarvis.core.world.Facing;
import com.gadgetman.jarvis.core.world.Ids;
import com.gadgetman.jarvis.memory.BuildExperience;
import com.gadgetman.jarvis.memory.ExperienceMemory;
import com.gadgetman.jarvis.memory.SituationSnapshot;
import com.gadgetman.jarvis.npc.ButlerService;
import com.gadgetman.jarvis.progression.ProgressionManager;
import com.gadgetman.jarvis.progression.ServiceRecord;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI-powered building.
 *
 * <p>Accepts natural language build requests, asks the AI for a plan (a
 * script or a block list), and lays the blocks a few per tick through the
 * world, with undo. Plans are validated through the platform's block
 * registry on the server thread, and placed with physics off so a build does
 * not set off cascades while it goes up; shapes that depend on their
 * neighbours (panes, bars, fences, walls, beds) are resolved here.
 */
public class BuildingAssistant {

    private final Platform platform;
    private final Log log;
    private final ButlerService butlers;
    private final AIConnector ai;
    private final ExperienceMemory memory;
    private final DatabaseManager database;
    private final ProgressionManager progression;

    private final Map<UUID, BuildState> activeBuilds = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<UndoEntry>> undoHistory = new ConcurrentHashMap<>();

    // Configuration
    private int blocksPerTick = 50;
    private int progressUpdateInterval = 100;
    private boolean enableUndo = true;
    private int maxAiBlocks = 5000;
    private String fallbackBlock = Ids.DIRT;

    // Script planner
    private String planner = "script";
    private int scriptRepairAttempts = 1;

    /** Null when GraalJS is not on the classpath, which forces the JSON planner. */
    private ScriptBuildPlanner scriptPlanner;

    /**
     * @param progression may be null when the service ladder is off
     */
    public BuildingAssistant(Platform platform, ButlerService butlers, AIConnector ai,
                             ExperienceMemory memory, DatabaseManager database,
                             ProgressionManager progression) {
        this.platform = platform;
        this.log = platform.log();
        this.butlers = butlers;
        this.ai = ai;
        this.memory = memory;
        this.database = database;
        this.progression = progression;
        loadConfig();
        log.info("Building assistant initialized");
    }

    private void loadConfig() {
        Config cfg = platform.config();
        blocksPerTick = cfg.getInt("build.blocks-per-tick", 50);
        progressUpdateInterval = cfg.getInt("build.progress-update-interval", 100);
        enableUndo = cfg.getBoolean("build.enable-undo", true);
        maxAiBlocks = cfg.getInt("build.max-ai-blocks", 5000);
        fallbackBlock = Ids.of(cfg.getString("build.fallback-material", "minecraft:dirt").toLowerCase(Locale.ROOT));

        planner = cfg.getString("build.planner", "script").toLowerCase(Locale.ROOT);
        int scriptMaxBlocks = cfg.getInt("build.script.max-blocks", 50000);
        int scriptTimeoutMs = cfg.getInt("build.script.timeout-ms", 5000);
        int scriptMaxHorizontal = cfg.getInt("build.script.max-horizontal", 128);
        int scriptMaxVertical = cfg.getInt("build.script.max-vertical", 128);
        int scriptMaxFillVolume = cfg.getInt("build.script.max-fill-volume", 200000);
        scriptRepairAttempts = cfg.getInt("build.script.repair-attempts", 1);

        if ("script".equals(planner)) {
            // GraalJS comes from the server's library loader, so it can genuinely
            // be absent -- an offline first start, or a resolver failure. That
            // must not stop the plugin loading, so probe and fall back rather
            // than letting a NoClassDefFoundError escape on first build.
            if (ScriptEngineProbe.isAvailable()) {
                // Snapshot the registry here, on the server thread, so the planner
                // can reject a bad block id while running async.
                Set<String> validBlockNames = new HashSet<>(platform.blockTypes().placeableIds());
                try {
                    scriptPlanner = new ScriptBuildPlanner(log, scriptMaxBlocks,
                            scriptTimeoutMs, scriptMaxHorizontal, scriptMaxVertical, scriptMaxFillVolume,
                            validBlockNames);
                } catch (NoClassDefFoundError e) {
                    log.warn("GraalJS is only partly present (" + e.getMessage() + "); using the JSON planner.");
                    scriptPlanner = null;
                    planner = "json";
                }
            } else {
                log.warn("build.planner is 'script' but GraalJS is not on the "
                        + "classpath -- falling back to the JSON planner. Check that the server "
                        + "could download the libraries listed in plugin.yml.");
                planner = "json";
            }
        }
    }

    /** Which planner is live: "script" or "json". */
    public String getPlanner() {
        return planner;
    }

    /** Releases the script engine. Called from plugin shutdown. */
    public void shutdown() {
        if (scriptPlanner != null) scriptPlanner.shutdown();
    }

    // ==================== DATA STRUCTURES ====================

    private static class BuildState {
        final UUID playerId;
        final String description;
        final World world;
        final Queue<BlockPlacement> queue = new LinkedList<>();
        int totalBlocks;
        int placedBlocks;
        Task task;
        final long startTime = System.currentTimeMillis();
        final List<BuildAction> actions = new ArrayList<>(); // For undo

        /**
         * Blocks whose look depends on their neighbours -- glass panes, iron
         * bars, fences, walls. Filled during placement and resolved afterwards,
         * because a pane placed before the wall beside it cannot know it will
         * be enclosed.
         */
        final Queue<BlockPos> connectQueue = new LinkedList<>();

        // Experience memory. Only AI-planned builds are remembered — a hand-built
        // wall says nothing about plan quality, so it must never be recorded or
        // demoted alongside one.
        boolean aiGenerated;
        String situationJson;
        String planJson;
        String provider;

        BuildState(UUID playerId, String description, World world) {
            this.playerId = playerId;
            this.description = description;
            this.world = world;
        }
    }

    private static class BlockPlacement {
        final BlockPos pos;
        /**
         * The block spec as the plan gave it, e.g. {@code oak_stairs[facing=north]}
         * or a bare id. Kept as a string because resolving one goes through the
         * registry, and plans are built off the server thread. It is resolved in
         * executeBuild, for exactly that reason.
         */
        final String spec;
        /** Resolved from {@link #spec} on the server thread. */
        BlockState state;

        BlockPlacement(BlockPos pos, String spec) {
            this.pos = pos;
            this.spec = spec;
        }
    }

    /**
     * One undoable batch. Carries whether the build was AI-planned so undoing a
     * hand-built shape cannot demote an unrelated AI build in memory.
     */
    private static class UndoEntry {
        final World world;
        final List<BuildAction> actions;
        final boolean aiGenerated;
        /**
         * The experience this batch produced, so undo demotes the row it
         * actually reverted. Held by reference: the id is filled in by the
         * async insert, and this sees it. Null for hand-built shapes and for
         * builds that never got recorded.
         */
        final BuildExperience experience;

        UndoEntry(World world, List<BuildAction> actions, boolean aiGenerated, BuildExperience experience) {
            this.world = world;
            this.actions = actions;
            this.aiGenerated = aiGenerated;
            this.experience = experience;
        }
    }

    /**
     * What a placement replaced, states and all. Restoring only the id would
     * turn a staircase the build covered into a stack of default-facing stairs
     * on undo.
     */
    private record BuildAction(BlockPos pos, BlockState original, String newId) { }

    // ==================== BUILD COMMANDS ====================

    /** Start an AI-powered build. */
    public void startBuild(Owner player, String description) {
        if (activeBuilds.containsKey(player.id())) {
            player.message(Colors.RED + "You already have an active build!");
            player.message(Colors.GRAY + "Use /jarvis build cancel to stop it first.");
            return;
        }

        // Check if NPC is summoned
        if (!butlers.isSpawned(player)) {
            player.message(Colors.RED + "Summon Jarvis first with /jarvis summon");
            return;
        }

        World world = platform.world(player.world()).orElse(null);
        if (world == null) return;

        player.message(Colors.YELLOW + "Jarvis is planning your build: " + Colors.WHITE + description);
        player.message(Colors.GRAY + "Please wait while the AI generates the structure...");

        // Capture the world state before going async — every world read is
        // server-thread only.
        final BlockPos origin = player.pos().block();
        final String situationJson = SituationSnapshot.capture(world, origin);
        // Build height is a world lookup, so it is read here with everything
        // else the async planner needs, and travels with the request.
        final ScriptBuildPlanner.WorldBounds worldBounds = new ScriptBuildPlanner.WorldBounds(
                origin.y(), world.minY(), world.maxY() + 1);
        final SiteSurvey site = SiteSurvey.around(world, origin);

        // Generate build asynchronously
        platform.scheduler().async(() -> {
            try {
                String examples = "";
                boolean unlocksReducedMode = false;
                if (memory != null && memory.isEnabled()) {
                    examples = memory.retrieveExamples(description, situationJson);
                    unlocksReducedMode = memory.isReducedModeBuildUnlocked();
                }

                // The plan text is kept whichever planner ran, because it is
                // what gets stored as the experience. For a script build that
                // text is the script itself, which makes a far better few-shot
                // example than a list of coordinates ever did.
                String response;
                List<BlockPlacement> placements;
                if (scriptPlanner != null) {
                    // Logged because there was no way to tell, after the
                    // fact, whether a constraint had been sent or the model
                    // had ignored one. A cave build came back uncarved and
                    // the log could not say which.
                    String note = siteNote(worldBounds, site);
                    if (!note.isBlank()) {
                        log.info("Site note for \"" + description + "\": " + note.replace("\n", " | "));
                    } else {
                        log.fine("No site constraints for \"" + description
                                + "\" (enclosed=" + site.enclosed()
                                + ", solid=" + site.solidPercent() + "%)");
                    }
                    ScriptPlan plan = planWithScript(description, examples, origin, worldBounds, site);
                    response = plan.script;
                    placements = plan.placements;
                } else {
                    response = ai.queryBuildPlan(description, examples, unlocksReducedMode);
                    placements = parseBuildPlan(response, origin);
                }
                final String provider = ai.getProvider();

                if (placements == null || placements.isEmpty()) {
                    // A plan that produced no blocks is a bad plan, not a bad
                    // connection — worth remembering as such.
                    if (memory != null) {
                        memory.record(BuildExperience.now(player.id(),
                                ExperienceMemory.TASK_BUILD_FREEFORM, description,
                                situationJson, response,
                                BuildExperience.Outcome.FAILED, provider));
                    }
                    platform.scheduler().sync(() ->
                            player.message(Colors.RED + "Failed to generate build plan. Try a different description."));
                    return;
                }

                // Enforce size limit
                if (placements.size() > maxAiBlocks) {
                    placements = placements.subList(0, maxAiBlocks);
                }

                final List<BlockPlacement> finalPlacements = placements;
                final String finalResponse = response;
                platform.scheduler().sync(() -> {
                    BuildState state = executeBuild(player, world, description, finalPlacements, true);
                    if (state != null) {
                        state.situationJson = situationJson;
                        state.planJson = finalResponse;
                        state.provider = provider;
                    }
                });

            } catch (Exception e) {
                log.warn("Build generation failed: " + e.getMessage());
                platform.scheduler().sync(() ->
                        player.message(Colors.RED + "AI build generation failed: " + e.getMessage()));
            }
        });
    }

    /**
     * What this particular spot allows, told to the model before it writes.
     *
     * <p>Only mentioned when the ground is genuinely tight. Saying it on every
     * build would be noise, and a cottage on a plain has hundreds of blocks of
     * headroom in both directions.
     */
    private String siteNote(ScriptBuildPlanner.WorldBounds b, SiteSurvey site) {
        StringBuilder sb = new StringBuilder();
        if (b.isTight()) {
            sb.append("Site constraint: from this origin you may only use y between ")
              .append(b.relativeMinY()).append(" and ").append(b.relativeMaxY())
              .append(". The build must fit inside that.");
        }
        if (site.enclosed()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("Site constraint: this spot is underground or enclosed — roughly ")
              .append(site.solidPercent())
              .append("% of the surrounding space is solid rock, not air. Nothing here is ")
              .append("hollow by default. Carve the volume out with fill(..., \"air\") FIRST, ")
              .append("including every room, doorway and stairwell, and then build into the ")
              .append("space you have cleared. A wall raised through untouched stone leaves ")
              .append("the interior filled in.");
        }
        return sb.toString();
    }

    /**
     * What the ground around the origin is actually like.
     *
     * <p>A script only ever *places* blocks; it never assumes it must remove
     * any. Above ground that is fine, because the space is already air. Build
     * inside a cave and the walls go up around an interior that is still solid
     * deepslate -- the rooms come out filled in. The model cannot see this, so
     * it has to be told.
     *
     * <p>Sampled on the server thread with everything else the async planner needs.
     */
    private record SiteSurvey(boolean enclosed, int solidPercent) {

        /** Reads the world. Server thread only. */
        static SiteSurvey around(World world, BlockPos origin) {
            int ox = origin.x(), oy = origin.y(), oz = origin.z();
            // Anything overhead means this is not open sky, cave or building alike.
            boolean roofed = world.highestY(ox, oz) > oy + 2;

            // Sample the space a modest build would occupy rather than the single
            // block underfoot: standing in a small clearing inside a cave still
            // means the walls will land in rock.
            int solid = 0, total = 0;
            for (int dx = -6; dx <= 6; dx += 3) {
                for (int dz = -6; dz <= 6; dz += 3) {
                    for (int dy = 0; dy <= 6; dy += 2) {
                        // isOccluding, not isSolid: leaves are solid, so a dense
                        // canopy would otherwise read as a cave and every forest
                        // build would be told to carve rock that is not there.
                        if (world.isOccluding(new BlockPos(ox + dx, oy + dy, oz + dz))) solid++;
                        total++;
                    }
                }
            }
            int pct = total == 0 ? 0 : (solid * 100) / total;
            // 15, not 25. The feet-level ring and everything above it is
            // sampled but the ground underfoot is not, so open sky reads near
            // zero and a canopy barely moves it -- leaves do not occlude. A
            // cave tight enough to matter clears 15 easily, and a build that
            // does not need the clearing loses nothing by being told.
            return new SiteSurvey(roofed && pct >= 15, pct);
        }
    }

    /** A script and the blocks it produced. Null placements mean the script never ran. */
    private record ScriptPlan(String script, List<BlockPlacement> placements) { }

    /**
     * Ask the AI for a build script, run it, and turn what it drew into blocks.
     *
     * <p>A script that will not run is retried with the error attached rather
     * than abandoned. Almost every failure here is mechanical -- a typo, a block
     * id that does not exist, a fill that overran the budget -- and the design
     * reasoning in the failed attempt is usually fine. Regenerating from scratch
     * would throw that away and cost a second full-price call for a worse
     * result.
     *
     * <p>Runs off the server thread.
     */
    private ScriptPlan planWithScript(String description, String examples, BlockPos origin,
                                      ScriptBuildPlanner.WorldBounds worldBounds, SiteSurvey site)
            throws Exception {
        String script = null;
        String error = null;

        for (int attempt = 0; attempt <= scriptRepairAttempts; attempt++) {
            String response = ai.queryBuildScript(description, examples, script, error, siteNote(worldBounds, site));
            script = AIConnector.extractScript(response);

            if (script == null || script.isBlank()) {
                error = "No JavaScript was found in your reply. "
                        + "Return the script in a ```javascript block.";
                script = null;
                continue;
            }

            try {
                ScriptBuildPlanner.Result result = scriptPlanner.run(script, worldBounds);
                log.info("Script plan for \"" + description + "\": "
                        + result.blocks.size() + " blocks from " + result.fillCalls
                        + " fill and " + result.setBlockCalls + " setBlock calls"
                        + (attempt > 0 ? " (after " + attempt + " repair)" : ""));

                List<BlockPlacement> placements = new ArrayList<>(result.blocks.size());
                for (ScriptBuildPlanner.PlannedBlock b : result.blocks) {
                    placements.add(new BlockPlacement(origin.offset(b.dx, b.dy, b.dz), b.spec));
                }
                // Bottom-up, so a half-finished build still reads as a building.
                placements.sort(Comparator.comparingInt(pl -> pl.pos.y()));
                return new ScriptPlan(script, placements);

            } catch (ScriptBuildPlanner.ScriptException e) {
                error = e.getMessage();
                log.warn("Build script attempt " + (attempt + 1) + " failed: " + error);
            }
        }

        log.warn("Build script failed after " + (scriptRepairAttempts + 1) + " attempts: " + error);
        return new ScriptPlan(script, null);
    }

    /**
     * Parse an AI JSON response into block placements.
     *
     * <p>Two dialects. The current one is a list of shapes under {@code ops},
     * expanded by {@link ShapePlan}; the original one, still accepted because
     * remembered plans are in it, lists every block under {@code blocks}.
     * Tolerant of what models actually send back: a ```json fence, a line of
     * prose, and a reply cut off by the provider's output cap. In the last
     * case the entries that arrived whole are built and the log says so,
     * rather than the whole plan being thrown away.
     */
    private List<BlockPlacement> parseBuildPlan(String jsonResponse, BlockPos origin) {
        String text = ModelJson.extractObject(jsonResponse);
        List<JSONObject> ops = new ArrayList<>();
        List<JSONObject> blocks = new ArrayList<>();
        try {
            JSONObject json = new JSONObject(text);
            ops = elements(json.optJSONArray("ops"));
            blocks = elements(json.optJSONArray("blocks"));
        } catch (JSONException e) {
            ops = ModelJson.salvageArray(text, "ops");
            if (ops.isEmpty()) blocks = ModelJson.salvageArray(text, "blocks");
            if (ops.isEmpty() && blocks.isEmpty()) {
                log.warn("Failed to parse build plan: " + e.getMessage());
                return null;
            }
            log.warn("Build plan was cut off or malformed (" + e.getMessage()
                    + "); building the " + (ops.isEmpty() ? blocks.size() + " blocks" : ops.size() + " shapes")
                    + " that arrived whole");
        }

        List<BlockPlacement> placements = new ArrayList<>();
        if (!ops.isEmpty()) {
            ShapePlan.Result result = ShapePlan.expand(new JSONArray(ops), maxAiBlocks);
            for (String w : result.warnings()) log.warn("Build plan: " + w);
            for (ShapePlan.Block b : result.blocks()) {
                placements.add(new BlockPlacement(origin.offset(b.x(), b.y(), b.z()), b.spec()));
            }
        } else {
            for (JSONObject block : blocks) {
                int x = block.optInt("x", 0);
                int y = block.optInt("y", 0);
                int z = block.optInt("z", 0);
                // Only the shape of the id is normalised here. Whether it is a
                // placeable block is checked in executeBuild, through the
                // registry, on the server thread.
                String id = Ids.of(block.optString("material", "minecraft:stone").trim().toLowerCase(Locale.ROOT));
                placements.add(new BlockPlacement(origin.offset(x, y, z), id));
            }
        }
        if (placements.isEmpty()) {
            log.warn("Build plan has no blocks");
            return null;
        }

        // Sort by Y to build from bottom up
        placements.sort(Comparator.comparingInt(p -> p.pos.y()));
        return placements;
    }

    private static List<JSONObject> elements(JSONArray array) {
        List<JSONObject> out = new ArrayList<>();
        if (array == null) return out;
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o != null) out.add(o);
        }
        return out;
    }

    /**
     * Execute the build with the NPC.
     *
     * @param aiGenerated whether an AI planned this — only those are remembered
     * @return the live build state, so the caller can attach memory context
     */
    private BuildState executeBuild(Owner player, World world, String description,
                                    List<BlockPlacement> placements, boolean aiGenerated) {
        // Resolve every spec through the registry, on the server thread.
        //
        // A name that exists is not proof of a block: a model asking for
        // "minecraft:brick" names the ITEM (the block is BRICKS), and placing
        // it would throw part-way through and kill the build. A spec the
        // server does not recognise degrades to the fallback instead.
        BlockState fallback = platform.blockTypes().parse(fallbackBlock).orElse(BlockState.of(Ids.DIRT));
        int repaired = 0;
        for (BlockPlacement bp : placements) {
            bp.state = platform.blockTypes().parse(bp.spec).orElse(null);
            if (bp.state == null) {
                log.fine("Plan asked for unknown or non-block '" + bp.spec + "'; substituting " + fallback.id());
                bp.state = fallback;
                repaired++;
            }
        }
        if (repaired > 0) {
            log.info("Build plan had " + repaired + " non-block material(s); substituted " + fallback.id() + ".");
        }

        // Beds are two blocks that have to agree with each other, so this runs
        // once the specs are resolved and before anything is queued.
        normaliseBeds(placements);

        BuildState state = new BuildState(player.id(), description, world);
        state.queue.addAll(placements);
        state.totalBlocks = placements.size();
        state.aiGenerated = aiGenerated;

        activeBuilds.put(player.id(), state);

        player.message(Colors.GREEN + "Starting build: " + Colors.YELLOW + description);
        player.message(Colors.GRAY + "Placing " + state.totalBlocks + " blocks...");

        // Start build task
        state.task = platform.scheduler().every(1L, 1L, self -> {
            if (!butlers.isSpawned(player) || !player.isOnline()) {
                cancelBuildInternal(player, "NPC or player unavailable", false);
                self.cancel();
                return;
            }

            // Place blocks this tick
            int placed = 0;
            try {
                while (!state.queue.isEmpty() && placed < blocksPerTick) {
                    BlockPlacement placement = state.queue.poll();
                    if (placement == null) break;

                    BlockState original = world.block(placement.pos);

                    // Skip only when the block is already exactly right. An
                    // id-only comparison would skip re-orienting stairs that
                    // happen to share a material with what is there.
                    if (original.matches(placement.state)) {
                        continue;
                    }

                    // Record for undo
                    if (enableUndo) {
                        state.actions.add(new BuildAction(placement.pos, original, placement.state.id()));
                    }

                    // Place the block, keeping states when the plan supplied them.
                    world.setBlock(placement.pos, placement.state, false);
                    if (connects(placement.state.id())) {
                        state.connectQueue.add(placement.pos);
                    }
                    state.placedBlocks++;
                    placed++;
                }

            } catch (Exception e) {
                // Never let a bad plan be remembered as a plan that worked.
                failBuild(player, state, e.getMessage());
                self.cancel();
                return;
            }

            // Progress update
            if (state.placedBlocks % progressUpdateInterval == 0 && state.placedBlocks > 0) {
                int percent = (state.placedBlocks * 100) / state.totalBlocks;
                player.message(Colors.YELLOW + "Build progress: " + percent + "% (" +
                    state.placedBlocks + "/" + state.totalBlocks + ")");
            }

            // Check completion
            if (state.queue.isEmpty()) {
                // Resolve connections in the same throttled loop rather than
                // all at once -- a village build can carry hundreds of panes.
                int connected = 0;
                while (!state.connectQueue.isEmpty() && connected < blocksPerTick) {
                    connectToNeighbours(world, state.connectQueue.poll());
                    connected++;
                }
                if (state.connectQueue.isEmpty()) {
                    completeBuild(player, state);
                    self.cancel();
                }
            }
        });

        return state;
    }

    /**
     * Make each bed's two halves agree, and drop any half left on its own.
     *
     * <p>A bed is a foot and a head, and the head must sit one block from the
     * foot in the direction of {@code facing}. Models get the axis wrong: one
     * live script asked for {@code facing=east} while offsetting the halves
     * along z, which renders as a bed with one end turned the wrong way.
     *
     * <p>Rather than trust the stated facing, this takes the geometry the
     * script actually laid out as the intent -- foot here, head there -- and
     * rewrites both facings to the direction between them. A half with no
     * partner is dropped: placing one alone leaves an invalid block that pops
     * off as an item the moment anything updates it.
     */
    private void normaliseBeds(List<BlockPlacement> placements) {
        Map<Long, BlockPlacement> beds = new HashMap<>();
        for (BlockPlacement p : placements) {
            if (isBed(p.state)) beds.put(p.pos.packed(), p);
        }
        if (beds.isEmpty()) return;

        Set<BlockPlacement> paired = new HashSet<>();
        for (BlockPlacement foot : beds.values()) {
            if (!"foot".equals(foot.state.prop("part"))) continue;
            for (Facing side : SIDES) {
                BlockPlacement head = beds.get(foot.pos.side(side).packed());
                if (head == null || !"head".equals(head.state.prop("part"))) continue;

                if (!side.key().equals(foot.state.prop("facing"))) {
                    foot.state = foot.state.with("facing", side.key());
                    head.state = head.state.with("facing", side.key());
                    log.fine("Bed facing corrected to " + side.key() + " to match where the halves were placed.");
                }
                paired.add(foot);
                paired.add(head);
                break;
            }
        }

        int orphans = 0;
        for (BlockPlacement p : beds.values()) {
            if (!paired.contains(p)) {
                placements.remove(p);
                orphans++;
            }
        }
        if (orphans > 0) {
            log.info("Dropped " + orphans + " bed half/halves with no matching partner.");
        }
    }

    private static boolean isBed(BlockState state) {
        return state != null && state.id().endsWith("_bed") && state.prop("part") != null;
    }

    /** The four horizontal faces a pane, bar, fence or wall can connect along. */
    private static final Facing[] SIDES = { Facing.NORTH, Facing.EAST, Facing.SOUTH, Facing.WEST };

    /** A pane, bar or fence: its four side properties are true or false. */
    static boolean isMultipleFacing(String id) {
        return id.endsWith("_pane") || id.equals(Ids.IRON_BARS) || id.endsWith("_fence");
    }

    /** A wall: its four side properties are none, low or tall. */
    static boolean isWall(String id) {
        return id.endsWith("_wall");
    }

    private static boolean connects(String id) {
        return isMultipleFacing(id) || isWall(id);
    }

    /**
     * Point a pane, bar, fence or wall at whatever ended up beside it.
     *
     * <p>Without this a window reads as a floating shard. Placement runs with
     * physics off -- deliberately, so a build does not set off cascading
     * updates, falling gravel and popping torches while it goes up -- but that
     * also means a glass pane keeps the disconnected shape it was placed with.
     * Re-placing it with physics on does not help either: a block does not
     * recompute its own shape, it only tells its neighbours to recompute
     * theirs, so a pane walled in by solid blocks is never told anything.
     *
     * <p>So the connections are worked out directly. A face connects when the
     * neighbour is a solid block, or is another pane, bar, fence or wall.
     */
    private void connectToNeighbours(World world, BlockPos pos) {
        BlockState data = world.block(pos);
        String id = data.id();

        if (isMultipleFacing(id)) {
            boolean changed = false;
            for (Facing side : SIDES) {
                boolean connect = connectsTo(world, pos.side(side));
                String have = data.prop(side.key());
                if (have == null) continue;                      // this shape has no such face
                if (Boolean.parseBoolean(have) != connect) {
                    data = data.with(side.key(), connect);
                    changed = true;
                }
            }
            if (changed) world.setBlock(pos, data, false);

        } else if (isWall(id)) {
            boolean changed = false;
            for (Facing side : SIDES) {
                String want = connectsTo(world, pos.side(side)) ? "low" : "none";
                if (!want.equals(data.prop(side.key()))) {
                    data = data.with(side.key(), want);
                    changed = true;
                }
            }
            // A wall with no side connections shows its centre post, which is
            // the right look for a free-standing post and wrong for a run.
            if (changed) world.setBlock(pos, data, false);
        }
    }

    /** True when a neighbour is something a pane or wall should attach to. */
    private boolean connectsTo(World world, BlockPos neighbour) {
        if (connects(world.block(neighbour).id())) return true;
        return world.isSolid(neighbour) && world.isOccluding(neighbour);
    }

    /**
     * Abort a build that threw part-way through.
     *
     * Deliberately records FAILED rather than letting the plan reach
     * completeBuild: a plan that crashes the placer is the clearest possible
     * example of a plan that does not work, and recording it as a success would
     * teach the memory to produce more like it.
     */
    private void failBuild(Owner player, BuildState state, String reason) {
        activeBuilds.remove(player.id());
        if (state.task != null) state.task.cancel();

        log.warn("Build failed after " + state.placedBlocks + "/" + state.totalBlocks + " blocks: " + reason);
        player.message(Colors.RED + "Jarvis: The design proved unbuildable, sir — "
                + "I stopped after " + state.placedBlocks + " blocks.");
        if (reason != null) player.message(Colors.GRAY + "  (" + reason + ")");

        // Whatever did get placed still needs to be revertible.
        if (enableUndo && !state.actions.isEmpty()) {
            history(player).addLast(new UndoEntry(state.world, state.actions, state.aiGenerated, null));
        }

        if (state.aiGenerated && memory != null) {
            memory.record(BuildExperience.now(
                player.id(),
                ExperienceMemory.TASK_BUILD_FREEFORM,
                state.description,
                state.situationJson,
                state.planJson,
                BuildExperience.Outcome.FAILED,
                state.provider));
        }
    }

    private Deque<UndoEntry> history(Owner player) {
        return undoHistory.computeIfAbsent(player.id(), k -> new ArrayDeque<>());
    }

    /** Complete a build. */
    private void completeBuild(Owner player, BuildState state) {
        activeBuilds.remove(player.id());

        // Remember the plan that worked. The label is free: a build that ran to
        // completion and was left alone is a success by definition.
        BuildExperience experience = null;
        if (state.aiGenerated && memory != null) {
            experience = BuildExperience.now(
                player.id(),
                ExperienceMemory.TASK_BUILD_FREEFORM,
                state.description,
                state.situationJson,
                state.planJson,
                BuildExperience.Outcome.SUCCESS,
                state.provider);
            memory.record(experience);
        }

        // Save to undo history, carrying the experience so a later undo demotes
        // this build rather than whichever success happens to be newest.
        if (enableUndo && !state.actions.isEmpty()) {
            Deque<UndoEntry> history = history(player);

            // Limit undo history size
            while (history.size() >= 10) {
                history.removeFirst();
            }
            history.addLast(new UndoEntry(state.world, state.actions, state.aiGenerated, experience));
        }

        // Building is a discipline like any other — credit it, or the service
        // record shows a permanent zero for blocks laid while the score that
        // uses it silently never moves.
        if (progression != null && state.placedBlocks > 0) {
            progression.record(player, ServiceRecord.Discipline.CONSTRUCTION, state.placedBlocks);
        }

        // Log to database
        try {
            BlockPos at = player.pos().block();
            database.saveBuildHistory(
                player.id().toString(),
                state.description,
                state.placedBlocks,
                state.world.name(),
                at.x(), at.y(), at.z());
        } catch (Exception e) {
            log.warn("Failed to log build history: " + e.getMessage());
        }

        long duration = (System.currentTimeMillis() - state.startTime) / 1000;
        player.message("");
        player.message(Colors.GREEN + "========================================");
        player.message(Colors.GOLD + "  Build Complete: " + Colors.YELLOW + state.description);
        player.message(Colors.GREEN + "========================================");
        player.message(Colors.WHITE + "  Blocks placed: " + state.placedBlocks);
        player.message(Colors.WHITE + "  Time: " + duration + " seconds");
        if (enableUndo) {
            player.message(Colors.GRAY + "  Use /jarvis build undo to revert");
        }
        player.message(Colors.GREEN + "========================================");

        player.sound(Ids.SOUND_ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
    }

    /** Cancel an active build. */
    public void cancelBuild(Owner player) {
        cancelBuildInternal(player, "Cancelled by player", true);
    }

    /**
     * @param playerInitiated true only when the player chose to stop the build.
     *        A build aborted because the NPC despawned or the player logged out
     *        says nothing about the quality of the plan, so it must not be
     *        recorded as a negative signal.
     */
    private void cancelBuildInternal(Owner player, String reason, boolean playerInitiated) {
        BuildState state = activeBuilds.remove(player.id());
        if (state == null) {
            player.message(Colors.GRAY + "No active build to cancel.");
            return;
        }

        if (state.task != null) {
            state.task.cancel();
        }

        player.message(Colors.YELLOW + "Build cancelled: " + reason);
        player.message(Colors.GRAY + "Placed " + state.placedBlocks + " of " + state.totalBlocks + " blocks.");

        // Still allow undo of partial build
        if (enableUndo && !state.actions.isEmpty()) {
            history(player).addLast(new UndoEntry(state.world, state.actions, state.aiGenerated, null));
        }

        if (playerInitiated && state.aiGenerated && memory != null) {
            memory.record(BuildExperience.now(
                player.id(),
                ExperienceMemory.TASK_BUILD_FREEFORM,
                state.description,
                state.situationJson,
                state.planJson,
                BuildExperience.Outcome.CANCELLED,
                state.provider));
        }
    }

    /** Undo the last build. */
    public void undoLastBuild(Owner player) {
        if (!enableUndo) {
            player.message(Colors.RED + "Undo is disabled in config.");
            return;
        }

        Deque<UndoEntry> history = undoHistory.get(player.id());
        if (history == null || history.isEmpty()) {
            player.message(Colors.GRAY + "No builds to undo.");
            return;
        }

        UndoEntry entry = history.removeLast();
        List<BuildAction> actions = entry.actions;
        player.message(Colors.YELLOW + "Undoing " + actions.size() + " blocks...");

        // Undo in reverse order
        int undone = 0;
        for (int i = actions.size() - 1; i >= 0; i--) {
            BuildAction action = actions.get(i);

            // Only undo if block hasn't been modified by someone else
            if (entry.world.block(action.pos()).id().equals(action.newId())) {
                entry.world.setBlock(action.pos(), action.original(), false);
                undone++;
            }
        }

        player.message(Colors.GREEN + "Reverted " + undone + " blocks.");

        // Reverting an AI build shortly after it finished is the clearest
        // "that plan was wrong" signal there is. Hand-built shapes share this
        // undo stack, so the flag matters.
        if (entry.aiGenerated && undone > 0 && memory != null) {
            if (entry.experience != null) {
                memory.markUndone(entry.experience);
            } else {
                memory.markRecentBuildUndone(player.id());
            }
        }
    }

    /** Build a simple shape without AI. */
    public void buildSimpleStructure(Owner player, String type, int size) {
        if (!butlers.isSpawned(player)) {
            player.message(Colors.RED + "Summon Jarvis first!");
            return;
        }
        World world = platform.world(player.world()).orElse(null);
        if (world == null) return;

        BlockPos origin = player.pos().block().offset(2, 0, 0); // Offset from player
        List<BlockPlacement> placements = new ArrayList<>();

        switch (type.toLowerCase(Locale.ROOT)) {
            case "wall":
                for (int x = 0; x < size; x++) {
                    for (int y = 0; y < size; y++) {
                        placements.add(new BlockPlacement(origin.offset(x, y, 0), Ids.STONE_BRICKS));
                    }
                }
                break;

            case "floor":
                for (int x = 0; x < size; x++) {
                    for (int z = 0; z < size; z++) {
                        placements.add(new BlockPlacement(origin.offset(x, 0, z), Ids.OAK_PLANKS));
                    }
                }
                break;

            case "pillar":
                for (int y = 0; y < size; y++) {
                    placements.add(new BlockPlacement(origin.offset(0, y, 0), Ids.STONE_BRICKS));
                }
                break;

            case "cube":
                for (int x = 0; x < size; x++) {
                    for (int y = 0; y < size; y++) {
                        for (int z = 0; z < size; z++) {
                            // Only edges (hollow cube)
                            boolean edge = (x == 0 || x == size - 1) ||
                                          (y == 0 || y == size - 1) ||
                                          (z == 0 || z == size - 1);
                            if (edge) {
                                placements.add(new BlockPlacement(origin.offset(x, y, z), Ids.STONE_BRICKS));
                            }
                        }
                    }
                }
                break;

            default:
                player.message(Colors.RED + "Unknown structure type: " + type);
                player.message(Colors.GRAY + "Available: wall, floor, pillar, cube");
                return;
        }

        if (!placements.isEmpty()) {
            executeBuild(player, world, type + " (" + size + "x)", placements, false);
        }
    }

    // ==================== GETTERS ====================

    public boolean isBuilding(Owner player) {
        return activeBuilds.containsKey(player.id());
    }

    public int getBuildProgress(Owner player) {
        BuildState state = activeBuilds.get(player.id());
        if (state == null) return 0;
        return state.totalBlocks > 0 ? (state.placedBlocks * 100) / state.totalBlocks : 0;
    }
}
