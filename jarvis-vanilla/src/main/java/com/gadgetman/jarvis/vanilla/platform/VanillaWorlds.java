package com.gadgetman.jarvis.vanilla.platform;

import com.gadgetman.jarvis.core.platform.EntityKind;
import com.gadgetman.jarvis.core.platform.Site;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.BlockState;
import com.gadgetman.jarvis.core.world.Environment;
import com.gadgetman.jarvis.core.world.Look;
import com.gadgetman.jarvis.core.world.Vec3;
import com.gadgetman.jarvis.core.world.WorldId;
import com.gadgetman.jarvis.vanilla.fake.FakePlayer;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;

import java.util.LinkedHashMap;
import java.util.Map;

/** Conversions between core's value types and the game's. */
public final class VanillaWorlds {

    private VanillaWorlds() { }

    public static Vec3 vec(net.minecraft.world.phys.Vec3 v) {
        return new Vec3(v.x, v.y, v.z);
    }

    public static net.minecraft.world.phys.Vec3 mc(Vec3 v) {
        return new net.minecraft.world.phys.Vec3(v.x(), v.y(), v.z());
    }

    public static net.minecraft.core.BlockPos mc(BlockPos p) {
        return new net.minecraft.core.BlockPos(p.x(), p.y(), p.z());
    }

    public static BlockPos block(net.minecraft.core.BlockPos p) {
        return new BlockPos(p.getX(), p.getY(), p.getZ());
    }

    public static Look look(Entity e) {
        return new Look(e.getYRot(), e.getXRot());
    }

    public static WorldId id(Level level) {
        return new WorldId(level.dimension().identifier().toString());
    }

    /** The level for an id, matching the dimension key first and its path second. */
    public static ServerLevel level(MinecraftServer server, WorldId id) {
        Identifier key = Identifier.tryParse(id.id());
        if (key != null) {
            ServerLevel l = server.getLevel(ResourceKey.create(Registries.DIMENSION, key));
            if (l != null) return l;
        }
        for (ServerLevel l : server.getAllLevels()) {
            if (l.dimension().identifier().getPath().equals(id.id())) return l;
        }
        return null;
    }

    public static Environment env(ServerLevel level) {
        if (level.dimension() == Level.NETHER) return Environment.NETHER;
        if (level.dimension() == Level.END) return Environment.END;
        return Environment.NORMAL;
    }

    public static Site site(Entity e) {
        return new Site(new VanillaWorld((ServerLevel) e.level()), vec(e.position()));
    }

    /** "minecraft:oak_log[axis=y]" as core's id plus properties. */
    public static BlockState state(net.minecraft.world.level.block.state.BlockState s) {
        String text = BlockStateParser.serialize(s);
        int bracket = text.indexOf('[');
        if (bracket < 0) return BlockState.of(text);
        String id = text.substring(0, bracket);
        String body = text.substring(bracket + 1, text.lastIndexOf(']'));
        Map<String, String> props = new LinkedHashMap<>();
        if (!body.isEmpty()) {
            for (String pair : body.split(",")) {
                int eq = pair.indexOf('=');
                if (eq > 0) props.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return new BlockState(id, props);
    }

    /** Core's spec through the game's own parser, or null when it does not parse. */
    public static net.minecraft.world.level.block.state.BlockState parse(MinecraftServer server, String spec) {
        String s = spec.contains(":") ? spec : "minecraft:" + spec;
        try {
            return BlockStateParser.parseForBlock(server.registryAccess().lookupOrThrow(Registries.BLOCK),
                    new StringReader(s), true).blockState();
        } catch (CommandSyntaxException | RuntimeException e) {
            return null;
        }
    }

    public static net.minecraft.world.level.block.state.BlockState data(MinecraftServer server, BlockState state) {
        if (state.props().isEmpty()) return parse(server, state.id());
        StringBuilder sb = new StringBuilder(state.id()).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : state.props().entrySet()) {
            if (!first) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return parse(server, sb.append(']').toString());
    }

    public static EntityKind kind(Entity e) {
        if (e instanceof FakePlayer) return EntityKind.BUTLER;
        if (e instanceof Player) return EntityKind.PLAYER;
        if (e instanceof ItemEntity) return EntityKind.ITEM;
        if (e instanceof Projectile) return EntityKind.PROJECTILE;
        if (e instanceof Enemy) return EntityKind.HOSTILE;
        if (e instanceof LivingEntity) return EntityKind.PASSIVE;
        return EntityKind.OTHER;
    }

    public static String typeId(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
    }
}
