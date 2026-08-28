package com.gadgetman.jarvis.building;

import com.gadgetman.jarvis.Jarvis;
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
    private final Map<UUID, Deque<List<BuildAction>>> undoHistory = new ConcurrentHashMap<>();

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

        // Generate build asynchronously
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    String response = plugin.getAIConnector().queryBuildPlan(description);
                    List<BlockPlacement> placements = parseBuildPlan(response, player.getLocation());

                    if (placements == null || placements.isEmpty()) {
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
                            executeBuild(player, description, finalPlacements);
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
     * Execute the build with the NPC
     */
    private void executeBuild(Player player, String description, List<BlockPlacement> placements) {
        BuildState state = new BuildState(player.getUniqueId(), description);
        state.queue.addAll(placements);
        state.totalBlocks = placements.size();

        activeBuilds.put(player.getUniqueId(), state);

        player.sendMessage(ChatColor.GREEN + "Starting build: " + ChatColor.YELLOW + description);
        player.sendMessage(ChatColor.GRAY + "Placing " + state.totalBlocks + " blocks...");

        // Start build task
        state.task = new BukkitRunnable() {
            @Override
            public void run() {
                NPC npc = plugin.getJarvisNPC().getNPCForPlayer(player.getUniqueId());
                if (npc == null || !npc.isSpawned() || !player.isOnline()) {
                    cancelBuildInternal(player, "NPC or player unavailable");
                    cancel();
                    return;
                }

                // Place blocks this tick
                int placed = 0;
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
    }

    /**
     * Complete a build
     */
    private void completeBuild(Player player, BuildState state) {
        activeBuilds.remove(player.getUniqueId());

        // Save to undo history
        if (enableUndo && !state.actions.isEmpty()) {
            Deque<List<BuildAction>> history = undoHistory.computeIfAbsent(
                player.getUniqueId(), k -> new ArrayDeque<>());

            // Limit undo history size
            while (history.size() >= 10) {
                history.removeFirst();
            }
            history.addLast(state.actions);
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
        cancelBuildInternal(player, "Cancelled by player");
    }

    private void cancelBuildInternal(Player player, String reason) {
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
            Deque<List<BuildAction>> history = undoHistory.computeIfAbsent(
                player.getUniqueId(), k -> new ArrayDeque<>());
            history.addLast(state.actions);
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

        Deque<List<BuildAction>> history = undoHistory.get(player.getUniqueId());
        if (history == null || history.isEmpty()) {
            player.sendMessage(ChatColor.GRAY + "No builds to undo.");
            return;
        }

        List<BuildAction> actions = history.removeLast();
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
            executeBuild(player, type + " (" + size + "x)", placements);
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
