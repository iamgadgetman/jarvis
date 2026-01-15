package com.yourname.jarvis.schematics;

import com.yourname.jarvis.Jarvis;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.*;

/**
 * SchematicManager - Manages schematic files and placement
 *
 * Supports JSON-based schematics and integrates with BuildingAssistant
 * for block placement.
 */
public class SchematicManager {

    private final Jarvis plugin;
    private final File schematicFolder;
    private final Map<String, SchematicData> loadedSchematics = new HashMap<>();

    // Configuration
    private boolean allowDownloads = true;
    private int maxDownloadSize = 10; // MB

    public SchematicManager(Jarvis plugin) {
        this.plugin = plugin;
        this.schematicFolder = new File(plugin.getDataFolder(), "schematics");
        loadConfig();
        initializeFolder();
        scanFolder();
        plugin.getLogger().info("Schematic manager initialized with " + loadedSchematics.size() + " schematics");
    }

    private void loadConfig() {
        allowDownloads = plugin.getConfig().getBoolean("schematics.allow-downloads", true);
        maxDownloadSize = plugin.getConfig().getInt("schematics.max-download-size", 10);
    }

    private void initializeFolder() {
        if (!schematicFolder.exists()) {
            schematicFolder.mkdirs();

            // Create example schematic
            createExampleSchematic();
        }
    }

    private void createExampleSchematic() {
        try {
            JSONObject example = new JSONObject();
            example.put("name", "example_house");
            example.put("author", "Jarvis");
            example.put("description", "A simple 5x5 house");

            JSONObject dimensions = new JSONObject();
            dimensions.put("width", 5);
            dimensions.put("height", 4);
            dimensions.put("length", 5);
            example.put("dimensions", dimensions);

            JSONArray blocks = new JSONArray();

            // Floor
            for (int x = 0; x < 5; x++) {
                for (int z = 0; z < 5; z++) {
                    JSONObject block = new JSONObject();
                    block.put("x", x);
                    block.put("y", 0);
                    block.put("z", z);
                    block.put("material", "OAK_PLANKS");
                    blocks.put(block);
                }
            }

            // Walls
            for (int y = 1; y <= 3; y++) {
                for (int x = 0; x < 5; x++) {
                    for (int z = 0; z < 5; z++) {
                        if (x == 0 || x == 4 || z == 0 || z == 4) {
                            // Skip door space
                            if (x == 2 && z == 0 && y <= 2) continue;

                            JSONObject block = new JSONObject();
                            block.put("x", x);
                            block.put("y", y);
                            block.put("z", z);
                            block.put("material", "OAK_PLANKS");
                            blocks.put(block);
                        }
                    }
                }
            }

            example.put("blocks", blocks);

            File exampleFile = new File(schematicFolder, "example_house.json");
            Files.writeString(exampleFile.toPath(), example.toString(2));

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to create example schematic: " + e.getMessage());
        }
    }

    // ==================== DATA STRUCTURES ====================

    public static class SchematicData {
        public String name;
        public String author;
        public String description;
        public int width, height, length;
        public List<BlockEntry> blocks;
        public File sourceFile;

        public int getBlockCount() {
            return blocks != null ? blocks.size() : 0;
        }
    }

    public static class BlockEntry {
        public int x, y, z;
        public Material material;

