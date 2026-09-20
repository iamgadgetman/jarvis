package com.gadgetman.jarvis.nav;

import com.gadgetman.jarvis.core.world.BlockPos;

/**
 * What the pathfinder needs to know about a block. Every answer is about
 * one position; the adapter reads its own world to give it.
 */
public interface Terrain {

    /** Something to stand on: a full or partial collision box. */
    boolean isSolid(BlockPos pos);

    /** Something a body can occupy: air, grass, water, an open door. */
    boolean isPassable(BlockPos pos);

    /** Water or lava. */
    boolean isLiquid(BlockPos pos);

    /** A ladder, vine or scaffolding: standable without a floor, climbable. */
    boolean isClimbable(BlockPos pos);

    /** A door or fence gate the butler may open, whether or not it is open now. */
    boolean isDoor(BlockPos pos);

    /** Lava, fire, cactus, magma, berry bushes, powder snow: never step here or on it. */
    boolean isHazard(BlockPos pos);

    int minY();

    int maxY();
}
