package com.gadgetman.jarvis.nav;

import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.Vec3;
import org.junit.jupiter.api.Test;

import static com.gadgetman.jarvis.nav.GridTerrain.Cell.*;
import static org.junit.jupiter.api.Assertions.*;

class PathFollowerTest {

    /** Walk the body along a path; the tick count it took, or -1 when it stalled. */
    private static int run(GridTerrain t, Sim sim, Path path, int maxTicks) {
        PathFollower f = new PathFollower(path);
        for (int tick = 0; tick < maxTicks; tick++) {
            Controls c = f.tick(sim.pos, sim.onGround, sim.inWater());
            if (f.isDone()) return tick;
            if (f.isStuck()) fail("stuck at tick " + tick + " pos " + sim.pos + " heading " + f.target());
            sim.apply(c);
        }
        fail("not done after " + maxTicks + " ticks; at " + sim.pos + " heading " + f.target()
                + " index " + f.index() + "/" + path.size());
        return -1;
    }

    private static void assertNear(BlockPos goal, Vec3 pos) {
        assertTrue(pos.distance(goal.standing()) < 0.8, "ended at " + pos + " not " + goal);
    }

    @Test
    void walksAStraightLine() {
        GridTerrain t = GridTerrain.floor(10);
        Sim sim = new Sim(t, new Vec3(0.5, 1, 0.5));
        BlockPos goal = new BlockPos(8, 1, 0);
        Path p = new AStar(t).find(sim.pos.block(), goal);
        int ticks = run(t, sim, p, 200);
        assertNear(goal, sim.pos);
        assertTrue(ticks < 60, "took " + ticks);   // sprinted
    }

    @Test
    void turnsCorners() {
        GridTerrain t = GridTerrain.floor(10);
        t.fill(3, 1, -10, 3, 2, 2, STONE);   // wall with a gap at z>=3
        Sim sim = new Sim(t, new Vec3(0.5, 1, 0.5));
        BlockPos goal = new BlockPos(6, 1, 0);
        Path p = new AStar(t).find(sim.pos.block(), goal);
        run(t, sim, p, 400);
        assertNear(goal, sim.pos);
    }

    @Test
    void jumpsUpStairs() {
        GridTerrain t = GridTerrain.floor(10);
        t.fill(2, 1, -1, 6, 1, 1, STONE);
        t.fill(3, 2, -1, 6, 2, 1, STONE);
        t.fill(4, 3, -1, 6, 3, 1, STONE);
        Sim sim = new Sim(t, new Vec3(0.5, 1, 0.5));
        BlockPos goal = new BlockPos(6, 4, 0);
        Path p = new AStar(t).find(sim.pos.block(), goal);
        run(t, sim, p, 400);
        assertNear(goal, sim.pos);
        assertTrue(sim.jumps >= 3, "jumped " + sim.jumps);
    }

    @Test
    void dropsOffALedge() {
        GridTerrain t = new GridTerrain();
        t.fill(0, 3, -1, 3, 3, 1, STONE);
        t.fill(4, 0, -1, 8, 0, 1, STONE);
        Sim sim = new Sim(t, new Vec3(0.5, 4, 0.5));
        BlockPos goal = new BlockPos(8, 1, 0);
        Path p = new AStar(t).find(sim.pos.block(), goal);
        run(t, sim, p, 400);
        assertNear(goal, sim.pos);
    }

    @Test
    void jumpsAGap() {
        GridTerrain t = new GridTerrain();
        t.fill(0, 0, -1, 3, 0, 1, STONE);
        t.fill(5, 0, -1, 8, 0, 1, STONE);
        Sim sim = new Sim(t, new Vec3(0.5, 1, 0.5));
        BlockPos goal = new BlockPos(8, 1, 0);
        Path p = new AStar(t).find(sim.pos.block(), goal);
        assertTrue(p.steps().stream().anyMatch(s -> s.via() == MoveType.JUMP_GAP));
        run(t, sim, p, 400);
        assertNear(goal, sim.pos);
        assertTrue(sim.pos.y() >= 1, "fell in: " + sim.pos);
    }

