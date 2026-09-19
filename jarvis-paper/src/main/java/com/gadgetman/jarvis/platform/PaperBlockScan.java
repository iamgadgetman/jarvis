package com.gadgetman.jarvis.platform;

import com.gadgetman.jarvis.core.platform.BlockScan;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.BlockState;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;

import java.util.HashMap;
import java.util.Map;

/**
 * A region copied out of chunk snapshots. Reads return the block id only,
 * which is all a scan for ores or portals needs, and are safe off-thread.
 */
public final class PaperBlockScan implements BlockScan {

    private final BlockPos min;
    private final BlockPos max;
    private final int worldMinY;
    private final int worldMaxY;
    private final Map<Long, ChunkSnapshot> chunks;
    private final Map<Material, BlockState> states = new HashMap<>();

    PaperBlockScan(BlockPos min, BlockPos max, int worldMinY, int worldMaxY, Map<Long, ChunkSnapshot> chunks) {
        this.min = min;
        this.max = max;
        this.worldMinY = worldMinY;
        this.worldMaxY = worldMaxY;
        this.chunks = chunks;
    }

    static long key(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    @Override
    public BlockState block(BlockPos pos) {
        if (!covers(pos)) return BlockState.AIR;
        ChunkSnapshot snap = chunks.get(key(pos.x() >> 4, pos.z() >> 4));
        if (snap == null) return BlockState.AIR;
        Material m = snap.getBlockType(pos.x() & 15, pos.y(), pos.z() & 15);
        return states.computeIfAbsent(m, mat -> BlockState.of(PaperItems.id(mat)));
    }

    @Override public BlockPos min() { return min; }
    @Override public BlockPos max() { return max; }

    @Override
    public boolean covers(BlockPos pos) {
        if (pos.x() < min.x() || pos.x() > max.x() || pos.z() < min.z() || pos.z() > max.z()) return false;
        if (pos.y() < Math.max(min.y(), worldMinY) || pos.y() > Math.min(max.y(), worldMaxY)) return false;
        return chunks.containsKey(key(pos.x() >> 4, pos.z() >> 4));
    }
}
