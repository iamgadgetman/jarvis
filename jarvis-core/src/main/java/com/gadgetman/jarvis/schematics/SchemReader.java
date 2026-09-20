package com.gadgetman.jarvis.schematics;

import com.gadgetman.jarvis.core.platform.Log;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Native .schem reader: Sponge Schematic v2/v3, no WorldEdit required.
 *
 * <p>Reading is pure Java and safe off the server thread. Placing what was
 * read is {@link SchematicLibrary}'s job, through the platform's world.
 */
public final class SchemReader {

    private final Log log;

    public SchemReader(Log log) {
        this.log = log;
    }

    /** Read a .schem file. Throws on anything but a well-formed schematic. */
    public SchematicData read(Path file) throws Exception {
        Map<String, Object> nbt;
        try (GZIPInputStream gzis = new GZIPInputStream(Files.newInputStream(file))) {
            nbt = Nbt.read(new DataInputStream(gzis));
        }

        if (nbt == null) {
            throw new IOException("Failed to read NBT data");
        }

        SchematicData data = new SchematicData();
        data.fileName = file.getFileName().toString();

        // Get dimensions
        data.width = ((Number) nbt.getOrDefault("Width", (short) 0)).intValue();
        data.height = ((Number) nbt.getOrDefault("Height", (short) 0)).intValue();
        data.length = ((Number) nbt.getOrDefault("Length", (short) 0)).intValue();

        if (data.width == 0 || data.height == 0 || data.length == 0) {
            throw new IOException("Invalid schematic dimensions");
        }

        // Get palette
        @SuppressWarnings("unchecked")
        Map<String, Object> paletteNbt = (Map<String, Object>) nbt.get("Palette");
        if (paletteNbt == null) {
            throw new IOException("No palette found in schematic");
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
            throw new IOException("No BlockData found in schematic");
        }

        // Decode varint block data
        data.blocks = Nbt.readVarIntArray(blockDataRaw, data.width * data.height * data.length);

        // Get offset if present
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) nbt.get("Metadata");
        if (metadata != null) {
            data.offsetX = ((Number) metadata.getOrDefault("WEOffsetX", 0)).intValue();
            data.offsetY = ((Number) metadata.getOrDefault("WEOffsetY", 0)).intValue();
            data.offsetZ = ((Number) metadata.getOrDefault("WEOffsetZ", 0)).intValue();
        }

        log.info("Loaded schematic: " + data.width + "x" + data.height + "x" + data.length
                + " (" + paletteMax + " block types)");

        return data;
    }

    /** A schematic as read: dimensions, palette and the palette index of every block, in Y-Z-X order. */
    public static class SchematicData {
        public String fileName;
        public int width, height, length;
        public String[] palette;
        public int[] blocks;
        public int offsetX, offsetY, offsetZ;

        public int volume() {
            return width * height * length;
        }
    }
}
