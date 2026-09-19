package com.gadgetman.jarvis.core.testing;

import com.gadgetman.jarvis.core.config.YamlConfig;
import com.gadgetman.jarvis.core.platform.BlockTypes;
import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Items;
import com.gadgetman.jarvis.core.platform.Log;
import com.gadgetman.jarvis.core.platform.Platform;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.world.BlockState;
import com.gadgetman.jarvis.core.world.Ids;
import com.gadgetman.jarvis.core.world.WorldId;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link Platform} made of fakes, for driving core without a server.
 *
 * <p>Config comes from YAML text the test supplies; the log is kept in a
 * list; time only moves when the test ticks the scheduler.
 */
public final class FakePlatform implements Platform {

    private final Config config;
    private final Path dataDir;
    private final FakeScheduler scheduler = new FakeScheduler();
    private final FakeEvents events = new FakeEvents();
    private final FakePlayers players = new FakePlayers(this);
    private final FakeButlers butlers = new FakeButlers(this);
    private final FakeUi ui = new FakeUi();
    private final Map<WorldId, FakeWorld> worlds = new LinkedHashMap<>();
    public final List<String> logLines = new ArrayList<>();
    public double tps = 20.0;

    private final Log log = new Log() {
        @Override public void info(String msg) { logLines.add("INFO " + msg); }
        @Override public void warn(String msg) { logLines.add("WARN " + msg); }
        @Override public void fine(String msg) { logLines.add("FINE " + msg); }
        @Override public void error(String msg, Throwable t) { logLines.add("ERROR " + msg + ": " + t); }
    };

    public FakePlatform(String configYaml, Path dataDir) {
        this.config = YamlConfig.parse(configYaml == null ? "" : configYaml);
        this.dataDir = dataDir;
    }

    public FakeWorld addWorld(FakeWorld world) {
        worlds.put(world.id(), world);
        return world;
    }

    @Override public String name() { return "fake"; }
    @Override public Config config() { return config; }
    @Override public Log log() { return log; }
    @Override public FakeScheduler scheduler() { return scheduler; }
    @Override public Path dataDir() { return dataDir; }
    @Override public FakePlayers players() { return players; }
    @Override public FakeEvents events() { return events; }
    @Override public FakeUi ui() { return ui; }
    @Override public String serverVersion() { return "Fake 1.0 (API test)"; }
    @Override public double tps() { return tps; }
    @Override public double mspt() { return 1000.0 / 20.0 / (tps / 20.0); }

    public FakeButlers butlers() {
        return butlers;
    }

    @Override
    public Optional<World> world(WorldId id) {
        return Optional.ofNullable(worlds.get(id));
    }

    @Override
    public Collection<World> worlds() {
        return new ArrayList<>(worlds.values());
    }

    public boolean logged(String fragment) {
        return logLines.stream().anyMatch(l -> l.contains(fragment));
    }

    // ---- registries ----

    private static final Set<String> EDIBLE = Set.of("minecraft:bread", "minecraft:apple", "minecraft:cooked_beef",
            "minecraft:cooked_cod", "minecraft:carrot", "minecraft:potato", "minecraft:baked_potato");

    private final Items items = new Items() {
        @Override public boolean isEdible(String id) { return EDIBLE.contains(id); }

        @Override
        public int maxStackSize(String id) {
            String key = Ids.key(id);
            if (key.endsWith("_sword") || key.endsWith("_pickaxe") || key.endsWith("_axe")
                    || key.endsWith("_hoe") || key.endsWith("_shovel") || key.equals("fishing_rod")) return 1;
            if (key.equals("ender_pearl") || key.equals("snowball") || key.equals("egg")) return 16;
            return 64;
        }

        @Override
        public Optional<String> resolve(String name) {
            if (name == null) return Optional.empty();
            String key = Ids.key(name.trim().toLowerCase(Locale.ROOT));
            return key.matches("[a-z0-9_]+") ? Optional.of(Ids.of(key)) : Optional.empty();
        }
    };

    /** Things a plan might name that are items, never blocks. */
    private static final Set<String> ITEM_ONLY = Set.of("diamond", "stick", "bread", "brick", "coal",
            "iron_ingot", "gold_ingot", "bow", "arrow", "string", "flint", "book", "paper", "compass");

    private final BlockTypes blockTypes = new BlockTypes() {
        @Override
        public Optional<BlockState> parse(String spec) {
            if (spec == null || spec.isBlank()) return Optional.empty();
            String s = spec.trim();
            String id = s;
            Map<String, String> props = new LinkedHashMap<>();
            int bracket = s.indexOf('[');
            if (bracket >= 0) {
                id = s.substring(0, bracket);
                String body = s.substring(bracket + 1, s.lastIndexOf(']'));
                for (String pair : body.split(",")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) props.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
            String key = Ids.key(id);
            if (!key.matches("[a-z0-9_]+") || ITEM_ONLY.contains(key)) return Optional.empty();
            if (key.endsWith("_sword") || key.endsWith("_pickaxe") || key.endsWith("_axe")
                    || key.endsWith("_hoe") || key.endsWith("_shovel") || key.endsWith("_ingot")) return Optional.empty();
            return Optional.of(new BlockState(Ids.of(key), props));
        }

        @Override
        public Set<String> placeableIds() {
            return Set.of("stone", "dirt", "oak_planks", "oak_log", "oak_stairs", "glass", "glass_pane",
                    "cobblestone", "stone_bricks", "torch", "red_bed", "oak_fence", "cobblestone_wall", "air");
        }
    };

    @Override public Items items() { return items; }
    @Override public BlockTypes blockTypes() { return blockTypes; }
}
