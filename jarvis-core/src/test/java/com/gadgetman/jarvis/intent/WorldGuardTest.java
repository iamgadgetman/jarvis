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
 * FB-15: time, weather, game mode, difficulty, rules, summons and clears only
 * when asked. Same shape as FB-14's gift guard.
 */
class WorldGuardTest {

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
        fx.actions.alsoHandles.addAll(java.util.List.of("set_time", "set_weather", "summon"));
        return fx;
    }

    private static final String DAY_REPLY = "{\"action\":\"set_time\",\"parameters\":{\"value\":\"day\"},"
            + "\"response\":\"Daylight it is, sir. Monsters dislike it.\"}";

    @Test
    @DisplayName("a question answered with set_time: the time stays, day is offered")
    void questionGetsOfferNotDaylight() throws IOException {
        f = withModel(DAY_REPLY);
        FakeOwner p = f.player("alice");
        f.core.intents().submit(p, "what should I do next?", IntentPipeline.Source.CONSOLE);
        assertFalse(f.actions.executed.contains("set_time"), f.actions.executed.toString());
        assertTrue(p.messages.stream().anyMatch(m -> m.contains("make it day")), p.messages.toString());
        assertTrue(p.messages.stream().noneMatch(m -> m.contains("Daylight it is")), p.messages.toString());
    }

    @Test
    @DisplayName("\"make it day\" still sets the time")
    void askedStillRuns() throws IOException {
        f = withModel(DAY_REPLY);
        FakeOwner p = f.player("alice");
        p.permissions.add("jarvis.admin");   // the ask guard, not permissions, is under test
        f.core.intents().submit(p, "make it day please", IntentPipeline.Source.CONSOLE);
        assertTrue(f.actions.executed.contains("set_time"), f.actions.executed.toString());
    }

    @Test
    @DisplayName("an unasked summon is offered, not done; \"spawn a cow\" is asking")
    void summon() throws IOException {
        f = withModel("{\"action\":\"summon\",\"parameters\":{\"entity\":\"minecraft:cow\"},\"response\":\"A cow, sir.\"}");
        FakeOwner p = f.player("alice");
        p.permissions.add("jarvis.admin");   // the ask guard, not permissions, is under test
        f.core.intents().submit(p, "I'm bored", IntentPipeline.Source.CONSOLE);
        assertFalse(f.actions.executed.contains("summon"), f.actions.executed.toString());
        assertTrue(p.messages.stream().anyMatch(m -> m.contains("summon a cow")), p.messages.toString());

        f.core.intents().submit(p, "spawn a cow", IntentPipeline.Source.CONSOLE);
        assertTrue(f.actions.executed.contains("summon"), f.actions.executed.toString());
    }

    @Test
    @DisplayName("the words that count as asking, per world action")
    void askWords() {
        assertTrue(IntentPipeline.unaskedAction("set_time", "what should I do today?"));
        assertFalse(IntentPipeline.unaskedAction("set_time", "set the time to noon"));
        assertFalse(IntentPipeline.unaskedAction("set_time", "make it night"));
        assertTrue(IntentPipeline.unaskedAction("set_weather", "how are you?"));
        assertFalse(IntentPipeline.unaskedAction("set_weather", "stop the rain"));
        assertFalse(IntentPipeline.unaskedAction("set_weather", "clear skies please"));
        assertTrue(IntentPipeline.unaskedAction("set_gamemode", "help me build"));
        assertFalse(IntentPipeline.unaskedAction("set_gamemode", "put me in creative"));
        assertTrue(IntentPipeline.unaskedAction("set_difficulty", "it's scary out there"));
        assertFalse(IntentPipeline.unaskedAction("set_difficulty", "set difficulty to peaceful"));
        assertTrue(IntentPipeline.unaskedAction("set_gamerule", "what's new?"));
        assertFalse(IntentPipeline.unaskedAction("set_gamerule", "turn off the keepInventory gamerule"));
        assertTrue(IntentPipeline.unaskedAction("summon", "I'm bored"));
        assertFalse(IntentPipeline.unaskedAction("summon", "summon a horse"));
        assertTrue(IntentPipeline.unaskedAction("clear_mobs", "there are zombies everywhere"));
        assertFalse(IntentPipeline.unaskedAction("clear_mobs", "kill the zombies"));
        assertTrue(IntentPipeline.unaskedAction("clear_drops", "it's laggy"));
        assertFalse(IntentPipeline.unaskedAction("clear_drops", "clear the dropped items"));
        assertFalse(IntentPipeline.unaskedAction("build", "anything"), "other actions are untouched");
    }

    @Test
    @DisplayName("the offer names what the model wanted to do")
    void offerText() {
        assertTrue(IntentPipeline.worldOffer("set_weather", new JSONObject().put("type", "clear"))
                .contains("set the weather to clear"));
        assertTrue(IntentPipeline.worldOffer("summon", new JSONObject().put("entity", "minecraft:iron_golem"))
                .contains("summon an iron golem"));
        assertTrue(IntentPipeline.worldOffer("set_time", new JSONObject()).contains("Just ask"));
    }
}
