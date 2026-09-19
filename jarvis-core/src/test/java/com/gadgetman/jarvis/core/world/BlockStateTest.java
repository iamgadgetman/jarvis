package com.gadgetman.jarvis.core.world;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockStateTest {

    private static final BlockState STAIR_IN_WORLD = new BlockState("minecraft:oak_stairs",
            Map.of("facing", "north", "half", "bottom", "shape", "straight", "waterlogged", "false"));

    @Test
    void aPlanThatNamesOnlyFacingMatchesTheFullStair() {
        BlockState plan = BlockState.of("minecraft:oak_stairs").with("facing", "north");
        assertTrue(STAIR_IN_WORLD.matches(plan));
    }

    @Test
    void aDifferentFacingDoesNotMatch() {
        BlockState plan = BlockState.of("minecraft:oak_stairs").with("facing", "south");
        assertFalse(STAIR_IN_WORLD.matches(plan));
    }

    @Test
    void aDifferentBlockDoesNotMatchWhateverTheProperties() {
        assertFalse(STAIR_IN_WORLD.matches(BlockState.of("minecraft:spruce_stairs")));
        assertFalse(STAIR_IN_WORLD.matches(null));
    }

    @Test
    void aBareIdMatchesAnyStateOfThatBlock() {
        assertTrue(STAIR_IN_WORLD.matches(BlockState.of("minecraft:oak_stairs")));
    }
}
