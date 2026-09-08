package com.gadgetman.jarvis.steward.remarks;

import com.gadgetman.jarvis.steward.remarks.RemarkDoctrine.Remark;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What deserves a remark, and — far more often — what does not.
 *
 * <p>The generation half of idle commentary was never the risk. The risk is a
 * butler who reads out your inventory every ninety seconds, and every guard
 * against that lives in {@link RemarkDoctrine}: the thresholds, the material
 * filter, and the refusal to speak about a subject that is still on cooldown.
 * None of it needs a server, so all of it is pinned here.
 */
class RemarkDoctrineTest {

    /** A player standing on the surface in daylight with nothing of note. */
    private static Observation quiet() {
        return new Observation(null, "oak planks", 12, 20, 0, "plains",
                "normal", 68, false, 15, false, false, 40.0, 0);
    }

    private static Observation carrying(String material, int count) {
        Observation q = quiet();
        return new Observation(q.heldName(), material, count, q.slotsFree(), q.xpLevel(),
                q.biome(), q.dimension(), q.y(), q.underground(), q.lightLevel(),
                q.night(), q.thundering(), q.homeDistance(), q.nearbyMonsters());
    }

    private static Remark choose(Observation o) {
        return RemarkDoctrine.choose(o, Set.of(), 0);
    }

    @Nested
    @DisplayName("silence is the default")
    class Silence {

        @Test @DisplayName("an unremarkable moment gets no remark at all")
        void nothingWorthSaying() {
            assertNull(choose(quiet()));
        }

        @Test @DisplayName("a pocketful of planks is not a haul")
        void ordinaryMaterialsAreIgnored() {
            assertNull(choose(carrying("oak planks", 512)));
        }

        @Test @DisplayName("forty-one cobblestone is not worth a sentence")
        void bulkBelowAbsurd() {
            assertNull(choose(carrying("cobblestone", 41)));
        }

        @Test @DisplayName("a null observation is simply nothing to say")
        void nullObservation() {
            assertNull(RemarkDoctrine.choose(null, Set.of(), 0));
        }
    }

    @Nested
    @DisplayName("the material filter")
    class Materials {

        @Test @DisplayName("seventy-two iron ore is the canonical remark")
        void theIronOreCase() {
            Remark remark = choose(carrying("iron ore", 72));
            assertNotNull(remark);
            assertEquals(RemarkSubject.HOARD, remark.subject());
            assertTrue(remark.line().contains("72 iron ore"), remark.line());
        }

        @Test @DisplayName("deepslate variants are judged on the ore, not the rock")
        void deepslateVariant() {
            assertEquals(RemarkDoctrine.PRECIOUS_AT, RemarkDoctrine.worthAt("deepslate diamond ore"));
        }

        @Test @DisplayName("copper is worth mentioning, but only by the crate")
        void modestMaterialsWaitLonger() {
            assertNull(choose(carrying("copper ingot", RemarkDoctrine.PRECIOUS_AT)));
            assertNotNull(choose(carrying("copper ingot", RemarkDoctrine.MODEST_AT)));
        }

        @Test @DisplayName("building blocks have no threshold at all — bulk is a different joke")
        void ordinaryHasNoWorth() {
            assertEquals(-1, RemarkDoctrine.worthAt("oak planks"));
            assertEquals(-1, RemarkDoctrine.worthAt(null));
        }

        @Test @DisplayName("an absurd quantity of cobblestone earns the other line")
        void bulkAtAbsurd() {
            Remark remark = choose(carrying("cobblestone", RemarkDoctrine.BULK_AT));
            assertNotNull(remark);
            assertEquals(RemarkSubject.BULK_HAUL, remark.subject());
        }
    }

    @Nested
    @DisplayName("cooldowns are keyed on the subject")
    class Cooldowns {

        @Test @DisplayName("the same haul four minutes later is silence, not a rewording")
        void subjectOnCooldownIsSkipped() {
            Observation hoard = carrying("iron ore", 72);
            assertEquals(RemarkSubject.HOARD, choose(hoard).subject());
            assertNull(RemarkDoctrine.choose(hoard, Set.of(RemarkSubject.HOARD), 0));
        }

