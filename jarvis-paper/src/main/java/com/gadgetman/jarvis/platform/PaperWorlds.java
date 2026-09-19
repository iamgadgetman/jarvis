package com.gadgetman.jarvis.platform;

import com.gadgetman.jarvis.core.platform.EntityKind;
import com.gadgetman.jarvis.core.platform.Site;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.BlockState;
import com.gadgetman.jarvis.core.world.Environment;
import com.gadgetman.jarvis.core.world.Look;
import com.gadgetman.jarvis.core.world.Vec3;
import com.gadgetman.jarvis.core.world.WorldId;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.util.Vector;

import java.util.LinkedHashMap;
import java.util.Map;

/** Conversions between core's value types and Bukkit's. */
public final class PaperWorlds {

    private PaperWorlds() { }

    public static Vec3 vec(Location l) {
        return new Vec3(l.getX(), l.getY(), l.getZ());
    }

    public static Vec3 vec(Vector v) {
        return new Vec3(v.getX(), v.getY(), v.getZ());
    }

    public static Vector vector(Vec3 v) {
        return new Vector(v.x(), v.y(), v.z());
    }

    public static BlockPos block(Location l) {
        return new BlockPos(l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    public static Look look(Location l) {
        return new Look(l.getYaw(), l.getPitch());
    }

    public static Location location(org.bukkit.World w, Vec3 v) {
        return new Location(w, v.x(), v.y(), v.z());
    }

    public static Location location(org.bukkit.World w, Vec3 v, Look look) {
        return new Location(w, v.x(), v.y(), v.z(), look.yaw(), look.pitch());
    }

    public static Location location(org.bukkit.World w, BlockPos p) {
        return new Location(w, p.x(), p.y(), p.z());
    }

    public static Location location(World world, Vec3 v) {
        return location(handle(world), v);
    }

    public static org.bukkit.World handle(World world) {
        return ((PaperWorld) world).handle();
    }

    public static World world(org.bukkit.World w) {
        return new PaperWorld(w);
    }

    /** Null for a null location or one with no world, so callers can pass what they have. */
    public static Site site(Location l) {
        if (l == null || l.getWorld() == null) return null;
        return new Site(world(l.getWorld()), vec(l));
    }

    public static WorldId id(org.bukkit.World w) {
        return new WorldId(w.getKey().toString());
    }

    /** The Bukkit world for an id, matching the key first and the plain name second. */
    public static org.bukkit.World bukkitWorld(WorldId id) {
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            if (w.getKey().toString().equals(id.id())) return w;
        }
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            if (w.getName().equals(id.id())) return w;
        }
        return null;
    }

    public static Environment env(org.bukkit.World.Environment e) {
        return switch (e) {
            case NETHER -> Environment.NETHER;
            case THE_END -> Environment.END;
            default -> Environment.NORMAL;
        };
    }

    /** "minecraft:oak_log[axis=y]" as core's id plus properties. */
    public static BlockState state(BlockData data) {
        String s = data.getAsString(true);
        int bracket = s.indexOf('[');
        if (bracket < 0) return BlockState.of(s);
        String id = s.substring(0, bracket);
        String body = s.substring(bracket + 1, s.lastIndexOf(']'));
        Map<String, String> props = new LinkedHashMap<>();
        if (!body.isEmpty()) {
            for (String pair : body.split(",")) {
                int eq = pair.indexOf('=');
                if (eq > 0) props.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return new BlockState(id, props);
    }

    public static BlockData data(BlockState state) {
        if (state.props().isEmpty()) return Bukkit.createBlockData(state.id());
        StringBuilder sb = new StringBuilder(state.id()).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : state.props().entrySet()) {
            if (!first) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return Bukkit.createBlockData(sb.append(']').toString());
    }

    public static EntityKind kind(org.bukkit.entity.Entity e) {
        if (e instanceof Player) return EntityKind.PLAYER;
        if (e instanceof org.bukkit.entity.Item) return EntityKind.ITEM;
        if (e instanceof Projectile) return EntityKind.PROJECTILE;
        if (e instanceof Enemy) return EntityKind.HOSTILE;
        if (e instanceof LivingEntity) return EntityKind.PASSIVE;
        return EntityKind.OTHER;
    }
}
