package com.gadgetman.jarvis.schematics;

import com.gadgetman.jarvis.Jarvis;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * LitematicConverter - Converts .litematic files to WorldEdit .schem format
 *
 * Litematica is a popular Minecraft mod for creating schematics.
 * This converter allows Jarvis to use .litematic files by converting them
 * to the WorldEdit .schem (Sponge Schematic) format.
 */
public class LitematicConverter {

    private final Jarvis plugin;
    private final File schematicFolder;

    public LitematicConverter(Jarvis plugin, File schematicFolder) {
        this.plugin = plugin;
        this.schematicFolder = schematicFolder;
    }

    /**
     * Convert a .litematic file to .schem format
     */
    public void convert(Player player, String litematicName) {
        // Find the litematic file
        File litematicFile = findLitematicFile(litematicName);

        if (litematicFile == null) {
            player.sendMessage(ChatColor.RED + "Litematic file not found: " + litematicName);
            player.sendMessage(ChatColor.GRAY + "Place .litematic files in: " + schematicFolder.getPath());
            return;
        }

        String outputName = litematicName.replace(".litematic", "").replace(".LITEMATIC", "") + ".schem";
        File outputFile = new File(schematicFolder, outputName);

        player.sendMessage(ChatColor.YELLOW + "Converting: " + litematicFile.getName() + " -> " + outputName);

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    boolean success = convertLitematicToSchem(litematicFile, outputFile);

                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            if (success) {
                                player.sendMessage(ChatColor.GREEN + "Conversion complete: " + outputName);
                                player.sendMessage(ChatColor.GRAY + "Use /jarvis schematic paste " +
                                    outputName.replace(".schem", ""));
                                // Refresh schematic list
                                plugin.getSchematicManager().scanFolder();
                            } else {
                                player.sendMessage(ChatColor.RED + "Conversion failed. Check console for details.");
                            }
                        }
                    }.runTask(plugin);

                } catch (Exception e) {
                    plugin.getLogger().warning("Litematic conversion failed: " + e.getMessage());
                    e.printStackTrace();

                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            player.sendMessage(ChatColor.RED + "Conversion error: " + e.getMessage());
                        }
                    }.runTask(plugin);
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * List available .litematic files
     */
    public List<String> listLitematicFiles() {
        List<String> files = new ArrayList<>();
        File[] allFiles = schematicFolder.listFiles();
        if (allFiles == null) return files;

        for (File file : allFiles) {
            if (file.getName().toLowerCase().endsWith(".litematic")) {
                files.add(file.getName());
            }
        }
        return files;
    }

    /**
     * Convert all .litematic files in the folder
     */
    public void convertAll(Player player) {
        List<String> litematicFiles = listLitematicFiles();

        if (litematicFiles.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "No .litematic files found to convert.");
            return;
        }

        player.sendMessage(ChatColor.GOLD + "Converting " + litematicFiles.size() + " litematic files...");

        new BukkitRunnable() {
            int converted = 0;
            int failed = 0;

            @Override
            public void run() {
                for (String fileName : litematicFiles) {
                    try {
                        File litematicFile = new File(schematicFolder, fileName);
                        String outputName = fileName.replace(".litematic", "").replace(".LITEMATIC", "") + ".schem";
                        File outputFile = new File(schematicFolder, outputName);

                        // Skip if already converted
                        if (outputFile.exists()) {
                            plugin.getLogger().info("Skipping " + fileName + " (already converted)");
                            continue;
                        }

                        if (convertLitematicToSchem(litematicFile, outputFile)) {
                            converted++;
                            plugin.getLogger().info("Converted: " + fileName);
                        } else {
                            failed++;
                        }
                    } catch (Exception e) {
                        failed++;
                        plugin.getLogger().warning("Failed to convert " + fileName + ": " + e.getMessage());
                    }
                }

                final int finalConverted = converted;
                final int finalFailed = failed;

                new BukkitRunnable() {
                    @Override
                    public void run() {
                        player.sendMessage(ChatColor.GREEN + "Conversion complete!");
                        player.sendMessage(ChatColor.WHITE + "  Converted: " + finalConverted);
                        if (finalFailed > 0) {
                            player.sendMessage(ChatColor.RED + "  Failed: " + finalFailed);
                        }
                        plugin.getSchematicManager().scanFolder();
                    }
                }.runTask(plugin);
            }
        }.runTaskAsynchronously(plugin);
    }

    private File findLitematicFile(String name) {
        // Try exact name first
        File exact = new File(schematicFolder, name);
        if (exact.exists()) return exact;

        // Try with extension
        File withExt = new File(schematicFolder, name + ".litematic");
        if (withExt.exists()) return withExt;

        // Case-insensitive search
        File[] files = schematicFolder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().equalsIgnoreCase(name) ||
                    file.getName().equalsIgnoreCase(name + ".litematic")) {
                    return file;
                }
            }
        }

        return null;
    }

    /**
     * Convert litematic NBT to Sponge Schematic format
     *
     * Litematic format: NBT with regions containing palette and block array
     * Sponge Schematic v2: NBT with Palette and BlockData
     */
    private boolean convertLitematicToSchem(File input, File output) throws Exception {
        // Read litematic NBT
        Map<String, Object> litematicNbt;
        try (GZIPInputStream gzis = new GZIPInputStream(new FileInputStream(input))) {
            litematicNbt = readNBT(new DataInputStream(gzis));
        }

        if (litematicNbt == null || litematicNbt.isEmpty()) {
            plugin.getLogger().warning("Failed to read litematic NBT");
            return false;
        }

        // Extract regions from litematic
        @SuppressWarnings("unchecked")
        Map<String, Object> regions = (Map<String, Object>) litematicNbt.get("Regions");
        if (regions == null || regions.isEmpty()) {
            plugin.getLogger().warning("No regions found in litematic");
            return false;
        }

        // Get metadata for dimensions
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) litematicNbt.get("Metadata");

        int totalWidth = 0, totalHeight = 0, totalLength = 0;

        if (metadata != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> enclosingSize = (Map<String, Object>) metadata.get("EnclosingSize");
            if (enclosingSize != null) {
                totalWidth = ((Number) enclosingSize.getOrDefault("x", 1)).intValue();
                totalHeight = ((Number) enclosingSize.getOrDefault("y", 1)).intValue();
                totalLength = ((Number) enclosingSize.getOrDefault("z", 1)).intValue();
            }
        }

        // Process first region (most litematics have one region)
        Map.Entry<String, Object> firstRegion = regions.entrySet().iterator().next();
        @SuppressWarnings("unchecked")
        Map<String, Object> regionData = (Map<String, Object>) firstRegion.getValue();

        // Get region size
        @SuppressWarnings("unchecked")
        Map<String, Object> size = (Map<String, Object>) regionData.get("Size");
        if (size != null) {
            totalWidth = Math.abs(((Number) size.getOrDefault("x", totalWidth)).intValue());
            totalHeight = Math.abs(((Number) size.getOrDefault("y", totalHeight)).intValue());
            totalLength = Math.abs(((Number) size.getOrDefault("z", totalLength)).intValue());
        }

        if (totalWidth == 0) totalWidth = 1;
        if (totalHeight == 0) totalHeight = 1;
        if (totalLength == 0) totalLength = 1;

        // Get block state palette from litematic
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> litematicPalette = (List<Map<String, Object>>) regionData.get("BlockStatePalette");
        if (litematicPalette == null) {
            plugin.getLogger().warning("No BlockStatePalette found");
            return false;
        }

        // Convert palette to Sponge format
        Map<String, Integer> schemPalette = new LinkedHashMap<>();
        List<String> paletteList = new ArrayList<>();

        for (Map<String, Object> state : litematicPalette) {
            String blockName = (String) state.get("Name");
            if (blockName == null) blockName = "minecraft:air";

            // Build block state string with properties
            StringBuilder stateStr = new StringBuilder(blockName);
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) state.get("Properties");
            if (properties != null && !properties.isEmpty()) {
                stateStr.append("[");
                boolean first = true;
                for (Map.Entry<String, Object> prop : properties.entrySet()) {
                    if (!first) stateStr.append(",");
                    stateStr.append(prop.getKey()).append("=").append(prop.getValue());
                    first = false;
                }
                stateStr.append("]");
            }

            String fullState = stateStr.toString();
            if (!schemPalette.containsKey(fullState)) {
                schemPalette.put(fullState, paletteList.size());
                paletteList.add(fullState);
            }
        }

        // Get block data from litematic
        long[] blockStates = (long[]) regionData.get("BlockStates");
        if (blockStates == null) {
            plugin.getLogger().warning("No BlockStates found");
            return false;
        }

        // Calculate bits per block
        int paletteSize = litematicPalette.size();
        int bitsPerBlock = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(paletteSize - 1));

        // Decode block data
        int totalBlocks = totalWidth * totalHeight * totalLength;
        int[] decodedBlocks = new int[totalBlocks];

        long mask = (1L << bitsPerBlock) - 1;
        int blocksPerLong = 64 / bitsPerBlock;

        for (int i = 0; i < totalBlocks; i++) {
            int longIndex = i / blocksPerLong;
            int bitOffset = (i % blocksPerLong) * bitsPerBlock;

            if (longIndex < blockStates.length) {
                int paletteIndex = (int) ((blockStates[longIndex] >> bitOffset) & mask);
                decodedBlocks[i] = Math.min(paletteIndex, paletteSize - 1);
            }
        }

        // Convert to Sponge schematic block data (varint encoded)
        ByteArrayOutputStream blockDataStream = new ByteArrayOutputStream();
        for (int y = 0; y < totalHeight; y++) {
            for (int z = 0; z < totalLength; z++) {
                for (int x = 0; x < totalWidth; x++) {
                    int index = (y * totalLength + z) * totalWidth + x;
                    int paletteIndex = index < decodedBlocks.length ? decodedBlocks[index] : 0;
                    writeVarInt(blockDataStream, paletteIndex);
                }
            }
        }

        // Build Sponge Schematic NBT
        Map<String, Object> schemNbt = new LinkedHashMap<>();
        schemNbt.put("Version", 2);
        schemNbt.put("DataVersion", 3465); // 1.20.4 data version

        schemNbt.put("Width", (short) totalWidth);
        schemNbt.put("Height", (short) totalHeight);
        schemNbt.put("Length", (short) totalLength);

        // Palette as compound
        Map<String, Object> paletteCompound = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : schemPalette.entrySet()) {
            paletteCompound.put(entry.getKey(), entry.getValue());
        }
        schemNbt.put("Palette", paletteCompound);
        schemNbt.put("PaletteMax", paletteList.size());

        schemNbt.put("BlockData", blockDataStream.toByteArray());

        // Metadata
        Map<String, Object> schemMetadata = new LinkedHashMap<>();
        schemMetadata.put("WEOffsetX", 0);
        schemMetadata.put("WEOffsetY", 0);
        schemMetadata.put("WEOffsetZ", 0);
        schemNbt.put("Metadata", schemMetadata);

        // Write Sponge schematic
        try (GZIPOutputStream gzos = new GZIPOutputStream(new FileOutputStream(output))) {
            DataOutputStream dos = new DataOutputStream(gzos);
            writeNBT(dos, "Schematic", schemNbt);
        }

        plugin.getLogger().info("Converted " + input.getName() + " -> " + output.getName() +
            " (" + totalWidth + "x" + totalHeight + "x" + totalLength + ", " + paletteList.size() + " block types)");

        return true;
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
            case 7: { // Byte array
                int length = dis.readInt();
                byte[] arr = new byte[length];
                dis.readFully(arr);
                return arr;
            }
            case 8: return dis.readUTF();
            case 9: { // List
                byte listType = dis.readByte();
                int length = dis.readInt();
                List<Object> list = new ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    list.add(readTag(dis, listType));
                }
                return list;
            }
            case 10: return readCompound(dis);
            case 11: { // Int array
                int length = dis.readInt();
                int[] arr = new int[length];
                for (int i = 0; i < length; i++) {
                    arr[i] = dis.readInt();
                }
                return arr;
            }
            case 12: { // Long array
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

    // ==================== NBT Writing ====================

    private void writeNBT(DataOutputStream dos, String name, Map<String, Object> compound) throws IOException {
        dos.writeByte(10); // TAG_Compound
        dos.writeUTF(name);
        writeCompound(dos, compound);
    }

    private void writeCompound(DataOutputStream dos, Map<String, Object> compound) throws IOException {
        for (Map.Entry<String, Object> entry : compound.entrySet()) {
            writeTag(dos, entry.getKey(), entry.getValue());
        }
        dos.writeByte(0); // TAG_End
    }

    private void writeTag(DataOutputStream dos, String name, Object value) throws IOException {
        byte tagType = getTagType(value);
        dos.writeByte(tagType);
        dos.writeUTF(name);
        writeTagValue(dos, value, tagType);
    }

    private void writeTagValue(DataOutputStream dos, Object value, byte tagType) throws IOException {
        switch (tagType) {
            case 1: dos.writeByte((Byte) value); break;
            case 2: dos.writeShort((Short) value); break;
            case 3: dos.writeInt((Integer) value); break;
            case 4: dos.writeLong((Long) value); break;
            case 5: dos.writeFloat((Float) value); break;
            case 6: dos.writeDouble((Double) value); break;
            case 7: {
                byte[] arr = (byte[]) value;
                dos.writeInt(arr.length);
                dos.write(arr);
                break;
            }
            case 8: dos.writeUTF((String) value); break;
            case 9: {
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) value;
                byte listType = list.isEmpty() ? 0 : getTagType(list.get(0));
                dos.writeByte(listType);
                dos.writeInt(list.size());
                for (Object item : list) {
                    writeTagValue(dos, item, listType);
                }
                break;
            }
            case 10: {
                @SuppressWarnings("unchecked")
                Map<String, Object> compound = (Map<String, Object>) value;
                writeCompound(dos, compound);
                break;
            }
            case 11: {
                int[] arr = (int[]) value;
                dos.writeInt(arr.length);
                for (int i : arr) dos.writeInt(i);
                break;
            }
            case 12: {
                long[] arr = (long[]) value;
                dos.writeInt(arr.length);
                for (long l : arr) dos.writeLong(l);
                break;
            }
        }
    }

    private byte getTagType(Object value) {
        if (value instanceof Byte) return 1;
        if (value instanceof Short) return 2;
        if (value instanceof Integer) return 3;
        if (value instanceof Long) return 4;
        if (value instanceof Float) return 5;
        if (value instanceof Double) return 6;
        if (value instanceof byte[]) return 7;
        if (value instanceof String) return 8;
        if (value instanceof List) return 9;
        if (value instanceof Map) return 10;
        if (value instanceof int[]) return 11;
        if (value instanceof long[]) return 12;
        return 0;
    }

    // ==================== VarInt ====================

    private void writeVarInt(OutputStream os, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            os.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        os.write(value);
    }
}
