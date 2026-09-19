package com.gadgetman.jarvis.vanilla.platform;

import com.gadgetman.jarvis.core.platform.Log;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BellBlock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Where controller bells have been placed. A placed bell is an ordinary
 * block with no item data left, so the positions are kept here, in a small
 * file beside the config, and a position stops counting once the bell is
 * gone.
 */
public final class BellRegistry {

    private final Path file;
    private final Log log;
    private final Set<String> placed = new LinkedHashSet<>();

    public BellRegistry(Path dataDir, Log log) {
        this.file = dataDir.resolve("bells.txt");
        this.log = log;
        load();
    }

    private static String key(ServerLevel level, BlockPos pos) {
        return VanillaWorlds.id(level) + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    public void add(ServerLevel level, BlockPos pos) {
        if (placed.add(key(level, pos))) save();
    }

    /** True when a controller bell stands here. Forgets positions whose bell is gone. */
    public boolean isPlaced(ServerLevel level, BlockPos pos) {
        String k = key(level, pos);
        if (!placed.contains(k)) return false;
        if (level.getBlockState(pos).getBlock() instanceof BellBlock) return true;
        placed.remove(k);
        save();
        return false;
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) placed.add(line.trim());
            }
        } catch (IOException e) {
            log.warn("Could not read " + file + ": " + e.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, placed, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not write " + file + ": " + e.getMessage());
        }
    }
}
