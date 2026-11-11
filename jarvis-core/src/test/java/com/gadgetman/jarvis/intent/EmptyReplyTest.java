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

import static org.junit.jupiter.api.Assertions.*;

/**
 * FB-13: replies with nothing in them. Both shapes were seen from qwen2.5:7b
 * on GadgetCraft: a chat with an empty response (Jarvis said nothing at all)
 * and a broadcast with an empty message (Confirm offered to post "?").
 */
class EmptyReplyTest {

    private Fixture f;
    private HttpServer ollama;

    @AfterEach
    void stop() {
        if (ollama != null) ollama.stop(0);
        if (f != null) f.close();
    }

    private Fixture withModel(String reply) throws IOException {
        ollama = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ollama.createContext("/api/chat", ex -> {
            ex.getRequestBody().readAllBytes();
            byte[] body = new JSONObject()
                    .put("message", new JSONObject().put("role", "assistant").put("content", reply))
                    .toString().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(body); }
        });
        ollama.start();
        String endpoint = "http://127.0.0.1:" + ollama.getAddress().getPort();
        Fixture fx = new Fixture(Fixture.DEFAULT_CONFIG.replace("http://127.0.0.1:1\n    model: test",
                endpoint + "\n    model: test"));
        fx.actions.alsoHandles.add("broadcast");
        fx.actions.alsoHandles.add("server_say");
        return fx;
    }

    @Test
    @DisplayName("a chat reply with an empty response still says something")
    void emptyChatIsNotSilent() throws IOException {
        f = withModel("{\"action\":\"chat\",\"parameters\":{},\"response\":\"\"}");
        FakeOwner p = f.player("alice");
        f.core.intents().submit(p, "jarvis what should I do next?", IntentPipeline.Source.CONSOLE);
        assertTrue(p.messages.stream().anyMatch(m -> m.contains("Ask me again")), p.messages.toString());
    }

    @Test
    @DisplayName("a whitespace-only response counts as empty")
    void blankChatIsNotSilent() throws IOException {
        f = withModel("{\"action\":\"chat\",\"response\":\"   \"}");
        FakeOwner p = f.player("alice");
        f.core.intents().submit(p, "jarvis how are you?", IntentPipeline.Source.CONSOLE);
        assertTrue(p.messages.stream().anyMatch(m -> m.contains("Ask me again")), p.messages.toString());
    }

    @Test
    @DisplayName("a normal chat reply is spoken, with no 'ask me again'")
    void normalChatUnchanged() throws IOException {
        f = withModel("{\"action\":\"chat\",\"response\":\"Gather some wood, sir.\"}");
        FakeOwner p = f.player("alice");
        f.core.intents().submit(p, "jarvis what should I do next?", IntentPipeline.Source.CONSOLE);
        assertTrue(p.messages.stream().anyMatch(m -> m.contains("Gather some wood")), p.messages.toString());
        assertTrue(p.messages.stream().noneMatch(m -> m.contains("Ask me again")), p.messages.toString());
    }

    @Test
    @DisplayName("an asked-for broadcast with an empty message asks what to announce, and posts nothing")
    void emptyBroadcastAsks() throws IOException {
        f = withModel("{\"action\":\"broadcast\",\"parameters\":{\"message\":\"\"},\"response\":\"Right away, sir.\"}");
        FakeOwner p = f.player("alice");
        f.core.intents().submit(p, "announce that the market opens at noon", IntentPipeline.Source.CONSOLE);
        assertFalse(f.actions.executed.contains("broadcast"), f.actions.executed.toString());
        assertTrue(p.messages.stream().anyMatch(m -> m.contains("What would you like me to announce")),
                p.messages.toString());
    }

    @Test
    @DisplayName("a broadcast with no parameters at all is caught too")
    void missingParametersAsks() throws IOException {
        f = withModel("{\"action\":\"server_say\",\"response\":\"Done.\"}");
        FakeOwner p = f.player("alice");
        f.core.intents().submit(p, "tell everyone hello", IntentPipeline.Source.CONSOLE);
        assertFalse(f.actions.executed.contains("server_say"), f.actions.executed.toString());
        assertTrue(p.messages.stream().anyMatch(m -> m.contains("What would you like me to announce")),
                p.messages.toString());
    }

    @Test
    @DisplayName("a broadcast with a message still goes out")
    void realBroadcastRuns() throws IOException {
        f = withModel("{\"action\":\"broadcast\",\"parameters\":{\"message\":\"Market opens at noon\"},"
                + "\"response\":\"Announcing it now, sir.\"}");
        FakeOwner p = f.player("alice");
        f.core.intents().submit(p, "announce that the market opens at noon", IntentPipeline.Source.CONSOLE);
        assertTrue(f.actions.executed.contains("broadcast"), f.actions.executed.toString());
    }

    @Test
    @DisplayName("blankMessage: null, missing, blank are blank; text is not")
    void blankMessageHelper() {
        assertTrue(IntentPipeline.blankMessage(null));
        assertTrue(IntentPipeline.blankMessage(new JSONObject()));
        assertTrue(IntentPipeline.blankMessage(new JSONObject().put("message", "  ")));
        assertFalse(IntentPipeline.blankMessage(new JSONObject().put("message", "hi")));
    }
}
