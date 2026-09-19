package com.gadgetman.jarvis.commands;

import com.gadgetman.jarvis.core.testing.FakeOwner;
import com.gadgetman.jarvis.core.testing.Fixture;
import com.gadgetman.jarvis.core.world.Ids;
import com.gadgetman.jarvis.ui.ControllerBell;
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

class CommandServiceTest {

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

    private void run(FakeOwner who, String... args) {
        f.core.commands().jarvis(who, Optional.of(who), List.of(args), false);
    }

    @Test
    @DisplayName("/jarvis summon reaches the butler")
    void summonReachesTheButler() {
        run(p, "summon");
        assertTrue(f.core.butlers().exists(p));
    }

    @Test
    @DisplayName("/jarvis bell hands over a marked bell")
    void bellGivesTheController() {
        run(p, "bell");
        assertEquals(1, p.given.size());
        assertTrue(ControllerBell.isController(p.given.get(0)));
        assertTrue(p.given.get(0).is(Ids.BELL));
    }

    @Test
    @DisplayName("a one-word typo gets a suggestion, not a trip to the model")
    void typoSuggestsTheNearestCommand() {
        run(p, "verison");
        assertTrue(p.wasTold("No such command"));
        assertTrue(p.wasTold("Did you mean /jarvis version"), String.join("\n", p.plainMessages()));
    }

    @Test
    @DisplayName("the console can reload but not summon")
    void consoleIsLimited() {
        var console = f.platform.players().console();
        f.core.commands().jarvis(console, Optional.empty(), List.of("summon"), false);
        assertTrue(f.platform.players().consoleLines.stream().anyMatch(l -> l.contains("Only players")));

        f.core.commands().jarvis(console, Optional.empty(), List.of("reload"), false);
        assertTrue(f.platform.players().consoleLines.stream().anyMatch(l -> l.contains("Systems reloaded")));
    }

    @Test
    @DisplayName("admin commands check the permission")
    void adminCommandsNeedPermission() {
        run(p, "reload");
        assertTrue(p.wasTold("don't have permission"));

        p.permissions.add("jarvis.admin");
        run(p, "reload");
        assertTrue(p.wasTold("Systems reloaded"));
    }

    @Test
    @DisplayName("tab completion offers the top-level words")
    void tabCompletes() {
        List<String> out = f.core.commands().jarvis(p, Optional.of(p), List.of("su"), true);
        assertEquals(List.of("summon"), out);
        assertTrue(f.core.commands().jarvis(p, Optional.of(p), List.of(), true).contains("help"));
        assertTrue(f.core.commands().jarvis(p, Optional.of(p), List.of("mine", "co"), true).isEmpty());
    }

    @Test
    @DisplayName("an admin approves an item request and the player is handed the item")
    void approveGivesTheItem() {
        FakeOwner admin = f.player("bob");
        admin.permissions.add("jarvis.admin");
        int id = f.core.requests().addRequest(p.id(), p.name(), "diamond", 3, "please");

        run(admin, "requests");
        assertTrue(admin.wasTold("#" + id + " alice wants 3x diamond"), String.join("\n", admin.plainMessages()));

        run(admin, "approve", String.valueOf(id));

        assertEquals(1, p.given.size());
        assertEquals("minecraft:diamond", p.given.get(0).id());
        assertEquals(3, p.given.get(0).count());
        assertTrue(p.wasTold("bob approved your request"));
        assertFalse(f.core.requests().hasPending());
    }

    @Test
    @DisplayName("the version screen names the server and the backend")
    void versionScreen() {
        run(p, "version");
        assertTrue(p.wasTold("Jarvis vtest"));
        assertTrue(p.wasTold("Fake 1.0"));
        assertTrue(p.wasTold("NPC backend: fake"));
    }

    @Test
    @DisplayName("the service record reads back what he has done")
    void rankScreen() {
        run(p, "summon");
        f.core.progression().record(p, com.gadgetman.jarvis.progression.ServiceRecord.Discipline.MINING, 4);
        run(p, "rank");
        assertTrue(p.wasTold("Service Record"));
        assertTrue(p.wasTold("Ore 4"), String.join("\n", p.plainMessages()));
    }

    @Test
    @DisplayName("/jarvis ai sets up providers for admins and refuses everyone else")
    void aiSetupCommands() {
        run(p, "ai", "key", "claude", "sk-1");
        assertTrue(p.wasTold("permission"));
        assertFalse(f.core.ai().hasApiKey("claude"));

        p.op = true;
        run(p, "ai", "key", "claude", "sk-1");
        assertTrue(f.core.ai().hasApiKey("claude"));
        assertTrue(p.wasTold("server log"), "warned that commands are logged");

        run(p, "ai", "disable", "gemini");
        assertFalse(f.core.ai().isEnabled("gemini"));
        run(p, "ai", "model", "ollama", "llama3.2");
        assertEquals("llama3.2", f.core.ai().modelOf("ollama"));
        run(p, "ai", "endpoint", "ollama", "nope");
        assertTrue(p.wasTold("starts with http"));
        run(p, "ai", "test", "ollama");
        assertTrue(p.wasTold("failed"));
        run(p, "ai", "enable", "bard");
        assertTrue(p.wasTold("not 'bard'"));

        var tab = f.core.commands().jarvis(p, Optional.of(p), List.of("ai", "ena"), true);
        assertEquals(List.of("enable"), tab);
        var providers = f.core.commands().jarvis(p, Optional.of(p), List.of("ai", "key", "c"), true);
        assertEquals(List.of("claude"), providers);
    }
}
