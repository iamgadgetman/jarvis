package com.gadgetman.jarvis.platform;

import com.gadgetman.jarvis.core.platform.BlockTypes;
import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Events;
import com.gadgetman.jarvis.core.platform.Items;
import com.gadgetman.jarvis.core.platform.Log;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Platform;
import com.gadgetman.jarvis.core.platform.Players;
import com.gadgetman.jarvis.core.platform.Scheduler;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.world.BlockState;
import com.gadgetman.jarvis.core.world.WorldId;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** The Paper implementation of core's {@link Platform}. */
public final class PaperPlatform implements Platform {

    private final JavaPlugin plugin;
    private final PaperConfig config;
    private final Log log;
    private final PaperScheduler scheduler;
    private final PaperPlayers players;
    private final PaperEvents events;
    private final Items items = new Items() {
        @Override
        public boolean isEdible(String id) {
            org.bukkit.Material m = org.bukkit.Material.matchMaterial(id);
            return m != null && m.isEdible();
        }

        @Override
        public int maxStackSize(String id) {
            org.bukkit.Material m = org.bukkit.Material.matchMaterial(id);
            return m == null ? 64 : m.getMaxStackSize();
        }
    };

    private final BlockTypes blockTypes = new BlockTypes() {
        @Override
        public Optional<BlockState> parse(String spec) {
            if (spec == null || spec.isBlank()) return Optional.empty();
            String s = spec.contains(":") ? spec : "minecraft:" + spec;
            try {
                org.bukkit.block.data.BlockData data = Bukkit.createBlockData(s);
                return data.getMaterial().isBlock() ? Optional.of(PaperWorlds.state(data)) : Optional.empty();
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }

        @Override
        public Set<String> placeableIds() {
            Set<String> out = new HashSet<>();
            for (Material m : Material.values()) {
                if (m.isBlock() && !m.isLegacy()) out.add(m.getKey().getKey());
            }
            return out;
        }
    };

    public PaperPlatform(JavaPlugin plugin) {
        this.plugin = plugin;
        this.config = new PaperConfig(plugin);
        this.log = Log.of(plugin.getLogger());
        this.scheduler = new PaperScheduler(plugin);
        this.players = new PaperPlayers();
        this.events = new PaperEvents(log);
    }

    /** Register the event bridge with Bukkit. Call once from onEnable. */
    public void registerEvents() {
        Bukkit.getPluginManager().registerEvents(events, plugin);
    }

    @Override public String name() { return "paper"; }
    @Override public Config config() { return config; }
    @Override public Log log() { return log; }
    @Override public Scheduler scheduler() { return scheduler; }
    @Override public Path dataDir() { return plugin.getDataFolder().toPath(); }
    @Override public Players players() { return players; }
    @Override public Events events() { return events; }
    @Override public Items items() { return items; }
    @Override public BlockTypes blockTypes() { return blockTypes; }

    /** The event bridge, for the adapter to tell it whose butler an entity is. */
    public PaperEvents paperEvents() { return events; }

    @Override
    public Optional<World> world(WorldId id) {
        org.bukkit.World w = PaperWorlds.bukkitWorld(id);
        return w == null ? Optional.empty() : Optional.of(new PaperWorld(w));
    }

    @Override
    public Collection<World> worlds() {
        List<World> out = new ArrayList<>();
        for (org.bukkit.World w : Bukkit.getWorlds()) out.add(new PaperWorld(w));
        return out;
    }

    @Override
    public double tps() {
        double[] tps = Bukkit.getTPS();
        return tps.length > 0 ? Math.min(20.0, tps[0]) : 20.0;
    }

    @Override
    public double mspt() {
        return Bukkit.getAverageTickTime();
    }

    // ---- conveniences for the Bukkit side ----

    public Owner owner(Player player) {
        return new PaperOwner(player.getUniqueId());
    }

    public World world(org.bukkit.World world) {
        return new PaperWorld(world);
    }

    /** The live player behind an owner handle, when online. */
    public Optional<Player> player(Owner owner) {
        return Optional.ofNullable(Bukkit.getPlayer(owner.id()));
    }
}
