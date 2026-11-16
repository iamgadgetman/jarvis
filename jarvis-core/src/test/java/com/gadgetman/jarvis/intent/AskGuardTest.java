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
 * End to end through the pipeline with a fake model: an action the player did
 * not ask for is answered as chat instead of run. The teleport reply is the
 * one the model really gave on GadgetCraft for "where is the market?".
 */
class AskGuardTest {

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
        fx.actions.alsoHandles.add("teleport");
        return fx;
    }

    private static final String TELEPORT_REPLY = "{\"action\":\"teleport\",\"parameters\":{\"x\":0,\"y\":64,\"z\":0},"
            + "\"response\":\"Arriving at the market, sir. Do try not to lose your bearings this time.\"}";

    @Test
    @DisplayName("\"where is the market?\" answered by a teleport pick: nobody moves, he says where")
    void whereIsDoesNotTeleport() throws IOException {
        f = withModel(TELEPORT_REPLY);
        FakeOwner p = f.player("alice");
        f.core.intents().submit(p, "where is the market?", IntentPipeline.Source.CONSOLE);
        assertFalse(f.actions.executed.contains("teleport"), "a question must not move the player");
        assertTrue(p.messages.stream().anyMatch(m -> m.contains("x 0, y 64, z 0")), p.messages.toString());
        assertTrue(p.messages.stream().noneMatch(m -> m.contains("Arriving")), p.messages.toString());
    }

    @Test
    @DisplayName("\"take me to the market\" still teleports")
    void takeMeStillTeleports() throws IOException {
        f = withModel(TELEPORT_REPLY);
        FakeOwner p = f.player("alice");
        p.permissions.add("jarvis.admin");   // the ask guard, not permissions, is under test
        f.core.intents().submit(p, "take me to the market", IntentPipeline.Source.CONSOLE);
        assertTrue(f.actions.executed.contains("teleport"), f.actions.executed.toString());
    }
}
