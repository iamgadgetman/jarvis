package com.gadgetman.jarvis.fabric.butler;

import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.Vec3;
import com.gadgetman.jarvis.fabric.JarvisFabric;
import com.gadgetman.jarvis.fabric.fake.ActionPack;
import com.gadgetman.jarvis.fabric.fake.ActionPack.Action;
import com.gadgetman.jarvis.fabric.fake.ActionPack.ActionType;
import com.gadgetman.jarvis.fabric.fake.FakePlayer;
import com.gadgetman.jarvis.nav.AStar;
import com.gadgetman.jarvis.nav.Controls;
import com.gadgetman.jarvis.nav.MoveType;
import com.gadgetman.jarvis.nav.Path;
import com.gadgetman.jarvis.nav.PathFollower;
import net.minecraft.world.entity.Entity;

/**
 * The fake player's navigator: turns a goal into per-tick action-pack input.
 * Plans with {@link AStar}, follows with {@link PathFollower}, re-plans when
 * the follower reports stuck or strayed or a partial path runs out, and
 * gives up through the stuck handler after too many tries. A follow target
 * is re-planned as it moves.
 */
public final class FakeDriver {

    private enum Mode { IDLE, GOTO, FOLLOW }

    private static final int MAX_REPLANS = 24;
    private static final int FOLLOW_REPLAN_TICKS = 10;

    private final FakePlayer player;
    private Mode mode = Mode.IDLE;
    private BlockPos goal;
    private Entity target;
    private boolean aggressive;
    private PathFollower follower;
    private Runnable onStuck;
    private Runnable defaultOnStuck;
    private int replans;
    private int ticks;
    private int sinceFollowPlan;
    private boolean jumpHeld;
    private boolean paused;
    private int doorCooldown;
    private String status = "idle";

    public FakeDriver(FakePlayer player) {
        this.player = player;
    }

    /** The stuck handler used by {@link #goTo(BlockPos)} when none is given. */
    public void setDefaultOnStuck(Runnable handler) {
        this.defaultOnStuck = handler;
    }

    public void goTo(BlockPos target) {
        goTo(target, defaultOnStuck);
    }

    public void goTo(BlockPos target, Runnable onStuck) {
        reset();
        mode = Mode.GOTO;
        goal = target;
        this.onStuck = onStuck;
        plan();
    }

    public void follow(Entity target, boolean aggressive) {
        if (target == null) return;
        reset();
        mode = Mode.FOLLOW;
        this.target = target;
        this.aggressive = aggressive;
        this.onStuck = defaultOnStuck;
        sinceFollowPlan = FOLLOW_REPLAN_TICKS;
        status = "following " + target.getName().getString();
    }

    public void stop() {
        reset();
        status = "idle";
    }

    public boolean isNavigating() {
        return mode != Mode.IDLE;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        if (paused) apply(Controls.IDLE);
    }

    public boolean isPaused() {
        return paused;
    }

    public String status() {
        return status + (mode == Mode.IDLE ? "" : " (" + ticks + " ticks, " + replans + " plans)");
    }

    private void reset() {
        mode = Mode.IDLE;
        goal = null;
        target = null;
        follower = null;
        onStuck = null;
        replans = 0;
        ticks = 0;
        jumpHeld = false;
        player.actionPack().stopAll();
    }

    private LevelTerrain terrain() {
        return new LevelTerrain(player.level());
    }

    private Vec3 feet() {
        net.minecraft.world.phys.Vec3 p = player.position();
        return new Vec3(p.x, p.y, p.z);
    }

    /** Give up: idle, and tell whoever asked. */
    private void stuck(String why) {
        Runnable handler = onStuck;
        status = why;
        reset();
        status = why;
        if (handler != null) handler.run();
    }

    /** Plan toward the goal. False when there is no way at all. */
    private boolean plan() {
        if (replans++ >= MAX_REPLANS) {
            stuck("gave up after " + MAX_REPLANS + " plans");
            return false;
        }
        BlockPos from = feet().block();
        Path path = new AStar(terrain()).find(from, goal);
        if (path.isEmpty()) {
            if (path.partial()) {
                stuck("no path from " + from + " to " + goal);
                return false;
            }
            follower = null;      // already there
            return true;
        }
        follower = new PathFollower(path);
        status = (path.partial() ? "partial path " : "path ") + path.size() + " steps";
        JarvisFabric.LOG.debug("[{}] {} -> {}: {} ({} nodes)", player.getName().getString(), from, goal,
                status, path.nodesExpanded());
        return true;
    }

