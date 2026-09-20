package com.gadgetman.jarvis.schematics;

import com.gadgetman.jarvis.core.platform.Log;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Scheduler;
import com.gadgetman.jarvis.core.text.Colors;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Converts .litematic files to the WorldEdit .schem (Sponge Schematic) format.
 *
 * <p>Litematica is a popular Minecraft mod for creating schematics. This lets
 * Jarvis use its files by converting them. The conversion is pure Java and
 * runs off the server thread; only the messages come back on it.
 */
public class LitematicConverter {

    private final Log log;
    private final Scheduler scheduler;
    private final Path schematicFolder;
    private final Runnable onConverted;

    /**
     * @param onConverted run on the server thread after a successful conversion,
     *                    so the library can rescan
     */
    public LitematicConverter(Log log, Scheduler scheduler, Path schematicFolder, Runnable onConverted) {
        this.log = log;
        this.scheduler = scheduler;
        this.schematicFolder = schematicFolder;
        this.onConverted = onConverted;
    }

    /** Convert a .litematic file to .schem format. */
    public void convert(Owner player, String litematicName) {
        // Find the litematic file
        Path litematicFile = findLitematicFile(litematicName);

        if (litematicFile == null) {
            player.message(Colors.RED + "Litematic file not found: " + litematicName);
            player.message(Colors.GRAY + "Place .litematic files in: " + schematicFolder);
            return;
        }

        String outputName = litematicName.replace(".litematic", "").replace(".LITEMATIC", "") + ".schem";
        Path outputFile = schematicFolder.resolve(outputName);

        player.message(Colors.YELLOW + "Converting: " + litematicFile.getFileName() + " -> " + outputName);

        scheduler.async(() -> {
            try {
                boolean success = convertLitematicToSchem(litematicFile, outputFile);

                scheduler.sync(() -> {
                    if (success) {
                        player.message(Colors.GREEN + "Conversion complete: " + outputName);
                        player.message(Colors.GRAY + "Use /jarvis schematic paste " + outputName.replace(".schem", ""));
                        onConverted.run();
                    } else {
                        player.message(Colors.RED + "Conversion failed. Check console for details.");
                    }
                });

            } catch (Exception e) {
                log.error("Litematic conversion failed: " + e.getMessage(), e);
                scheduler.sync(() -> player.message(Colors.RED + "Conversion error: " + e.getMessage()));
            }
        });
    }

    /** List available .litematic files. */
    public List<String> listLitematicFiles() {
        List<String> files = new ArrayList<>();
        if (!Files.isDirectory(schematicFolder)) return files;
        try (Stream<Path> all = Files.list(schematicFolder)) {
            all.map(p -> p.getFileName().toString())
               .filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".litematic"))
               .sorted()
               .forEach(files::add);
        } catch (IOException e) {
            log.warn("Could not list schematics folder: " + e.getMessage());
        }
        return files;
    }

    /** Convert all .litematic files in the folder. */
    public void convertAll(Owner player) {
        List<String> litematicFiles = listLitematicFiles();

        if (litematicFiles.isEmpty()) {
            player.message(Colors.YELLOW + "No .litematic files found to convert.");
            return;
        }

        player.message(Colors.GOLD + "Converting " + litematicFiles.size() + " litematic files...");

        scheduler.async(() -> {
            int converted = 0;
            int failed = 0;

            for (String fileName : litematicFiles) {
                try {
                    Path litematicFile = schematicFolder.resolve(fileName);
                    String outputName = fileName.replace(".litematic", "").replace(".LITEMATIC", "") + ".schem";
                    Path outputFile = schematicFolder.resolve(outputName);

                    // Skip if already converted
                    if (Files.exists(outputFile)) {
                        log.info("Skipping " + fileName + " (already converted)");
                        continue;
                    }

                    if (convertLitematicToSchem(litematicFile, outputFile)) {
                        converted++;
                        log.info("Converted: " + fileName);
                    } else {
                        failed++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.warn("Failed to convert " + fileName + ": " + e.getMessage());
                }
            }

            final int finalConverted = converted;
            final int finalFailed = failed;

            scheduler.sync(() -> {
                player.message(Colors.GREEN + "Conversion complete!");
                player.message(Colors.WHITE + "  Converted: " + finalConverted);
                if (finalFailed > 0) {
                    player.message(Colors.RED + "  Failed: " + finalFailed);
                }
                onConverted.run();
            });
        });
    }

    private Path findLitematicFile(String name) {
        // Try exact name first
        Path exact = schematicFolder.resolve(name);
        if (Files.exists(exact)) return exact;

        // Try with extension
        Path withExt = schematicFolder.resolve(name + ".litematic");
        if (Files.exists(withExt)) return withExt;

        // Case-insensitive search
        for (String file : listLitematicFiles()) {
            if (file.equalsIgnoreCase(name) || file.equalsIgnoreCase(name + ".litematic")) {
                return schematicFolder.resolve(file);
            }
        }

        return null;
    }

    /**
     * Convert litematic NBT to Sponge Schematic format.
     *
     * Litematic format: NBT with regions containing palette and block array.
     * Sponge Schematic v2: NBT with Palette and BlockData.
     */
    boolean convertLitematicToSchem(Path input, Path output) throws Exception {
        // Read litematic NBT
        Map<String, Object> litematicNbt;
        try (GZIPInputStream gzis = new GZIPInputStream(Files.newInputStream(input))) {
            litematicNbt = Nbt.read(new DataInputStream(gzis));
        }

        if (litematicNbt == null || litematicNbt.isEmpty()) {
            log.warn("Failed to read litematic NBT");
            return false;
        }

        // Extract regions from litematic
        @SuppressWarnings("unchecked")
        Map<String, Object> regions = (Map<String, Object>) litematicNbt.get("Regions");
        if (regions == null || regions.isEmpty()) {
            log.warn("No regions found in litematic");
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
            log.warn("No BlockStatePalette found");
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
            log.warn("No BlockStates found");
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
                    Nbt.writeVarInt(blockDataStream, paletteIndex);
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
        Map<String, Object> paletteCompound = new LinkedHashMap<>(schemPalette);
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
        try (GZIPOutputStream gzos = new GZIPOutputStream(Files.newOutputStream(output))) {
            DataOutputStream dos = new DataOutputStream(gzos);
            Nbt.write(dos, "Schematic", schemNbt);
            dos.flush();
        }

        log.info("Converted " + input.getFileName() + " -> " + output.getFileName()
                + " (" + totalWidth + "x" + totalHeight + "x" + totalLength + ", " + paletteList.size() + " block types)");

        return true;
    }
}
