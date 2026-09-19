package com.gadgetman.jarvis.nav;

import com.gadgetman.jarvis.core.world.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.gadgetman.jarvis.nav.GridTerrain.Cell.*;
import static org.junit.jupiter.api.Assertions.*;

class AStarTest {

    private static List<MoveType> moves(Path p) {
        return p.steps().stream().map(Path.Step::via).toList();
    }

    @Test
    void walksAcrossFlatGround() {
        GridTerrain t = GridTerrain.floor(10);
        Path p = new AStar(t).find(new BlockPos(0, 1, 0), new BlockPos(5, 1, 0));
        assertFalse(p.partial());
        assertEquals(5, p.size());
        assertEquals(new BlockPos(5, 1, 0), p.end());
        assertTrue(moves(p).stream().allMatch(m -> m == MoveType.WALK));
    }

    @Test
    void goalOnTheGroundIsLiftedToTheStandableBlockAbove() {
        GridTerrain t = GridTerrain.floor(10);
        // Caller passes the floor block itself, as a clicked block would be.
        Path p = new AStar(t).find(new BlockPos(0, 1, 0), new BlockPos(3, 0, 0));
        assertEquals(new BlockPos(3, 1, 0), p.end());
    }

    @Test
    void climbsStairsWithStepUps() {
        GridTerrain t = GridTerrain.floor(10);
        // A staircase rising east: x=2 is one high, x=3 two high, x=4 three high, then a landing.
        t.fill(2, 1, -1, 6, 1, 1, STONE);
        t.fill(3, 2, -1, 6, 2, 1, STONE);
        t.fill(4, 3, -1, 6, 3, 1, STONE);
        Path p = new AStar(t).find(new BlockPos(0, 1, 0), new BlockPos(6, 4, 0));
        assertFalse(p.partial());
        assertEquals(new BlockPos(6, 4, 0), p.end());
        assertEquals(3, moves(p).stream().filter(m -> m == MoveType.STEP_UP).count());
    }

    @Test
    void dropsOffALedgeButNotACliff() {
        GridTerrain t = new GridTerrain();
        t.fill(0, 3, 0, 3, 3, 0, STONE);     // a ledge at y=3 (stand at 4)
        t.fill(4, 0, 0, 8, 0, 0, STONE);     // ground 3 below its lip
        Path p = new AStar(t).find(new BlockPos(0, 4, 0), new BlockPos(8, 1, 0));
        assertFalse(p.partial());
        assertTrue(moves(p).contains(MoveType.DROP));

        GridTerrain cliff = new GridTerrain();
        cliff.fill(0, 6, 0, 3, 6, 0, STONE); // six blocks up: too far to drop
        cliff.fill(4, 0, 0, 8, 0, 0, STONE);
        Path none = new AStar(cliff).find(new BlockPos(0, 7, 0), new BlockPos(8, 1, 0));
        assertTrue(none.partial());
    }

    @Test
    void jumpsAOneBlockGap() {
        GridTerrain t = new GridTerrain();
        t.fill(0, 0, 0, 3, 0, 0, STONE);
        t.fill(5, 0, 0, 8, 0, 0, STONE);     // x=4 is a hole with nothing under it
        Path p = new AStar(t).find(new BlockPos(0, 1, 0), new BlockPos(8, 1, 0));
        assertFalse(p.partial());
        assertTrue(moves(p).contains(MoveType.JUMP_GAP));
        assertFalse(p.steps().stream().anyMatch(s -> s.pos().x() == 4));

        AStar.Options noJump = new AStar.Options(4000, 3, 12, true, false, true);
        assertTrue(new AStar(t, noJump).find(new BlockPos(0, 1, 0), new BlockPos(8, 1, 0)).partial());
    }

    @Test
    void crossesAPondAlongTheSurface() {
        GridTerrain t = GridTerrain.floor(10);
        t.fill(2, 0, -2, 5, 0, 2, WATER);    // the floor becomes water for a stretch
        t.fill(2, -1, -2, 5, -1, 2, WATER);
        t.fill(2, -2, -2, 5, -2, 2, STONE);
        Path p = new AStar(t).find(new BlockPos(0, 1, 0), new BlockPos(7, 1, 0));
        assertFalse(p.partial());
        assertEquals(new BlockPos(7, 1, 0), p.end());
        // Surface nodes are floated on the water, not dived through it.
        assertTrue(p.steps().stream().anyMatch(s -> t.isLiquid(s.pos().below())));
        assertFalse(p.steps().stream().anyMatch(s -> t.isLiquid(s.pos())));
    }

