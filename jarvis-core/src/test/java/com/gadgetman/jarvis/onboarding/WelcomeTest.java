package com.gadgetman.jarvis.onboarding;

import com.gadgetman.jarvis.core.platform.events.JoinEvent;
import com.gadgetman.jarvis.core.platform.events.QuitEvent;
import com.gadgetman.jarvis.core.testing.FakeOwner;
import com.gadgetman.jarvis.core.testing.Fixture;
import com.gadgetman.jarvis.ui.ControllerBell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/** FB-11: a player's first join, handled once; the admin's "no AI yet" buttons. */
class WelcomeTest {

    private Fixture f;
    private OnboardingStore store;

    @BeforeEach
    void boot() throws IOException {
        f = new Fixture();
        f.core.setAiCheck(() -> true);
        store = new OnboardingStore(f.core.database(), f.platform.log());
    }

    @AfterEach
    void stop() {
        f.close();
    }

    private void join(FakeOwner p, boolean firstJoin) {
        f.platform.events().publish(new JoinEvent(p, firstJoin));
        f.tick(80);   // past steward.report-join-delay-ticks
    }

    private void rejoin(FakeOwner p, boolean firstJoin) {
        f.platform.events().publish(new QuitEvent(p));
        p.clearMessages();
        join(p, firstJoin);
    }

    /** Anything from the welcome (the join briefing may still speak). */
    private static boolean welcomeSaid(FakeOwner p) {
        return p.wasTold("I'm Jarvis") || p.wasTold("tutorial") || p.wasTold("can't think yet");
    }

    private static long bells(FakeOwner p) {
        return p.given.stream().filter(ControllerBell::isController).count();
    }

    @Test
    @DisplayName("first join: bell once, greeting and tutorial offer, row written; second join: nothing")
    void firstJoinOnce() {
        FakeOwner p = f.player("alice");
        join(p, true);
        assertEquals(1, bells(p));
        assertTrue(p.wasTold("I'm Jarvis"), String.join("\n", p.plainMessages()));
        assertTrue(p.wasTold("Right-click it"), String.join("\n", p.plainMessages()));
        assertTrue(p.wasTold("[Start the tutorial]") && p.wasTold("[Maybe later]"), String.join("\n", p.plainMessages()));
        assertTrue(store.load(p.id()).isPresent(), "welcomed row written");

        rejoin(p, false);
        assertEquals(1, bells(p), "no second bell");
        assertFalse(welcomeSaid(p), String.join("\n", p.plainMessages()));
    }

    @Test
    @DisplayName("already holding a controller bell: no second one")
    void alreadyHasBell() {
        FakeOwner p = f.player("bob");
        p.inventory.add(ControllerBell.create());
        join(p, true);
        assertEquals(0, bells(p));
        assertTrue(p.wasTold("I'm Jarvis"));
    }

    @Test
    @DisplayName("give-bell: false greets without the item; enabled: false does nothing and writes no row")
    void switches() {
        f.platform.config().set("onboarding.welcome.give-bell", false);
        FakeOwner p = f.player("carol");
        join(p, true);
        assertEquals(0, bells(p));
        assertTrue(p.wasTold("/jarvis help"), String.join("\n", p.plainMessages()));

        f.platform.config().set("onboarding.welcome.enabled", false);
        FakeOwner q = f.player("dave");
        join(q, true);
        assertFalse(welcomeSaid(q), String.join("\n", q.plainMessages()));
        assertTrue(store.load(q.id()).isEmpty());
    }

