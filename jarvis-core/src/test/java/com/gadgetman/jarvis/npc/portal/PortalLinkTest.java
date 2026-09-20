package com.gadgetman.jarvis.npc.portal;

import com.gadgetman.jarvis.npc.portal.PortalLink.Coords;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The 1:8 mapping, and the one place it is easy to get wrong.
 */
class PortalLinkTest {

    @Nested
    @DisplayName("the scale")
    class Scale {

        @Test @DisplayName("eight overworld blocks are one nether block")
        void overworldToNether() {
            Coords there = PortalLink.toNether(1600, 64, 800);
            assertEquals(200, there.x());
            assertEquals(100, there.z());
        }

        @Test @DisplayName("and back the other way")
        void netherToOverworld() {
            Coords there = PortalLink.toOverworld(200, 64, 100);
            assertEquals(1600, there.x());
            assertEquals(800, there.z());
        }

        @Test @DisplayName("y is carried across untouched")
        void heightIsNotScaled() {
            assertEquals(72, PortalLink.toNether(80, 72, 80).y());
            assertEquals(72, PortalLink.toOverworld(10, 72, 10).y());
        }

        @Test @DisplayName("counterpart picks the direction from the side you are on")
        void counterpartFollowsTheSide() {
            assertEquals(PortalLink.toNether(1600, 64, 800),
                    PortalLink.counterpart(1600, 64, 800, false));
            assertEquals(PortalLink.toOverworld(200, 64, 100),
                    PortalLink.counterpart(200, 64, 100, true));
        }
    }

    @Nested
    @DisplayName("negative coordinates — where truncation lies")
    class Negatives {

        @Test @DisplayName("a real base west of spawn maps by flooring, not truncation")
        void theWestOfSpawnCase() {
            // -1227 / 8 truncates to -153; the game floors to -154, and eight
            // blocks is a different chunk. This is the whole reason floorDiv is
            // in there.
            Coords there = PortalLink.toNether(-1227, 127, 587);
            assertEquals(-154, there.x());
            assertEquals(73, there.z());
        }

        @Test @DisplayName("just west of the axis still floors")
        void justPastZero() {
            assertEquals(-1, PortalLink.toNether(-1, 64, -1).x());
            assertEquals(-1, PortalLink.toNether(-8, 64, -8).x());
            assertEquals(-2, PortalLink.toNether(-9, 64, -9).x());
        }

        @Test @DisplayName("the round trip lands inside the block it came from")
        void roundTripStaysInRange() {
            for (int x = -2000; x <= 2000; x += 37) {
                Coords nether = PortalLink.toNether(x, 64, x);
                Coords back = PortalLink.toOverworld(nether.x(), 64, nether.z());
                int delta = x - back.x();
                assertEquals(true, delta >= 0 && delta < PortalLink.SCALE,
                        "x=" + x + " came back as " + back.x());
            }
        }
    }

    @Nested
    @DisplayName("height clamping")
    class Clamping {

        @Test @DisplayName("an overworld portal above the nether roof arrives under it")
        void clampsToDestinationCeiling() {
            assertEquals(127, PortalLink.clampY(200, 0, 127));
        }

        @Test @DisplayName("and one below the floor arrives on it")
        void clampsToDestinationFloor() {
            assertEquals(-64, PortalLink.clampY(-100, -64, 319));
        }

        @Test @DisplayName("anything in range is left alone")
        void leavesSaneHeightsAlone() {
            assertEquals(64, PortalLink.clampY(64, -64, 319));
        }
    }
}
