package com.gadgetman.jarvis.nav;

import com.gadgetman.jarvis.core.world.BlockPos;

import java.util.HashMap;
import java.util.Map;

/** A test world: a map of cells, everything else air. */
final class GridTerrain implements Terrain {

    enum Cell { AIR, STONE, WATER, LADDER, DOOR, LAVA }

    private final Map<BlockPos, Cell> cells = new HashMap<>();
    private final int minY;
    private final int maxY;

    GridTerrain() {
        this(-64, 320);
    }

    GridTerrain(int minY, int maxY) {
        this.minY = minY;
        this.maxY = maxY;
    }

    GridTerrain set(int x, int y, int z, Cell cell) {
        cells.put(new BlockPos(x, y, z), cell);
        return this;
    }

    GridTerrain fill(int x1, int y1, int z1, int x2, int y2, int z2, Cell cell) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++)
                    cells.put(new BlockPos(x, y, z), cell);
        return this;
    }

    /** A stone floor at y = 0 for a square of side blocks around the origin. */
    static GridTerrain floor(int half) {
        return new GridTerrain().fill(-half, 0, -half, half, 0, half, Cell.STONE);
    }

    Cell at(BlockPos pos) {
        return cells.getOrDefault(pos, Cell.AIR);
    }

    @Override public boolean isSolid(BlockPos pos) { return at(pos) == Cell.STONE; }
    @Override public boolean isPassable(BlockPos pos) {
        Cell c = at(pos);
        return c == Cell.AIR || c == Cell.WATER || c == Cell.LADDER || c == Cell.LAVA;
    }
    @Override public boolean isLiquid(BlockPos pos) { return at(pos) == Cell.WATER || at(pos) == Cell.LAVA; }
    @Override public boolean isClimbable(BlockPos pos) { return at(pos) == Cell.LADDER; }
    @Override public boolean isDoor(BlockPos pos) { return at(pos) == Cell.DOOR; }
    @Override public boolean isHazard(BlockPos pos) { return at(pos) == Cell.LAVA; }
    @Override public int minY() { return minY; }
    @Override public int maxY() { return maxY; }
}
