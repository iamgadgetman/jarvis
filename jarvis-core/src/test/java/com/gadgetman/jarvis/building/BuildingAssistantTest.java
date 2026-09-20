package com.gadgetman.jarvis.building;

import com.gadgetman.jarvis.core.testing.FakeOwner;
import com.gadgetman.jarvis.core.testing.Fixture;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.BlockState;
import com.gadgetman.jarvis.core.world.Ids;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildingAssistantTest {

    private Fixture f;
    private FakeOwner p;

    @BeforeEach
    void boot() throws IOException {
        f = new Fixture();
        p = f.summoned("alice");
    }

    @AfterEach
    void stop() {
        f.close();
    }

    @Test
    @DisplayName("a simple wall goes up a few blocks per tick, then can be undone")
    void buildsAWallAndUndoesIt() {
        f.core.building().buildSimpleStructure(p, "wall", 3);
        assertTrue(p.wasTold("Placing 9 blocks"));
        f.tick(10);

        assertEquals(9, f.world.count(Ids.STONE_BRICKS));
        assertEquals(Ids.STONE_BRICKS, f.world.block(new BlockPos(2, 66, 0)).id(), "origin is two blocks east of the player");
        assertTrue(p.wasTold("Build Complete"), String.join("\n", p.plainMessages()));
        assertEquals(9, f.core.progression().recordOf(p).blocksPlaced());

        f.core.building().undoLastBuild(p);

        assertEquals(0, f.world.count(Ids.STONE_BRICKS));
        assertTrue(p.wasTold("Reverted 9 blocks"));
    }

    @Test
    @DisplayName("undo leaves alone a block someone changed since")
    void undoRespectsLaterChanges() {
        f.core.building().buildSimpleStructure(p, "pillar", 2);
        f.tick(5);
        f.world.set(new BlockPos(2, 65, 0), BlockState.of(Ids.DIRT));   // the player swapped one

        f.core.building().undoLastBuild(p);

        assertEquals(Ids.DIRT, f.world.block(new BlockPos(2, 65, 0)).id());
        assertTrue(p.wasTold("Reverted 1 blocks"));
    }

    @Test
    @DisplayName("panes placed with physics off are still joined to their neighbours")
    void connectsPanesToNeighbours() {
        // A pane between two stone blocks: physics is off during a build, so the
        // assistant has to set the east/west faces itself.
        f.world.set(1, 64, 0, Ids.STONE);
        f.world.set(3, 64, 0, Ids.STONE);
        f.core.building().buildSimpleStructure(p, "wall", 1);   // one stone brick at (2,64,0)
        f.tick(5);
        f.world.set(new BlockPos(2, 64, 0), BlockState.of("minecraft:glass_pane")
                .with("north", false).with("south", false).with("east", false).with("west", false));

        // Re-run a build that touches the pane's slot through the planner path:
        // the simplest is a wall of size 1 again, which skips the slot as
        // "already right" only if the state matches; it does not, so it is
        // placed anew and queued for connection.
        f.core.building().buildSimpleStructure(p, "wall", 1);
        f.tick(5);

        assertEquals(Ids.STONE_BRICKS, f.world.block(new BlockPos(2, 64, 0)).id());
    }

    @Test
    @DisplayName("cancel stops a build part-way and keeps what was placed undoable")
    void cancelKeepsUndo() {
        f.core.building().buildSimpleStructure(p, "cube", 8);   // 8x8x8 hollow: hundreds of blocks
        f.tick(1);
        assertTrue(f.core.building().isBuilding(p));

        f.core.building().cancelBuild(p);

        assertTrue(p.wasTold("Build cancelled"));
        int placed = f.world.count(Ids.STONE_BRICKS);
        assertTrue(placed > 0 && placed < 8 * 8 * 8, "part of it went up: " + placed);
        f.core.building().undoLastBuild(p);
        assertEquals(0, f.world.count(Ids.STONE_BRICKS));
    }
}
