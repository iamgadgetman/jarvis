package com.gadgetman.jarvis.building;

import com.gadgetman.jarvis.JarvisCore;
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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UndoServiceTest {

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

    private void run(String... args) {
        f.core.commands().jarvis(p, Optional.of(p), List.of(args), false);
    }

    @Test
    @DisplayName("a block the player placed on the build is left alone and counted as skipped")
    void skipsWhatThePlayerChanged() {
        f.core.building().buildSimpleStructure(p, "wall", 2);   // 4 stone bricks
        f.tick(5);
        BlockPos theirs = new BlockPos(2, 65, 0);
        f.world.set(theirs, BlockState.of(Ids.DIRT));

        run("undo");
        f.tick(5);

        assertEquals(0, f.world.count(Ids.STONE_BRICKS));
        assertEquals(Ids.DIRT, f.world.block(theirs).id());
        assertTrue(p.wasTold("Restored 3 blocks. Skipped 1 changed since."), String.join("\n", p.plainMessages()));
    }

    @Test
    @DisplayName("undo n takes down the last n jobs, newest first, and list shows what is left")
    void undoesSeveralAndLists() {
        f.core.building().buildSimpleStructure(p, "pillar", 3);
        f.tick(5);
        f.core.building().buildSimpleStructure(p, "floor", 2);
        f.tick(5);
        p.pos = p.pos.add(0, 0, 10);   // somewhere clear for the third
        f.core.building().buildSimpleStructure(p, "wall", 2);
        f.tick(5);
        assertEquals(3, f.core.undo().historySize(p));

        p.clearMessages();
        run("undo", "list");
        List<String> said = p.plainMessages();
        assertTrue(said.get(1).contains("1. build: wall"), String.join("\n", said));
        assertTrue(said.get(2).contains("2. build: floor"), String.join("\n", said));
        assertTrue(said.get(3).contains("3. build: pillar"), String.join("\n", said));

        run("undo", "2");
        f.tick(10);
        assertEquals(1, f.core.undo().historySize(p));
        assertEquals(0, f.world.count(Ids.OAK_PLANKS), "the floor is gone");
        assertEquals(3, f.world.count(Ids.STONE_BRICKS), "the wall is gone and the pillar is still up");
    }

    @Test
    @DisplayName("redo puts back what undo took down, and it can be undone again")
    void redoesOneLevel() {
        f.core.building().buildSimpleStructure(p, "wall", 2);
        f.tick(5);
        run("undo");
        f.tick(5);
        assertEquals(0, f.world.count(Ids.STONE_BRICKS));

        run("redo");
        f.tick(5);
        assertEquals(4, f.world.count(Ids.STONE_BRICKS));
        assertTrue(p.wasTold("Replaced 4 blocks."), String.join("\n", p.plainMessages()));
        assertEquals(1, f.core.undo().historySize(p));

        run("undo");
        f.tick(5);
        assertEquals(0, f.world.count(Ids.STONE_BRICKS));
    }

    @Test
    @DisplayName("/jarvis build undo still works as an alias")
    void buildUndoAlias() {
        f.core.building().buildSimpleStructure(p, "pillar", 2);
        f.tick(5);
        run("build", "undo");
        f.tick(5);
        assertEquals(0, f.world.count(Ids.STONE_BRICKS));
    }

    @Test
    @DisplayName("history survives a restart and can still be undone")
    void survivesARestart() {
        f.core.building().buildSimpleStructure(p, "pillar", 3);
        f.tick(5);
        f.core.shutdown();

        JarvisCore again = new JarvisCore(f.platform, "test", f.platform.butlers(), f.actions);
        again.start();
        try {
            assertEquals(1, again.undo().historySize(p));
            p.clearMessages();
            again.commands().jarvis(p, Optional.of(p), List.of("undo", "list"), false);
            assertTrue(p.wasTold("build: pillar"), String.join("\n", p.plainMessages()));

            again.commands().jarvis(p, Optional.of(p), List.of("undo"), false);
            f.tick(5);
            assertEquals(0, f.world.count(Ids.STONE_BRICKS));
        } finally {
            again.shutdown();
        }
    }

    @Test
    @DisplayName("history keeps only build.undo-history jobs")
    void trimsToTheLimit() throws IOException {
        f.close();
        f = new Fixture(Fixture.DEFAULT_CONFIG + """
                build:
                  undo-history: 2
                """);
        p = f.summoned("alice");
        for (int i = 0; i < 4; i++) {
            f.core.building().buildSimpleStructure(p, "pillar", 1);
            f.tick(3);
            f.world.set(new BlockPos(2, 64, 0), BlockState.AIR);   // clear it so the next one places
        }
        assertEquals(2, f.core.undo().historySize(p));
    }

    @Test
    @DisplayName("block states survive the trip through storage")
    void encodesStates() {
        BlockState stairs = BlockState.of("minecraft:oak_stairs").with("facing", "north").with("waterlogged", true);
        List<UndoService.BuildAction> in = List.of(
                new UndoService.BuildAction(new BlockPos(1, -60, 3), stairs, Ids.STONE),
                new UndoService.BuildAction(new BlockPos(-5, 70, 0), BlockState.AIR, Ids.OAK_PLANKS));
        assertEquals(in, UndoService.decode(UndoService.encode(in)));
    }

    @Test
    @DisplayName("'put it back' and 'undo that' are undo, not a recall")
    void naturalLanguage() {
        assertTrue(com.gadgetman.jarvis.intent.IntentPipeline.isUndo("jarvis, put it back"));
        assertTrue(com.gadgetman.jarvis.intent.IntentPipeline.isUndo("undo that"));
        f.core.building().buildSimpleStructure(p, "pillar", 2);
        f.tick(5);
        f.core.intents().executeFallbackAction(p, "jarvis put it back");
        f.tick(5);
        assertEquals(0, f.world.count(Ids.STONE_BRICKS));
    }
}
