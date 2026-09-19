package com.gadgetman.jarvis.core.world;

/** A facing direction in degrees, the game's own convention. */
public record Look(float yaw, float pitch) {

    public static final Look SOUTH = new Look(0, 0);

    /** Unit vector pointing where this look points. */
    public Vec3 direction() {
        double yawR = Math.toRadians(yaw);
        double pitchR = Math.toRadians(pitch);
        double xz = Math.cos(pitchR);
        return new Vec3(-xz * Math.sin(yawR), -Math.sin(pitchR), xz * Math.cos(yawR));
    }
}
