package com.gadgetman.jarvis.schematics;

import com.gadgetman.jarvis.Jarvis;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * SchemReader - Native .schem file reader for Jarvis
 *
 * Reads Sponge Schematic v2/v3 format (.schem files) without requiring WorldEdit.
 * Places blocks directly using Bukkit API.
 */
public class SchemReader {

    private final Jarvis plugin;

    public SchemReader(Jarvis plugin) {
        this.plugin = plugin;
    }

    /**
     * Load and paste a .schem file at the player's location
     */
    public void pasteSchematic(Player player, File schematicFile) {
        player.sendMessage(ChatColor.YELLOW + "Jarvis: Loading schematic: " + schematicFile.getName());

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    SchematicData data = readSchematic(schematicFile);

                    if (data == null) {
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                player.sendMessage(ChatColor.RED + "Failed to read schematic file.");
                            }
                        }.runTask(plugin);
                        return;
                    }

                    // Place on main thread
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            placeSchematic(player, data);
                        }
                    }.runTask(plugin);

                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to load schematic: " + e.getMessage());
                    e.printStackTrace();

                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            player.sendMessage(ChatColor.RED + "Error loading schematic: " + e.getMessage());
                        }
                    }.runTask(plugin);
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * Read a .schem file and return schematic data
     */
    public SchematicData readSchematic(File file) throws Exception {
        Map<String, Object> nbt;
        try (GZIPInputStream gzis = new GZIPInputStream(new FileInputStream(file))) {
            nbt = readNBT(new DataInputStream(gzis));
        }

        if (nbt == null) {
            throw new Exception("Failed to read NBT data");
        }

        SchematicData data = new SchematicData();
        data.fileName = file.getName();

        // Get dimensions
        data.width = ((Number) nbt.getOrDefault("Width", (short) 0)).intValue();
        data.height = ((Number) nbt.getOrDefault("Height", (short) 0)).intValue();
        data.length = ((Number) nbt.getOrDefault("Length", (short) 0)).intValue();

        if (data.width == 0 || data.height == 0 || data.length == 0) {
            throw new Exception("Invalid schematic dimensions");
        }

        // Get palette
        @SuppressWarnings("unchecked")
        Map<String, Object> paletteNbt = (Map<String, Object>) nbt.get("Palette");
        if (paletteNbt == null) {
            throw new Exception("No palette found in schematic");
        }

        // Build palette array (index -> block state string)
        int paletteMax = ((Number) nbt.getOrDefault("PaletteMax", paletteNbt.size())).intValue();
        data.palette = new String[paletteMax];

        for (Map.Entry<String, Object> entry : paletteNbt.entrySet()) {
            int index = ((Number) entry.getValue()).intValue();
            if (index >= 0 && index < paletteMax) {
                data.palette[index] = entry.getKey();
            }
        }

        // Get block data (varint encoded)
        byte[] blockDataRaw = (byte[]) nbt.get("BlockData");
        if (blockDataRaw == null) {
            throw new Exception("No BlockData found in schematic");
        }

        // Decode varint block data
        data.blocks = decodeVarIntArray(blockDataRaw, data.width * data.height * data.length);

        // Get offset if present
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) nbt.get("Metadata");
        if (metadata != null) {
            data.offsetX = ((Number) metadata.getOrDefault("WEOffsetX", 0)).intValue();
            data.offsetY = ((Number) metadata.getOrDefault("WEOffsetY", 0)).intValue();
            data.offsetZ = ((Number) metadata.getOrDefault("WEOffsetZ", 0)).intValue();
        }

        plugin.getLogger().info("Loaded schematic: " + data.width + "x" + data.height + "x" + data.length +
            " (" + paletteMax + " block types)");

        return data;
    }

    /**
     * Place schematic blocks in the world
     */
    private void placeSchematic(Player player, SchematicData data) {
        Location origin = player.getLocation();
        int blocksPerTick = plugin.getConfig().getInt("build.blocks-per-tick", 50);

        player.sendMessage(ChatColor.GREEN + "Placing " + (data.width * data.height * data.length) +
            " blocks (" + data.width + "x" + data.height + "x" + data.length + ")...");

        final int[] placed = {0};
        final int[] index = {0};
        final int total = data.width * data.height * data.length;
        final long startTime = System.currentTimeMillis();

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }

                int thisTickPlaced = 0;

                while (index[0] < total && thisTickPlaced < blocksPerTick) {
                    // Calculate position (Y, Z, X order in sponge schematic)
                    int y = index[0] / (data.width * data.length);
                    int remainder = index[0] % (data.width * data.length);
                    int z = remainder / data.width;
                    int x = remainder % data.width;

                    int paletteIndex = data.blocks[index[0]];

                    if (paletteIndex >= 0 && paletteIndex < data.palette.length) {
                        String blockState = data.palette[paletteIndex];

                        if (blockState != null && !blockState.equals("minecraft:air") && !blockState.equals("air")) {
                            Location loc = origin.clone().add(
                                x + data.offsetX,
                                y + data.offsetY,
                                z + data.offsetZ
                            );

                            try {
                                Block block = loc.getBlock();
                                BlockData blockData = parseBlockState(blockState);
                                if (blockData != null) {
                                    block.setBlockData(blockData, false);
                                    placed[0]++;
                                }
                            } catch (Exception e) {
                                // Skip invalid blocks
                            }
                        }
                    }

                    index[0]++;
                    thisTickPlaced++;
                }

                // Progress update every 500 blocks
                if (placed[0] > 0 && placed[0] % 500 == 0) {
                    int percent = (index[0] * 100) / total;
                    player.sendMessage(ChatColor.YELLOW + "Progress: " + percent + "% (" + placed[0] + " blocks placed)");
                }

                // Check completion
                if (index[0] >= total) {
                    long duration = (System.currentTimeMillis() - startTime) / 1000;
                    player.sendMessage("");
                    player.sendMessage(ChatColor.GREEN + "========================================");
                    player.sendMessage(ChatColor.GOLD + "  Schematic Complete!");
                    player.sendMessage(ChatColor.GREEN + "========================================");
                    player.sendMessage(ChatColor.WHITE + "  File: " + data.fileName);
                    player.sendMessage(ChatColor.WHITE + "  Blocks placed: " + placed[0]);
                    player.sendMessage(ChatColor.WHITE + "  Time: " + duration + " seconds");
                    player.sendMessage(ChatColor.GREEN + "========================================");
                    player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * Parse a block state string into BlockData
     */
    private BlockData parseBlockState(String blockState) {
        try {
            // Handle minecraft: prefix
            if (!blockState.contains(":")) {
                blockState = "minecraft:" + blockState;
            }

            // Try to parse directly with Bukkit
            return Bukkit.createBlockData(blockState);
        } catch (Exception e) {
            // Try without properties
            try {
                String baseName = blockState.split("\\[")[0];
                return Bukkit.createBlockData(baseName);
            } catch (Exception e2) {
                // Try to get material directly
                try {
                    String materialName = blockState.replace("minecraft:", "")
                        .split("\\[")[0]
                        .toUpperCase();
                    Material mat = Material.getMaterial(materialName);
                    if (mat != null && mat.isBlock()) {
                        return mat.createBlockData();
                    }
                } catch (Exception e3) {
                    // Give up
                }
            }
        }
        return null;
    }

    /**
     * Decode varint encoded block data
     */
    private int[] decodeVarIntArray(byte[] data, int expectedSize) {
        int[] result = new int[expectedSize];
        int dataIndex = 0;
        int resultIndex = 0;

        while (dataIndex < data.length && resultIndex < expectedSize) {
            int value = 0;
            int shift = 0;

            while (true) {
                if (dataIndex >= data.length) break;
                byte b = data[dataIndex++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }

            result[resultIndex++] = value;
        }

        return result;
    }

    // ==================== NBT Reading ====================

    private Map<String, Object> readNBT(DataInputStream dis) throws IOException {
        byte tagType = dis.readByte();
        if (tagType != 10) { // TAG_Compound
            throw new IOException("Expected TAG_Compound, got " + tagType);
        }
        dis.readUTF(); // Root name
        return readCompound(dis);
    }

    private Map<String, Object> readCompound(DataInputStream dis) throws IOException {
        Map<String, Object> compound = new LinkedHashMap<>();
        byte tagType;
        while ((tagType = dis.readByte()) != 0) { // TAG_End
            String name = dis.readUTF();
            Object value = readTag(dis, tagType);
            compound.put(name, value);
        }
        return compound;
    }

    private Object readTag(DataInputStream dis, byte tagType) throws IOException {
        switch (tagType) {
            case 1: return dis.readByte();
            case 2: return dis.readShort();
            case 3: return dis.readInt();
            case 4: return dis.readLong();
            case 5: return dis.readFloat();
            case 6: return dis.readDouble();
            case 7: {
                int length = dis.readInt();
                byte[] arr = new byte[length];
                dis.readFully(arr);
                return arr;
            }
            case 8: return dis.readUTF();
            case 9: {
                byte listType = dis.readByte();
                int length = dis.readInt();
                List<Object> list = new ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    list.add(readTag(dis, listType));
                }
                return list;
            }
            case 10: return readCompound(dis);
            case 11: {
                int length = dis.readInt();
                int[] arr = new int[length];
                for (int i = 0; i < length; i++) {
                    arr[i] = dis.readInt();
                }
                return arr;
            }
            case 12: {
                int length = dis.readInt();
                long[] arr = new long[length];
                for (int i = 0; i < length; i++) {
                    arr[i] = dis.readLong();
                }
                return arr;
            }
            default:
                throw new IOException("Unknown tag type: " + tagType);
        }
    }

    // ==================== Data Structure ====================

    public static class SchematicData {
        public String fileName;
        public int width, height, length;
        public String[] palette;
        public int[] blocks;
        public int offsetX, offsetY, offsetZ;
    }
}
