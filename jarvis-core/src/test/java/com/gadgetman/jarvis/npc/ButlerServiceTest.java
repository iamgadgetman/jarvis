package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.core.testing.FakeButlers;
import com.gadgetman.jarvis.core.testing.FakeOwner;
import com.gadgetman.jarvis.core.testing.Fixture;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.Ids;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.core.world.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerServiceTest {

    private Fixture f;

    @BeforeEach
    void boot() throws IOException {
        f = new Fixture();
    }

    @AfterEach
    void stop() {
        f.close();
    }

    @Test
    @DisplayName("summon puts him on the floor beside the player, pickaxe in hand")
    void summonSpawnsHimBesideThePlayer() {
        FakeOwner p = f.player("alice");

        f.core.butlers().summon(p);

        FakeButlers.State b = f.butler(p);
        assertNotNull(b, "he should exist after a summon");
        assertTrue(b.spawned);
        assertEquals(64, b.pos.y(), 0.01, "standing on the y=63 floor");
        assertTrue(b.pos.distance(p.pos) <= 5, "within a few blocks of the player");
        assertTrue(b.invulnerable, "protected from harm");
        assertTrue(Ids.key(b.inventory.get(0).id()).endsWith("_pickaxe"), "a pickaxe in slot 0, was " + b.inventory.get(0));
        assertTrue(p.wasTold("At your service"));
        assertTrue(f.world.sounds.contains(Ids.SOUND_BLOCK_BELL_USE));
    }

    @Test
    @DisplayName("summoning twice is refused politely")
    void summonTwiceIsRefused() {
        FakeOwner p = f.summoned("alice");

        f.core.butlers().summon(p);

        assertTrue(p.wasTold("already here"));
    }

    @Test
    @DisplayName("summon looks for solid ground when the player is over a hole")
    void summonAvoidsAHole() {
        FakeOwner p = f.player("alice");
        f.world.setBlock(new BlockPos(0, 63, 0), com.gadgetman.jarvis.core.world.BlockState.AIR, false);

        f.core.butlers().summon(p);

        FakeButlers.State b = f.butler(p);
        assertTrue(f.world.isSolid(b.pos.block().below()), "spawned on something solid: " + b.pos);
    }

    @Test
    @DisplayName("dismiss drops his loot at his feet and forgets him")
    void dismissDropsLootAndDespawns() {
        FakeOwner p = f.summoned("alice");
        FakeButlers.State b = f.butler(p);
        b.inventory.set(1, Item.of(Ids.COBBLESTONE, 12));

        f.core.butlers().dismiss(p);

        assertFalse(f.core.butlers().exists(p));
        assertEquals(1, f.world.entities.size(), "one dropped stack");
        assertEquals(Ids.COBBLESTONE, f.world.entities.get(0).item.id());
        assertTrue(p.wasTold("Until next time"));
    }

    @Test
    @DisplayName("tasks need him summoned first")
    void tasksNeedHimSummoned() {
        FakeOwner p = f.player("alice");

        f.core.butlers().chop(p, 3);

        assertTrue(p.wasTold("Summon me first"));
        assertEquals(0, f.core.butlers().getActiveTaskCount());
    }

    @Test
    @DisplayName("he sweeps nearby drops into his bags, leaving the junk")
    void pickupSweepsDropsIntoBags() {
        FakeOwner p = f.summoned("alice");
        FakeButlers.State b = f.butler(p);
        f.world.dropItem(b.pos.add(1, 0, 0), Item.of(Ids.DIAMOND_ORE, 3));
        f.world.dropItem(b.pos.add(-1, 0, 0), Item.of(Ids.COBBLESTONE, 5));

        f.core.butlers().pickupNearbyItems(p, b.pos);

        assertEquals(1, f.core.butlers().lootSlotsUsed(p), "the ore, not the cobble");
        assertTrue(b.inventory.stream().anyMatch(i -> i.is(Ids.DIAMOND_ORE) && i.count() == 3));
        assertTrue(f.world.entities.stream().filter(e -> e.alive).allMatch(e -> e.item.is(Ids.COBBLESTONE)),
                "the cobblestone stays on the ground");
    }

    @Test
    @DisplayName("stop cancels the running task and says so")
    void stopCancelsTheTask() {
        FakeOwner p = f.summoned("alice");
        f.core.butlers().follow(p);
        assertEquals(1, f.core.butlers().getActiveTaskCount());

        f.core.butlers().stop(p);

        assertEquals(0, f.core.butlers().getActiveTaskCount());
    }

    @Test
    @DisplayName("a quitting player takes his butler with him")
    void quitDismissesTheButler() {
        FakeOwner p = f.summoned("alice");

        f.platform.events().publish(new com.gadgetman.jarvis.core.platform.events.QuitEvent(p));

        assertFalse(f.core.butlers().exists(p));
    }

    @Test
    @DisplayName("distance to owner is measured in the same world only")
    void distanceToOwner() {
        FakeOwner p = f.summoned("alice");
        f.butler(p).pos = new Vec3(3.5, 64, 4.5);

        assertEquals(5.0, f.core.butlers().distanceToOwner(p), 0.01);

        p.world = com.gadgetman.jarvis.core.world.WorldId.NETHER;
        assertEquals(Double.MAX_VALUE, f.core.butlers().distanceToOwner(p));
    }
}
