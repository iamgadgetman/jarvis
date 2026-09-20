package com.gadgetman.jarvis.ui;

import com.gadgetman.jarvis.core.platform.events.ChatEvent;
import com.gadgetman.jarvis.core.testing.FakeOwner;
import com.gadgetman.jarvis.core.testing.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptsTest {

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

    private boolean chat(FakeOwner who, String text) {
        AtomicBoolean cancelled = new AtomicBoolean();
        f.platform.events().publish(new ChatEvent(who, text, cancelled::set));
        return cancelled.get();
    }

    @Test
    @DisplayName("the next chat line answers the question and stays out of chat")
    void answeredFromChat() {
        AtomicReference<String> answer = new AtomicReference<>();
        f.core.prompts().ask(p, "Where is the server?", answer::set);
        assertTrue(p.wasTold("Where is the server?"));
        assertTrue(f.core.prompts().isWaiting(p));

        assertTrue(chat(p, "  http://10.0.0.5:11434 "), "the answer is kept out of chat");
        assertEquals("http://10.0.0.5:11434", answer.get());
        assertFalse(f.core.prompts().isWaiting(p));

        assertFalse(chat(p, "hello everyone"), "ordinary chat passes again");
    }

    @Test
    @DisplayName("cancel leaves the value alone, and another player's chat is nobody's answer")
    void cancelAndOtherPlayers() {
        FakeOwner bob = f.player("bob");
        AtomicReference<String> answer = new AtomicReference<>();
        f.core.prompts().ask(p, "Paste the key.", answer::set);

        assertFalse(chat(bob, "sk-not-mine"));
        assertNull(answer.get());

        assertTrue(chat(p, "Cancel"));
        assertNull(answer.get());
        assertTrue(p.wasTold("Left as it was"));
        assertFalse(f.core.prompts().isWaiting(p));
    }
}
