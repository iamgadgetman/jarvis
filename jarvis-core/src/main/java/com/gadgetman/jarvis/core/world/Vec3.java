package com.gadgetman.jarvis.core.world;

/** An exact position or direction in a world. Immutable. */
public record Vec3(double x, double y, double z) {

    public static final Vec3 ZERO = new Vec3(0, 0, 0);

    public Vec3 add(double dx, double dy, double dz) {
        return new Vec3(x + dx, y + dy, z + dz);
    }

    public Vec3 add(Vec3 o) {
        return add(o.x, o.y, o.z);
    }

    public Vec3 subtract(Vec3 o) {
        return new Vec3(x - o.x, y - o.y, z - o.z);
    }

    public Vec3 scale(double f) {
        return new Vec3(x * f, y * f, z * f);
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public Vec3 normalize() {
        double len = length();
        return len < 1e-9 ? ZERO : scale(1.0 / len);
    }

    public double distanceSq(Vec3 o) {
        double dx = x - o.x, dy = y - o.y, dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distance(Vec3 o) {
        return Math.sqrt(distanceSq(o));
    }

    /** Horizontal distance, ignoring height. */
    public double distanceFlat(Vec3 o) {
        double dx = x - o.x, dz = z - o.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** The block this position is inside. */
    public BlockPos block() {
        return new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    /** The yaw and pitch that look from here toward the target. */
    public Look lookToward(Vec3 target) {
        double dx = target.x - x, dy = target.y - y, dz = target.z - z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, flat));
        return new Look(yaw, pitch);
    }

    @Override
    public String toString() {
        return String.format("%.2f,%.2f,%.2f", x, y, z);
    }
}
