package com.gadgetman.jarvis.building;

import com.gadgetman.jarvis.Jarvis;
import com.gadgetman.jarvis.memory.BuildExperience;
import com.gadgetman.jarvis.memory.ExperienceMemory;
import com.gadgetman.jarvis.memory.SituationSnapshot;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BuildingAssistant - AI-powered building system
 *
 * Accepts natural language build requests, queries AI for block placement,
 * and executes builds through the NPC with undo support.
 */
public class BuildingAssistant {

    private final Jarvis plugin;
    private final Map<UUID, BuildState> activeBuilds = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<UndoEntry>> undoHistory = new ConcurrentHashMap<>();

    // Configuration
    private int blocksPerTick = 50;
    private int progressUpdateInterval = 100;
    private boolean enableUndo = true;
    private int undoTimeout = 300; // seconds
    private int maxUndoSize = 10000;
    private int maxAiBlocks = 5000;
    private String fallbackMaterial = "DIRT";

    public BuildingAssistant(Jarvis plugin) {
        this.plugin = plugin;
        loadConfig();
        plugin.getLogger().info("Building assistant initialized");
    }

    private void loadConfig() {
        blocksPerTick = plugin.getConfig().getInt("build.blocks-per-tick", 50);
        progressUpdateInterval = plugin.getConfig().getInt("build.progress-update-interval", 100);
        enableUndo = plugin.getConfig().getBoolean("build.enable-undo", true);
        undoTimeout = plugin.getConfig().getInt("build.undo-timeout", 300);
        maxUndoSize = plugin.getConfig().getInt("build.max-undo-size", 10000);
        maxAiBlocks = plugin.getConfig().getInt("build.max-ai-blocks", 5000);
        fallbackMaterial = plugin.getConfig().getString("build.fallback-material", "minecraft:dirt")
            .replace("minecraft:", "").toUpperCase();
    }

    // ==================== DATA STRUCTURES ====================

    private static class BuildState {
        UUID playerId;
        String description;
        Queue<BlockPlacement> queue;
        int totalBlocks;
        int placedBlocks;
        BukkitTask task;
        long startTime;
        List<BuildAction> actions; // For undo

        // Experience memory. Only AI-planned builds are remembered — a hand-built
        // wall says nothing about plan quality, so it must never be recorded or
        // demoted alongside one.
        boolean aiGenerated;
        String situationJson;
        String planJson;
        String provider;

        BuildState(UUID playerId, String description) {
            this.playerId = playerId;
            this.description = description;
            this.queue = new LinkedList<>();
            this.totalBlocks = 0;
            this.placedBlocks = 0;
            this.startTime = System.currentTimeMillis();
            this.actions = new ArrayList<>();
        }
    }

    private static class BlockPlacement {
        Location location;
        Material material;

        BlockPlacement(Location location, Material material) {
            this.location = location;
            this.material = material;
        }
    }

    /**
     * One undoable batch. Carries whether the build was AI-planned so undoing a
     * hand-built shape cannot demote an unrelated AI build in memory.
     */
    private static class UndoEntry {
        final List<BuildAction> actions;
        final boolean aiGenerated;
        /**
         * The experience this batch produced, so undo demotes the row it
         * actually reverted. Held by reference: the id is filled in by the
         * async insert, and this sees it. Null for hand-built shapes and for
         * builds that never got recorded.
         */
        final BuildExperience experience;

        UndoEntry(List<BuildAction> actions, boolean aiGenerated, BuildExperience experience) {
            this.actions = actions;
            this.aiGenerated = aiGenerated;
            this.experience = experience;
        }
    }

    private static class BuildAction {
        Location location;
        Material originalMaterial;
        Material newMaterial;

        BuildAction(Location location, Material original, Material newMat) {
            this.location = location;
            this.originalMaterial = original;
            this.newMaterial = newMat;
        }
    }

    // ==================== BUILD COMMANDS ====================

