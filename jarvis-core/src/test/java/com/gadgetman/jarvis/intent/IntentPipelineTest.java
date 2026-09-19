package com.gadgetman.jarvis.intent;

import com.gadgetman.jarvis.core.platform.events.ChatEvent;
import com.gadgetman.jarvis.core.testing.FakeOwner;
import com.gadgetman.jarvis.core.testing.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentPipelineTest {

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

    @Test
    @DisplayName("the JSON is found whatever the model wrapped it in")
    void extractJson() {
        assertEquals("{\"action\":\"summon\"}", IntentPipeline.extractJson("{\"action\":\"summon\"}"));
        assertEquals("{\"action\":\"summon\"}", IntentPipeline.extractJson("```json\n{\"action\":\"summon\"}\n```"));
        assertEquals("{\"a\":{\"b\":1}}", IntentPipeline.extractJson("Sure! Here you go: {\"a\":{\"b\":1}} hope that helps"));
        assertEquals("{\"d\":\"a } inside\"}", IntentPipeline.extractJson("x {\"d\":\"a } inside\"} y"));
        assertEquals("", IntentPipeline.extractJson(null));
    }

    @Test
    @DisplayName("with the model unreachable, a prefixed chat line still summons him by keyword")
    void chatFallsBackToKeywords() {
        AtomicBoolean cancelled = new AtomicBoolean();
        f.platform.events().publish(new ChatEvent(p, "Jarvis, summon yourself", cancelled::set));

        assertTrue(cancelled.get(), "the line was for him, so it stays out of public chat");
        assertTrue(f.core.butlers().exists(p), "keyword fallback summoned him");
    }

    @Test
    @DisplayName("a line without the prefix is not for him")
    void unprefixedChatIsIgnored() {
        AtomicBoolean cancelled = new AtomicBoolean();
        f.platform.events().publish(new ChatEvent(p, "summon the council", cancelled::set));

        assertFalse(cancelled.get());
        assertFalse(f.core.butlers().exists(p));
    }

    @Test
    @DisplayName("an order nothing matches is answered rather than dropped")
    void unmatchedOrderIsAnswered() {
        f.core.intents().submit(p, "polish the silverware", IntentPipeline.Source.CONSOLE);
        assertTrue(p.wasTold("couldn't make an order of it"), String.join("\n", p.plainMessages()));
    }
}
