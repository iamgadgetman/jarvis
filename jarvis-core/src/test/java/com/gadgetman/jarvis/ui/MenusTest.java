package com.gadgetman.jarvis.ui;

import com.gadgetman.jarvis.core.platform.events.ButlerInteractEvent;
import com.gadgetman.jarvis.core.platform.events.ItemUseEvent;
import com.gadgetman.jarvis.core.testing.FakeOwner;
import com.gadgetman.jarvis.core.testing.Fixture;
import com.gadgetman.jarvis.core.world.Ids;
import com.gadgetman.jarvis.core.world.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenusTest {

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

    private void ringBell() {
        AtomicBoolean cancelled = new AtomicBoolean();
        f.platform.events().publish(new ItemUseEvent(p, ControllerBell.create(), cancelled::set));
        assertTrue(cancelled.get(), "the bell's own use is cancelled");
    }

    @Test
    @DisplayName("ringing the bell opens the main menu; any other item is ignored")
    void bellOpensTheMenu() {
        f.platform.events().publish(new ItemUseEvent(p, Item.of(Ids.STICK).marked("something"), c -> { }));
        assertFalse(f.platform.ui().isOpen(p));

        ringBell();

        assertTrue(f.platform.ui().isOpen(p));
        assertEquals("Jarvis", f.platform.ui().menuFor(p).title());
        assertTrue(p.sounds.contains(Ids.SOUND_BLOCK_BELL_USE));
    }

    @Test
    @DisplayName("the first slot summons him, then offers to dismiss him")
    void summonAndDismissFromTheMenu() {
        ringBell();
        int summon = f.platform.ui().slotNamed(p, "Summon");
        assertEquals(0, summon);

        f.platform.ui().click(p, summon, false);

        assertTrue(f.core.butlers().exists(p));
        assertFalse(f.platform.ui().isOpen(p), "the menu closes on a summon");

        ringBell();
        assertEquals(0, f.platform.ui().slotNamed(p, "Dismiss"));
        assertTrue(f.platform.ui().slotNamed(p, "Awaiting orders") >= 0);
    }

    @Test
    @DisplayName("the mining department opens and its torch toggle writes config")
    void miningMenuTogglesConfig() {
        ringBell();
        f.platform.ui().click(p, f.platform.ui().slotNamed(p, "Mining"), false);
        assertEquals("Jarvis — Mining", f.platform.ui().menuFor(p).title());
        assertFalse(f.platform.config().getBoolean("mining.place-torches", false));

        f.platform.ui().click(p, f.platform.ui().slotNamed(p, "Torches:"), false);

        assertTrue(f.platform.config().getBoolean("mining.place-torches", false));
        assertEquals("Jarvis — Mining", f.platform.ui().menuFor(p).title(), "redrawn in place");
        assertTrue(f.platform.ui().slotNamed(p, "Torches: ON") >= 0
                || f.platform.ui().slotNamed(p, "Torches:") >= 0);
    }

    @Test
    @DisplayName("a right-click on your own butler opens the menu; on someone else's it does not")
    void clickingTheButler() {
        FakeOwner other = f.summoned("bob");
        f.platform.events().publish(new ButlerInteractEvent(other.id(), p, false, c -> { }));
        assertFalse(f.platform.ui().isOpen(p));

        f.core.butlers().summon(p);
        f.platform.events().publish(new ButlerInteractEvent(p.id(), p, false, c -> { }));
        assertTrue(f.platform.ui().isOpen(p));
    }

    @Test
    @DisplayName("without the menu permission the bell is refused")
    void permissionIsChecked() {
        p.permissions.remove("jarvis.menu.use");
        ringBell();
        assertFalse(f.platform.ui().isOpen(p));
        assertTrue(p.wasTold("aren't permitted"));
    }

    @Test
    @DisplayName("the service record menu lists the ladder")
    void serviceRecordMenu() {
        f.core.butlers().summon(p);
        ringBell();
        f.platform.ui().click(p, f.platform.ui().slotNamed(p, "Service record"), false);
        assertEquals("Jarvis — Service Record", f.platform.ui().menuFor(p).title());
        assertTrue(f.platform.ui().slotNamed(p, "Ore mined") >= 0);
    }

    @Test
    @DisplayName("an operator sets up a provider from the admin page: toggle, key by chat, model")
    void aiSetupFromTheMenu() {
        p.op = true;
        ringBell();
        f.platform.ui().click(p, f.platform.ui().slotNamed(p, "Admin"), false);
        f.platform.ui().click(p, f.platform.ui().slotNamed(p, "AI setup"), false);
        assertEquals("Jarvis — AI providers", f.platform.ui().menuFor(p).title());

        int claude = f.platform.ui().slotNamed(p, "Claude");
        f.platform.ui().click(p, claude, true);          // right-click: disable
        assertFalse(f.core.aiSettings().isEnabled("claude"));
        assertTrue(f.platform.ui().slotNamed(p, "Claude OFF") >= 0 || f.platform.ui().slotNamed(p, "Claude") >= 0);
        f.platform.ui().click(p, claude, true);          // and back on
        assertTrue(f.core.aiSettings().isEnabled("claude"));

        f.platform.ui().click(p, claude, false);         // left-click: its page
        assertEquals("Jarvis — Claude", f.platform.ui().menuFor(p).title());
        f.platform.ui().click(p, f.platform.ui().slotNamed(p, "API key"), false);
        assertFalse(f.platform.ui().isOpen(p), "the menu closes so the key can be typed");
        assertTrue(f.core.prompts().isWaiting(p));

        AtomicBoolean cancelled = new AtomicBoolean();
        f.platform.events().publish(new com.gadgetman.jarvis.core.platform.events.ChatEvent(p, "sk-ant-menu", cancelled::set));
        assertTrue(cancelled.get(), "the key never reaches chat");
        assertEquals("sk-ant-menu", f.platform.config().getString("ai.claude.api-key", ""));
        assertTrue(f.core.ai().hasApiKey("claude"));
        assertTrue(f.platform.ui().isOpen(p), "back on the provider page");
        assertTrue(f.platform.ui().slotNamed(p, "API key") >= 0);
    }

    @Test
    @DisplayName("Custom build asks for a description in chat and hands it to /jarvis build")
    void customBuildFromTheMenu() {
        ringBell();
        f.platform.ui().click(p, f.platform.ui().slotNamed(p, "Building"), false);
        assertEquals("Jarvis — Building", f.platform.ui().menuFor(p).title());

        f.platform.ui().click(p, f.platform.ui().slotNamed(p, "Custom build"), false);
        assertFalse(f.platform.ui().isOpen(p), "the menu closes so the description can be typed");
        assertTrue(f.core.prompts().isWaiting(p));
        assertTrue(p.wasTold("What shall I build"));

        AtomicBoolean cancelled = new AtomicBoolean();
        f.platform.events().publish(new com.gadgetman.jarvis.core.platform.events.ChatEvent(p, "a small oak cottage", cancelled::set));
        assertTrue(cancelled.get(), "the description is an answer, not a chat line");
        // No AI is configured in the fixture, so the build command answers
        // as it would for /jarvis build: the request reached it.
        assertFalse(f.core.prompts().isWaiting(p));
        assertTrue(p.plainMessages().size() >= 2, String.join("\n", p.plainMessages()));
    }

    @Test
    @DisplayName("an operator turns voice on and sets the speech server from the admin page")
    void voiceSetupFromTheMenu() {
        p.op = true;
        ringBell();
        f.platform.ui().click(p, f.platform.ui().slotNamed(p, "Admin"), false);
        f.platform.ui().click(p, f.platform.ui().slotNamed(p, "Voice setup"), false);
        assertEquals("Jarvis — Voice", f.platform.ui().menuFor(p).title());

        f.platform.ui().click(p, f.platform.ui().slotNamed(p, "Voice"), false);
        assertTrue(f.platform.config().getBoolean("voice.enabled", false));
        f.platform.ui().click(p, f.platform.ui().slotNamed(p, "Gate:"), false);
        assertEquals("always", f.platform.config().getString("voice.gate", ""), "whisper cycles to always");

        assertEquals(-1, f.platform.ui().slotNamed(p, "Speech server"), "embedded needs no server address");
        f.platform.ui().click(p, f.platform.ui().slotNamed(p, "Engine:"), false);
        assertEquals("server", f.platform.config().getString("voice.engine", ""));
        f.platform.ui().click(p, f.platform.ui().slotNamed(p, "Speech server"), false);
        assertTrue(f.core.prompts().isWaiting(p));
        AtomicBoolean cancelled = new AtomicBoolean();
        f.platform.events().publish(new com.gadgetman.jarvis.core.platform.events.ChatEvent(p, "http://speech.lab:8000", cancelled::set));
        assertTrue(cancelled.get());
        assertEquals("http://speech.lab:8000", f.platform.config().getString("voice.endpoint", ""));
        assertTrue(f.platform.ui().isOpen(p), "back on the voice page");
    }

    @Test
    @DisplayName("a player without admin rights never sees the AI page")
    void aiSetupNeedsAdmin() {
        ringBell();
        assertEquals(-1, f.platform.ui().slotNamed(p, "Admin"));
    }
}