    @Test
    void crossesAPond() {
        GridTerrain t = GridTerrain.floor(10);
        t.fill(2, 0, -2, 5, 0, 2, WATER);
        t.fill(2, -1, -2, 5, -1, 2, WATER);
        t.fill(2, -2, -2, 5, -2, 2, STONE);
        Sim sim = new Sim(t, new Vec3(0.5, 1, 0.5));
        BlockPos goal = new BlockPos(7, 1, 0);
        Path p = new AStar(t).find(sim.pos.block(), goal);
        run(t, sim, p, 600);
        assertNear(goal, sim.pos);
    }

    @Test
    void swimsOutOfAPool() {
        GridTerrain t = GridTerrain.floor(10);
        t.fill(0, 0, -1, 3, 0, 1, WATER);
        t.fill(0, -1, -1, 3, -1, 1, WATER);
        t.fill(0, -2, -1, 3, -2, 1, STONE);
        Sim sim = new Sim(t, new Vec3(1.5, -1, 0.5));
        BlockPos goal = new BlockPos(6, 1, 0);
        Path p = new AStar(t).find(sim.pos.block(), goal);
        run(t, sim, p, 600);
        assertNear(goal, sim.pos);
    }

    @Test
    void climbsALadder() {
        GridTerrain t = new GridTerrain();
        t.fill(0, 0, -1, 2, 0, 1, STONE);
        t.fill(3, 0, -1, 3, 4, 1, STONE);
        t.fill(2, 1, 0, 2, 4, 0, LADDER);
        t.fill(4, 4, -1, 6, 4, 1, STONE);
        Sim sim = new Sim(t, new Vec3(0.5, 1, 0.5));
        BlockPos goal = new BlockPos(6, 5, 0);
        Path p = new AStar(t).find(sim.pos.block(), goal);
        run(t, sim, p, 600);
        assertNear(goal, sim.pos);
    }

    @Test
    void opensADoorOnTheWay() {
        GridTerrain t = GridTerrain.floor(10);
        t.fill(3, 1, -10, 3, 3, 10, STONE);
        t.set(3, 1, 0, DOOR).set(3, 2, 0, DOOR);
        Sim sim = new Sim(t, new Vec3(0.5, 1, 0.5));
        BlockPos goal = new BlockPos(6, 1, 0);
        Path p = new AStar(t).find(sim.pos.block(), goal);
        run(t, sim, p, 400);
        assertNear(goal, sim.pos);
        assertTrue(sim.doorsOpened > 0);
    }

    @Test
    void reportsStuckWhenBlocked() {
        GridTerrain t = GridTerrain.floor(10);
        Sim sim = new Sim(t, new Vec3(0.5, 1, 0.5));
        Path p = new AStar(t).find(sim.pos.block(), new BlockPos(6, 1, 0));
        t.fill(2, 1, -10, 2, 3, 10, STONE);  // a wall appears after planning
        PathFollower f = new PathFollower(p);
        boolean stuck = false;
        for (int tick = 0; tick < 200 && !stuck; tick++) {
            Controls c = f.tick(sim.pos, sim.onGround, sim.inWater());
            sim.apply(c);
            stuck = f.isStuck();
        }
        assertTrue(stuck);
        assertFalse(f.isDone());
    }

    @Test
    void reportsStrayWhenCarriedAway() {
        GridTerrain t = GridTerrain.floor(20);
        Sim sim = new Sim(t, new Vec3(0.5, 1, 0.5));
        Path p = new AStar(t).find(sim.pos.block(), new BlockPos(6, 1, 0));
        PathFollower f = new PathFollower(p);
        f.tick(sim.pos, true, false);
        sim.pos = new Vec3(0.5, 1, 15.5);   // teleported
        f.tick(sim.pos, true, false);
        assertTrue(f.hasStrayed());
    }

    @Test
    void emptyPathIsDoneAtOnce() {
        PathFollower f = new PathFollower(Path.EMPTY);
        assertTrue(f.isDone());
        assertTrue(f.tick(Vec3.ZERO, true, false).isIdle());
        assertNull(f.target());
    }
}
