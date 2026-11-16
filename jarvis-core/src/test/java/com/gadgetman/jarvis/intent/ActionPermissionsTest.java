package com.gadgetman.jarvis.intent;

import com.gadgetman.jarvis.commands.ActionPermissions;

import com.gadgetman.jarvis.core.testing.FakeOwner;
import com.gadgetman.jarvis.core.testing.Fixture;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The model picks admin actions from what anyone types; the player's permissions decide. */
class ActionPermissionsTest {

    private Fixture f;
    private FakeOwner p;

    @BeforeEach
    void boot() throws IOException {
        f = new Fixture();
        p = f.player("alice");
    }

    @AfterEach
    void stop() {
        f.close();
    }

    private void act(String action) {
        act(action, "");
    }

    private void act(String action, String reply) {
        f.core.intents().executeAction(p, action, new JSONObject(), reply, "", IntentPipeline.Source.CHAT,
                IntentPipeline.CHAT_RESPONDER);
    }

    @Test
    @DisplayName("a refused order isn't answered as if it were done")
    void refusalComesFirst() {
        act("admin_give", "At once, sir. Sixty-four diamonds.");
        assertFalse(p.wasTold("At once"), String.join("\n", p.plainMessages()));
        assertTrue(p.wasTold("needs an administrator"));
    }

    @Test
    @DisplayName("a player without jarvis.admin can't have the model run an admin action, even one that needs a confirm")
    void playerIsRefused() {
        act("admin_give");
        act("admin_dangerous");
        assertTrue(f.actions.executed.isEmpty());
        assertFalse(f.core.confirmations().hasPending(p.id()), "no prompt the player could confirm themselves");
        assertTrue(p.wasTold("needs an administrator"), String.join("\n", p.plainMessages()));
    }

    @Test
    @DisplayName("jarvis.admin, or the per-action node, lets it run")
    void adminOrNodeRuns() {
        p.permissions.add("jarvis.admin.actions.admin_give");
        act("admin_give");
        assertEquals(List.of("admin_give"), f.actions.executed);
        act("admin_other");
        assertEquals(1, f.actions.executed.size(), "the node covers only its own action");
        p.permissions.add("jarvis.admin");
        act("admin_other");
        assertEquals(2, f.actions.executed.size());
    }

    @Test
    @DisplayName("/jarvis confirm checks again: a prompt queued for an admin doesn't run once they lose it")
    void confirmRechecks() {
        p.permissions.add("jarvis.admin");
        act("admin_dangerous");
        assertTrue(f.core.confirmations().hasPending(p.id()));
        p.permissions.remove("jarvis.admin");
        f.core.commands().jarvis(p, Optional.of(p), List.of("confirm"), false);
        assertTrue(f.actions.executed.isEmpty());
        assertTrue(p.wasTold("needs an administrator"), String.join("\n", p.plainMessages()));
    }

    @Test
    @DisplayName("request_item stays open: it only asks an admin")
    void requestItemIsOpen() {
        assertTrue(ActionPermissions.allowed(p, "request_item"));
        assertFalse(ActionPermissions.allowed(p, "console_command"));
        assertFalse(ActionPermissions.allowed(p, "give_item"));
    }
}
