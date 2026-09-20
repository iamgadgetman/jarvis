package com.gadgetman.jarvis.vanilla.platform;

import com.gadgetman.jarvis.core.config.YamlConfig;
import com.gadgetman.jarvis.core.platform.BlockTypes;
import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Events;
import com.gadgetman.jarvis.core.platform.Items;
import com.gadgetman.jarvis.core.platform.Log;
import com.gadgetman.jarvis.core.platform.Platform;
import com.gadgetman.jarvis.core.platform.Players;
import com.gadgetman.jarvis.core.platform.Scheduler;
import com.gadgetman.jarvis.core.platform.Ui;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.world.WorldId;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Core's {@link Platform} over a vanilla server, shared by the Fabric and
 * NeoForge mods. One per server run. The loader tells it its name and
 * version; nothing else here knows which loader it is.
 */
public final class VanillaPlatform implements Platform {

    private final MinecraftServer server;
    private final Path dataDir;
    private final Log log;
    private final YamlConfig config;
    private final VanillaScheduler scheduler;
    private final VanillaPlayers players;
    private final VanillaEvents events;
    private final VanillaItems items;
    private final VanillaBlockTypes blockTypes;
    private final VanillaUi ui;
    private final BellRegistry bells;
    private final String platformName;
    private final String loaderDescription;

    /**
     * @param platformName      what core's {@code platform().name()} answers: "fabric" or "neoforge"
     * @param loaderDescription the loader and its version, for the debug report
     */
    public VanillaPlatform(MinecraftServer server, Path dataDir, Log log,
                           String platformName, String loaderDescription) throws IOException {
        this.server = server;
        this.dataDir = dataDir;
        this.log = log;
        this.platformName = platformName;
        this.loaderDescription = loaderDescription;
        Files.createDirectories(dataDir);
        String defaults = resource("config.yml");
        writeDefault("config.yml", defaults);
        writeDefault("databases.yml", DEFAULT_DATABASES);
        this.config = YamlConfig.load(dataDir.resolve("config.yml"), YamlConfig.parse(defaults));
        this.scheduler = new VanillaScheduler(server, log);
        this.players = new VanillaPlayers(server);
        this.bells = new BellRegistry(dataDir, log);
        this.events = new VanillaEvents(server, log, bells);
        this.items = new VanillaItems(server);
        this.blockTypes = new VanillaBlockTypes(server);
        this.ui = new VanillaUi(server);
    }

    /**
     * The data sources core opens on first run: sqlite beside the config.
     * Written from here rather than shipped as a resource, since the
     * repository ignores files of that name to keep credentials out of it.
     */
    private static final String DEFAULT_DATABASES = String.join("\n",
            "# Jarvis data sources. sqlite needs nothing installed; the file lives beside this one.",
            "sqlite:",
            "  driver: org.sqlite.JDBC",
            "  url: jdbc:sqlite:./config/jarvis/database.db",
            "  username: \"\"",
            "  password: \"\"",
            "");

    private void writeDefault(String name, String content) throws IOException {
        Path target = dataDir.resolve(name);
        if (Files.exists(target)) return;
        Files.writeString(target, content, StandardCharsets.UTF_8);
        log.info("Wrote default " + name + " to " + target);
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = VanillaPlatform.class.getResourceAsStream("/" + name)) {
            if (in == null) throw new IOException("Bundled " + name + " is missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Once per server tick. */
    public void tick() {
        scheduler.tick();
        events.tick();
    }

    public void shutdown() {
        ui.shutdown();
        scheduler.shutdown();
    }

    @Override public String name() { return platformName; }
    @Override public Config config() { return config; }
    @Override public Log log() { return log; }
    @Override public Scheduler scheduler() { return scheduler; }
    @Override public Path dataDir() { return dataDir; }
    @Override public Players players() { return players; }
    @Override public Events events() { return events; }
    @Override public Items items() { return items; }
    @Override public BlockTypes blockTypes() { return blockTypes; }
    @Override public Ui ui() { return ui; }

    public VanillaEvents vanillaEvents() { return events; }
    public MinecraftServer server() { return server; }

    @Override
    public String serverVersion() {
        return loaderDescription + ", Minecraft " + server.getServerVersion();
    }

    @Override
    public Optional<World> world(WorldId id) {
        ServerLevel level = VanillaWorlds.level(server, id);
        return level == null ? Optional.empty() : Optional.of(new VanillaWorld(level));
    }

    @Override
    public Collection<World> worlds() {
        List<World> out = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) out.add(new VanillaWorld(level));
        return out;
    }

    @Override
    public double mspt() {
        return server.getAverageTickTimeNanos() / 1_000_000.0;
    }

    @Override
    public double tps() {
        double ms = mspt();
        return ms <= 50.0 ? 20.0 : Math.min(20.0, 1000.0 / ms);
    }
}
