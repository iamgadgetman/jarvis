package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.core.testing.FakeButlers;
import com.gadgetman.jarvis.core.testing.FakeOwner;
import com.gadgetman.jarvis.core.testing.Fixture;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.BlockState;
import com.gadgetman.jarvis.core.world.Ids;
import com.gadgetman.jarvis.core.world.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Lumberjack, the Farmer and the Lamplighter, driven on the fake world. */
class GroundskeepingTest {

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

    @Nested
    class Lumberjack {

        private void plantOak(int x, int z, int height) {
            f.world.set(x, 63, z, Ids.DIRT);
            for (int y = 64; y < 64 + height; y++) f.world.set(x, y, z, Ids.OAK_LOG);
            f.world.set(x, 64 + height, z, "minecraft:oak_leaves");
        }

        @Test
        @DisplayName("he fells the tree, collects the logs and replants a sapling")
        void fellsAndReplants() {
            plantOak(5, 0, 4);

            f.core.butlers().chop(p, 1);
            f.tick(200);

            assertEquals(0, f.world.count(Ids.OAK_LOG), "every log came down");
            assertEquals(Ids.OAK_SAPLING, f.world.block(new BlockPos(5, 64, 0)).id(), "a sapling in the stump's place");
            assertTrue(p.wasTold("Timber work complete"), String.join("\n", p.plainMessages()));
            assertTrue(f.butler(p).inventory.stream().anyMatch(i -> i.is(Ids.OAK_LOG)), "logs in his bags");
            assertEquals(1, f.core.progression().recordOf(p).treesFelled());
        }

        @Test
        @DisplayName("a log with no leaves is a fence post, not a tree")
        void ignoresLogsThatAreNotTrees() {
            f.world.set(5, 63, 0, Ids.DIRT);
            f.world.set(5, 64, 0, Ids.OAK_LOG);

            f.core.butlers().chop(p, 1);
            f.tick(40);

            assertEquals(1, f.world.count(Ids.OAK_LOG));
            assertTrue(p.wasTold("Timber work complete"));
        }
    }

    @Nested
    class Farmer {

        @Test
        @DisplayName("ripe wheat is harvested and replanted from the seeds he carries")
        void harvestsRipeWheatAndReplants() {
            f.world.set(3, 63, 0, Ids.FARMLAND);
            f.world.set(new BlockPos(3, 64, 0), BlockState.of(Ids.WHEAT).with("age", 7));
            f.world.set(4, 63, 0, Ids.FARMLAND);
            f.world.set(new BlockPos(4, 64, 0), BlockState.of(Ids.WHEAT).with("age", 3));   // still growing
            f.butler(p).inventory.set(1, Item.of(Ids.WHEAT_SEEDS, 4));

            f.core.butlers().farm(p, "wheat", false);
            f.tick(120);

            assertEquals(0, f.world.block(new BlockPos(3, 64, 0)).intProp("age", -1), "replanted at age 0");
            assertEquals(3, f.world.block(new BlockPos(4, 64, 0)).intProp("age", -1), "the green one left alone");
            assertTrue(p.wasTold("Harvest complete"), String.join("\n", p.plainMessages()));
            assertEquals(1, f.core.progression().recordOf(p).cropsHarvested());
            assertEquals(3, f.butler(p).inventory.get(1).count(), "one seed used");
        }

        @Test
        @DisplayName("nothing ripe means nothing to do")
        void nothingRipe() {
            f.core.butlers().farm(p, null, false);

            assertTrue(p.wasTold("Nothing here is ready"));
        }
    }

    @Nested
    class Lamplighter {

        @Test
        @DisplayName("dark ground gets a grid of torches")
        void lightsTheGrounds() {
            f.core.butlers().light(p, 8, "torch", 4);
            f.tick(400);

            assertTrue(f.world.count(Ids.TORCH) >= 9, "a grid of torches, got " + f.world.count(Ids.TORCH));
            assertTrue(p.wasTold("The grounds are lit"), String.join("\n", p.plainMessages()));
        }

        @Test
        @DisplayName("already-lit ground is left alone")
        void skipsLitGround() {
            f.world.defaultBlockLight = 14;

            f.core.butlers().light(p, 8, "torch", 4);
            f.tick(20);

            assertEquals(0, f.world.count(Ids.TORCH));
            assertTrue(p.wasTold("already well lit"));
        }
    }
}
