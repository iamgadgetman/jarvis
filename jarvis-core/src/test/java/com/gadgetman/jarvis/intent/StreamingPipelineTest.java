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

/** FB-05 end to end: a fake Ollama streams NDJSON; the player sees sentence lines, once each. */
class StreamingPipelineTest {

    private Fixture f;
    private HttpServer ollama;
    private final List<Boolean> streamFlags = new ArrayList<>();

    @AfterEach
    void stop() {
        if (ollama != null) ollama.stop(0);
        if (f != null) f.close();
    }

    /** Streams {@code reply} in small NDJSON pieces when asked to stream; one JSON body otherwise. */
    private Fixture withModel(String reply, String extraAiConfig) throws IOException {
        ollama = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ollama.createContext("/api/chat", ex -> {
            JSONObject req = new JSONObject(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean stream = req.optBoolean("stream", false);
            streamFlags.add(stream);
            StringBuilder body = new StringBuilder();
            if (stream) {
                for (int i = 0; i < reply.length(); i += 6) {
                    String piece = reply.substring(i, Math.min(reply.length(), i + 6));
                    body.append(new JSONObject().put("message", new JSONObject().put("content", piece))
                            .put("done", false)).append('\n');
                }
                body.append(new JSONObject().put("message", new JSONObject().put("content", ""))
                        .put("done", true)).append('\n');
            } else {
                body.append(new JSONObject().put("message", new JSONObject().put("content", reply)));
            }
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
        });
        ollama.start();
        String endpoint = "http://127.0.0.1:" + ollama.getAddress().getPort();
        String cfg = Fixture.DEFAULT_CONFIG
                .replace("http://127.0.0.1:1\n    model: test", endpoint + "\n    model: test")
                .replace("  light-timeout-seconds: 1\n", "  light-timeout-seconds: 5\n" + extraAiConfig);
        return new Fixture(cfg);
    }

    private static final String STORY = "{\"action\":\"chat\",\"parameters\":{},"
            + "\"response\":\"Once a creeper sat by a pond. It hissed at a frog. The frog was unimpressed.\"}";

    private static List<String> jarvisLines(FakeOwner p) {
        List<String> out = new ArrayList<>();
        for (String m : p.messages) {
            String plain = m.replaceAll("§.", "");
            if (plain.startsWith("Jarvis: ")) out.add(plain.substring(8));
        }
        return out;
    }

    @Test
    @DisplayName("streaming on: each sentence is its own line, and the whole reply isn't repeated")
    void streamsSentences() throws IOException {
        f = withModel(STORY, "  streaming:\n    min-interval-ms: 0\n");
        FakeOwner p = f.player("alice");
        f.core.intents().submit(p, "tell me a short story about a creeper", IntentPipeline.Source.CONSOLE);
        assertEquals(List.of(true), streamFlags, "one streamed request");
        List<String> lines = jarvisLines(p);
        assertEquals(List.of("Once a creeper sat by a pond.", "It hissed at a frog.", "The frog was unimpressed."),
                lines, p.messages.toString());
    }

    @Test
    @DisplayName("streaming off: one request without streaming, one reply line")
    void offIsBuffered() throws IOException {
        f = withModel(STORY, "  streaming:\n    enabled: false\n");
        FakeOwner p = f.player("alice");
        f.core.intents().submit(p, "tell me a short story about a creeper", IntentPipeline.Source.CONSOLE);
        assertEquals(List.of(false), streamFlags);
        assertEquals(List.of("Once a creeper sat by a pond. It hissed at a frog. The frog was unimpressed."),
                jarvisLines(p));
    }

    @Test
    @DisplayName("a provider left out of ai.streaming.providers is not streamed")
    void providerList() throws IOException {
        f = withModel(STORY, "  streaming:\n    providers: [claude, openai]\n");
        FakeOwner p = f.player("alice");
        f.core.intents().submit(p, "tell me a short story", IntentPipeline.Source.CONSOLE);
        assertEquals(List.of(false), streamFlags);
        assertEquals(1, jarvisLines(p).size());
    }

    @Test
    @DisplayName("a teleport for a question is held back, then answered once with the place")
    void guardedReplyNotStreamed() throws IOException {
        f = withModel("{\"action\":\"teleport\",\"parameters\":{\"x\":120,\"y\":70,\"z\":-40},"
                + "\"response\":\"Arriving at the market. Mind the step.\"}", "  streaming:\n    min-interval-ms: 0\n");
        FakeOwner p = f.player("alice");
        f.core.intents().submit(p, "where is the market?", IntentPipeline.Source.CONSOLE);
        List<String> lines = jarvisLines(p);
        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("x 120, y 70, z -40"), lines.toString());
        assertTrue(lines.stream().noneMatch(l -> l.contains("Arriving")), lines.toString());
    }