        @Test @DisplayName("a smaller pile of the same ore is still the same subject")
        void cousinsAreTheSameSubject() {
            Observation smaller = carrying("iron ore", 68);
            assertNull(RemarkDoctrine.choose(smaller, Set.of(RemarkSubject.HOARD), 0));
        }

        @Test @DisplayName("with the top subject spent, a lesser one gets its turn")
        void lowerPrioritySubjectSurfaces() {
            Observation deepWithOre = new Observation(null, "iron ore", 72, 20, 0,
                    "deepslate", "normal", -45, true, 8, false, false, 40.0, 0);
            assertEquals(RemarkSubject.DEPTH, choose(deepWithOre).subject());
            assertEquals(RemarkSubject.HOARD,
                    RemarkDoctrine.choose(deepWithOre, Set.of(RemarkSubject.DEPTH), 0).subject());
        }

        @Test @DisplayName("every subject spent means nothing is said")
        void everythingSpent() {
            Observation loud = new Observation(null, "iron ore", 72, 0, 40,
                    "deepslate", "normal", -45, true, 0, true, true, 4000.0, 6);
            assertNull(RemarkDoctrine.choose(loud, Set.of(RemarkSubject.values()), 0));
        }
    }

    @Nested
    @DisplayName("the situational subjects")
    class Situational {

        @Test @DisplayName("a crowd outranks anything you are carrying")
        void dangerFirst() {
            Observation surrounded = new Observation(null, "diamond", 64, 20, 0, "plains",
                    "normal", 68, false, 15, false, false, 40.0, RemarkDoctrine.A_CROWD);
            assertEquals(RemarkSubject.COMPANY, choose(surrounded).subject());
        }

        @Test @DisplayName("one wandering zombie is not a crowd")
        void oneMonsterIsNotACrowd() {
            Observation oneMob = new Observation(null, "oak planks", 12, 20, 0, "plains",
                    "normal", 68, false, 15, false, false, 40.0, 1);
            assertNull(choose(oneMob));
        }

        @Test @DisplayName("depth only counts when he is actually under something")
        void depthNeedsCover() {
            Observation deepButOpen = new Observation(null, "oak planks", 12, 20, 0, "badlands",
                    "normal", RemarkDoctrine.DEEP_Y, false, 15, false, false, 40.0, 0);
            assertNull(choose(deepButOpen));
        }

        @Test @DisplayName("night indoors is not nightfall")
        void nightfallNeedsSky() {
            Observation nightInACave = new Observation(null, "oak planks", 12, 20, 0, "plains",
                    "normal", 40, true, 15, true, false, 40.0, 0);
            assertNull(choose(nightInACave));
        }

        @Test @DisplayName("the nether names the biome he is standing in")
        void elsewhereNamesTheBiome() {
            Observation nether = new Observation(null, "oak planks", 12, 20, 0, "crimson_forest",
                    "nether", 40, false, 15, false, false, -1.0, 0);
            Remark remark = choose(nether);
            assertEquals(RemarkSubject.ELSEWHERE, remark.subject());
            assertTrue(remark.line().contains("crimson forest") || remark.line().contains("warm work"),
                    remark.line());
        }

        @Test @DisplayName("another world is not a distance, and never reads as far from home")
        void negativeHomeDistanceIsNotFar() {
            Observation elsewhere = new Observation(null, "oak planks", 12, 20, 0, "plains",
                    "normal", 68, false, 15, false, false, -1.0, 0);
            assertNull(choose(elsewhere));
        }
    }

    @Nested
    @DisplayName("phrasing")
    class Phrasing {

        @Test @DisplayName("the variant number selects a wording and never falls off the end")
        void variantsWrapAndStaySane() {
            Observation hoard = carrying("iron ore", 72);
            for (int variant = -8; variant < 64; variant++) {
                Remark remark = RemarkDoctrine.choose(hoard, Set.of(), variant);
                assertNotNull(remark, "variant " + variant);
                assertTrue(remark.line().endsWith(".") || remark.line().endsWith("!"), remark.line());
                assertTrue(remark.line().contains("sir"), remark.line());
            }
        }
    }
}