    /** Called from the fake player's tick, before the action pack applies input. */
    public void tick() {
        if (mode == Mode.IDLE || paused) return;
        ticks++;
        if (doorCooldown > 0) doorCooldown--;

        if (mode == Mode.FOLLOW && !tickFollow()) return;

        if (follower == null) {
            if (mode == Mode.GOTO) finish("arrived");
            else apply(Controls.IDLE);
            return;
        }

        Controls c = follower.tick(feet(), player.onGround(), feetInLiquid());
        if (follower.isDone()) {
            apply(Controls.IDLE);
            if (follower.path().partial()) {
                plan();            // chain the next leg
            } else if (mode == Mode.GOTO) {
                finish("arrived");
            } else {
                follower = null;
            }
            return;
        }
        if (follower.isStuck() || follower.hasStrayed()) {
            JarvisFabric.LOG.debug("[{}] {} at {}, planning again", player.getName().getString(),
                    follower.isStuck() ? "stuck" : "strayed", feet());
            apply(Controls.IDLE);
            plan();
            return;
        }
        apply(c);
    }

    /** Keep the goal on the target. False when this tick is spoken for. */
    private boolean tickFollow() {
        if (target == null || !target.isAlive() || target.level() != player.level()) {
            finish("lost " + (target == null ? "target" : target.getName().getString()));
            return false;
        }
        double close = aggressive ? 2.0 : 2.5;
        double distance = player.distanceTo(target);
        if (distance <= close) {
            follower = null;
            apply(Controls.IDLE);
            player.actionPack().lookAt(target.getEyePosition());
            return false;
        }
        sinceFollowPlan++;
        net.minecraft.core.BlockPos at = target.blockPosition();
        BlockPos now = new BlockPos(at.getX(), at.getY(), at.getZ());
        boolean moved = goal == null || goal.distance(now) > 1.5;
        if ((follower == null || follower.isDone() || moved) && sinceFollowPlan >= FOLLOW_REPLAN_TICKS) {
            sinceFollowPlan = 0;
            goal = now;
            replans = 0;          // a moving target is not a stuck one
            if (!plan()) return false;
        }
        return true;
    }

    private boolean feetInLiquid() {
        return !player.level().getFluidState(player.blockPosition()).isEmpty();
    }

    private void finish(String why) {
        reset();
        status = why;
    }

    private void apply(Controls c) {
        ActionPack ap = player.actionPack();
        if (c.isIdle() && !c.useDoor()) {
            ap.stopMovement();
            if (jumpHeld) {
                ap.start(ActionType.JUMP, null);
                jumpHeld = false;
            }
            return;
        }
        ap.look(c.yaw(), c.pitch());
        ap.setForward(c.forward());
        ap.setStrafing(c.strafe());
        ap.setSneaking(c.sneak());
        ap.setSprinting(c.sprint());
        if (c.jump() != jumpHeld) {
            ap.start(ActionType.JUMP, c.jump() ? Action.continuous() : null);
            jumpHeld = c.jump();
        }
        if (c.useDoor() && doorCooldown == 0) {
            BlockPos door = shutDoorAhead();
            if (door != null) {
                ap.lookAt(net.minecraft.world.phys.Vec3.atCenterOf(
                        new net.minecraft.core.BlockPos(door.x(), door.y(), door.z())));
                ap.start(ActionType.USE, Action.once());
                doorCooldown = 10;
            }
        }
    }

    /** The shut door at the node he is heading for, if any. */
    private BlockPos shutDoorAhead() {
        if (follower == null || follower.isDone()) return null;
        Path.Step step = follower.path().step(follower.index());
        if (step.via() != MoveType.DOOR) return null;
        LevelTerrain t = terrain();
        for (BlockPos p : new BlockPos[] { step.pos(), step.pos().above(), step.pos().below() }) {
            if (t.isShutDoor(p)) return p;
        }
        return null;
    }
}
