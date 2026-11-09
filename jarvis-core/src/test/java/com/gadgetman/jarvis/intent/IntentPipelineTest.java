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

import static org.junit.jupiter.api.Assertions.assertAll;
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

    // ---- FB-01: fast path ------------------------------------------------

    /**
     * Every trip into {@code AIConnector}'s provider loop leaves a log line:
     * a provider that was tried and failed ("AI provider ollama failed ...")
     * or one skipped while cooling down ("Skipping ollama ..."). The fixture's
     * AI points at a port nothing listens on, so a call can never succeed
     * silently. Zero such lines means zero AI invocations.
     */
    private static long aiAttempts(Fixture fx) {
        return fx.platform.logLines.stream()
                .filter(l -> l.contains("AI provider ") || l.contains("Skipping ")
                        || l.contains("All AI providers failed") || l.contains("No AI providers configured"))
                .count();
    }

    private static String log(Fixture fx) {
        return String.join("\n", fx.platform.logLines);
    }

    @Test
    @DisplayName("control: an order the fast path cannot place does reach the AI")
    void fastPathMiss_callsAi() {
        FakeOwner alice = f.summoned("alice");
        f.platform.logLines.clear();

        f.core.intents().submit(alice, "can you follow me?", IntentPipeline.Source.CONSOLE);

        assertTrue(aiAttempts(f) > 0, "a question goes to the model; if this fails, aiAttempts() "
                + "no longer sees AI calls and the zero-call tests below prove nothing\n" + log(f));
    }

    @Test
    @DisplayName("a fast-path hit from chat runs the order with zero AI invocations")
    void fastPathHit_makesNoAiCall() {
        FakeOwner alice = f.summoned("alice");
        f.platform.logLines.clear();
        AtomicBoolean cancelled = new AtomicBoolean();

        f.platform.events().publish(new ChatEvent(alice, "Jarvis, follow me", cancelled::set));

        assertAll(
                () -> assertEquals(0, aiAttempts(f), "fast path must not call the AI\n" + log(f)),
                () -> assertTrue(cancelled.get(), "the line was for him"),
                () -> assertEquals(1, f.core.butlers().getActiveTaskCount(), "he is following"),
                () -> assertFalse(alice.plainMessages().isEmpty(), "a canned acknowledgement was sent"));
    }

    @Test
    @DisplayName("a fast-path hit from the console path makes zero AI invocations")
    void fastPathHit_consoleSource_makesNoAiCall() {
        FakeOwner alice = f.summoned("alice");
        f.platform.logLines.clear();

        f.core.intents().submit(alice, "follow me", IntentPipeline.Source.CONSOLE);

        assertEquals(0, aiAttempts(f), log(f));
        assertEquals(1, f.core.butlers().getActiveTaskCount());
    }

    @Test
    @DisplayName("an extra phrase from intent.fast-path.extra-phrases is honoured with zero AI invocations")
    void extraPhraseFromConfig_makesNoAiCall() throws IOException {
        try (Fixture fx = new Fixture(Fixture.DEFAULT_CONFIG + """
                intent:
                  fast-path:
                    enabled: true
                    log-hits: false
                    extra-phrases:
                      "heel": follow
                """)) {
            FakeOwner bob = fx.summoned("bob");
            fx.platform.logLines.clear();

            fx.core.intents().submit(bob, "heel", IntentPipeline.Source.CONSOLE);

            assertEquals(0, aiAttempts(fx), log(fx));
            assertEquals(1, fx.core.butlers().getActiveTaskCount(), "the alias ran follow");
        }
    }

    @Test
    @DisplayName("parser names are translated to the names each action reads")
    void executorParams_translatesNames() {
        var mine = IntentPipeline.executorParams(new Intent("mine",
                new org.json.JSONObject().put("item", "iron").put("count", 32)));
        assertEquals("iron", mine.getString("ore"));
        assertFalse(mine.has("item"), mine.toString());
        assertFalse(mine.has("count"), "mining has no count; it must not be passed on: " + mine);

        var req = IntentPipeline.executorParams(new Intent("request_item",
                new org.json.JSONObject().put("item", "minecraft:torch").put("count", 16)));
        assertEquals("minecraft:torch", req.getString("item"));
        assertEquals(16, req.getInt("amount"));
        assertFalse(req.has("count"), req.toString());

        var chop = IntentPipeline.executorParams(new Intent("chop",
                new org.json.JSONObject().put("count", 5)));
        assertEquals(5, chop.getInt("count"), "chop already reads count");
    }

    @Test
    @DisplayName("\"come here\" summons Jarvis even when the server executor also claims summon")
    void comeHere_summonsJarvisNotAMob() {
        // Like Paper: the adapter's executor handles "summon" (a mob) and wants confirmation.
        f.actions.alsoHandles.add("summon");
        f.actions.alsoDangerous.add("summon");
        FakeOwner alice = f.player("alice");

        f.core.intents().submit(alice, "come here", IntentPipeline.Source.CONSOLE);

        assertTrue(f.core.butlers().exists(alice), "Jarvis was summoned\n" + String.join("\n", alice.plainMessages()));
        assertTrue(f.actions.executed.isEmpty(), "no mob was spawned: " + f.actions.executed);
        assertFalse(alice.plainMessages().stream().anyMatch(m -> m.toLowerCase().contains("confirm")),
                "no mob-summon confirmation was offered\n" + String.join("\n", alice.plainMessages()));
    }

    @Test
    @DisplayName("an alias to a server action is ignored and warned about once")
    void aliasToServerAction_warnsOnce() throws IOException {
        try (Fixture fx = new Fixture(Fixture.DEFAULT_CONFIG + """
                intent:
                  fast-path:
                    extra-phrases:
                      "patch me up": heal
                """)) {
            FakeOwner bob = fx.summoned("bob");
            fx.platform.logLines.clear();

            fx.core.intents().submit(bob, "follow me", IntentPipeline.Source.CONSOLE);
            fx.core.intents().submit(bob, "stop", IntentPipeline.Source.CONSOLE);

            long warnings = fx.platform.logLines.stream()
                    .filter(l -> l.startsWith("WARN") && l.contains("\"patch me up\" -> heal")).count();
            assertEquals(1, warnings, log(fx));
        }
    }

    @Test
    @DisplayName("with intent.fast-path.enabled: false, a simple order goes to the AI")
    void fastPathDisabled_callsAi() throws IOException {
        try (Fixture fx = new Fixture(Fixture.DEFAULT_CONFIG + """
                intent:
                  fast-path:
                    enabled: false
                """)) {
            FakeOwner bob = fx.summoned("bob");
            fx.platform.logLines.clear();

            fx.core.intents().submit(bob, "follow me", IntentPipeline.Source.CONSOLE);

            assertTrue(aiAttempts(fx) > 0, "disabled fast path means the AI is asked first\n" + log(fx));
        }
    }
}
