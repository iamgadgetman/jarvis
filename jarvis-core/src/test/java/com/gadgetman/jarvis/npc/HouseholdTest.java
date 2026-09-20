package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.core.platform.Site;
import com.gadgetman.jarvis.core.platform.events.DeathEvent;
import com.gadgetman.jarvis.core.platform.events.OwnerDamagedEvent;
import com.gadgetman.jarvis.core.testing.FakeOwner;
import com.gadgetman.jarvis.core.testing.FakeWorld;
import com.gadgetman.jarvis.core.testing.Fixture;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.Ids;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.core.world.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The DepositManager, EscortService, RecoveryService and Defender on the fake world. */
class HouseholdTest {

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
    class Deposits {

        @Test
        @DisplayName("the chest you are looking at becomes the deposit chest")
        void setChestNeedsAChestInView() {
            f.core.butlers().deposits().setChest(p);
            assertTrue(p.wasTold("Do look at a chest"));
            assertFalse(f.core.butlers().deposits().hasChest(p));

            f.world.container(new BlockPos(2, 64, 0), new FakeWorld.FakeContainer(27));
            p.targetBlock = new BlockPos(2, 64, 0);
            f.core.butlers().deposits().setChest(p);

            assertTrue(f.core.butlers().deposits().hasChest(p));
            assertTrue(p.wasTold("where I'll deposit"));
        }

        @Test
        @DisplayName("a deposit run walks to the chest and empties his bags into it")
        void depositEmptiesTheBags() {
            FakeWorld.FakeContainer chest = new FakeWorld.FakeContainer(27);
            f.world.container(new BlockPos(6, 64, 0), chest);
            p.targetBlock = new BlockPos(6, 64, 0);
            f.core.butlers().deposits().setChest(p);
            f.butler(p).inventory.set(1, Item.of(Ids.COBBLESTONE, 40));
            f.butler(p).inventory.set(2, Item.of(Ids.IRON_ORE, 3));

            f.core.butlers().deposits().deposit(p);
            f.tick(60);

            assertEquals(40, chest.count(Ids.COBBLESTONE));
            assertEquals(3, chest.count(Ids.IRON_ORE));
            assertEquals(0, f.core.butlers().lootSlotsUsed(p));
            assertTrue(p.wasTold("Deposited 43 items"), String.join("\n", p.plainMessages()));
            assertTrue(f.world.sounds.contains(Ids.SOUND_BLOCK_CHEST_OPEN));
        }

        @Test
        @DisplayName("the register survives a restart")
        void chestIsPersisted() throws IOException {
            f.world.container(new BlockPos(2, 64, 0), new FakeWorld.FakeContainer(27));
            p.targetBlock = new BlockPos(2, 64, 0);
            f.core.butlers().deposits().setChest(p);

            DepositManager reloaded = new DepositManager(f.platform, f.core.butlers());
            Optional<Site> chest = reloaded.getChest(p);

            assertTrue(chest.isPresent());
            assertEquals(new BlockPos(2, 64, 0), chest.get().block());
        }
    }

    @Nested
    class Escort {

        @Test
        @DisplayName("home must be set before he can lead you there")
        void needsAHome() {
            f.core.butlers().getEscortService().takeHome(p);
            assertTrue(p.wasTold("No home on record"));
        }

        @Test
        @DisplayName("he announces the walk and the arrival")
        void leadsYouHome() {
            f.core.butlers().getEscortService().setHome(p);
            assertTrue(p.wasTold("Home noted"));
            p.pos = new Vec3(30.5, 64, 0.5);
            f.butler(p).pos = p.pos;
            p.clearMessages();

            f.core.butlers().getEscortService().takeHome(p);
            assertTrue(p.wasTold("This way, sir"));
            f.tick(40);
            assertTrue(f.butler(p).pos.distance(new Vec3(30.5, 64, 0.5)) > 0.5, "he set off ahead of you");

            p.pos = new Vec3(0.5, 64, 0.5);     // the player walks the rest
            f.tick(40);

            assertTrue(p.wasTold("Home, sir"), String.join("\n", p.plainMessages()));
            assertEquals(0, f.core.butlers().getActiveTaskCount());
        }
    }

    @Nested
    class Recovery {

        @Test
        @DisplayName("a death is remembered and the drops fetched back to you")
        void recoversDrops() {
            Vec3 where = new Vec3(10.5, 64, 0.5);
            f.platform.events().publish(new DeathEvent(p, new Site(f.world, where),
                    List.of(Item.of(Ids.DIAMOND_PICKAXE)), false, "alice fell"));
            assertTrue(f.core.butlers().getRecoveryService().hasDeathPoint(p));
            f.tick(70);
            assertTrue(p.wasTold("Shall I retrieve your effects"), String.join("\n", p.plainMessages()));
            f.world.dropItem(where, Item.of(Ids.DIAMOND_PICKAXE));
            f.world.dropItem(where.add(1, 0, 0), Item.of(Ids.COBBLESTONE, 7));

            f.core.butlers().getRecoveryService().recover(p);
            f.tick(200);

            assertTrue(p.given.stream().anyMatch(i -> i.is(Ids.DIAMOND_PICKAXE)), "the pickaxe came back");
            assertTrue(p.given.stream().anyMatch(i -> i.is(Ids.COBBLESTONE)), "on a recovery even cobble counts");
            assertTrue(p.wasTold("Your effects, sir"), String.join("\n", p.plainMessages()));
            assertFalse(f.core.butlers().getRecoveryService().hasDeathPoint(p));
        }

        @Test
        @DisplayName("nothing to recover is said plainly")
        void nothingOnRecord() {
            f.core.butlers().getRecoveryService().recover(p);
            assertTrue(p.wasTold("no death site on record"));
        }
    }

    @Nested
    class Guard {

        @Test
        @DisplayName("weapons free: a hostile within reach is cut down and credited")
        void aggressiveEngagesHostiles() {
            FakeWorld.FakeEntity zombie = f.world.spawnEntity(FakeWorld.FakeEntity.hostile("minecraft:zombie", new Vec3(2.5, 64, 0.5)));
            zombie.health = 6;

            f.core.butlers().guard(p, "aggressive");
            assertTrue(p.wasTold("weapons free"));
            f.tick(80);

            assertFalse(zombie.alive, "the zombie is dead");
            assertEquals(1, f.core.progression().recordOf(p).threatsFelled());
        }

        @Test
        @DisplayName("defensive: he only answers what attacks you")
        void defensiveWaitsForAggression() {
            FakeWorld.FakeEntity zombie = f.world.spawnEntity(FakeWorld.FakeEntity.hostile("minecraft:zombie", new Vec3(2.5, 64, 0.5)));
            zombie.health = 6;

            f.core.butlers().guard(p, "defensive");
            f.tick(40);
            assertTrue(zombie.alive, "an idle zombie is left alone");

            f.platform.events().publish(new OwnerDamagedEvent(p, Optional.of(zombie), 2.0));
            f.tick(80);

            assertFalse(zombie.alive, "the attacker is dealt with");
        }

        @Test
        @DisplayName("the stance can be changed in place")
        void stanceChangesInPlace() {
            f.core.butlers().guard(p, "defensive");
            f.core.butlers().guard(p, "passive");
            assertTrue(p.wasTold("Observing only"));
            assertEquals(1, f.core.butlers().getActiveTaskCount(), "still the same guard");
        }
    }
}
