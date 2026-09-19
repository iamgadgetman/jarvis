package com.gadgetman.jarvis.nav;

import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.Look;
import com.gadgetman.jarvis.core.world.Vec3;

/**
 * A rough player body for the follower to push around: gravity, a jump that
 * clears one block, auto step-up of a half block, ladders, water, and doors
 * that open when asked. Nothing like the game's physics, just enough that a
 * follower which works here has a fair chance of working there.
 */
final class Sim {

    private static final double WALK = 0.2;
    private static final double SPRINT = 0.26;
    private static final double SWIM = 0.1;
    private static final double CLIMB = 0.2;
    private static final double SLIDE = 0.15;
    private static final double GRAVITY = 0.08;
    private static final double JUMP = 0.42;
    private static final double STEP = 0.6;
    private static final double HALF_WIDTH = 0.3;

    private final GridTerrain terrain;
    Vec3 pos;
    private double vy;
    boolean onGround;
    int doorsOpened;
    int jumps;

    Sim(GridTerrain terrain, Vec3 pos) {
        this.terrain = terrain;
        this.pos = pos;
        this.onGround = floorUnder(pos);
    }

    boolean inWater() {
        return terrain.isLiquid(pos.block());
    }

    private boolean onLadder() {
        return terrain.isClimbable(pos.block());
    }

    private static boolean hard(GridTerrain t, BlockPos p) {
        return t.isSolid(p) || t.isDoor(p);
    }

    /** A body cannot be here when feet or head overlap stone or a closed door. */
    private boolean blocked(Vec3 at) {
        for (double dx : new double[] { -HALF_WIDTH, HALF_WIDTH })
            for (double dz : new double[] { -HALF_WIDTH, HALF_WIDTH }) {
                BlockPos feet = new Vec3(at.x() + dx, at.y() + 0.001, at.z() + dz).block();
                if (hard(terrain, feet) || hard(terrain, feet.above())) return true;
                BlockPos crown = new Vec3(at.x() + dx, at.y() + 1.79, at.z() + dz).block();
                if (hard(terrain, crown)) return true;
            }
        return false;
    }

    private boolean floorUnder(Vec3 at) {
        if (Math.abs(at.y() - Math.rint(at.y())) > 0.01) return false;
        for (double dx : new double[] { -HALF_WIDTH, HALF_WIDTH })
            for (double dz : new double[] { -HALF_WIDTH, HALF_WIDTH }) {
                BlockPos below = new Vec3(at.x() + dx, Math.rint(at.y()) - 0.5, at.z() + dz).block();
                if (hard(terrain, below)) return true;
            }
        return false;
    }

    /** The highest block top under the footprint between two heights, or NaN. */
    private double floorTopBetween(Vec3 at, double fromY, double toY) {
        double best = Double.NaN;
        for (double dx : new double[] { -HALF_WIDTH, HALF_WIDTH })
            for (double dz : new double[] { -HALF_WIDTH, HALF_WIDTH }) {
                int cx = (int) Math.floor(at.x() + dx), cz = (int) Math.floor(at.z() + dz);
                for (int by = (int) Math.floor(fromY - 0.001); by >= (int) Math.floor(toY); by--) {
                    if (hard(terrain, new BlockPos(cx, by, cz))) {
                        double top = by + 1;
                        if (top <= fromY + 1e-9 && (Double.isNaN(best) || top > best)) best = top;
                        break;
                    }
                }
            }
        return best;
    }

    void apply(Controls c) {
        if (c.useDoor()) openDoorsNearby();

        Vec3 dir = new Look(c.yaw(), 0).direction();
        double speed = inWater() ? SWIM : onLadder() ? CLIMB : c.sprint() ? SPRINT : WALK;
        double mx = dir.x() * c.forward() * speed;
        double mz = dir.z() * c.forward() * speed;

        Vec3 next = tryMove(pos, mx, 0);
        next = tryMove(next, 0, mz);
        boolean bumped = (mx != 0 || mz != 0) && next.distanceFlat(pos) < 1e-9;

        if (onLadder()) {
            // As in the game: pushing into the wall or holding jump climbs, otherwise slide.
            vy = c.jump() || bumped ? CLIMB : c.sneak() ? 0 : -SLIDE;
        } else if (inWater()) {
            vy = c.jump() ? 0.08 : -0.04;
        } else {
            if (c.jump() && onGround) { vy = JUMP; jumps++; }
        }

        double newY = next.y() + vy;
        if (!onLadder() && !inWater()) {
            // The game's order: move with this tick's velocity, then apply gravity and drag.
            vy = (vy - GRAVITY) * 0.98;
            vy = Math.max(vy, -3.0);
        }
        if (vy < 0) {
            double top = floorTopBetween(next, next.y(), newY);
            if (!Double.isNaN(top)) {
                next = new Vec3(next.x(), top, next.z());
                vy = 0;
                onGround = true;
            } else {
                next = new Vec3(next.x(), newY, next.z());
                onGround = false;
            }
        } else if (vy > 0) {
            Vec3 up = new Vec3(next.x(), newY, next.z());
            if (blocked(up)) {
                vy = 0;
            } else {
                next = up;
            }
            onGround = false;
        } else {
            onGround = floorUnder(next);
        }
        pos = next;
    }

    private void openDoorsNearby() {
        BlockPos feet = pos.block();
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                for (int dy = -1; dy <= 2; dy++) {
                    BlockPos p = feet.offset(dx, dy, dz);
                    if (terrain.isDoor(p)) {
                        terrain.set(p.x(), p.y(), p.z(), GridTerrain.Cell.AIR);
                        doorsOpened++;
                    }
                }
    }

    private Vec3 tryMove(Vec3 from, double dx, double dz) {
        if (dx == 0 && dz == 0) return from;
        Vec3 to = from.add(dx, 0, dz);
        if (!blocked(to)) return to;
        // Auto step: a floor no more than STEP higher
        if (onGround || onLadder() || inWater()) {
            Vec3 stepped = new Vec3(to.x(), Math.floor(to.y()) + 1, to.z());
            if (stepped.y() - from.y() <= STEP + 1e-9 && !blocked(stepped)) {
                vy = 0;
                onGround = true;
                return stepped;
            }
        }
        return from;
    }
}