    @Test
    void swimsUpAndOutOfAPool() {
        GridTerrain t = GridTerrain.floor(10);
        t.fill(0, 0, -1, 3, 0, 1, WATER);    // a two-deep pool he has fallen into
        t.fill(0, -1, -1, 3, -1, 1, WATER);
        t.fill(0, -2, -1, 3, -2, 1, STONE);
        Path p = new AStar(t).find(new BlockPos(1, -1, 0), new BlockPos(6, 1, 0));
        assertFalse(p.partial());
        assertEquals(new BlockPos(6, 1, 0), p.end());
        assertTrue(moves(p).contains(MoveType.SWIM));
        assertEquals(MoveType.SWIM, p.step(0).via());
    }

    @Test
    void climbsALadder() {
        GridTerrain t = new GridTerrain();
        t.fill(0, 0, 0, 2, 0, 0, STONE);
        t.fill(3, 0, 0, 3, 4, 0, STONE);     // a wall with a ladder on its west face
        t.fill(2, 1, 0, 2, 4, 0, LADDER);
        t.fill(4, 4, 0, 6, 4, 0, STONE);     // roof, level with the wall top, to walk along
        Path p = new AStar(t).find(new BlockPos(0, 1, 0), new BlockPos(6, 5, 0));
        assertFalse(p.partial());
        assertEquals(new BlockPos(6, 5, 0), p.end());
        assertEquals(3, moves(p).stream().filter(m -> m == MoveType.CLIMB_UP).count());
        assertTrue(moves(p).contains(MoveType.STEP_UP));
    }

    @Test
    void walksThroughADoorAndNotAWall() {
        GridTerrain t = GridTerrain.floor(10);
        t.fill(3, 1, -10, 3, 3, 10, STONE);  // a wall across the world
        t.set(3, 1, 0, DOOR).set(3, 2, 0, DOOR);
        Path p = new AStar(t).find(new BlockPos(0, 1, 0), new BlockPos(6, 1, 0));
        assertFalse(p.partial());
        assertTrue(moves(p).contains(MoveType.DOOR));
        assertTrue(p.steps().stream().anyMatch(s -> s.pos().equals(new BlockPos(3, 1, 0))));

        AStar.Options noDoors = new AStar.Options(4000, 3, 12, true, true, false);
        assertTrue(new AStar(t, noDoors).find(new BlockPos(0, 1, 0), new BlockPos(6, 1, 0)).partial());
    }

    @Test
    void neverStepsInLava() {
        GridTerrain t = GridTerrain.floor(10);
        t.fill(2, 1, -1, 2, 1, 1, LAVA);     // a lava strip; the way round is through z=±2
        Path p = new AStar(t).find(new BlockPos(0, 1, 0), new BlockPos(4, 1, 0));
        assertFalse(p.partial());
        assertFalse(p.steps().stream().anyMatch(s -> t.isHazard(s.pos()) || t.isHazard(s.pos().below())));
        assertTrue(p.steps().stream().anyMatch(s -> Math.abs(s.pos().z()) >= 2));
    }

    @Test
    void keepsAwayFromEdgesWhenItCosts() {
        GridTerrain t = new GridTerrain();
        t.fill(0, 0, 0, 10, 0, 3, STONE);    // a strip with a cliff along z=-1 (nothing below)
        Path p = new AStar(t).find(new BlockPos(0, 1, 0), new BlockPos(10, 1, 0));
        assertFalse(p.partial());
        // Both ends are on the edge, but the middle should step in from it.
        assertTrue(p.steps().stream().anyMatch(s -> s.pos().z() >= 1));
    }

    @Test
    void outOfBudgetGivesAPartialPathTowardTheGoal() {
        GridTerrain t = GridTerrain.floor(60);
        Path p = new AStar(t, AStar.Options.defaults().withBudget(30))
                .find(new BlockPos(0, 1, 0), new BlockPos(50, 1, 0));
        assertTrue(p.partial());
        assertFalse(p.isEmpty());
        assertTrue(p.end().x() > 3, "went " + p.end());
        assertTrue(p.nodesExpanded() <= 31);
    }

    @Test
    void noStandableStartGivesEmpty() {
        GridTerrain t = new GridTerrain();
        Path p = new AStar(t).find(new BlockPos(0, 50, 0), new BlockPos(5, 50, 0));
        assertTrue(p.isEmpty());
        assertTrue(p.partial());
    }

    @Test
    void alreadyThereIsAnEmptyCompletePath() {
        GridTerrain t = GridTerrain.floor(5);
        Path p = new AStar(t).find(new BlockPos(1, 1, 1), new BlockPos(1, 1, 1));
        assertTrue(p.isEmpty());
        assertFalse(p.partial());
    }
}
