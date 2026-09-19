package com.gadgetman.jarvis.platform;

import com.gadgetman.jarvis.core.platform.BlockScan;
import com.gadgetman.jarvis.core.platform.Container;
import com.gadgetman.jarvis.core.platform.Entity;
import com.gadgetman.jarvis.core.platform.EntityKind;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.BlockState;
import com.gadgetman.jarvis.core.world.Environment;
import com.gadgetman.jarvis.core.world.Ids;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.core.world.Tag;
import com.gadgetman.jarvis.core.world.Vec3;
import com.gadgetman.jarvis.core.world.WorldId;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Core's {@link World} over a Bukkit world. Cheap to make; equal when the worlds are. */
public final class PaperWorld implements World {

    private static final Map<String, Optional<org.bukkit.Tag<Material>>> TAGS = new ConcurrentHashMap<>();

    private final org.bukkit.World w;

    public PaperWorld(org.bukkit.World w) {
        this.w = w;
    }

    public org.bukkit.World handle() {
        return w;
    }

    private Block at(BlockPos p) {
        return w.getBlockAt(p.x(), p.y(), p.z());
    }

    private Location loc(Vec3 v) {
        return PaperWorlds.location(w, v);
    }

    @Override public WorldId id() { return PaperWorlds.id(w); }
    @Override public String name() { return w.getName(); }
    @Override public Environment environment() { return PaperWorlds.env(w.getEnvironment()); }
    @Override public long time() { return w.getTime(); }
    @Override public long fullTime() { return w.getFullTime(); }
    @Override public boolean isRaining() { return w.hasStorm(); }
    @Override public boolean isThundering() { return w.isThundering(); }
    @Override public int minY() { return w.getMinHeight(); }
    @Override public int maxY() { return w.getMaxHeight() - 1; }
    @Override public boolean isChunkLoaded(int chunkX, int chunkZ) { return w.isChunkLoaded(chunkX, chunkZ); }
    @Override public Vec3 spawn() { return PaperWorlds.vec(w.getSpawnLocation()); }

    @Override public BlockState block(BlockPos pos) { return PaperWorlds.state(at(pos).getBlockData()); }
    @Override public void setBlock(BlockPos pos, BlockState state) { at(pos).setBlockData(PaperWorlds.data(state), true); }
    @Override public boolean isSolid(BlockPos pos) { return at(pos).getType().isSolid(); }
    @Override public boolean isPassable(BlockPos pos) { return at(pos).isPassable(); }
    @Override public boolean isLiquid(BlockPos pos) { return at(pos).isLiquid(); }
    @Override public boolean isOccluding(BlockPos pos) { return at(pos).getType().isOccluding(); }

    @Override
    public boolean is(BlockPos pos, Tag tag) {
        Optional<org.bukkit.Tag<Material>> t = TAGS.computeIfAbsent(tag.id(), id -> {
            NamespacedKey key = NamespacedKey.fromString(id);
            return key == null ? Optional.empty()
                    : Optional.ofNullable(Bukkit.getTag(org.bukkit.Tag.REGISTRY_BLOCKS, key, Material.class));
        });
        return t.isPresent() && t.get().isTagged(at(pos).getType());
    }

    @Override public int highestY(int x, int z) { return w.getHighestBlockYAt(x, z); }
    @Override public int blockLight(BlockPos pos) { return at(pos).getLightFromBlocks(); }
    @Override public int skyLight(BlockPos pos) { return at(pos).getLightFromSky(); }

    @Override
    public String biome(BlockPos pos) {
        Block b = at(pos);
        try {
            return b.getBiome().getKey().getKey();
        } catch (Throwable t) {
            // Biome moved from enum to registry interface across versions;
            // the string form is good enough for prompts and scores.
            return String.valueOf(b.getBiome()).toLowerCase(Locale.ROOT);
        }
    }

    @Override
    public void breakNaturally(BlockPos pos, Item tool) {
        at(pos).breakNaturally(PaperItems.toStack(tool));
    }

    @Override
    public Entity dropItem(Vec3 at, Item item) {
        return new PaperEntity(w.dropItemNaturally(loc(at), PaperItems.toStack(item)));
    }

    @Override
    public Optional<Container> container(BlockPos pos) {
        return at(pos).getState() instanceof org.bukkit.block.Container c
                ? Optional.of(new PaperContainer(c.getInventory()))
                : Optional.empty();
    }

    @Override
    public List<Entity> nearby(Vec3 center, double radius) {
        List<Entity> out = new ArrayList<>();
        double r2 = radius * radius;
        Location c = loc(center);
        for (org.bukkit.entity.Entity e : w.getNearbyEntities(c, radius, radius, radius)) {
            if (e.getLocation().distanceSquared(c) <= r2) out.add(new PaperEntity(e));
        }
        return out;
    }

    @Override
    public List<Entity> nearby(Vec3 center, double dx, double dy, double dz) {
        List<Entity> out = new ArrayList<>();
        for (org.bukkit.entity.Entity e : w.getNearbyEntities(loc(center), dx, dy, dz)) {
            out.add(new PaperEntity(e));
        }
        return out;
    }

    @Override
    public List<Entity> nearby(Vec3 center, double radius, EntityKind kind) {
        List<Entity> out = new ArrayList<>();
        for (Entity e : nearby(center, radius)) {
            if (e.kind() == kind) out.add(e);
        }
        return out;
    }

    @Override
    public Optional<Entity> entity(UUID id) {
        org.bukkit.entity.Entity e = Bukkit.getEntity(id);
        return e != null && e.getWorld().equals(w) ? Optional.of(new PaperEntity(e)) : Optional.empty();
    }

    @Override
    public void sound(Vec3 at, String soundId, float volume, float pitch) {
        w.playSound(loc(at), soundId, volume, pitch);
    }

    @Override
    public void particle(Vec3 at, String particleId, int count, double spread) {
        Particle p;
        try {
            p = Particle.valueOf(Ids.key(particleId).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return;   // a particle that does not exist here is not worth a stack trace
        }
        w.spawnParticle(p, loc(at), count, spread, spread, spread);
    }

    @Override
    public BlockScan snapshot(BlockPos min, BlockPos max) {
        Map<Long, ChunkSnapshot> chunks = new HashMap<>();
        for (int cx = min.x() >> 4; cx <= max.x() >> 4; cx++) {
            for (int cz = min.z() >> 4; cz <= max.z() >> 4; cz++) {
                if (w.isChunkLoaded(cx, cz)) {
                    chunks.put(PaperBlockScan.key(cx, cz), w.getChunkAt(cx, cz).getChunkSnapshot(false, false, false));
                }
            }
        }
        return new PaperBlockScan(min, max, minY(), maxY(), chunks);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PaperWorld other && other.w.getUID().equals(w.getUID());
    }

    @Override
    public int hashCode() {
        return w.getUID().hashCode();
    }

    @Override
    public String toString() {
        return w.getName();
    }
}