    /**
     * Start an AI-powered build
     */
    public void startBuild(Player player, String description) {
        if (activeBuilds.containsKey(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "You already have an active build!");
            player.sendMessage(ChatColor.GRAY + "Use /jarvis build cancel to stop it first.");
            return;
        }

        // Check if NPC is summoned
        NPC npc = plugin.getJarvisNPC().getNPCForPlayer(player.getUniqueId());
        if (npc == null || !npc.isSpawned()) {
            player.sendMessage(ChatColor.RED + "Summon Jarvis first with /jarvis summon");
            return;
        }

        player.sendMessage(ChatColor.YELLOW + "Jarvis is planning your build: " + ChatColor.WHITE + description);
        player.sendMessage(ChatColor.GRAY + "Please wait while the AI generates the structure...");

        // Capture the world state before going async — every getter in
        // SituationSnapshot touches the world and is main-thread only.
        final Location origin = player.getLocation();
        final String situationJson = SituationSnapshot.capture(origin);
        final ExperienceMemory memory = plugin.getExperienceMemory();

        // Generate build asynchronously
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    String examples = "";
                    boolean unlocksReducedMode = false;
                    if (memory != null && memory.isEnabled()) {
                        examples = memory.retrieveExamples(description, situationJson);
                        unlocksReducedMode = memory.isReducedModeBuildUnlocked();
                    }

                    String response = plugin.getAIConnector()
                            .queryBuildPlan(description, examples, unlocksReducedMode);
                    final String provider = plugin.getAIConnector().getProvider();
                    List<BlockPlacement> placements = parseBuildPlan(response, origin);

                    if (placements == null || placements.isEmpty()) {
                        // A plan that produced no blocks is a bad plan, not a bad
                        // connection — worth remembering as such.
                        if (memory != null) {
                            memory.record(BuildExperience.now(player.getUniqueId(),
                                    ExperienceMemory.TASK_BUILD_FREEFORM, description,
                                    situationJson, response,
                                    BuildExperience.Outcome.FAILED, provider));
                        }
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                player.sendMessage(ChatColor.RED + "Failed to generate build plan. Try a different description.");
                            }
                        }.runTask(plugin);
                        return;
                    }

                    // Enforce size limit
                    if (placements.size() > maxAiBlocks) {
                        placements = placements.subList(0, maxAiBlocks);
                    }

                    final List<BlockPlacement> finalPlacements = placements;
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            BuildState state = executeBuild(player, description, finalPlacements, true);
                            if (state != null) {
                                state.situationJson = situationJson;
                                state.planJson = response;
                                state.provider = provider;
                            }
                        }
                    }.runTask(plugin);

                } catch (Exception e) {
                    plugin.getLogger().warning("Build generation failed: " + e.getMessage());
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            player.sendMessage(ChatColor.RED + "AI build generation failed: " + e.getMessage());
                        }
                    }.runTask(plugin);
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * Parse AI response into block placements
     */
    private List<BlockPlacement> parseBuildPlan(String jsonResponse, Location origin) {
        try {
            JSONObject json = new JSONObject(jsonResponse);
            JSONArray blocks = json.optJSONArray("blocks");

            if (blocks == null || blocks.isEmpty()) {
                return null;
            }

            List<BlockPlacement> placements = new ArrayList<>();

            for (int i = 0; i < blocks.length(); i++) {
                JSONObject block = blocks.optJSONObject(i);
                if (block == null) continue;

                int x = block.optInt("x", 0);
                int y = block.optInt("y", 0);
                int z = block.optInt("z", 0);
                String materialStr = block.optString("material", "minecraft:stone")
                    .replace("minecraft:", "").toUpperCase();

                // Only the NAME is validated here. Whether the material is
                // actually placeable is checked in executeBuild: Material#isBlock
                // resolves through the registry, and this runs off the main
                // thread.
                Material material;
                try {
                    material = Material.valueOf(materialStr);
                } catch (IllegalArgumentException e) {
                    material = Material.valueOf(fallbackMaterial);
                }

                Location loc = origin.clone().add(x, y, z);
                placements.add(new BlockPlacement(loc, material));
            }

            // Sort by Y to build from bottom up
            placements.sort(Comparator.comparingDouble(p -> p.location.getY()));

            return placements;

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to parse build plan: " + e.getMessage());
            return null;
        }
    }

    /**
     * Execute the build with the NPC.
     *
     * @param aiGenerated whether an AI planned this — only those are remembered
     * @return the live build state, so the caller can attach memory context
     */
    private BuildState executeBuild(Player player, String description,
                                    List<BlockPlacement> placements, boolean aiGenerated) {
        // Drop anything that cannot actually be placed, on the main thread.
        //
        // Material.valueOf() only proves the name exists. A model asking for
        // "minecraft:brick" resolves to Material.BRICK — the ITEM; the block is
        // BRICKS — and setType() then throws "Provided material must be a block"
        // part-way through, killing the build. Material#isBlock resolves through
        // the registry, so this has to happen here rather than in the async
        // parse.
        Material fallback = Material.valueOf(fallbackMaterial);
        int repaired = 0;
        for (BlockPlacement bp : placements) {
            if (!bp.material.isBlock()) {
                plugin.getLogger().fine("Plan asked for non-block material "
                        + bp.material + "; substituting " + fallback);
                bp.material = fallback;
                repaired++;
            }
        }
        if (repaired > 0) {
            plugin.getLogger().info("Build plan had " + repaired
                    + " non-block material(s); substituted " + fallback + ".");
        }

        BuildState state = new BuildState(player.getUniqueId(), description);
        state.queue.addAll(placements);
        state.totalBlocks = placements.size();
        state.aiGenerated = aiGenerated;

        activeBuilds.put(player.getUniqueId(), state);

        player.sendMessage(ChatColor.GREEN + "Starting build: " + ChatColor.YELLOW + description);
        player.sendMessage(ChatColor.GRAY + "Placing " + state.totalBlocks + " blocks...");

        // Start build task
        state.task = new BukkitRunnable() {
            @Override
            public void run() {
                NPC npc = plugin.getJarvisNPC().getNPCForPlayer(player.getUniqueId());
                if (npc == null || !npc.isSpawned() || !player.isOnline()) {
                    cancelBuildInternal(player, "NPC or player unavailable", false);
                    cancel();
                    return;
                }

                // Place blocks this tick
                int placed = 0;
                try {
                while (!state.queue.isEmpty() && placed < blocksPerTick) {
                    BlockPlacement placement = state.queue.poll();
                    if (placement == null) break;

                    Block block = placement.location.getBlock();
                    Material original = block.getType();

                    // Skip if same material
                    if (original == placement.material) {
                        continue;
                    }

                    // Record for undo
                    if (enableUndo) {
                        state.actions.add(new BuildAction(placement.location, original, placement.material));
                    }

                    // Place the block
                    block.setType(placement.material);
                    state.placedBlocks++;
                    placed++;
                }

                } catch (Exception e) {
                    // Never let a bad plan be remembered as a plan that worked.
                    failBuild(player, state, e.getMessage());
                    cancel();
                    return;
                }

                // Progress update
                if (state.placedBlocks % progressUpdateInterval == 0 && state.placedBlocks > 0) {
                    int percent = (state.placedBlocks * 100) / state.totalBlocks;
                    player.sendMessage(ChatColor.YELLOW + "Build progress: " + percent + "% (" +
                        state.placedBlocks + "/" + state.totalBlocks + ")");
                }

                // Check completion
                if (state.queue.isEmpty()) {
                    completeBuild(player, state);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        return state;
    }

    /**
     * Abort a build that threw part-way through.
     *
     * Deliberately records FAILED rather than letting the plan reach
     * completeBuild: a plan that crashes the placer is the clearest possible
     * example of a plan that does not work, and recording it as a success would
     * teach the memory to produce more like it.
     */
    private void failBuild(Player player, BuildState state, String reason) {
        activeBuilds.remove(player.getUniqueId());
        if (state.task != null) state.task.cancel();

        plugin.getLogger().warning("Build failed after " + state.placedBlocks
                + "/" + state.totalBlocks + " blocks: " + reason);
        player.sendMessage(ChatColor.RED + "Jarvis: The design proved unbuildable, sir — "
                + "I stopped after " + state.placedBlocks + " blocks.");
        if (reason != null) player.sendMessage(ChatColor.GRAY + "  (" + reason + ")");

        // Whatever did get placed still needs to be revertible.
        if (enableUndo && !state.actions.isEmpty()) {
            Deque<UndoEntry> history = undoHistory.computeIfAbsent(
                player.getUniqueId(), k -> new ArrayDeque<>());
            history.addLast(new UndoEntry(state.actions, state.aiGenerated, null));
        }

        if (state.aiGenerated && plugin.getExperienceMemory() != null) {
            plugin.getExperienceMemory().record(BuildExperience.now(
                player.getUniqueId(),
                ExperienceMemory.TASK_BUILD_FREEFORM,
                state.description,
                state.situationJson,
                state.planJson,
                BuildExperience.Outcome.FAILED,
                state.provider));
        }
    }

    /**
     * Complete a build
     */
    private void completeBuild(Player player, BuildState state) {
        activeBuilds.remove(player.getUniqueId());

        // Remember the plan that worked. The label is free: a build that ran to
        // completion and was left alone is a success by definition.
        BuildExperience experience = null;
        if (state.aiGenerated && plugin.getExperienceMemory() != null) {
            experience = BuildExperience.now(
                player.getUniqueId(),
                ExperienceMemory.TASK_BUILD_FREEFORM,
                state.description,
                state.situationJson,
                state.planJson,
                BuildExperience.Outcome.SUCCESS,
                state.provider);
            plugin.getExperienceMemory().record(experience);
        }

        // Save to undo history, carrying the experience so a later undo demotes
        // this build rather than whichever success happens to be newest.
        if (enableUndo && !state.actions.isEmpty()) {
            Deque<UndoEntry> history = undoHistory.computeIfAbsent(
                player.getUniqueId(), k -> new ArrayDeque<>());

            // Limit undo history size
            while (history.size() >= 10) {
                history.removeFirst();
            }
            history.addLast(new UndoEntry(state.actions, state.aiGenerated, experience));
        }

        // Log to database
        try {
            Location loc = player.getLocation();
            plugin.getDatabaseManager().saveBuildHistory(
                player.getUniqueId().toString(),
                state.description,
                state.placedBlocks,
                loc.getWorld().getName(),
                loc.getBlockX(),
                loc.getBlockY(),
                loc.getBlockZ()
            );
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to log build history: " + e.getMessage());
        }

        long duration = (System.currentTimeMillis() - state.startTime) / 1000;
        player.sendMessage("");
        player.sendMessage(ChatColor.GREEN + "========================================");
        player.sendMessage(ChatColor.GOLD + "  Build Complete: " + ChatColor.YELLOW + state.description);
        player.sendMessage(ChatColor.GREEN + "========================================");
        player.sendMessage(ChatColor.WHITE + "  Blocks placed: " + state.placedBlocks);
        player.sendMessage(ChatColor.WHITE + "  Time: " + duration + " seconds");
        if (enableUndo) {
            player.sendMessage(ChatColor.GRAY + "  Use /jarvis build undo to revert");
        }
        player.sendMessage(ChatColor.GREEN + "========================================");

        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
    }

    /**
     * Cancel an active build
     */
    public void cancelBuild(Player player) {
        cancelBuildInternal(player, "Cancelled by player", true);
    }

    /**
     * @param playerInitiated true only when the player chose to stop the build.
     *        A build aborted because the NPC despawned or the player logged out
     *        says nothing about the quality of the plan, so it must not be
     *        recorded as a negative signal.
     */
    private void cancelBuildInternal(Player player, String reason, boolean playerInitiated) {
        BuildState state = activeBuilds.remove(player.getUniqueId());
        if (state == null) {
            player.sendMessage(ChatColor.GRAY + "No active build to cancel.");
            return;
        }

        if (state.task != null) {
            state.task.cancel();
        }

        player.sendMessage(ChatColor.YELLOW + "Build cancelled: " + reason);
        player.sendMessage(ChatColor.GRAY + "Placed " + state.placedBlocks + " of " + state.totalBlocks + " blocks.");

        // Still allow undo of partial build
        if (enableUndo && !state.actions.isEmpty()) {
            Deque<UndoEntry> history = undoHistory.computeIfAbsent(
                player.getUniqueId(), k -> new ArrayDeque<>());
            history.addLast(new UndoEntry(state.actions, state.aiGenerated, null));
        }

        if (playerInitiated && state.aiGenerated && plugin.getExperienceMemory() != null) {
            plugin.getExperienceMemory().record(BuildExperience.now(
                player.getUniqueId(),
                ExperienceMemory.TASK_BUILD_FREEFORM,
                state.description,
                state.situationJson,
                state.planJson,
                BuildExperience.Outcome.CANCELLED,
                state.provider));
        }
    }

    /**
     * Undo the last build
     */
    public void undoLastBuild(Player player) {
        if (!enableUndo) {
            player.sendMessage(ChatColor.RED + "Undo is disabled in config.");
            return;
        }

        Deque<UndoEntry> history = undoHistory.get(player.getUniqueId());
        if (history == null || history.isEmpty()) {
            player.sendMessage(ChatColor.GRAY + "No builds to undo.");
            return;
        }

        UndoEntry entry = history.removeLast();
        List<BuildAction> actions = entry.actions;
        player.sendMessage(ChatColor.YELLOW + "Undoing " + actions.size() + " blocks...");

        // Undo in reverse order
        int undone = 0;
        for (int i = actions.size() - 1; i >= 0; i--) {
            BuildAction action = actions.get(i);
            Block block = action.location.getBlock();

            // Only undo if block hasn't been modified by someone else
            if (block.getType() == action.newMaterial) {
                block.setType(action.originalMaterial);
                undone++;
            }
        }

        player.sendMessage(ChatColor.GREEN + "Reverted " + undone + " blocks.");

        // Reverting an AI build shortly after it finished is the clearest
        // "that plan was wrong" signal there is. Hand-built shapes share this
        // undo stack, so the flag matters.
        if (entry.aiGenerated && undone > 0 && plugin.getExperienceMemory() != null) {
            if (entry.experience != null) {
                plugin.getExperienceMemory().markUndone(entry.experience);
            } else {
                plugin.getExperienceMemory().markRecentBuildUndone(player.getUniqueId());
            }
        }
    }

    /**
     * Build a simple shape without AI
     */
    public void buildSimpleStructure(Player player, String type, int size) {
        NPC npc = plugin.getJarvisNPC().getNPCForPlayer(player.getUniqueId());
        if (npc == null || !npc.isSpawned()) {
            player.sendMessage(ChatColor.RED + "Summon Jarvis first!");
            return;
        }

        Location origin = player.getLocation().add(2, 0, 0); // Offset from player
        List<BlockPlacement> placements = new ArrayList<>();

        switch (type.toLowerCase()) {
            case "wall":
                for (int x = 0; x < size; x++) {
                    for (int y = 0; y < size; y++) {
                        placements.add(new BlockPlacement(
                            origin.clone().add(x, y, 0),
                            Material.STONE_BRICKS
                        ));
                    }
                }
                break;

            case "floor":
                for (int x = 0; x < size; x++) {
                    for (int z = 0; z < size; z++) {
                        placements.add(new BlockPlacement(
                            origin.clone().add(x, 0, z),
                            Material.OAK_PLANKS
                        ));
                    }
                }
                break;

            case "pillar":
                for (int y = 0; y < size; y++) {
                    placements.add(new BlockPlacement(
                        origin.clone().add(0, y, 0),
                        Material.STONE_BRICKS
                    ));
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
                                placements.add(new BlockPlacement(
                                    origin.clone().add(x, y, z),
                                    Material.STONE_BRICKS
                                ));
                            }
                        }
                    }
                }
                break;

            default:
                player.sendMessage(ChatColor.RED + "Unknown structure type: " + type);
                player.sendMessage(ChatColor.GRAY + "Available: wall, floor, pillar, cube");
                return;
        }

        if (!placements.isEmpty()) {
            executeBuild(player, type + " (" + size + "x)", placements, false);
        }
    }

    // ==================== GETTERS ====================

    public boolean isBuilding(Player player) {
        return activeBuilds.containsKey(player.getUniqueId());
    }

    public int getBuildProgress(Player player) {
        BuildState state = activeBuilds.get(player.getUniqueId());
        if (state == null) return 0;
        return state.totalBlocks > 0 ? (state.placedBlocks * 100) / state.totalBlocks : 0;
    }
}
