package com.gadgetman.jarvis.npc.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A portal is many blocks and a memory is a file. The rules that keep the first
 * from flooding the second.
 */
class PortalSightingTest {

    private static PortalSighting at(int x, int y, int z, long when) {
        return new PortalSighting("world", x, y, z, when);
    }

    @Nested
    @DisplayName("a frame is one portal")
    class Merging {

        @Test @DisplayName("the blocks of one frame collapse to a single entry")
        void frameBlocksMerge() {
            List<PortalSighting> known = new ArrayList<>();
            // A 4x5 frame: the interior is six blocks, all within a metre or two.
            for (int y = 0; y < 3; y++) {
                for (int x = 0; x < 2; x++) {
                    known = PortalSighting.remember(known, at(100 + x, 64 + y, 100, 1000),
                            PortalSighting.DEFAULT_LIMIT, PortalSighting.MERGE_RADIUS);
                }
            }
            assertEquals(1, known.size());
        }

        @Test @DisplayName("a merge keeps the original position rather than wandering")
        void mergeDoesNotDrift() {
            List<PortalSighting> known = List.of(at(100, 64, 100, 1000));
            known = PortalSighting.remember(known, at(104, 64, 100, 2000),
                    PortalSighting.DEFAULT_LIMIT, PortalSighting.MERGE_RADIUS);
            assertEquals(1, known.size());
            assertEquals(100, known.get(0).x());
            assertEquals(2000, known.get(0).seenAt(), "the timestamp should refresh");
        }

        @Test @DisplayName("a portal down the road is a different portal")
        void distantPortalIsSeparate() {
            List<PortalSighting> known = List.of(at(100, 64, 100, 1000));
            known = PortalSighting.remember(known, at(400, 64, 100, 2000),
                    PortalSighting.DEFAULT_LIMIT, PortalSighting.MERGE_RADIUS);
            assertEquals(2, known.size());
        }

        @Test @DisplayName("same coordinates in another world are not the same portal")
        void worldsDoNotMerge() {
            List<PortalSighting> known = List.of(at(100, 64, 100, 1000));
            known = PortalSighting.remember(known,
                    new PortalSighting("world_nether", 100, 64, 100, 2000),
                    PortalSighting.DEFAULT_LIMIT, PortalSighting.MERGE_RADIUS);
            assertEquals(2, known.size());
        }

        @Test @DisplayName("isNew agrees with what remember does")
        void isNewMatchesMerge() {
            List<PortalSighting> known = List.of(at(100, 64, 100, 1000));
            assertFalse(PortalSighting.isNew(known, at(102, 64, 100, 2000), PortalSighting.MERGE_RADIUS));
            assertTrue(PortalSighting.isNew(known, at(400, 64, 100, 2000), PortalSighting.MERGE_RADIUS));
        }
    }

    @Nested
    @DisplayName("the list is bounded")
    class Bounded {

        @Test @DisplayName("past the limit the oldest is forgotten, not the nearest")
        void oldestGoesFirst() {
            List<PortalSighting> known = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                known = PortalSighting.remember(known, at(i * 100, 64, 0, 1000 + i), 3,
                        PortalSighting.MERGE_RADIUS);
            }
            assertEquals(3, known.size());
            assertEquals(1002, known.get(0).seenAt());
            assertEquals(1004, known.get(2).seenAt());
        }

        @Test @DisplayName("re-seeing an old portal saves it from being dropped")
        void refreshingKeepsIt() {
            List<PortalSighting> known = new ArrayList<>();
            known = PortalSighting.remember(known, at(0, 64, 0, 1000), 2, PortalSighting.MERGE_RADIUS);
            known = PortalSighting.remember(known, at(500, 64, 0, 1001), 2, PortalSighting.MERGE_RADIUS);
            known = PortalSighting.remember(known, at(0, 64, 0, 1002), 2, PortalSighting.MERGE_RADIUS);
            known = PortalSighting.remember(known, at(900, 64, 0, 1003), 2, PortalSighting.MERGE_RADIUS);

            assertEquals(2, known.size());
            assertNotNull(PortalSighting.nearest(known, "world", 0, 64, 0));
            assertEquals(0, PortalSighting.nearest(known, "world", 0, 64, 0).x(),
                    "the refreshed portal should have outlived the one seen once");
        }
    }

    @Nested
    @DisplayName("finding the nearest")
    class Nearest {

        @Test @DisplayName("distance is measured in three dimensions")
        void picksTheClosest() {
            List<PortalSighting> known = List.of(at(100, 64, 0, 1), at(0, 200, 0, 2));
            assertEquals(100, PortalSighting.nearest(known, "world", 90, 64, 0).x());
        }

        @Test @DisplayName("portals in another world are not candidates")
        void ignoresOtherWorlds() {
            List<PortalSighting> known = List.of(new PortalSighting("world_nether", 0, 64, 0, 1));
            assertNull(PortalSighting.nearest(known, "world", 0, 64, 0));
        }

        @Test @DisplayName("knowing none is not an error")
        void emptyIsNull() {
            assertNull(PortalSighting.nearest(List.of(), "world", 0, 64, 0));
        }
    }
}