    @Test
    @DisplayName("an order with speech: the reply streams, then the action runs")
    void actionAfterSpeech() throws IOException {
        f = withModel("{\"action\":\"follow\",\"parameters\":{},"
                + "\"response\":\"Right behind you, sir. Why did the creeper cross the road? To get to the other ssside.\"}",
                "  streaming:\n    min-interval-ms: 0\n");
        FakeOwner p = f.summoned("alice");
        f.core.intents().submit(p, "follow me and tell me a joke", IntentPipeline.Source.CONSOLE);
        List<String> lines = jarvisLines(p);
        assertEquals(3, lines.size(), lines.toString());
        assertEquals("Right behind you, sir.", lines.get(0));
        assertEquals(1, f.core.butlers().getActiveTaskCount(), "follow started after the reply");
        assertTrue(lines.stream().noneMatch(l -> l.equals("Right behind you, sir. Why did the creeper cross the road?")),
                "streamed lines, not one block");

    }

    /** A fake Ollama whose answer to the streamed request is {@code streamed}; buffered gets {@code reply}. */
    private Fixture withStreamAnswer(int status, String streamed, String reply) throws IOException {
        ollama = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ollama.createContext("/api/chat", ex -> {
            JSONObject req = new JSONObject(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean stream = req.optBoolean("stream", false);
            streamFlags.add(stream);
            byte[] bytes = (stream ? streamed
                    : new JSONObject().put("message", new JSONObject().put("content", reply)).toString())
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(stream ? status : 200, bytes.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
        });
        ollama.start();
        String endpoint = "http://127.0.0.1:" + ollama.getAddress().getPort();
        return new Fixture(Fixture.DEFAULT_CONFIG
                .replace("http://127.0.0.1:1\n    model: test", endpoint + "\n    model: test")
                .replace("  light-timeout-seconds: 1\n", "  light-timeout-seconds: 5\n  streaming:\n    min-interval-ms: 0\n"));
    }

    private static String ndjson(String text) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < text.length(); i += 6) {
            b.append(new JSONObject().put("message", new JSONObject()
                    .put("content", text.substring(i, Math.min(text.length(), i + 6)))).put("done", false)).append('\n');
        }
        return b.toString();
    }

    @Test
    @DisplayName("a stream that stops early is retried once without streaming; the retry is shown whole")
    void droppedStreamRetries() throws IOException {
        String half = STORY.substring(0, STORY.indexOf("It hissed") + 4); // no done line: dropped
        f = withStreamAnswer(200, ndjson(half), STORY);
        FakeOwner p = f.player("alice");
        f.core.intents().submit(p, "tell me a story", IntentPipeline.Source.CONSOLE);
        assertEquals(List.of(true, false), streamFlags);
        List<String> lines = jarvisLines(p);
        assertEquals("Once a creeper sat by a pond.", lines.get(0), lines.toString());
        assertEquals("Once a creeper sat by a pond. It hissed at a frog. The frog was unimpressed.",
                lines.get(lines.size() - 1), lines.toString());
    }

    @Test
    @DisplayName("an error status is not sent again without streaming")
    void errorStatusNotRetried() throws IOException {
        f = withStreamAnswer(500, "{\"error\":\"model failed\"}", STORY);
        FakeOwner p = f.player("alice");
        f.core.intents().submit(p, "tell me a story", IntentPipeline.Source.CONSOLE);
        assertEquals(List.of(true), streamFlags, "one request, then provider failover as before");
    }

    @Test
    @DisplayName("an unknown action isn't streamed, so the keyword fallback still answers")
    void unknownGoesToFallback() throws IOException {
        f = withModel("{\"action\":\"unknown\",\"parameters\":{},\"response\":\"Hmm, not sure. Truly.\"}",
                "  streaming:\n    min-interval-ms: 0\n");
        FakeOwner p = f.player("alice");
        f.core.intents().submit(p, "flibber the jibber", IntentPipeline.Source.CONSOLE);
        assertTrue(jarvisLines(p).stream().noneMatch(l -> l.startsWith("Hmm, not sure")), p.messages.toString());
        assertFalse(p.messages.isEmpty(), "the fallback said something");
    }
}
