package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.core.testing.FakeOwner;
import com.gadgetman.jarvis.core.testing.Fixture;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.Ids;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ShaftDigger and the OreMiner, driven on the fake world. */
class MiningTest {

    private Fixture f;
    private FakeOwner p;

    @BeforeEach
    void boot() throws IOException {
        f = new Fixture();
        p = f.summoned("alice");
        // Solid rock under the origin, so there is something to dig into.
        f.world.fill(-4, 40, -4, 4, 62, 4, Ids.STONE);
    }

    @AfterEach
    void stop() {
        f.close();
    }

    @Nested
    class ShaftDigger {

        @Test
        @DisplayName("he digs straight down the asked depth, laddering the wall")
        void digsDown() {
            BlockPos start = f.butler(p).pos.block();

            f.core.butlers().digDown(p, 5);
            f.tick(100);

            for (int d = 1; d <= 5; d++) {
                BlockPos cell = start.offset(0, -d, 0);
                String id = f.world.block(cell).id();
                assertTrue(id.equals(Ids.AIR) || id.equals(Ids.LADDER), "cleared at " + cell + " but was " + id);
            }
            assertEquals(start.y() - 5, f.butler(p).pos.block().y(), "standing at the bottom");
            assertTrue(f.world.count(Ids.LADDER) >= 4, "ladders on the way down");
            assertTrue(p.wasTold("Shaft complete"), String.join("\n", p.plainMessages()));
        }

        @Test
        @DisplayName("lava beside the shaft is sealed before the block comes out")
        void sealsLava() {
            BlockPos start = f.butler(p).pos.block();
            f.world.set(start.offset(1, -1, 0), com.gadgetman.jarvis.core.world.BlockState.of(Ids.LAVA));

            f.core.butlers().digDown(p, 2);
            f.tick(60);

            assertEquals(Ids.COBBLESTONE, f.world.block(start.offset(1, -1, 0)).id());
            assertTrue(p.wasTold("Sealed 1 fluid pocket"));
        }
    }

    @Nested
    class OreMiner {

        @Test
        @DisplayName("an exposed ore nearby is found, mined and bagged")
        void minesExposedOre() {
            f.world.set(4, 64, 0, Ids.IRON_ORE);

            f.core.butlers().mine(p);
            f.tick(200);

            assertEquals(0, f.world.count(Ids.IRON_ORE), "the ore is gone");
            assertTrue(f.butler(p).inventory.stream().anyMatch(i -> i.is(Ids.IRON_ORE)), "and in his bags");
            assertTrue(p.wasTold("seam appears exhausted"), String.join("\n", p.plainMessages()));
        }

        @Test
        @DisplayName("an ore filter only takes what was asked for")
        void respectsTheOreFilter() {
            f.world.set(4, 64, 0, Ids.IRON_ORE);
            f.world.set(-4, 64, 0, Ids.COAL_ORE);

            f.core.butlers().mine(p, new String[]{"coal"});
            f.tick(200);

            assertEquals(1, f.world.count(Ids.IRON_ORE), "iron left alone");
            assertEquals(0, f.world.count(Ids.COAL_ORE), "coal taken");
        }
    }
}
