package com.gadgetman.jarvis.fabric.platform;

import com.gadgetman.jarvis.core.platform.BlockScan;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.BlockState;

import java.util.Set;

/**
 * A region copied out of loaded chunks: block ids only, which is all a scan
 * for ores or portals needs, and safe to read off-thread.
 */
public final class FabricBlockScan implements BlockScan {

    private final BlockPos min;
    private final BlockPos max;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final BlockState[] states;
    private final Set<Long> loadedChunks;

    FabricBlockScan(BlockPos min, BlockPos max, BlockState[] states, Set<Long> loadedChunks) {
        this.min = min;
        this.max = max;
        this.sizeX = max.x() - min.x() + 1;
        this.sizeY = max.y() - min.y() + 1;
        this.sizeZ = max.z() - min.z() + 1;
        this.states = states;
        this.loadedChunks = loadedChunks;
    }

    static long key(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    int index(int x, int y, int z) {
        return ((y - min.y()) * sizeZ + (z - min.z())) * sizeX + (x - min.x());
    }

    @Override
    public BlockState block(BlockPos pos) {
        if (!covers(pos)) return BlockState.AIR;
        BlockState s = states[index(pos.x(), pos.y(), pos.z())];
        return s == null ? BlockState.AIR : s;
    }

    @Override public BlockPos min() { return min; }
    @Override public BlockPos max() { return max; }

    @Override
    public boolean covers(BlockPos pos) {
        if (pos.x() < min.x() || pos.x() > max.x() || pos.z() < min.z() || pos.z() > max.z()) return false;
        if (pos.y() < min.y() || pos.y() > max.y()) return false;
        return loadedChunks.contains(key(pos.x() >> 4, pos.z() >> 4));
    }
}
