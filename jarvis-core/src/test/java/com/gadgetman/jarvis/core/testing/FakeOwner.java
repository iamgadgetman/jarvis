package com.gadgetman.jarvis.core.testing;

import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Site;
import com.gadgetman.jarvis.core.text.Colors;
import com.gadgetman.jarvis.core.text.RichLine;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.core.world.Look;
import com.gadgetman.jarvis.core.world.Vec3;
import com.gadgetman.jarvis.core.world.WorldId;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** A player the test controls. Everything sent to them is kept for asserting on. */
public final class FakeOwner implements Owner {

    private final UUID id;
    private final String name;
    private final FakePlatform platform;

    public boolean online = true;
    public boolean op = false;
    public final Set<String> permissions = new HashSet<>(Set.of("jarvis.use", "jarvis.menu.use"));
    public WorldId world = WorldId.OVERWORLD;
    public Vec3 pos = new Vec3(0.5, 64, 0.5);
    public Look look = Look.SOUTH;
    public double health = 20;
    public int level = 0;
    public int foodLevel = 20;
    public double heldItemWear = -1;
    public boolean sneaking = false;
    public String gameMode = "survival";
    public Item heldItem = Item.EMPTY;
    public final List<Item> inventory = new ArrayList<>();
    public BlockPos targetBlock = null;

    public final List<String> messages = new ArrayList<>();
    public final List<String> actionBars = new ArrayList<>();
    public final List<String> sounds = new ArrayList<>();
    public final List<Item> given = new ArrayList<>();
    public final List<Site> teleports = new ArrayList<>();

    FakeOwner(FakePlatform platform, UUID id, String name) {
        this.platform = platform;
        this.id = id;
        this.name = name;
    }

    @Override public UUID id() { return id; }
    @Override public String name() { return name; }
    @Override public boolean isOnline() { return online; }
    @Override public boolean isOp() { return op; }
    @Override public boolean hasPermission(String node) { return op || permissions.contains(node); }
    @Override public WorldId world() { return world; }
    @Override public Vec3 pos() { return pos; }
    @Override public Vec3 eyePos() { return pos.add(0, 1.62, 0); }
    @Override public Look look() { return look; }
    @Override public double health() { return health; }
    @Override public double maxHealth() { return 20; }
    @Override public int level() { return level; }
    @Override public int foodLevel() { return foodLevel; }
    @Override public double heldItemWear() { return heldItemWear; }
    @Override public boolean isSneaking() { return sneaking; }
    @Override public String gameMode() { return gameMode; }
    @Override public Item heldItem() { return heldItem; }
    @Override public List<Item> inventory() { return List.copyOf(inventory); }

    @Override
    public void give(Item item) {
        given.add(item);
        inventory.add(item);
    }

    @Override
    public Optional<BlockPos> targetBlock(double reach) {
        return Optional.ofNullable(targetBlock);
    }

    @Override
    public Site respawnPlace() {
        return new Site(platform.world(world).orElseThrow(), platform.world(world).orElseThrow().spawn());
    }

    @Override
    public void teleport(WorldId world, Vec3 pos, Look look) {
        this.world = world;
        this.pos = pos;
        this.look = look;
        teleports.add(new Site(platform.world(world).orElseThrow(), pos));
    }

    @Override public void message(String text) { messages.add(text); }
    @Override public void rich(RichLine line) { messages.add(line.plain()); }
    @Override public void actionBar(String text) { actionBars.add(text); }
    @Override public void sound(String soundId, float volume, float pitch) { sounds.add(soundId); }

    // ---- helpers for assertions ----

    /** Every message so far, colour codes stripped. */
    public List<String> plainMessages() {
        List<String> out = new ArrayList<>();
        for (String m : messages) out.add(Colors.strip(m));
        return out;
    }

    public boolean wasTold(String fragment) {
        return plainMessages().stream().anyMatch(m -> m.contains(fragment));
    }

    public String lastMessage() {
        return messages.isEmpty() ? null : Colors.strip(messages.get(messages.size() - 1));
    }

    public void clearMessages() {
        messages.clear();
        actionBars.clear();
    }

    @Override public boolean equals(Object o) { return o instanceof Owner other && other.id().equals(id); }
    @Override public int hashCode() { return id.hashCode(); }
    @Override public String toString() { return name; }
}
