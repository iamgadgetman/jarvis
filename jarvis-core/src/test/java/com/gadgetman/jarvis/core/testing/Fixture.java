package com.gadgetman.jarvis.core.testing;

import com.gadgetman.jarvis.JarvisCore;
import com.gadgetman.jarvis.commands.ActionExecutor;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.world.Vec3;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Jarvis, booted on the fake platform.
 *
 * <p>One overworld with a flat stone floor at y=63, an sqlite database in a
 * temp folder so the service record and build history have somewhere to go,
 * and a config that keeps every network-facing feature quiet: the AI points
 * at a port nothing listens on, memory and remarks are off.
 */
public final class Fixture implements AutoCloseable {

    public static final String DEFAULT_CONFIG = """
            ai:
              provider: ollama
              ollama:
                endpoint: http://127.0.0.1:1
                model: test
              light-timeout-seconds: 1
              heavy-timeout-seconds: 1
            memory:
              enabled: false
            natural-language:
              enabled: true
              prefix: jarvis
              require-prefix: true
              cooldown-ms: 0
            butler:
              auto-greet: false
              death-commentary: false
            steward:
              remarks:
                enabled: false
            mining:
              timed-breaking: true
              debug: false
            progression:
              enabled: true
              op-bypass: true
            schematics:
              feature-tags:
                enabled: false
            ui:
              task-bar: true
              task-queue: true
            """;

    /** Records what the AI asked the server to do. */
    public static final class FakeActions implements ActionExecutor {
        public final List<String> executed = new ArrayList<>();

        @Override public boolean handles(String actionType) { return actionType.startsWith("admin_"); }
        @Override public Set<String> dangerousActions() { return Set.of("admin_dangerous"); }

        @Override
        public String execute(String actionType, JSONObject params, Owner requester) {
            executed.add(actionType);
            return "did " + actionType;
        }

        @Override public String describe(String actionType, JSONObject params) { return "do " + actionType; }
    }

    public final Path dataDir;
    public final FakePlatform platform;
    public final FakeWorld world;
    public final FakeActions actions = new FakeActions();
    public final JarvisCore core;

    public Fixture() throws IOException {
        this(DEFAULT_CONFIG);
    }

    public Fixture(String configYaml) throws IOException {
        dataDir = Files.createTempDirectory("jarvis-test");
        Files.writeString(dataDir.resolve("databases.yml"), """
                sqlite:
                  driver: org.sqlite.JDBC
                  url: jdbc:sqlite:%s
                  username: ""
                  password: ""
                """.formatted(dataDir.resolve("test.db").toString().replace("\\", "/")),
                StandardCharsets.UTF_8);
        platform = new FakePlatform(configYaml, dataDir);
        world = platform.addWorld(FakeWorld.overworld().flatFloor(48));
        core = new JarvisCore(platform, "test", platform.butlers(), actions);
        core.start();
    }

    /** An online player standing on the floor at the origin. */
    public FakeOwner player(String name) {
        FakeOwner o = platform.players().join(name);
        o.pos = new Vec3(0.5, 64, 0.5);
        return o;
    }

    /** A player whose butler is already at their side. */
    public FakeOwner summoned(String name) {
        FakeOwner o = player(name);
        core.butlers().summon(o);
        o.clearMessages();
        return o;
    }

    public FakeButlers.State butler(Owner owner) {
        return platform.butlers().state(owner);
    }

    public void tick(int ticks) {
        platform.scheduler().tick(ticks);
    }

    @Override
    public void close() {
        core.shutdown();
    }
}
