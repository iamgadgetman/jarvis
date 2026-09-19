package com.gadgetman.jarvis.vanilla.platform;

import com.gadgetman.jarvis.core.platform.BlockTypes;
import com.gadgetman.jarvis.core.world.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Core's {@link BlockTypes} over the block registry and the game's state parser. */
public final class VanillaBlockTypes implements BlockTypes {

    private final MinecraftServer server;

    public VanillaBlockTypes(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public Optional<BlockState> parse(String spec) {
        if (spec == null || spec.isBlank()) return Optional.empty();
        net.minecraft.world.level.block.state.BlockState s = VanillaWorlds.parse(server, spec.trim());
        return s == null ? Optional.empty() : Optional.of(VanillaWorlds.state(s));
    }

    @Override
    public Set<String> placeableIds() {
        Set<String> out = new HashSet<>();
        for (Identifier key : BuiltInRegistries.BLOCK.keySet()) out.add(key.getPath());
        return out;
    }
}
