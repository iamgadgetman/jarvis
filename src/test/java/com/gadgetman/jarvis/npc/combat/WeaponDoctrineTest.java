package com.gadgetman.jarvis.npc.combat;

import com.gadgetman.jarvis.npc.combat.Engagement.Tactic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The archery decision table.
 *
 * <p>{@link WeaponDoctrine} is a pure function of its situation, which is the
 * whole reason it was pulled out of {@code Defender}: the half of archery that
 * is a judgement call can be checked here, exhaustively and in milliseconds,
 * leaving only the half that is physics and pathing to be proved in a world.
 *
 * <p>These are the boundaries worth pinning. The bands are the numbers most
 * likely to be tuned later, and a tuning that quietly inverts "cornered" or
 * "no line of sight" would otherwise be invisible until someone watched him
 * lose a fight.
 */
class WeaponDoctrineTest {

    /** On land, with everything except the flags a case cares about. */
    private static WeaponDoctrine.Situation onLand(double distance, boolean hasBow,
                                                   boolean sight, boolean explosive,
                                                   boolean canGiveGround) {
        return new WeaponDoctrine.Situation(distance, false, hasBow, false,
                sight, explosive, canGiveGround);
    }

    private static void expect(WeaponDoctrine.Situation s, Armament weapon, Tactic tactic) {
        Engagement got = WeaponDoctrine.choose(s);
        assertEquals(weapon, got.weapon(), "weapon");
        assertEquals(tactic, got.tactic(), "tactic");
    }

    @Nested
    @DisplayName("the stand-off band")
    class Band {
        @Test @DisplayName("beyond the far edge he closes, bow still up")
        void beyondBand() {
            expect(onLand(30, true, true, false, true), Armament.BOW, Tactic.CLOSE);
        }

        @Test @DisplayName("on the far edge he shoots")
        void farEdge() {
            expect(onLand(Armament.BOW.standOffMax(), true, true, false, true),
                    Armament.BOW, Tactic.LOOSE);
        }

        @Test @DisplayName("mid band he shoots")
        void midBand() {
            expect(onLand(15, true, true, false, true), Armament.BOW, Tactic.LOOSE);
        }

        @Test @DisplayName("on the near edge he still shoots")
        void nearEdge() {
            expect(onLand(Armament.BOW.standOffMin(), true, true, false, true),
                    Armament.BOW, Tactic.LOOSE);
        }

        @Test @DisplayName("inside the near edge he gives ground rather than trading blows")
        void insideNearEdge() {
            expect(onLand(Armament.BOW.standOffMin() - 0.1, true, true, false, true),
                    Armament.BOW, Tactic.WITHDRAW);
            expect(onLand(3, true, true, false, true), Armament.BOW, Tactic.WITHDRAW);
        }
    }

    @Nested
    @DisplayName("cornered, with nowhere to give ground")
    class Cornered {
        @Test @DisplayName("draws the sword and strikes if the thing is in reach")
        void withinReach() {
            expect(onLand(Armament.SWORD.reach() - 0.2, true, true, false, false),
                    Armament.SWORD, Tactic.STRIKE);
        }

        @Test @DisplayName("draws the sword and closes if it is not")
        void outsideReach() {
            expect(onLand(3, true, true, false, false), Armament.SWORD, Tactic.CLOSE);
            expect(onLand(5, true, true, false, false), Armament.SWORD, Tactic.CLOSE);
        }

        @Test @DisplayName("but against a creeper, a bad shot beats standing next to the blast")
        void creeperException() {
            expect(onLand(3, true, true, true, false), Armament.BOW, Tactic.LOOSE);
        }

        @Test @DisplayName("a creeper with room behind him is withdrawn from, not closed on")
        void creeperWithRoom() {
            expect(onLand(3, true, true, true, true), Armament.BOW, Tactic.WITHDRAW);
        }
    }

    @Nested
    @DisplayName("without line of sight a bow only plinks walls")
    class NoSight {
        @Test @DisplayName("he closes instead of standing at range")
        void closes() {
            expect(onLand(15, true, false, false, true), Armament.SWORD, Tactic.CLOSE);
        }

        @Test @DisplayName("and strikes normally once he is there")
        void strikes() {
            expect(onLand(2, true, false, false, true), Armament.SWORD, Tactic.STRIKE);
        }
    }

    @Nested
    @DisplayName("without the archery rank nothing changes")
    class NoBow {
        @Test @DisplayName("he closes as he always did")
        void closes() {
            expect(onLand(15, false, true, false, true), Armament.SWORD, Tactic.CLOSE);
        }

        @Test @DisplayName("sword reach is the boundary, and it is inclusive")
        void reachBoundary() {
            expect(onLand(Armament.SWORD.reach(), false, true, false, true),
                    Armament.SWORD, Tactic.STRIKE);
            expect(onLand(Armament.SWORD.reach() + 0.1, false, true, false, true),
                    Armament.SWORD, Tactic.CLOSE);
        }
    }

    @Nested
    @DisplayName("underwater, where the bow is never the answer")
    class Submerged {
        private WeaponDoctrine.Situation inWater(double distance, boolean hasTrident) {
            return new WeaponDoctrine.Situation(distance, true, true, hasTrident,
                    true, false, true);
        }

        @Test @DisplayName("he throws the trident rather than shooting")
        void throwsTrident() {
            expect(inWater(15, true), Armament.TRIDENT, Tactic.LOOSE);
        }

        @Test @DisplayName("and stabs with it up close")
        void stabs() {
            expect(inWater(2, true), Armament.TRIDENT, Tactic.STRIKE);
        }

        @Test @DisplayName("beyond throwing range he swims to it")
        void swims() {
            expect(inWater(30, true), Armament.TRIDENT, Tactic.CLOSE);
        }

        @Test @DisplayName("holding a bow but no trident, he falls back to the sword")
        void noTrident() {
            expect(inWater(15, false), Armament.SWORD, Tactic.CLOSE);
        }
    }
}