    @Test
    @DisplayName("admin with no usable provider: the three-button line, no bell or offer; once per restart; welcome once AI works")
    void adminNag() {
        f.core.setAiCheck(() -> false);
        FakeOwner admin = f.player("erin");
        admin.op = true;
        join(admin, true);
        assertTrue(admin.wasTold("can't think yet"), String.join("\n", admin.plainMessages()));
        assertTrue(admin.wasTold("[Use a free local model]") && admin.wasTold("[Use a free Gemini key]")
                && admin.wasTold("[Try Jarvis Cloud free]"), String.join("\n", admin.plainMessages()));
        assertEquals(0, bells(admin));
        assertFalse(admin.wasTold("[Start the tutorial]"));
        assertTrue(store.load(admin.id()).isEmpty(), "no welcome row while the AI is down");

        rejoin(admin, true);
        assertFalse(admin.wasTold("can't think yet"), "once per restart");

        f.core.setAiCheck(() -> true);
        rejoin(admin, true);
        assertEquals(1, bells(admin), "the normal welcome, now that something answers");
        assertTrue(admin.wasTold("I'm Jarvis"));
    }

    @Test
    @DisplayName("admin with a usable provider gets the normal welcome; admin-nag: never stays quiet")
    void adminWithAi() {
        FakeOwner admin = f.player("frank");
        admin.op = true;
        join(admin, true);
        assertTrue(admin.wasTold("I'm Jarvis"));
        assertFalse(admin.wasTold("can't think yet"));

        f.core.setAiCheck(() -> false);
        f.platform.config().set("onboarding.admin-nag", "never");
        FakeOwner other = f.player("gina");
        other.op = true;
        join(other, true);
        assertFalse(other.wasTold("can't think yet"));
        assertTrue(other.wasTold("I'm Jarvis"), "with the nag off, the ordinary welcome");
    }

    @Test
    @DisplayName("a regular from before this version (played before, no row): no welcome, a quiet row")
    void existingPlayerQuiet() {
        FakeOwner p = f.player("vera");
        join(p, false);
        assertEquals(0, bells(p));
        assertFalse(welcomeSaid(p), String.join("\n", p.plainMessages()));
        assertTrue(store.load(p.id()).isPresent(), "noted, so a later wipe doesn't welcome them either");

        f.platform.config().set("onboarding.welcome.existing-players", true);
        FakeOwner q = f.player("walt");
        join(q, false);
        assertEquals(1, bells(q), "existing-players: true welcomes them");
    }

    @Test
    @DisplayName("/jarvis tutorial reset: the next join is a welcome again, even for a regular, even after a restart")
    void resetWelcomesAgain() {
        FakeOwner p = f.player("xena");
        join(p, false);                     // a regular: quiet row
        assertEquals(0, bells(p));
        f.core.tutorial().reset(p.id());
        f.tick(1);
        assertEquals(0L, store.load(p.id()).orElseThrow().welcomedAt(), "kept as a 'welcome again' row");
        rejoin(p, false);
        assertEquals(1, bells(p));
        assertTrue(p.wasTold("I'm Jarvis"), String.join("\n", p.plainMessages()));
        assertTrue(store.load(p.id()).orElseThrow().welcomedAt() > 0);
        rejoin(p, false);
        assertFalse(welcomeSaid(p), "once only");
    }

    @Test
    @DisplayName("player data wiped (firstJoin true again) but the row exists: nothing")
    void wipedPlayerData() {
        FakeOwner p = f.player("hank");
        store.save(p.id(), new OnboardingStore.State(1L, 0, false, 0));
        join(p, true);
        assertEquals(0, bells(p));
        assertFalse(welcomeSaid(p), String.join("\n", p.plainMessages()));
    }

    @Test
    @DisplayName("the nag's buttons answer: /jarvis setup ollama and /jarvis setup gemini print the steps")
    void setupButtonsAnswer() {
        FakeOwner admin = f.player("ivy");
        admin.op = true;
        f.core.commands().jarvis(admin, java.util.Optional.of(admin), java.util.List.of("setup", "ollama"), false);
        assertTrue(admin.wasTold("ollama pull test"), String.join("\n", admin.plainMessages()));
        f.core.commands().jarvis(admin, java.util.Optional.of(admin), java.util.List.of("setup", "gemini"), false);
        assertTrue(admin.wasTold("aistudio.google.com/apikey") && admin.wasTold("GEMINI_API_KEY"),
                String.join("\n", admin.plainMessages()));
    }
}
