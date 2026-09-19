package com.gadgetman.jarvis.fabric.spike;

import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.Facing;
import com.gadgetman.jarvis.core.world.Vec3;
import com.gadgetman.jarvis.fabric.spike.fake.ActionPack;
import com.gadgetman.jarvis.fabric.spike.fake.ActionPack.Action;
import com.gadgetman.jarvis.fabric.spike.fake.ActionPack.ActionType;
import com.gadgetman.jarvis.fabric.spike.fake.FakePlayer;
import com.gadgetman.jarvis.nav.AStar;
import com.gadgetman.jarvis.nav.Controls;
import com.gadgetman.jarvis.nav.MoveType;
import com.gadgetman.jarvis.nav.Path;
import com.gadgetman.jarvis.nav.PathFollower;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Turns a goal into per-tick action-pack input: plans with {@link AStar},
 * follows with {@link PathFollower}, re-plans when the follower reports
 * stuck or strayed or a partial path runs out, and for a dig walks within
 * reach of the block and holds attack until it is gone.
 */
public final class FakeDriver {

    private enum Mode { IDLE, GOTO, DIG }

    private static final int MAX_REPLANS = 24;
    private static final double DIG_REACH = 4.0;

    private final FakePlayer player;
    private Mode mode = Mode.IDLE;
    private BlockPos goal;
    private BlockPos digTarget;
    private PathFollower follower;
    private int replans;
    private int ticks;
    private boolean jumpHeld;
    private boolean digging;
    private int doorCooldown;
    private String status = "idle";

    public FakeDriver(FakePlayer player) {
        this.player = player;
    }

    public void goTo(BlockPos target) {
        reset();
        mode = Mode.GOTO;
        goal = target;
        plan();
    }

    public void dig(BlockPos target) {
        reset();
        mode = Mode.DIG;
        digTarget = target;
        goal = standBeside(target);
        plan();
    }

    /** Somewhere to stand within arm's reach of a block: beside or on top of it, nearest first. */
    private BlockPos standBeside(BlockPos target) {
        AStar search = new AStar(terrain());
        Vec3 here = feet();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        java.util.List<BlockPos> candidates = new java.util.ArrayList<>();
        for (Facing f : Facing.values()) {
            if (!f.isHorizontal()) continue;
            candidates.add(target.side(f));
            candidates.add(target.side(f).below());
        }
        candidates.add(target.above());
        for (BlockPos c : candidates) {
            BlockPos stand = search.nearestStandable(c, 1);
            if (stand == null) continue;
            double d = stand.standing().distance(here);
            if (d < bestDistance) {
                bestDistance = d;
                best = stand;
            }
        }
        return best != null ? best : target;
    }

    public void stop() {
        reset();
        status = "idle";
    }

    public String status() {
        return status + (mode == Mode.IDLE ? "" : " (" + ticks + " ticks, " + replans + " plans)");
    }

    private void reset() {
        mode = Mode.IDLE;
        goal = null;
        digTarget = null;
        follower = null;
        replans = 0;
        ticks = 0;
        digging = false;
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

    private void plan() {
        if (replans++ >= MAX_REPLANS) {
            status = "gave up after " + MAX_REPLANS + " plans";
            mode = Mode.IDLE;
            player.actionPack().stopAll();
            return;
        }
        BlockPos from = feet().block();
        Path path = new AStar(terrain()).find(from, goal);
        if (path.isEmpty()) {
            if (path.partial()) {
                status = "no path from " + from + " to " + goal;
                mode = Mode.IDLE;
                player.actionPack().stopAll();
                return;
            }
            follower = null;      // already there
            return;
        }
        follower = new PathFollower(path);
        status = (path.partial() ? "partial path " : "path ") + path.size() + " steps, " + path.nodesExpanded() + " nodes";
        SpikeMod.LOG.info("[{}] {} → {}: {}", player.getName().getString(), from, goal, status);
    }

    /** Called from the fake player's tick, before the action pack applies input. */
    public void tick() {
        if (mode == Mode.IDLE) return;
        ticks++;
        if (doorCooldown > 0) doorCooldown--;

        if (mode == Mode.DIG && tickDig()) return;

        if (follower == null) {
            finish("arrived");
            return;
        }

        Controls c = follower.tick(feet(), player.onGround(), feetInLiquid());
        if (follower.isDone()) {
            if (follower.path().partial()) {
                plan();            // chain the next leg
            } else if (mode == Mode.GOTO) {
                finish("arrived");
            } else {
                follower = null;   // dig: let tickDig take over from here
            }
            apply(Controls.IDLE);
            return;
        }
        if (follower.isStuck() || follower.hasStrayed()) {
            SpikeMod.LOG.info("[{}] {} at {}, planning again", player.getName().getString(),
                    follower.isStuck() ? "stuck" : "strayed", feet());
            apply(Controls.IDLE);
            plan();
            return;
        }
        apply(c);
    }

    private boolean feetInLiquid() {
        return !player.level().getFluidState(player.blockPosition()).isEmpty();
    }

    private void finish(String why) {
        status = why;
        mode = Mode.IDLE;
        follower = null;
        apply(Controls.IDLE);
    }

    /** True when the dig has taken over movement this tick. */
    private boolean tickDig() {
        LevelTerrain t = terrain();
        BlockState state = t.state(digTarget);
        if (state.isAir()) {
            player.actionPack().start(ActionType.ATTACK, null);
            digging = false;
            finish("dug " + digTarget);
            return true;
        }
        net.minecraft.world.phys.Vec3 centre = net.minecraft.world.phys.Vec3.atCenterOf(
                new net.minecraft.core.BlockPos(digTarget.x(), digTarget.y(), digTarget.z()));
        boolean inReach = player.getEyePosition().distanceTo(centre) <= DIG_REACH;
        if (inReach) {
            ActionPack ap = player.actionPack();
            ap.stopMovement();
            ap.lookAt(centre);
            if (jumpHeld) {
                ap.start(ActionType.JUMP, null);
                jumpHeld = false;
            }
            if (!digging) {
                ap.start(ActionType.ATTACK, Action.continuous());
                digging = true;
                status = "digging " + digTarget;
            }
            return true;
        }
        if (digging) {
            player.actionPack().start(ActionType.ATTACK, null);
            digging = false;
        }
        if (follower == null) {
            // Walked the whole path and still out of reach: try again from here.
            plan();
            if (follower == null) finish("cannot reach " + digTarget);
            return true;
        }
        return false;
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
