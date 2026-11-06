package com.gadgetman.jarvis.ai;

import com.gadgetman.jarvis.core.platform.Audience;
import com.gadgetman.jarvis.core.testing.FakeOwner;
import com.gadgetman.jarvis.core.testing.Fixture;
import com.gadgetman.jarvis.core.text.RichLine;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudActivationTest {

    /** Answers from a script, and remembers what it was asked. */
    static final class ScriptedApi implements CloudActivation.Api {
        final Deque<CloudActivation.Reply> replies = new ArrayDeque<>();
        final List<String> calls = new ArrayList<>();
        final List<JSONObject> bodies = new ArrayList<>();
        final List<Map<String, String>> headers = new ArrayList<>();

        ScriptedApi then(int status, String json) {
            replies.add(new CloudActivation.Reply(status, new JSONObject(json)));
            return this;
        }

        @Override
        public CloudActivation.Reply post(String url, JSONObject body, Map<String, String> h) throws Exception {
            calls.add("POST " + url);
            bodies.add(body);
            headers.add(h);
            return next();
        }

        @Override
        public CloudActivation.Reply get(String url, Map<String, String> h) throws Exception {
            calls.add("GET " + url);
            headers.add(h);
            return next();
        }

        private CloudActivation.Reply next() throws Exception {
            CloudActivation.Reply r = replies.poll();
            if (r == null) throw new IOException("no scripted reply");
            if (r.status() == -2) throw new IOException("connection reset");
            return r;
        }
    }

    /** Keeps rich lines whole, so the link's URL can be checked. */
    static final class Listener implements Audience {
        final List<String> lines = new ArrayList<>();
        final List<RichLine> rich = new ArrayList<>();
        @Override public void message(String text) { lines.add(text); }
        @Override public void rich(RichLine line) { rich.add(line); lines.add(line.plain()); }
        String all() { return String.join("\n", lines); }
    }

    private static final String START = """
            {"device_code":"dev123","user_code":"K7QX-M2PA","interval":5,
             "verification_url":"https://api.gadgetcraft.cloud/activate?code=K7QX-M2PA",
             "expires_at":"%s"}""";

    /** The fixture points Jarvis Cloud at a dead port, so no test can reach the real service. */
    private static final String BASE = "http://127.0.0.1:1";

    private Fixture f;
    private ScriptedApi api;
    private CloudActivation cloud;
    private Listener who;

    @BeforeEach
    void boot() throws IOException {
        f = new Fixture();
        api = new ScriptedApi();
        cloud = new CloudActivation(f.platform, f.core.aiSettings(), "11111111-1111-4111-8111-111111111111", api);
        who = new Listener();
    }

    @AfterEach
    void stop() {
        cloud.stop();
        f.close();
    }

    private static String inMinutes(int m) {
        return Instant.now().plusSeconds(m * 60L).toString();
    }

    @Test
    @DisplayName("activate: link in chat, polls until approved, then the key and endpoint land in config with Claude first")
    void happyPath() {
        api.then(201, START.formatted(inMinutes(15)))
                .then(202, "{\"status\":\"pending\"}")
                .then(200, "{\"status\":\"ready\",\"key\":\"gc_abc\",\"plan\":\"trial\",\"expires_at\":\"2026-11-20T00:00:00Z\"}");

        cloud.activate(who, "Bobs SMP");
        assertEquals("POST " + BASE + "/v1/activate/start", api.calls.get(0));
        JSONObject sent = api.bodies.get(0);
        assertEquals("11111111-1111-4111-8111-111111111111", sent.getString("server_id"));
        assertEquals("server", sent.getString("kind"));
        assertEquals("Bobs SMP", sent.getString("name"));

        RichLine link = who.rich.get(0);
        RichLine.Part button = link.parts().get(1);
        assertEquals("https://api.gadgetcraft.cloud/activate?code=K7QX-M2PA", button.url());
        assertTrue(who.all().contains("K7QX-M2PA"), "the code is shown so it can be matched on the page");
        assertTrue(cloud.isWaiting());

        f.tick(99);
        assertEquals(1, api.calls.size(), "no poll before the first interval");
        f.tick(1);
        assertEquals("POST " + BASE + "/v1/activate/poll", api.calls.get(1));
        assertEquals("dev123", api.bodies.get(1).getString("device_code"));
        assertFalse(cloud.isActive());

        f.tick(100);
        assertTrue(cloud.isActive());
        assertFalse(cloud.isWaiting());
        assertEquals("gc_abc", f.platform.config().getString("ai.claude.api-key", ""));
        assertEquals(BASE + "/v1/messages", f.platform.config().getString("ai.claude.endpoint", ""));
        assertEquals("claude", f.core.aiSettings().enabled().get(0));
        assertTrue(f.core.aiSettings().hasKey("claude"), "the connector re-read config");
        assertTrue(who.all().contains("14-day trial"));

        f.tick(500);
        assertEquals(3, api.calls.size(), "polling stopped once the key arrived");
    }

    @Test
    @DisplayName("activate: single-player sends kind=singleplayer and no name when none is given")
    void singleplayerKind() {
        f.platform.singleplayer = true;
        api.then(201, START.formatted(inMinutes(15)));
        cloud.activate(who, null);
        assertEquals("singleplayer", api.bodies.get(0).getString("kind"));
        assertFalse(api.bodies.get(0).has("name"));

        // In single-player the player may run activate without op (cheats off).
        cloud.stop();
        FakeOwner alex = f.player("Alex");
        f.core.commands().jarvis(alex, java.util.Optional.of(alex), List.of("cloud", "activate"), false);
        assertFalse(alex.messages.stream().anyMatch(m -> m.contains("permission")));
        f.core.cloud().stop();
    }

    @Test
    @DisplayName("activate: expired link stops polling with a clear message; claimed is reported too")
    void expiredAndClaimed() {
        api.then(201, START.formatted(inMinutes(15))).then(410, "{\"status\":\"expired\"}");
        cloud.activate(who, null);
        f.tick(100);
        assertFalse(cloud.isWaiting());
        assertTrue(who.all().contains("expired"));

        api.then(201, START.formatted(inMinutes(15))).then(410, "{\"status\":\"claimed\"}");
        cloud.activate(who, null);
        f.tick(100);
        assertFalse(cloud.isWaiting());
        assertTrue(who.all().contains("already collected"));
        assertFalse(cloud.isActive());
    }

    @Test
    @DisplayName("activate: a network blip while polling is retried; past the expiry it gives up without asking")
    void blipThenGiveUp() {
        api.then(201, START.formatted(inMinutes(15))).then(-2, "{}").then(202, "{}");
        cloud.activate(who, null);
        f.tick(200);
        assertTrue(cloud.isWaiting(), "a dropped connection doesn't end the wait");
        assertEquals(3, api.calls.size());

        cloud.stop();
        api.replies.clear();
        api.then(201, START.formatted(inMinutes(-2)));   // already expired (past the 1-minute grace)
        cloud.activate(who, null);
        int before = api.calls.size();
        f.tick(100);
        assertEquals(before, api.calls.size(), "no poll for a dead link");
        assertFalse(cloud.isWaiting());
        assertTrue(who.all().contains("expired"));
    }

    @Test
    @DisplayName("activate: refused when already active, when a link is waiting; 409 and 429 explained")
    void refusals() {
        api.then(409, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Jarvis Cloud: already_activated: this install already has a license key\"}}");
        cloud.activate(who, null);
        assertTrue(who.all().contains("already has a Jarvis Cloud key"));
        assertFalse(cloud.isWaiting());

        api.then(429, "{}");
        cloud.activate(who, null);
        assertTrue(who.all().contains("Too many activation attempts"));

        api.then(201, START.formatted(inMinutes(15)));
        cloud.activate(who, null);
        int calls = api.calls.size();
        cloud.activate(who, null);
        assertEquals(calls, api.calls.size(), "a second activate while one waits doesn't start another");
        assertTrue(who.all().contains("already waiting"));
        cloud.stop();

        f.core.aiSettings().setKey("claude", "gc_existing");
        cloud.activate(who, null);
        assertEquals(calls, api.calls.size());
        assertTrue(who.all().contains("already active"));
    }

    @Test
    @DisplayName("activate: unreachable service is reported, nothing left waiting")
    void unreachable() {
        cloud.activate(who, null);   // no scripted reply -> IOException
        assertTrue(who.all().contains("couldn't reach Jarvis Cloud"));
        assertFalse(cloud.isWaiting());
    }

    @Test
    @DisplayName("status: sends the key and install ID, shows plan, usage and trial end")
    void status() {
        cloud.status(who);
        assertTrue(who.all().contains("isn't active"));
        assertTrue(api.calls.isEmpty());

        f.core.aiSettings().setKey("claude", "gc_live");
        api.then(200, """
                {"plan":"trial","requests_used":12,"requests_quota":300,
                 "resets_at":"2026-12-01T00:00:00.000Z","trial_expires_at":"2026-11-20T03:11:20.832Z"}""");
        cloud.status(who);
        assertEquals("GET " + BASE + "/v1/usage", api.calls.get(0));
        assertEquals("gc_live", api.headers.get(0).get("x-api-key"));
        assertEquals("11111111-1111-4111-8111-111111111111", api.headers.get(0).get("x-jarvis-server"));
        String out = who.all();
        assertTrue(out.contains("12 of 300"));
        assertTrue(out.contains("2026-12-01"));
        assertTrue(out.contains("Trial ends 2026-11-20"));
    }

    @Test
    @DisplayName("no cloud.url means the real service; the fixture never uses it")
    void defaultUrl() throws IOException {
        var bare = new com.gadgetman.jarvis.core.testing.FakePlatform("", Files.createTempDirectory("jarvis-url"));
        assertEquals("https://api.gadgetcraft.cloud", new CloudActivation(bare, f.core.aiSettings(), "x", api).baseUrl());
        assertEquals(BASE, f.core.cloud().baseUrl(), "core's own client in tests must not point at production");
        assertTrue(api.calls.isEmpty());
    }

    @Test
    @DisplayName("cloud.url in config overrides the service address")
    void customUrl() {
        f.platform.config().set("cloud.url", "http://127.0.0.1:8787/");
        api.then(201, START.formatted(inMinutes(15)));
        cloud.activate(who, null);
        assertEquals("POST http://127.0.0.1:8787/v1/activate/start", api.calls.get(0));
    }

    @Test
    @DisplayName("install ID: made once, kept across restarts, malformed file replaced")
    void installId() throws IOException {
        String first = f.core.installId();
        assertTrue(first.matches("[0-9a-f-]{36}"));
        assertEquals(first, InstallId.load(f.dataDir, f.platform.log()), "same ID on the next start");
        Files.writeString(f.dataDir.resolve(InstallId.FILE), "bad id!");
        String fresh = InstallId.load(f.dataDir, f.platform.log());
        assertNotEquals("bad id!", fresh);
        assertTrue(fresh.matches("[0-9a-f-]{36}"));
    }

    @Test
    @DisplayName("/jarvis cloud: activate needs admin on a server; status is open; completions offered")
    void command() {
        FakeOwner steve = f.player("Steve");
        f.core.commands().jarvis(steve, java.util.Optional.of(steve), List.of("cloud", "activate"), false);
        assertTrue(steve.messages.stream().anyMatch(m -> m.contains("permission")));
        f.core.commands().jarvis(steve, java.util.Optional.of(steve), List.of("cloud"), false);
        assertTrue(steve.messages.stream().anyMatch(m -> m.contains("isn't active")));
        assertEquals(List.of("activate"), f.core.commands().jarvis(steve, java.util.Optional.of(steve), List.of("cloud", "ac"), true));
        assertTrue(f.core.commands().jarvis(steve, java.util.Optional.of(steve), List.of("clo"), true).contains("cloud"));
    }
}