        public BlockEntry(int x, int y, int z, Material material) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.material = material;
        }
    }

    // ==================== SCHEMATIC LOADING ====================

    /**
     * Scan the schematics folder for .json files
     */
    public void scanFolder() {
        loadedSchematics.clear();

        File[] files = schematicFolder.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return;

        for (File file : files) {
            try {
                SchematicData data = loadSchematicFile(file);
                if (data != null) {
                    loadedSchematics.put(data.name.toLowerCase(), data);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load schematic " + file.getName() + ": " + e.getMessage());
            }
        }
    }

    private SchematicData loadSchematicFile(File file) throws Exception {
        String content = Files.readString(file.toPath());
        JSONObject json = new JSONObject(content);

        SchematicData data = new SchematicData();
        data.sourceFile = file;
        data.name = json.optString("name", file.getName().replace(".json", ""));
        data.author = json.optString("author", "Unknown");
        data.description = json.optString("description", "No description");

        JSONObject dimensions = json.optJSONObject("dimensions");
        if (dimensions != null) {
            data.width = dimensions.optInt("width", 1);
            data.height = dimensions.optInt("height", 1);
            data.length = dimensions.optInt("length", 1);
        }

        data.blocks = new ArrayList<>();
        JSONArray blocksArray = json.optJSONArray("blocks");
        if (blocksArray != null) {
            for (int i = 0; i < blocksArray.length(); i++) {
                JSONObject block = blocksArray.getJSONObject(i);
                int x = block.getInt("x");
                int y = block.getInt("y");
                int z = block.getInt("z");
                String materialStr = block.optString("material", "STONE").toUpperCase();

                Material material;
                try {
                    material = Material.valueOf(materialStr);
                } catch (IllegalArgumentException e) {
                    material = Material.STONE;
                }

                data.blocks.add(new BlockEntry(x, y, z, material));
            }
        }

        return data;
    }

    // ==================== SCHEMATIC COMMANDS ====================

    /**
     * List available schematics
     */
    public void listSchematics(Player player) {
        if (loadedSchematics.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "No schematics available.");
            player.sendMessage(ChatColor.GRAY + "Add .json schematics to: " + schematicFolder.getPath());
            return;
        }

        player.sendMessage("");
        player.sendMessage(ChatColor.GREEN + "======== Available Schematics ========");

        for (SchematicData data : loadedSchematics.values()) {
            player.sendMessage(ChatColor.GOLD + "  " + data.name);
            player.sendMessage(ChatColor.GRAY + "    " + data.description);
            player.sendMessage(ChatColor.WHITE + "    Size: " + data.width + "x" + data.height + "x" + data.length +
                " (" + data.getBlockCount() + " blocks)");
        }

        player.sendMessage("");
        player.sendMessage(ChatColor.GRAY + "Use: /jarvis schematic load <name>");
        player.sendMessage(ChatColor.GREEN + "====================================");
    }

    /**
     * Load and place a schematic
     */
    public void loadSchematic(Player player, String name) {
        SchematicData data = loadedSchematics.get(name.toLowerCase());

        if (data == null) {
            player.sendMessage(ChatColor.RED + "Schematic not found: " + name);
            player.sendMessage(ChatColor.GRAY + "Use /jarvis schematic list to see available schematics");
            return;
        }

        // Check if NPC is summoned
        if (plugin.getJarvisNPC().getNPCForPlayer(player.getUniqueId()) == null) {
            player.sendMessage(ChatColor.RED + "Summon Jarvis first with /jarvis summon");
            return;
        }

        player.sendMessage(ChatColor.GREEN + "Loading schematic: " + ChatColor.YELLOW + data.name);
        player.sendMessage(ChatColor.GRAY + "Placing " + data.getBlockCount() + " blocks...");

        // Place blocks at player's location
        Location origin = player.getLocation().add(2, 0, 2); // Offset from player

        // Use BuildingAssistant to place blocks
        placeSchematic(player, data, origin);
    }

    private void placeSchematic(Player player, SchematicData data, Location origin) {
        // Sort blocks by Y (bottom to top)
        List<BlockEntry> sortedBlocks = new ArrayList<>(data.blocks);
        sortedBlocks.sort(Comparator.comparingInt(b -> b.y));

        int blocksPerTick = plugin.getConfig().getInt("build.blocks-per-tick", 50);
        final int[] placed = {0};
        final int total = sortedBlocks.size();

        new BukkitRunnable() {
            int index = 0;

            @Override
            public void run() {
                if (!player.isOnline() || plugin.getJarvisNPC().getNPCForPlayer(player.getUniqueId()) == null) {
                    cancel();
                    return;
                }

                int thisTickPlaced = 0;
                while (index < sortedBlocks.size() && thisTickPlaced < blocksPerTick) {
                    BlockEntry entry = sortedBlocks.get(index);
                    Location loc = origin.clone().add(entry.x, entry.y, entry.z);

                    loc.getBlock().setType(entry.material);
                    placed[0]++;
                    thisTickPlaced++;
                    index++;
                }

                // Progress update every 100 blocks
                if (placed[0] % 100 == 0 && placed[0] > 0) {
                    int percent = (placed[0] * 100) / total;
                    player.sendMessage(ChatColor.YELLOW + "Schematic progress: " + percent + "%");
                }

                if (index >= sortedBlocks.size()) {
                    player.sendMessage(ChatColor.GREEN + "Schematic " + data.name + " placed successfully!");
                    player.sendMessage(ChatColor.GRAY + "Placed " + placed[0] + " blocks.");
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * Build using AI description (delegates to BuildingAssistant)
     */
    public void buildWithAI(Player player, String description) {
        plugin.getBuildingAssistant().startBuild(player, description);
    }

    /**
     * Download a schematic from URL
     */
    public void downloadSchematic(Player player, String urlString, String name) {
        if (!allowDownloads) {
            player.sendMessage(ChatColor.RED + "Schematic downloads are disabled.");
            return;
        }

        player.sendMessage(ChatColor.YELLOW + "Downloading schematic...");

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL(urlString);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);

                    // Check size
                    int contentLength = conn.getContentLength();
                    if (contentLength > maxDownloadSize * 1024 * 1024) {
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                player.sendMessage(ChatColor.RED + "Schematic too large (max " + maxDownloadSize + "MB)");
                            }
                        }.runTask(plugin);
                        return;
                    }

                    // Download
                    InputStream in = conn.getInputStream();
                    String finalName = name.endsWith(".json") ? name : name + ".json";
                    File outputFile = new File(schematicFolder, finalName);

                    FileOutputStream out = new FileOutputStream(outputFile);
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                    out.close();
                    in.close();

                    // Reload schematics
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            scanFolder();
                            player.sendMessage(ChatColor.GREEN + "Downloaded schematic: " + finalName);
                        }
                    }.runTask(plugin);

                } catch (Exception e) {
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            player.sendMessage(ChatColor.RED + "Download failed: " + e.getMessage());
                        }
                    }.runTask(plugin);
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * Save current selection as schematic (placeholder for future WorldEdit integration)
     */
    public void saveSchematic(Player player, String name) {
        player.sendMessage(ChatColor.YELLOW + "Schematic saving requires WorldEdit selection.");
        player.sendMessage(ChatColor.GRAY + "This feature will be expanded in a future update.");
    }

    // ==================== GETTERS ====================

    public File getSchematicFolder() {
        return schematicFolder;
    }

    public Collection<SchematicData> getSchematics() {
        return loadedSchematics.values();
    }

    public SchematicData getSchematic(String name) {
        return loadedSchematics.get(name.toLowerCase());
    }

    public int getSchematicCount() {
        return loadedSchematics.size();
    }
}
