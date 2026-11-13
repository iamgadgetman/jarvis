package com.gadgetman.jarvis.onboarding;

import com.gadgetman.jarvis.core.platform.events.ItemUseEvent;
import com.gadgetman.jarvis.core.testing.FakeOwner;
import com.gadgetman.jarvis.core.testing.Fixture;
import com.gadgetman.jarvis.ui.ControllerBell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** FB-11: the tutorial moves on what the player does, persists, and never dead-ends. */
class TutorialTest {

    private Fixture f;
    private Tutorial t;
    private FakeOwner p;

    @BeforeEach
    void boot() throws IOException {
        f = new Fixture();
        f.core.setAiCheck(() -> true);
        t = f.core.tutorial();
        p = f.player("alice");
    }

    @AfterEach
    void stop() {
        f.close();
    }

    private void cmd(String... args) {
        f.core.commands().jarvis(p, Optional.of(p), List.of(args), false);
    }

    private void reach(Milestone m) {
        f.core.milestones().reached(p, m);
        f.tick(25);   // praise and the next step come a second later
    }

    private String said() {
        return String.join("\n", p.plainMessages());
    }

    @Test
    @DisplayName("steps advance on the right milestone and not on others")
    void advancesOnTheRightThing() {
        cmd("tutorial", "start");
        assertEquals(TutorialStep.RING_BELL, t.current(p));
        assertTrue(p.wasTold("Step 1 of 7"), said());

        reach(Milestone.FOLLOWING);
        assertEquals(TutorialStep.RING_BELL, t.current(p), "following doesn't ring the bell");

        // The real bell, through the menu code.
        f.platform.events().publish(new ItemUseEvent(p, ControllerBell.create(), cancel -> { }));
        f.tick(25);
        assertEquals(TutorialStep.SUMMON, t.current(p), said());
        assertTrue(p.wasTold("There's the menu"), said());

        cmd("summon");   // the real summons reports SUMMONED
        f.tick(25);
        assertEquals(TutorialStep.FOLLOW, t.current(p), said());

        cmd("follow");   // the real follow reports FOLLOWING
        f.tick(25);
        assertEquals(TutorialStep.CHEST, t.current(p), said());
    }

    @Test
    @DisplayName("summon step is already done if the butler is out when it starts")
    void summonAlreadyOut() {
        f.core.butlers().summon(p);
        cmd("tutorial", "start");
        reach(Milestone.BELL);
        assertEquals(TutorialStep.FOLLOW, t.current(p), said());
        assertTrue(p.wasTold("already got me out"), said());
    }

    @Test
    @DisplayName("the current step is in the database, and a new run picks it up")
    void persists() {
        cmd("tutorial", "start");
        reach(Milestone.BELL);
        reach(Milestone.SUMMONED);
        OnboardingStore store = new OnboardingStore(f.core.database(), f.platform.log());
        OnboardingStore.State row = store.load(p.id()).orElseThrow();
        assertEquals(3, row.step());
        assertTrue(row.active());

        // A fresh Tutorial, as after a restart: nothing in memory, the row in the database.
        Tutorial after = new Tutorial(f.platform, store, new Milestones(), () -> true, () -> true,
                o -> false, null, System::currentTimeMillis);
        p.clearMessages();
        after.resume(p);
        assertEquals(TutorialStep.FOLLOW, after.current(p));
        assertTrue(p.wasTold("Step 3 of 7"), said());
    }

    @Test
    @DisplayName("no usable AI: the build step is skipped with a note, and the tutorial still finishes")
    void noAiStillFinishes() {
        f.core.setAiCheck(() -> false);
        cmd("tutorial", "start");
        for (Milestone m : List.of(Milestone.BELL, Milestone.SUMMONED, Milestone.FOLLOWING,
                Milestone.CHEST, Milestone.MINING, Milestone.DEPOSIT)) {
            reach(m);
        }
        assertNull(t.current(p), said());
        assertTrue(p.wasTold("Skipping step 7"), said());
        assertTrue(p.wasTold("That's the lot"), said());
        assertTrue(t.isDone(p));
    }

    @Test
    @DisplayName("with AI the build step runs and an undo finishes it")
    void buildStepWithAi() {
        cmd("tutorial", "start");
        for (Milestone m : List.of(Milestone.BELL, Milestone.SUMMONED, Milestone.FOLLOWING,
                Milestone.CHEST, Milestone.MINING, Milestone.DEPOSIT)) {
            reach(m);
        }
        assertEquals(TutorialStep.BUILD, t.current(p));
        reach(Milestone.UNDO);
        assertTrue(t.isDone(p), said());
    }

    @Test
    @DisplayName("skip, later, stop and reset do what they say")
    void controls() {
        cmd("tutorial", "start");
        cmd("tutorial", "skip");
        assertEquals(TutorialStep.SUMMON, t.current(p));

        cmd("tutorial", "stop");
        assertNull(t.current(p));
        assertTrue(p.wasTold("picks up at step 2"), said());
        cmd("tutorial");   // resume
        assertEquals(TutorialStep.SUMMON, t.current(p));

        cmd("tutorial", "later");
        assertNull(t.current(p));
        assertTrue(p.wasTold("Whenever you like"), said());

        p.op = true;
        cmd("tutorial", "reset", "alice");
        assertTrue(p.wasTold("next join is a first join"), said());
        OnboardingStore store = new OnboardingStore(f.core.database(), f.platform.log());
        assertEquals(0L, store.load(p.id()).map(OnboardingStore.State::welcomedAt).orElse(-1L),
                "reset leaves a 'welcome again' row (welcomedAt 0)");

        FakeOwner bob = f.player("bob");
        f.core.commands().jarvis(bob, Optional.of(bob), List.of("tutorial", "reset", "alice"), false);
        assertTrue(bob.wasTold("don't have permission"), String.join("\n", bob.plainMessages()));
    }

    @Test
    @DisplayName("the bell's Tutorial button shows until the tutorial is done, then moves to the Steward page")
    void menuButton() {
        f.core.menus().openMainMenu(p);
        assertTrue(f.platform.ui().slotNamed(p, "Tutorial") >= 0);

        cmd("tutorial", "start");
        for (Milestone m : List.of(Milestone.BELL, Milestone.SUMMONED, Milestone.FOLLOWING,
                Milestone.CHEST, Milestone.MINING, Milestone.DEPOSIT, Milestone.UNDO)) {
            reach(m);
        }
        assertTrue(t.isDone(p));
        f.core.menus().openMainMenu(p);
        assertEquals(-1, f.platform.ui().slotNamed(p, "Tutorial"), "gone from the front page");
        f.platform.ui().click(p, f.platform.ui().slotNamed(p, "Steward"), false);
        assertTrue(f.platform.ui().slotNamed(p, "Tutorial") >= 0, "on the Steward page");
    }

    @Test
    @DisplayName("one reminder per step after reminder-minutes, never two")
    void reminderOnce() {
        f.platform.config().set("onboarding.tutorial.reminder-minutes", 1);
        cmd("tutorial", "start");
        p.clearMessages();
        f.tick(1200 + 5);
        long nudges = p.plainMessages().stream().filter(m -> m.contains("Whenever you're ready")).count();
        assertEquals(1, nudges, said());
        f.tick(1200 * 3);
        nudges = p.plainMessages().stream().filter(m -> m.contains("Whenever you're ready")).count();
        assertEquals(1, nudges, "never twice for the same step");
    }
}
