package com.gadgetman.jarvis.nav;

import com.gadgetman.jarvis.core.world.Look;
import com.gadgetman.jarvis.core.world.Vec3;

/**
 * Drives a body along a {@link Path}, one tick at a time.
 *
 * <p>Each tick it aims at the next node, pushes forward, and holds jump when
 * the node is higher or he is in water, sprint on straight flat runs, and
 * asks for a door to be opened when one is next. It notices when he has
 * stopped making progress and reports stuck, and when he has strayed too
 * far from the path and reports that a new one is needed.
 */
public final class PathFollower {

    /** Tuning. Distances in blocks, times in ticks. */
    public record Settings(double arriveDistance, int stuckAfterTicks, double strayDistance, double jumpWithin) {
        public static Settings defaults() {
            return new Settings(0.35, 40, 6.0, 1.3);
        }
    }

    private final Path path;
    private final Settings settings;
    private int index = 0;
    private boolean done;
    private boolean stuck;
    private boolean strayed;
    private double bestDistance = Double.MAX_VALUE;
    private int noProgressTicks = 0;

    public PathFollower(Path path) {
        this(path, Settings.defaults());
    }

    public PathFollower(Path path, Settings settings) {
        this.path = path;
        this.settings = settings;
        this.done = path.isEmpty();
    }

    public Path path() { return path; }
    public int index() { return index; }
    public boolean isDone() { return done; }
    public boolean isStuck() { return stuck; }
    /** True when he is far from the node he is heading for; the caller should search again. */
    public boolean hasStrayed() { return strayed; }

    /** Where he is heading right now, or null when done. */
    public Vec3 target() {
        return done ? null : path.step(index).pos().standing();
    }

    /**
     * One tick.
     *
     * @param pos      his feet
     * @param onGround whether he is standing on something
     * @param inWater  whether his feet are in liquid
     */
    public Controls tick(Vec3 pos, boolean onGround, boolean inWater) {
        if (done) return Controls.IDLE;

        // Arrive at, and move past, as many nodes as this position covers.
        for (int guard = 0; guard < 3 && !done; guard++) {
            Path.Step step = path.step(index);
            Vec3 target = step.pos().standing();
            double flat = pos.distanceFlat(target);
            double dy = target.y() - pos.y();
            boolean vertical = step.via() == MoveType.CLIMB_UP || step.via() == MoveType.CLIMB_DOWN
                    || (step.via() == MoveType.SWIM && pos.block().x() == step.pos().x() && pos.block().z() == step.pos().z());
            boolean arrived = vertical
                    ? flat < 0.6 && Math.abs(dy) < 0.5
                    : flat < settings.arriveDistance() && dy > -1.2 && dy < 0.8;
            if (!arrived) break;
            advance();
        }
        if (done) return Controls.IDLE;

        Path.Step step = path.step(index);
        Vec3 target = step.pos().standing();
        double flat = pos.distanceFlat(target);
        double dy = target.y() - pos.y();
        double dist = pos.distance(target);

        // Progress watch
        if (dist < bestDistance - 0.02) {
            bestDistance = dist;
            noProgressTicks = 0;
        } else if (++noProgressTicks > settings.stuckAfterTicks()) {
            stuck = true;
        }
        if (dist > settings.strayDistance()) strayed = true;

        Look look = pos.lookToward(new Vec3(target.x(), pos.y(), target.z()));
        float yaw = look.yaw();
        float pitch = 0;
        float forward = 1f;
        boolean jump = false;
        boolean sneak = false;

        switch (step.via()) {
            case STEP_UP -> jump = onGround && flat < settings.jumpWithin();
            case JUMP_GAP -> jump = onGround && flat < settings.jumpWithin() + 0.7;
            case CLIMB_UP -> {
                // Push into the ladder and hold jump: that is how a player climbs.
                jump = true;
                pitch = -60;
                if (flat < 0.3) forward = 0.3f;
            }
            case CLIMB_DOWN -> {
                // Let go and slide; pressing forward would climb back up.
                forward = flat < 0.3 ? 0f : 0.4f;
                pitch = 60;
            }
            case SWIM -> {
                if (inWater) {
                    jump = dy > -0.3;                // tread water or rise
                    pitch = dy > 0.5 ? -45 : dy < -0.5 ? 45 : 0;
                }
            }
            case DROP -> sneak = false;            // a planned drop is a safe one
            default -> {
                if (inWater && dy > -0.3) jump = true;   // keep his head up crossing a puddle
            }
        }

        boolean useDoor = step.via() == MoveType.DOOR && flat < 1.6;
        boolean sprint = !inWater && !jump && straightRunAhead(3);

        return new Controls(yaw, pitch, forward, 0f, jump, sneak, sprint, useDoor);
    }

    private void advance() {
        index++;
        bestDistance = Double.MAX_VALUE;
        noProgressTicks = 0;
        strayed = false;
        if (index >= path.size()) done = true;
    }

    /** The next few nodes are plain walking in one direction. */
    private boolean straightRunAhead(int count) {
        if (index + count > path.size()) return false;
        Path.Step first = path.step(index);
        if (first.via() != MoveType.WALK) return false;
        int dx = 0, dz = 0;
        for (int i = index; i < index + count; i++) {
            Path.Step s = path.step(i);
            if (s.via() != MoveType.WALK) return false;
            if (i > index) {
                Path.Step prev = path.step(i - 1);
                int ddx = s.pos().x() - prev.pos().x();
                int ddz = s.pos().z() - prev.pos().z();
                if (i == index + 1) { dx = ddx; dz = ddz; }
                else if (ddx != dx || ddz != dz) return false;
            }
        }
        return true;
    }
}
