package com.gadgetman.jarvis.commands;

import com.gadgetman.jarvis.core.testing.FakeOwner;
import com.gadgetman.jarvis.core.testing.Fixture;
import com.gadgetman.jarvis.core.world.Vec3;
import com.gadgetman.jarvis.intent.IntentPipeline;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** FB-08: reward bundles — admin-written commands, triggered by name only. */
class BundleRegistryTest {

    private static final String BUNDLES = """
            bundles:
              first-diamond:
                description: "Celebrate a player's first diamond"
                once-per-player: true
                commands:
                  - "give {player} minecraft:cooked_beef 8"
                  - "/playsound minecraft:ui.toast.challenge_complete master {player}"
              shrine-blessing:
                description: "Reward for reaching the hidden shrine"
                cooldown-minutes: 1440
                commands: ["effect give {player} minecraft:regeneration 60 1"]
              where-am-i:
                commands: ["say {player} {uuid} at {x} {y} {z} in {world}"]
              staff-kit:
                description: "Admin hands this out"
                ai: false
                commands: ["give {player} minecraft:diamond 1"]
              Bad Name:
                commands: ["say nope"]
              empty-one:
                description: "no commands"
            """;

    private Fixture f;
    private HttpServer ollama;
    private final List<String> prompts = new CopyOnWriteArrayList<>();
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);

    @AfterEach
    void stop() {
        if (ollama != null) ollama.stop(0);
        if (f != null) f.close();
    }

    private BundleRegistry registry() throws IOException {
        f = new Fixture(Fixture.DEFAULT_CONFIG + BUNDLES);
        return new BundleRegistry(f.platform, f.core.database(), now::get);
    }

    @Test
    @DisplayName("config: bad names and empty bundles are skipped; a leading / is dropped")
    void loads() throws IOException {
        BundleRegistry r = registry();
        assertEquals(List.of("first-diamond", "shrine-blessing", "where-am-i", "staff-kit"), List.copyOf(r.all().keySet()));
        assertEquals("playsound minecraft:ui.toast.challenge_complete master {player}",
                r.get("first-diamond").orElseThrow().commands().get(1));
        assertTrue(f.platform.logLines.stream().anyMatch(l -> l.contains("'Bad Name' skipped")), f.platform.logLines.toString());
        assertTrue(f.platform.logLines.stream().anyMatch(l -> l.contains("'empty-one' skipped")));
    }

    @Test
    @DisplayName("placeholders: {player} {uuid} {x} {y} {z} {world}")
    void placeholders() throws IOException {
        BundleRegistry r = registry();
        FakeOwner p = f.player("Gadget_1");
        p.pos = new Vec3(-10.7, 63.2, 5.9);
        assertTrue(r.trigger(p, "where-am-i", BundleRegistry.By.ADMIN).ran());
        assertEquals(List.of("say Gadget_1 " + p.id() + " at -11 63 5 in minecraft:overworld"), f.platform.consoleCommands);
    }

    @Test
    @DisplayName("once-per-player: the second trigger is refused and logged; another player still gets it")
    void oncePerPlayer() throws IOException {
        BundleRegistry r = registry();
        FakeOwner alice = f.player("alice");
        assertTrue(r.trigger(alice, "first-diamond", BundleRegistry.By.AI).ran());
        assertEquals(2, f.platform.consoleCommands.size());
        assertEquals("give alice minecraft:cooked_beef 8", f.platform.consoleCommands.get(0));

        BundleRegistry.Result again = r.trigger(alice, "First-Diamond", BundleRegistry.By.AI);
        assertFalse(again.ran());
        assertTrue(again.message().contains("already celebrated"), again.message());
        assertEquals(2, f.platform.consoleCommands.size(), "nothing ran the second time");
        assertTrue(f.platform.logLines.stream().anyMatch(l -> l.contains("[bundle] first-diamond for alice by ai: refused, already given")));

        assertTrue(r.trigger(f.player("bob"), "first-diamond", BundleRegistry.By.AI).ran());
    }

    @Test
    @DisplayName("cooldown with a fake clock; players are independent; it survives a reload")
    void cooldown() throws IOException {
        BundleRegistry r = registry();
        FakeOwner alice = f.player("alice");
        assertTrue(r.trigger(alice, "shrine-blessing", BundleRegistry.By.AI).ran());
        now.addAndGet(60 * 60_000L);
        BundleRegistry.Result early = r.trigger(alice, "shrine-blessing", BundleRegistry.By.AI);
        assertFalse(early.ran());
        assertTrue(early.message().contains("ready in 23h 0m"), early.message());
        assertTrue(r.trigger(f.player("bob"), "shrine-blessing", BundleRegistry.By.AI).ran(), "bob has his own clock");

        // A new registry over the same database: the use is remembered.
        BundleRegistry fresh = new BundleRegistry(f.platform, f.core.database(), now::get);
        assertFalse(fresh.trigger(alice, "shrine-blessing", BundleRegistry.By.AI).ran());
        now.addAndGet(23 * 60 * 60_000L);
        assertTrue(fresh.trigger(alice, "shrine-blessing", BundleRegistry.By.AI).ran());
    }

    @Test
    @DisplayName("unknown bundle: refused, logged, nothing run")
    void unknown() throws IOException {
        BundleRegistry r = registry();
        BundleRegistry.Result res = r.trigger(f.player("alice"), "op-me", BundleRegistry.By.AI);
        assertFalse(res.ran());
        assertTrue(res.message().contains("no reward called 'op-me'"), res.message());
        assertTrue(f.platform.consoleCommands.isEmpty());
        assertTrue(f.platform.logLines.stream().anyMatch(l -> l.contains("[bundle] op-me for alice by ai: refused, no such bundle")));
    }

    @Test
    @DisplayName("ai: false — the model can't trigger it, an admin can; it isn't in the prompt")
    void adminOnly() throws IOException {
        BundleRegistry r = registry();
        FakeOwner alice = f.player("alice");
        assertFalse(r.trigger(alice, "staff-kit", BundleRegistry.By.AI).ran());
        assertTrue(f.platform.consoleCommands.isEmpty());
        assertTrue(r.trigger(alice, "staff-kit", BundleRegistry.By.ADMIN).ran());
        String prompt = r.promptSection();
        assertTrue(prompt.contains("first-diamond - Celebrate a player's first diamond"), prompt);
        assertFalse(prompt.contains("staff-kit"), prompt);
    }

    @Test
    @DisplayName("no bundles configured: the prompt section is empty")
    void noBundles() throws IOException {
        f = new Fixture();
        assertEquals("", new BundleRegistry(f.platform, f.core.database(), now::get).promptSection());
    }

    // ---- through the pipeline, with a fake model ----

    private Fixture withModel(String reply) throws IOException {
        ollama = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ollama.createContext("/api/chat", ex -> {
            prompts.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = new JSONObject()
                    .put("message", new JSONObject().put("role", "assistant").put("content", reply))
                    .toString().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(body); }
        });
        ollama.start();
        String endpoint = "http://127.0.0.1:" + ollama.getAddress().getPort();
        return new Fixture(Fixture.DEFAULT_CONFIG.replace("http://127.0.0.1:1\n    model: test",
                endpoint + "\n    model: test") + BUNDLES);
    }

    @Test
    @DisplayName("the model names a bundle: its commands run as the console (Ollama only, reduced mode), "
            + "the prompt lists the bundles, and a second time is refused")
    void throughThePipeline() throws IOException {
        f = withModel("{\"action\":\"trigger_bundle\",\"parameters\":{\"name\":\"first-diamond\"},"
                + "\"response\":\"Diamonds! A banquet is in order, sir.\"}");
        FakeOwner p = f.player("alice");
        f.core.intents().submit(p, "I just found my first diamond!", IntentPipeline.Source.CONSOLE);

        assertEquals(List.of("give alice minecraft:cooked_beef 8",
                "playsound minecraft:ui.toast.challenge_complete master alice"), f.platform.consoleCommands);
        assertTrue(f.actions.executed.isEmpty(), "never through the player's executor: " + f.actions.executed);
        assertTrue(prompts.get(0).contains("REWARD BUNDLES"), "bundles listed in the prompt");
        assertTrue(prompts.get(0).contains("shrine-blessing - Reward for reaching the hidden shrine"));

        f.core.intents().submit(p, "I just found my first diamond!", IntentPipeline.Source.CONSOLE);
        assertEquals(2, f.platform.consoleCommands.size());
        assertTrue(p.messages.stream().anyMatch(m -> m.contains("already celebrated")), p.messages.toString());
    }

    @Test
    @DisplayName("/jarvis bundle: admin only; run for yourself or a named player; the console must name one")
    void command() throws IOException {
        f = new Fixture(Fixture.DEFAULT_CONFIG + BUNDLES);
        FakeOwner alice = f.player("alice");
        f.core.commands().jarvis(alice, Optional.of(alice), List.of("bundle", "run", "first-diamond"), false);
        assertTrue(alice.messages.stream().anyMatch(m -> m.contains("don't have permission")));
        assertTrue(f.platform.consoleCommands.isEmpty());

        FakeOwner admin = f.player("boss");
        admin.op = true;
        f.core.commands().jarvis(admin, Optional.of(admin), List.of("bundle", "list"), false);
        assertTrue(admin.messages.stream().anyMatch(m -> m.contains("staff-kit") && m.contains("admin only")), admin.messages.toString());
        f.core.commands().jarvis(admin, Optional.of(admin), List.of("bundle", "run", "staff-kit", "alice"), false);
        assertEquals(List.of("give alice minecraft:diamond 1"), f.platform.consoleCommands);

        FakeOwner console = f.player("console-stand-in");
        f.core.commands().jarvis(console, Optional.empty(), List.of("bundle", "run", "first-diamond"), false);
        assertTrue(console.messages.stream().anyMatch(m -> m.contains("name the player")), console.messages.toString());
    }
}
