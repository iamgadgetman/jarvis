package com.gadgetman.jarvis.intent;

import com.gadgetman.jarvis.core.testing.FakeOwner;
import com.gadgetman.jarvis.core.testing.Fixture;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FB-02: while a sentence is with the AI, the player sees "Jarvis is thinking…"
 * and Jarvis faces them and fidgets; it all stops when the reply (or the
 * failure) comes back.
 */
class ThinkingFeedbackTest {

    private Fixture f;
    private HttpServer ollama;

    @AfterEach
    void stop() {
        if (ollama != null) ollama.stop(0);
        if (f != null) f.close();
    }

    /** What the player had seen, and Jarvis had done, at the moment the model was asked. */
    private final List<String> barsWhenAsked = new ArrayList<>();
    private int swingsWhenAsked = -1;

    /** A fake Ollama that answers every chat with {@code reply}, noting the feedback state first. */
    private Fixture withModel(String reply) throws IOException {
        ollama = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ollama.createContext("/api/chat", ex -> {
            ex.getRequestBody().readAllBytes();
            snapshot();
            byte[] body = new JSONObject()
                    .put("message", new JSONObject().put("role", "assistant").put("content", reply))
                    .toString().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(body); }
        });
        ollama.start();
        String endpoint = "http://127.0.0.1:" + ollama.getAddress().getPort();
        return new Fixture(Fixture.DEFAULT_CONFIG.replace("http://127.0.0.1:1\n    model: test",
                endpoint + "\n    model: test"));
    }

    private FakeOwner alice;

    private void snapshot() {
        barsWhenAsked.addAll(alice.actionBars);
        swingsWhenAsked = f.butler(alice).swings;
    }

    private static boolean thinkingLine(String s) {
        return s.startsWith("Jarvis is thinking");
    }

    @Test
    @DisplayName("AI path: thinking shows before the model is asked and stops when the reply lands")
    void aiPath_showsThenStops() throws IOException {
        f = withModel("{\"action\":\"chat\",\"parameters\":{},\"response\":\"An excellent question, sir.\"}");
        alice = f.summoned("alice");
        int swingsBefore = f.butler(alice).swings;
        int tasksBefore = f.platform.scheduler().pending();

        f.core.intents().submit(alice, "what should I do next?", IntentPipeline.Source.CONSOLE);

        assertAll(
                () -> assertTrue(barsWhenAsked.stream().anyMatch(ThinkingFeedbackTest::thinkingLine),
                        "action bar read 'Jarvis is thinking' while the model worked: " + barsWhenAsked),
                () -> assertTrue(swingsWhenAsked > swingsBefore, "he fidgeted while the model worked"),
                () -> assertFalse(f.butler(alice).lookedAt.isEmpty(), "he turned to face her"),
                () -> assertEquals("", last(alice.actionBars), "the bar was cleared after the reply"),
                () -> assertTrue(alice.wasTold("An excellent question"), "the reply arrived: " + alice.plainMessages()),
                () -> assertEquals(tasksBefore, f.platform.scheduler().pending(), "no feedback task left running"));
    }

    @Test
    @DisplayName("failure path: with the model unreachable the feedback still stops")
    void failurePath_stillStops() throws IOException {
        f = new Fixture();   // the default AI endpoint points at a port nothing listens on
        alice = f.summoned("alice");
        int tasksBefore = f.platform.scheduler().pending();

        f.core.intents().submit(alice, "what should I do next?", IntentPipeline.Source.CONSOLE);

        assertAll(
                () -> assertTrue(alice.actionBars.stream().anyMatch(ThinkingFeedbackTest::thinkingLine),
                        "it started: " + alice.actionBars),
                () -> assertEquals("", last(alice.actionBars), "and was cleared"),
                () -> assertEquals(tasksBefore, f.platform.scheduler().pending(), "no stuck fidget task"));
    }

    @Test
    @DisplayName("a fast-path hit never shows thinking")
    void fastPath_noThinking() throws IOException {
        f = new Fixture();
        alice = f.summoned("alice");
        int swingsBefore = f.butler(alice).swings;

        f.core.intents().submit(alice, "follow me", IntentPipeline.Source.CONSOLE);

        assertTrue(alice.actionBars.stream().noneMatch(ThinkingFeedbackTest::thinkingLine), alice.actionBars.toString());
        assertEquals(swingsBefore, f.butler(alice).swings);
    }

    @Test
    @DisplayName("enabled: false shows nothing; animate: false keeps only the bar")
    void switches() throws IOException {
        f = new Fixture(Fixture.DEFAULT_CONFIG.replace("natural-language:",
                "intent:\n  thinking-feedback:\n    enabled: false\nnatural-language:"));
        alice = f.summoned("alice");
        int swingsBefore = f.butler(alice).swings;
        f.core.intents().submit(alice, "what should I do next?", IntentPipeline.Source.CONSOLE);
        assertTrue(alice.actionBars.stream().noneMatch(ThinkingFeedbackTest::thinkingLine), "disabled: no bar");
        assertEquals(swingsBefore, f.butler(alice).swings, "disabled: no fidget");
        f.close();

        f = new Fixture(Fixture.DEFAULT_CONFIG.replace("natural-language:",
                "intent:\n  thinking-feedback:\n    animate: false\nnatural-language:"));
        alice = f.summoned("alice");
        swingsBefore = f.butler(alice).swings;
        f.core.intents().submit(alice, "what should I do next?", IntentPipeline.Source.CONSOLE);
        assertTrue(alice.actionBars.stream().anyMatch(ThinkingFeedbackTest::thinkingLine), "animate off: bar still shows");
        assertEquals(swingsBefore, f.butler(alice).swings, "animate off: no fidget");
    }

    @Test
    @DisplayName("the safety cutoff ends feedback by itself, and stop() twice is harmless")
    void cutoffAndIdempotentStop() throws IOException {
        f = new Fixture();
        alice = f.summoned("alice");
        ThinkingFeedback t = ThinkingFeedback.start(alice, f.core.butlers().butler(alice),
                f.platform.config(), f.platform.scheduler());
        assertTrue(t.isRunning());
        f.platform.scheduler().tick((int) ThinkingFeedback.CUTOFF_TICKS);
        assertFalse(t.isRunning(), "cut off after " + ThinkingFeedback.CUTOFF_TICKS + " ticks");
        int bars = alice.actionBars.size();
        t.stop();
        assertEquals(bars, alice.actionBars.size(), "a second stop sends nothing");
    }

    private static String last(List<String> xs) {
        return xs.isEmpty() ? null : xs.get(xs.size() - 1);
    }
}
