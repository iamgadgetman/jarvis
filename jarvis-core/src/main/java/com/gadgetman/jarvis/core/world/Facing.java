package com.gadgetman.jarvis.core.world;

/** The six block faces. */
public enum Facing {
    DOWN(0, -1, 0), UP(0, 1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0);

    private final int dx, dy, dz;

    Facing(int dx, int dy, int dz) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
    }

    public int dx() { return dx; }
    public int dy() { return dy; }
    public int dz() { return dz; }

    public Facing opposite() {
        return switch (this) {
            case DOWN -> UP;
            case UP -> DOWN;
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case WEST -> EAST;
            case EAST -> WEST;
        };
    }

    public boolean isHorizontal() {
        return dy == 0;
    }

    /** The lower-case name the game uses in block state properties. */
    public String key() {
        return name().toLowerCase();
    }

    /** The horizontal face closest to a yaw, for placing directional blocks. */
    public static Facing fromYaw(float yaw) {
        int q = Math.floorMod(Math.round(yaw / 90f), 4);
        return switch (q) {
            case 0 -> SOUTH;
            case 1 -> WEST;
            case 2 -> NORTH;
            default -> EAST;
        };
    }
}
