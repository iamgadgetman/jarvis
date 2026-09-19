package com.gadgetman.jarvis.core.world;

/** An integer block position. Immutable. */
public record BlockPos(int x, int y, int z) {

    public BlockPos offset(int dx, int dy, int dz) {
        return new BlockPos(x + dx, y + dy, z + dz);
    }

    public BlockPos below() { return offset(0, -1, 0); }
    public BlockPos above() { return offset(0, 1, 0); }

    public BlockPos side(Facing facing) {
        return offset(facing.dx(), facing.dy(), facing.dz());
    }

    public double distanceSq(BlockPos o) {
        double dx = x - o.x, dy = y - o.y, dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distance(BlockPos o) {
        return Math.sqrt(distanceSq(o));
    }

    /** The centre of the block, for navigation targets and sounds. */
    public Vec3 center() {
        return new Vec3(x + 0.5, y + 0.5, z + 0.5);
    }

    /** The bottom centre, where an entity stands on this block's top face. */
    public Vec3 standing() {
        return new Vec3(x + 0.5, y, z + 0.5);
    }

    /**
     * A single long identifying this position, for visited sets and map keys.
     * 26 bits of x and z and 12 of y, like the game's own packing, so any
     * position inside a world is unique.
     */
    public long packed() {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public static BlockPos unpack(long packed) {
        int x = (int) (packed << 0 >> 38);
        int z = (int) (packed << 26 >> 38);
        int y = (int) (packed << 52 >> 52);
        return new BlockPos(x, y, z);
    }

    @Override
    public String toString() {
        return x + "," + y + "," + z;
    }
}
