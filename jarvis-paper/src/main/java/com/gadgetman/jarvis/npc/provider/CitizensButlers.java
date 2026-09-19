package com.gadgetman.jarvis.npc.provider;

import com.gadgetman.jarvis.Jarvis;
import com.gadgetman.jarvis.core.platform.Butler;
import com.gadgetman.jarvis.core.platform.Butlers;
import com.gadgetman.jarvis.core.platform.Owner;
import net.citizensnpcs.api.npc.NPC;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Core's {@link Butlers} over the Citizens registry the provider owns. */
public final class CitizensButlers implements Butlers {

    private final Jarvis plugin;
    private final CitizensNPCProvider provider;

    public CitizensButlers(Jarvis plugin, CitizensNPCProvider provider) {
        this.plugin = plugin;
        this.provider = provider;
    }

    @Override
    public String name() {
        return "Citizens";
    }

    @Override
    public Butler of(Owner owner) {
        return new CitizensButler(plugin, provider, owner.id());
    }

    @Override
    public Collection<Butler> spawned() {
        List<Butler> out = new ArrayList<>();
        for (Map.Entry<UUID, NPC> e : provider.registry().entrySet()) {
            if (e.getValue().isSpawned()) out.add(new CitizensButler(plugin, provider, e.getKey()));
        }
        return out;
    }

    @Override
    public Collection<Butler> all() {
        List<Butler> out = new ArrayList<>();
        for (UUID ownerId : provider.registry().keySet()) {
            out.add(new CitizensButler(plugin, provider, ownerId));
        }
        return out;
    }

    /** Whose butler this entity is, when it is one. For the damage events. */
    public Optional<UUID> ownerOf(org.bukkit.entity.Entity entity) {
        return Optional.ofNullable(provider.ownerOf(entity));
    }
}
