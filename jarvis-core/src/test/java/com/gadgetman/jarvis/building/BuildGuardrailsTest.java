package com.gadgetman.jarvis.building;

import com.gadgetman.jarvis.core.testing.FakeOwner;
import com.gadgetman.jarvis.core.testing.Fixture;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
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

import static org.junit.jupiter.api.Assertions.*;

/** FB-07 end to end: an AI build goes to a preview and waits for yes. */
class BuildGuardrailsTest {

    private Fixture f;
    private HttpServer ollama;

    @AfterEach
    void stop() {
        if (ollama != null) ollama.stop(0);
        if (f != null) f.close();
    }

    /** A plan of `n` blocks of `id` in a row, from the legacy "blocks" dialect. */
    private static String plan(String id, int n) {
        JSONArray blocks = new JSONArray();
        for (int i = 0; i < n; i++) blocks.put(new JSONObject().put("x", 2 + i).put("y", 0).put("z", 2).put("material", id));
        return new JSONObject().put("blocks", blocks).toString();
    }

    private FakeOwner withPlan(String reply, String extraBuildYaml) throws IOException {
        ollama = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // An OpenAI-style provider, so this isn't Ollama-only (reduced) mode,
        // where freeform builds are off.
        ollama.createContext("/v1/chat/completions", ex -> {
            ex.getRequestBody().readAllBytes();
            byte[] body = new JSONObject()
                    .put("choices", new JSONArray().put(new JSONObject()
                            .put("message", new JSONObject().put("role", "assistant").put("content", reply))))
                    .toString().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(body); }
        });
        ollama.start();
        String endpoint = "http://127.0.0.1:" + ollama.getAddress().getPort();
        f = new Fixture(Fixture.DEFAULT_CONFIG.replace("  provider: ollama\n", "  provider: auto\n  openai:\n"
                + "    api-key: test\n    endpoint: " + endpoint + "/v1/chat/completions\n    model: test\n")
                + "build:\n  planner: json\n  blocks-per-tick: 100\n"
                + "  limits:\n    default: { cooldown-seconds: -1, daily-builds: -1, daily-blocks: -1 }\n" + extraBuildYaml);
        return f.summoned("alice");
    }

    private void say(FakeOwner p, String... args) {
        f.core.commands().jarvis(p, Optional.of(p), List.of(args), false);
    }

    @Test
    @DisplayName("the plan is previewed and nothing is placed; Confirm builds it and it is in the undo history")
    void previewThenConfirm() throws IOException {
        FakeOwner p = withPlan(plan("minecraft:stone_bricks", 5), "");
        f.core.building().startBuild(p, "a little wall");
        f.tick(20);
        assertEquals(0, f.world.count("minecraft:stone_bricks"), "nothing before yes");
        assertTrue(p.wasTold("Say yes to build"), String.join("\n", p.plainMessages()));
        assertFalse(f.world.particles.isEmpty(), "an outline was drawn");
        assertTrue(f.core.building().hasPending(p));

        say(p, "confirm");
        f.tick(20);
        assertEquals(5, f.world.count("minecraft:stone_bricks"));
        assertEquals(1, f.core.undo().historySize(p));
    }

    @Test
    @DisplayName("no places nothing, stops the outline, and leaves the undo history alone")
    void previewThenNo() throws IOException {
        FakeOwner p = withPlan(plan("minecraft:stone_bricks", 5), "");
        f.core.building().startBuild(p, "a little wall");
        f.core.intents().submit(p, "no", com.gadgetman.jarvis.intent.IntentPipeline.Source.CHAT);
        assertFalse(f.core.building().hasPending(p));
        int drawn = f.world.particles.size();
        f.tick(40);
        assertEquals(drawn, f.world.particles.size(), "the outline stopped");
        assertEquals(0, f.world.count("minecraft:stone_bricks"));
        assertEquals(0, f.core.undo().historySize(p));
        assertTrue(p.wasTold("Nothing was built"));
    }

    @Test
    @DisplayName("/jarvis no and /jarvis yes are commands, not typos (found live: \"No such command: no\")")
    void slashYesNo() throws IOException {
        FakeOwner p = withPlan(plan("minecraft:stone_bricks", 3), "");
        f.core.building().startBuild(p, "a little wall");
        say(p, "no");
        assertFalse(f.core.building().hasPending(p));
        assertTrue(p.wasTold("Nothing was built"), String.join("\n", p.plainMessages()));
        f.core.building().startBuild(p, "a little wall");
        say(p, "yes");
        f.tick(20);
        assertEquals(3, f.world.count("minecraft:stone_bricks"));
    }

    @Test
    @DisplayName("the preview counts real blocks, not the air a plan uses to clear space")
    void previewCountsSolid() throws IOException {
        JSONArray blocks = new JSONArray();
        for (int i = 0; i < 3; i++) blocks.put(new JSONObject().put("x", 2 + i).put("y", 0).put("z", 2).put("material", "minecraft:stone_bricks"));
        for (int i = 0; i < 4; i++) blocks.put(new JSONObject().put("x", 2 + i).put("y", 1).put("z", 2).put("material", "minecraft:air"));
        FakeOwner p = withPlan(new JSONObject().put("blocks", blocks).toString(), "");
        f.core.building().startBuild(p, "a little wall");
        assertTrue(p.wasTold("3 blocks, 4x2x1"), String.join("\n", p.plainMessages()));
    }

    @Test
    @DisplayName("spoken yes builds")
    void spokenYes() throws IOException {
        FakeOwner p = withPlan(plan("minecraft:stone_bricks", 3), "");
        f.core.building().startBuild(p, "a little wall");
        f.core.intents().submit(p, "yes", com.gadgetman.jarvis.intent.IntentPipeline.Source.CHAT);
        f.tick(20);
        assertEquals(3, f.world.count("minecraft:stone_bricks"));
    }

    @Test
    @DisplayName("a plan with TNT is refused before any preview")
    void tntRefused() throws IOException {
        FakeOwner p = withPlan(plan("minecraft:tnt", 4), "");
        f.core.building().startBuild(p, "a tower of tnt");
        assertFalse(f.core.building().hasPending(p));
        assertTrue(p.wasTold("tnt is blocked"), String.join("\n", p.plainMessages()));
        f.tick(20);
        assertEquals(0, f.world.count("minecraft:tnt"));
    }

    @Test
    @DisplayName("preview-seconds: 0 builds straight away, as before")
    void noPreview() throws IOException {
        FakeOwner p = withPlan(plan("minecraft:stone_bricks", 4), "  guardrails:\n    preview-seconds: 0\n");
        f.core.building().startBuild(p, "a little wall");
        f.tick(20);
        assertEquals(4, f.world.count("minecraft:stone_bricks"));
    }
}
