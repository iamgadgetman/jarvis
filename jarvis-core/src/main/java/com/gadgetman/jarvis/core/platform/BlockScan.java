package com.gadgetman.jarvis.core.platform;

import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.BlockState;

/** A copied region of a world. Positions outside the copy, or in unloaded chunks, read as air. */
public interface BlockScan {

    BlockState block(BlockPos pos);

    default String blockId(BlockPos pos) {
        return block(pos).id();
    }

    BlockPos min();

    BlockPos max();

    /** True if the chunk holding this position was loaded when the copy was taken. */
    boolean covers(BlockPos pos);
}
