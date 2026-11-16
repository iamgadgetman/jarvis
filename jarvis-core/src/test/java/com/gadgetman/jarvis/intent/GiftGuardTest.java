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
 * FB-14: free items, heals and food only when asked. The sword reply is the one
 * qwen2.5:7b really gave on GadgetCraft for "what should I do next?".
 */
class GiftGuardTest {

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
        fx.actions.alsoHandles.addAll(java.util.List.of("give_item", "heal", "feed"));
        return fx;
    }

    private static final String SWORD_REPLY = "{\"action\":\"give_item\",\"parameters\":{\"item\":\"diamond_sword\","
            + "\"amount\":1},\"response\":\"Here's a sword. Do try not to immediately use it on yourself.\"}";

    @Test
    @DisplayName("\"what should I do next?\" answered with a free sword: nothing given, the sword is offered")
    void questionGetsOfferNotSword() throws IOException {
        f = withModel(SWORD_REPLY);
        FakeOwner p = f.player("alice");
        f.core.intents().submit(p, "what should I do next?", IntentPipeline.Source.CONSOLE);
        assertFalse(f.actions.executed.contains("give_item"), f.actions.executed.toString());
        assertTrue(p.messages.stream().anyMatch(m -> m.contains("diamond sword") && m.contains("give me")),
                p.messages.toString());
        assertTrue(p.messages.stream().noneMatch(m -> m.contains("Here's a sword")), p.messages.toString());
    }

    @Test
    @DisplayName("\"give me a sword\" still gives one")
    void askedStillGives() throws IOException {
        f = withModel(SWORD_REPLY);
        FakeOwner p = f.player("alice");
        p.permissions.add("jarvis.admin");   // the ask guard, not permissions, is under test
        f.core.intents().submit(p, "give me a diamond sword", IntentPipeline.Source.CONSOLE);
        assertTrue(f.actions.executed.contains("give_item"), f.actions.executed.toString());
    }

    @Test
    @DisplayName("an unasked heal is offered, not done; \"I'm hurt\" is asking")
    void heal() throws IOException {
        f = withModel("{\"action\":\"heal\",\"parameters\":{},\"response\":\"Tending to your injuries first.\"}");
        FakeOwner p = f.player("alice");
        p.permissions.add("jarvis.admin");   // the ask guard, not permissions, is under test
        f.core.intents().submit(p, "what should I do next?", IntentPipeline.Source.CONSOLE);
        assertFalse(f.actions.executed.contains("heal"), f.actions.executed.toString());
        assertTrue(p.messages.stream().anyMatch(m -> m.contains("heal me")), p.messages.toString());

        f.core.intents().submit(p, "I'm hurt, help", IntentPipeline.Source.CONSOLE);
        assertTrue(f.actions.executed.contains("heal"), f.actions.executed.toString());
    }

    @Test
    @DisplayName("the words that count as asking, per gift action")
    void askWords() {
        assertTrue(IntentPipeline.unaskedAction("give_item", "what should I do next?"));
        assertFalse(IntentPipeline.unaskedAction("give_item", "can I have some torches"));
        assertFalse(IntentPipeline.unaskedAction("give_item", "I need a pickaxe"));
        assertTrue(IntentPipeline.unaskedAction("feed", "how are you?"));
        assertFalse(IntentPipeline.unaskedAction("feed", "I'm starving"));
        assertTrue(IntentPipeline.unaskedAction("heal", "tell me a joke"));
        assertFalse(IntentPipeline.unaskedAction("heal", "heal me please"));
        assertTrue(IntentPipeline.unaskedAction("enchant", "what's this sword?"));
        assertFalse(IntentPipeline.unaskedAction("enchant", "enchant my sword with sharpness"));
        assertTrue(IntentPipeline.unaskedAction("potion_effect", "it's dark down here"));
        assertFalse(IntentPipeline.unaskedAction("potion_effect", "give me night vision"));
        assertFalse(IntentPipeline.unaskedAction("build", "anything"), "other actions are untouched");
    }

    @Test
    @DisplayName("the offer names the item with the right article")
    void offerText() {
        assertTrue(IntentPipeline.giftOffer("give_item", new JSONObject().put("item", "minecraft:iron_sword"))
                .contains("an iron sword"));
        assertTrue(IntentPipeline.giftOffer("give_item", new JSONObject().put("item", "bread"))
                .contains("a bread"));
        assertTrue(IntentPipeline.giftOffer("give_item", new JSONObject()).contains("anything"));
    }
}
