package com.gadgetman.jarvis.fabric.butler;

import com.gadgetman.jarvis.core.platform.Butler;
import com.gadgetman.jarvis.core.platform.Butlers;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.world.Vec3;
import com.gadgetman.jarvis.fabric.fake.FakePlayer;
import com.gadgetman.jarvis.fabric.platform.FabricPlatform;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Core's {@link Butlers} over a registry of fake players, one per owner. */
public final class FakeButlers implements Butlers {

    private final FabricPlatform platform;
    private final SkinCache skins;
    private final Map<UUID, FakePlayer> registry = new ConcurrentHashMap<>();

    public FakeButlers(FabricPlatform platform) {
        this.platform = platform;
        this.skins = new SkinCache(platform.server(), platform.log());
    }

    public FabricPlatform platform() {
        return platform;
    }

    public SkinCache skins() {
        return skins;
    }

    @Override
    public String name() {
        return "fake player";
    }

    @Override
    public Butler of(Owner owner) {
        return new FakeButler(this, owner.id());
    }

    @Override
    public Collection<Butler> spawned() {
        List<Butler> out = new ArrayList<>();
        for (Map.Entry<UUID, FakePlayer> e : registry.entrySet()) {
            if (!e.getValue().isLeaving()) out.add(new FakeButler(this, e.getKey()));
        }
        return out;
    }

    @Override
    public Collection<Butler> all() {
        List<Butler> out = new ArrayList<>();
        for (UUID ownerId : registry.keySet()) out.add(new FakeButler(this, ownerId));
        return out;
    }

    /** The live fake player for an owner, or null when he is not in the world. */
    FakePlayer player(UUID ownerId) {
        FakePlayer p = registry.get(ownerId);
        return p == null || p.isLeaving() ? null : p;
    }

    boolean exists(UUID ownerId) {
        return registry.containsKey(ownerId);
    }

    /** Whose butler this entity is, when it is one. For the damage and click events. */
    public Optional<UUID> ownerOf(net.minecraft.world.entity.Entity entity) {
        if (!(entity instanceof FakePlayer)) return Optional.empty();
        for (Map.Entry<UUID, FakePlayer> e : registry.entrySet()) {
            if (e.getValue() == entity) return Optional.of(e.getKey());
        }
        return Optional.empty();
    }

    void spawn(UUID ownerId, ServerLevel level, Vec3 at, String name) {
        if (player(ownerId) != null) return;
        GameProfile profile = skins.profile(name);
        ServerPlayer clash = platform.server().getPlayerList().getPlayer(profile.id());
        if (clash != null) {
            // Someone with that id is already here (a second butler of the same
            // name, say): same name and skin, a fresh id.
            profile = new GameProfile(UUID.randomUUID(), name, profile.properties());
        }
        FakePlayer p = FakePlayer.place(platform.server(), level, profile,
                new net.minecraft.world.phys.Vec3(at.x(), at.y(), at.z()), 0f, 0f, GameType.SURVIVAL);
        registry.put(ownerId, p);
    }

    void despawn(UUID ownerId) {
        FakePlayer p = registry.remove(ownerId);
        if (p == null || p.isLeaving()) return;
        p.breaker().cancel();
        p.driver().stop();
        p.kill(Component.literal("Dismissed"));
    }
}
