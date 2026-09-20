package com.gadgetman.jarvis.core.platform;

import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.Vec3;

/** A place in a particular world. */
public record Site(World world, Vec3 pos) {

    public BlockPos block() {
        return pos.block();
    }

    public boolean sameWorld(Site other) {
        return other != null && world.id().equals(other.world.id());
    }

    /** Distance, or a negative number when the sites are in different worlds. */
    public double distance(Site other) {
        return sameWorld(other) ? pos.distance(other.pos) : -1.0;
    }
}
