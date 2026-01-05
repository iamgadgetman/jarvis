package com.yourname.jarvis.building;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.block.BlockState;
import com.yourname.jarvis.Jarvis;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BuildingAssistant {

    private final Jarvis plugin;
    private final Map<UUID, BuildTask> activeBuildTasks = new HashMap<>();

    private static class BuildTask {
        String description;
        Location startLocation;
        int blocksPlaced;
        int totalBlocks;
        boolean cancelled;
    }

    public BuildingAssistant(Jarvis plugin) {
        this.plugin = plugin;
    }

    public void startBuild(Player player, String description) {
        // Check if already building
        if (activeBuildTasks.containsKey(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "Jarvis: I'm already building something for you!");
            return;
        }

        // Check WorldEdit availability
        if (plugin.getServer().getPluginManager().getPlugin("WorldEdit") == null) {
            player.sendMessage(ChatColor.RED + "Jarvis: WorldEdit is required for building!");
            return;
        }

        BuildTask task = new BuildTask();
        task.description = description;
        task.startLocation = player.getLocation().clone();
        activeBuildTasks.put(player.getUniqueId(), task);

        player.sendMessage(ChatColor.GOLD + "Jarvis: Let me design that for you...");

        // Query AI for build plan asynchronously
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    String buildPlanJson = plugin.getAIConnector().queryBuildPlan(description);
                    
                    // Parse and execute build on main thread
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            executeBuild(player, buildPlanJson, task);
                        }
                    }.runTask(plugin);
                    
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to generate build plan: " + e.getMessage());
                    
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            player.sendMessage(ChatColor.RED + "Jarvis: I couldn't design that. Try something simpler!");
                            activeBuildTasks.remove(player.getUniqueId());
                        }
                    }.runTask(plugin);
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    private void executeBuild(Player player, String buildPlanJson, BuildTask task) {
        try {
            // Clean up JSON if it has markdown code blocks
            buildPlanJson = buildPlanJson.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            
            JSONObject plan = new JSONObject(buildPlanJson);
            JSONObject dimensions = plan.getJSONObject("dimensions");
            JSONArray blocks = plan.getJSONArray("blocks");

            int width = dimensions.getInt("width");
            int height = dimensions.getInt("height");
            int length = dimensions.getInt("length");

            task.totalBlocks = blocks.length();
            task.blocksPlaced = 0;

            player.sendMessage(ChatColor.GREEN + String.format("Jarvis: Building %dx%dx%d structure with %d blocks...", 
                    width, height, length, task.totalBlocks));

            // Get WorldEdit instance
            com.sk89q.worldedit.world.World world = BukkitAdapter.adapt(player.getWorld());
            
            // Place blocks gradually to avoid lag
            new BukkitRunnable() {
                int index = 0;
                
                @Override
                public void run() {
                    if (task.cancelled) {
                        cancel();
                        activeBuildTasks.remove(player.getUniqueId());
                        player.sendMessage(ChatColor.YELLOW + "Jarvis: Build cancelled.");
                        return;
                    }

                    // Place blocks in batches
                    int batchSize = 50; // Place 50 blocks per tick
                    int placed = 0;
                    
                    while (index < blocks.length() && placed < batchSize) {
                        try {
                            JSONObject block = blocks.getJSONObject(index);
                            int x = block.getInt("x");
                            int y = block.getInt("y");
                            int z = block.getInt("z");
                            String materialName = block.getString("material");

                            // Convert coordinates to world location
                            Location blockLoc = task.startLocation.clone().add(x, y, z);
                            
                            // Parse material
                            Material material = parseMaterial(materialName);
                            if (material != null && material.isBlock()) {
                                blockLoc.getBlock().setType(material);
                                task.blocksPlaced++;
                                placed++;
                            }
                            
                        } catch (Exception e) {
                            plugin.getLogger().warning("Error placing block: " + e.getMessage());
                        }
                        index++;
                    }

                    // Update progress
                    if (index % 100 == 0 && index < blocks.length()) {
                        int percent = (index * 100) / blocks.length();
                        player.sendMessage(ChatColor.GRAY + "Progress: " + percent + "%");
                    }

                    // Check if complete
                    if (index >= blocks.length()) {
                        cancel();
                        activeBuildTasks.remove(player.getUniqueId());
                        player.sendMessage(ChatColor.GREEN + String.format(
                                "Jarvis: Construction complete! Placed %d blocks.", task.blocksPlaced));
                    }
                }
            }.runTaskTimer(plugin, 0L, 1L);

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to execute build: " + e.getMessage());
            e.printStackTrace();
            player.sendMessage(ChatColor.RED + "Jarvis: Something went wrong during construction!");
            activeBuildTasks.remove(player.getUniqueId());
        }
    }

    public void cancelBuild(Player player) {
        BuildTask task = activeBuildTasks.get(player.getUniqueId());
        if (task != null) {
            task.cancelled = true;
            player.sendMessage(ChatColor.YELLOW + "Jarvis: Cancelling build...");
        } else {
            player.sendMessage(ChatColor.RED + "Jarvis: I'm not building anything right now.");
        }
    }

    public boolean isBuilding(UUID playerId) {
        return activeBuildTasks.containsKey(playerId);
    }

    private Material parseMaterial(String materialName) {
        // Remove minecraft: prefix if present
        materialName = materialName.replace("minecraft:", "").toUpperCase();
        
        try {
            return Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            // Try fallback
            String fallback = plugin.getConfig().getString("build.fallback-material", "minecraft:dirt");
            fallback = fallback.replace("minecraft:", "").toUpperCase();
            try {
                return Material.valueOf(fallback);
            } catch (IllegalArgumentException ex) {
                return Material.DIRT;
            }
        }
    }

    /**
     * Build a simple structure (fallback when AI fails)
     */
    public void buildSimpleStructure(Player player, String type) {
        Location loc = player.getLocation().clone();
        
        new BukkitRunnable() {
            @Override
            public void run() {
                switch (type.toLowerCase()) {
                    case "house" -> buildSimpleHouse(loc);
                    case "tower" -> buildSimpleTower(loc);
                    case "wall" -> buildSimpleWall(loc);
                    default -> player.sendMessage(ChatColor.RED + "Jarvis: I don't know how to build that!");
                }
                player.sendMessage(ChatColor.GREEN + "Jarvis: Done building a simple " + type + "!");
            }
        }.runTask(plugin);
    }

    private void buildSimpleHouse(Location start) {
        // 5x5 house with door
        Material wall = Material.OAK_PLANKS;
        Material floor = Material.STONE_BRICKS;
        Material roof = Material.OAK_STAIRS;
        
        // Floor
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                start.clone().add(x, -1, z).getBlock().setType(floor);
            }
        }
        
        // Walls
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 5; x++) {
                start.clone().add(x, y, 0).getBlock().setType(wall);
                start.clone().add(x, y, 4).getBlock().setType(wall);
            }
            for (int z = 1; z < 4; z++) {
                start.clone().add(0, y, z).getBlock().setType(wall);
                start.clone().add(4, y, z).getBlock().setType(wall);
            }
        }
        
        // Door
        start.clone().add(2, 0, 0).getBlock().setType(Material.AIR);
        start.clone().add(2, 1, 0).getBlock().setType(Material.AIR);
        
        // Simple roof
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                start.clone().add(x, 3, z).getBlock().setType(Material.OAK_SLAB);
            }
        }
    }

    private void buildSimpleTower(Location start) {
        for (int y = 0; y < 10; y++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && z == 0) continue; // Hollow center
                    start.clone().add(x, y, z).getBlock().setType(Material.STONE_BRICKS);
                }
            }
        }
    }

    private void buildSimpleWall(Location start) {
        for (int x = 0; x < 10; x++) {
            for (int y = 0; y < 4; y++) {
                start.clone().add(x, y, 0).getBlock().setType(Material.COBBLESTONE);
            }
        }
    }
}
