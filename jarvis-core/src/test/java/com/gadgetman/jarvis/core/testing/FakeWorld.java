package com.gadgetman.jarvis.core.testing;

import com.gadgetman.jarvis.core.platform.BlockScan;
import com.gadgetman.jarvis.core.platform.Container;
import com.gadgetman.jarvis.core.platform.Entity;
import com.gadgetman.jarvis.core.platform.EntityKind;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.BlockState;
import com.gadgetman.jarvis.core.world.Environment;
import com.gadgetman.jarvis.core.world.Ids;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.core.world.Tag;
import com.gadgetman.jarvis.core.world.Vec3;
import com.gadgetman.jarvis.core.world.WorldId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A world in a hash map. Unset positions are air; a handful of block ids
 * are known to be non-solid, liquid or see-through, and tags are guessed
 * from the id the way the real registry would answer.
 */
public final class FakeWorld implements World {

    private final WorldId id;
    private final String name;
    private final Environment environment;
    private final Map<BlockPos, BlockState> blocks = new HashMap<>();
    private final Map<BlockPos, Integer> blockLight = new HashMap<>();
    private final Map<BlockPos, Integer> skyLight = new HashMap<>();
    private final Map<BlockPos, FakeContainer> containers = new HashMap<>();
    public final List<FakeEntity> entities = new ArrayList<>();
    public final List<String> sounds = new ArrayList<>();
    public final List<String> particles = new ArrayList<>();
    public final List<BlockPos> brokenNaturally = new ArrayList<>();
    public long time = 1000;
    public long fullTime = 1000;
    public boolean raining = false;
    public boolean thundering = false;
    public Vec3 spawn = new Vec3(0.5, 64, 0.5);
    public int defaultSkyLight = 15;
    public int defaultBlockLight = 0;

    private static final Set<String> NON_SOLID = Set.of(
            Ids.AIR, Ids.CAVE_AIR, Ids.VOID_AIR, Ids.WATER, Ids.LAVA, Ids.TORCH, Ids.WALL_TORCH,
            Ids.LANTERN, Ids.SOUL_LANTERN, Ids.END_ROD, Ids.LADDER, Ids.RAIL, Ids.WHEAT, Ids.CARROTS,
            Ids.POTATOES, Ids.BEETROOTS, Ids.NETHER_PORTAL, Ids.FIRE, "minecraft:short_grass",
            "minecraft:tall_grass", "minecraft:vine", "minecraft:snow", "minecraft:oak_sign");
    private static final Set<String> NOT_OCCLUDING = Set.of("minecraft:glass", Ids.IRON_BARS,
            Ids.CHEST, Ids.TRAPPED_CHEST, Ids.BARREL, Ids.SEA_LANTERN);

    public FakeWorld(WorldId id, String name, Environment environment) {
        this.id = id;
        this.name = name;
        this.environment = environment;
    }

    public static FakeWorld overworld() {
        return new FakeWorld(WorldId.OVERWORLD, "world", Environment.NORMAL);
    }

    // ---- building the world ----

    public FakeWorld set(int x, int y, int z, String id) {
        return set(new BlockPos(x, y, z), BlockState.of(id));
    }

    public FakeWorld set(BlockPos pos, BlockState state) {
        if (state == null || state.isAir()) blocks.remove(pos);
        else blocks.put(pos, state);
        return this;
    }

    /** Fill a box, inclusive on every side. */
    public FakeWorld fill(int x1, int y1, int z1, int x2, int y2, int z2, String id) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++)
                    set(x, y, z, id);
        return this;
    }

    /** A flat stone floor at y=63 from -radius to +radius, so y=64 is standing room. */
    public FakeWorld flatFloor(int radius) {
        return fill(-radius, 63, -radius, radius, 63, radius, Ids.STONE);
    }

    public FakeWorld container(BlockPos pos, FakeContainer container) {
        set(pos, BlockState.of(Ids.CHEST));
        containers.put(pos, container);
        return this;
    }

    public FakeWorld light(BlockPos pos, int block, int sky) {
        blockLight.put(pos, block);
        skyLight.put(pos, sky);
        return this;
    }

    public FakeEntity spawnEntity(FakeEntity e) {
        e.world = this;
        entities.add(e);
        return e;
    }

    public int count(String id) {
        int n = 0;
        for (BlockState s : blocks.values()) if (s.is(id)) n++;
        return n;
    }

    // ---- World ----

    @Override public WorldId id() { return id; }
    @Override public String name() { return name; }
    @Override public Environment environment() { return environment; }
    @Override public long time() { return time; }
    @Override public long fullTime() { return fullTime; }
    @Override public boolean isRaining() { return raining; }
    @Override public boolean isThundering() { return thundering; }
    @Override public int minY() { return -64; }
    @Override public int maxY() { return 319; }
    @Override public boolean isChunkLoaded(int chunkX, int chunkZ) { return true; }
    @Override public Vec3 spawn() { return spawn; }

    @Override
    public BlockState block(BlockPos pos) {
        return blocks.getOrDefault(pos, BlockState.AIR);
    }

    @Override
    public void setBlock(BlockPos pos, BlockState state, boolean physics) {
        set(pos, state);
        if (state.isAir()) containers.remove(pos);
    }

    @Override public boolean isSolid(BlockPos pos) { return !NON_SOLID.contains(block(pos).id()) && !isSapling(pos); }
    @Override public boolean isPassable(BlockPos pos) { return !isSolid(pos); }
    @Override public boolean isLiquid(BlockPos pos) { String b = block(pos).id(); return b.equals(Ids.WATER) || b.equals(Ids.LAVA); }

    @Override
    public boolean isOccluding(BlockPos pos) {
        String b = block(pos).id();
        return isSolid(pos) && !NOT_OCCLUDING.contains(b) && !b.endsWith("_leaves")
                && !b.endsWith("_glass_pane") && !b.endsWith("_fence") && !b.endsWith("_wall");
    }

    private boolean isSapling(BlockPos pos) {
        return block(pos).id().endsWith("_sapling");
    }

    @Override
    public boolean is(BlockPos pos, Tag tag) {
        String b = block(pos).id();
        if (tag.equals(Tag.LOGS)) return b.endsWith("_log");
        if (tag.equals(Tag.LEAVES)) return b.endsWith("_leaves");
        if (tag.equals(Tag.SAPLINGS)) return b.endsWith("_sapling") || b.equals(Ids.MANGROVE_PROPAGULE);
        if (tag.equals(Tag.DIRT)) return Set.of(Ids.DIRT, Ids.GRASS_BLOCK, Ids.PODZOL, Ids.COARSE_DIRT, Ids.ROOTED_DIRT, Ids.MUD).contains(b);
        if (tag.equals(Tag.CROPS)) return Set.of(Ids.WHEAT, Ids.CARROTS, Ids.POTATOES, Ids.BEETROOTS).contains(b);
        if (tag.equals(Tag.DOORS)) return b.endsWith("_door");
        if (tag.equals(Tag.BEDS)) return b.endsWith("_bed");
        if (tag.equals(Tag.CLIMBABLE)) return b.equals(Ids.LADDER) || b.equals("minecraft:vine");
        String ore = tag.id().replace("minecraft:", "").replace("_ores", "_ore");
        return b.endsWith(ore);
    }

    @Override
    public int highestY(int x, int z) {
        int best = minY();
        for (BlockPos p : blocks.keySet()) {
            if (p.x() == x && p.z() == z && p.y() > best) best = p.y();
        }
        return best;
    }

    @Override public int blockLight(BlockPos pos) { return blockLight.getOrDefault(pos, defaultBlockLight); }
    @Override public int skyLight(BlockPos pos) { return skyLight.getOrDefault(pos, defaultSkyLight); }
    @Override public String biome(BlockPos pos) { return "plains"; }

    @Override
    public void breakNaturally(BlockPos pos, Item tool) {
        BlockState was = block(pos);
        if (was.isAir()) return;
        brokenNaturally.add(pos);
        set(pos, BlockState.AIR);
        containers.remove(pos);
        dropItem(pos.center(), Item.of(was.id()));
    }

    @Override
    public Entity dropItem(Vec3 at, Item item) {
        FakeEntity e = FakeEntity.item(at, item);
        return spawnEntity(e);
    }

    @Override
    public Optional<Container> container(BlockPos pos) {
        return Optional.ofNullable(containers.get(pos));
    }

    @Override
    public List<Entity> nearby(Vec3 center, double radius) {
        List<Entity> out = new ArrayList<>();
        for (FakeEntity e : entities) if (e.alive && e.pos.distance(center) <= radius) out.add(e);
        return out;
    }

    @Override
    public List<Entity> nearby(Vec3 center, double dx, double dy, double dz) {
        List<Entity> out = new ArrayList<>();
        for (FakeEntity e : entities) {
            if (!e.alive) continue;
            if (Math.abs(e.pos.x() - center.x()) <= dx && Math.abs(e.pos.y() - center.y()) <= dy
                    && Math.abs(e.pos.z() - center.z()) <= dz) out.add(e);
        }
        return out;
    }

    @Override
    public List<Entity> nearby(Vec3 center, double radius, EntityKind kind) {
        List<Entity> out = new ArrayList<>();
        for (Entity e : nearby(center, radius)) if (e.kind() == kind) out.add(e);
        return out;
    }

    @Override
    public Optional<Entity> entity(UUID id) {
        for (FakeEntity e : entities) if (e.id().equals(id)) return Optional.of(e);
        return Optional.empty();
    }

    @Override public void sound(Vec3 at, String soundId, float volume, float pitch) { sounds.add(soundId); }
    @Override public void particle(Vec3 at, String particleId, int count, double spread) { particles.add(particleId); }

    @Override
    public BlockScan snapshot(BlockPos min, BlockPos max) {
        Map<BlockPos, BlockState> copy = new HashMap<>();
        for (Map.Entry<BlockPos, BlockState> e : blocks.entrySet()) {
            BlockPos p = e.getKey();
            if (p.x() >= min.x() && p.x() <= max.x() && p.y() >= min.y() && p.y() <= max.y()
                    && p.z() >= min.z() && p.z() <= max.z()) copy.put(p, e.getValue());
        }
        return new BlockScan() {
            @Override public BlockState block(BlockPos pos) { return copy.getOrDefault(pos, BlockState.AIR); }
            @Override public BlockPos min() { return min; }
            @Override public BlockPos max() { return max; }
            @Override public boolean covers(BlockPos pos) {
                return pos.x() >= min.x() && pos.x() <= max.x() && pos.y() >= min.y() && pos.y() <= max.y()
                        && pos.z() >= min.z() && pos.z() <= max.z();
            }
        };
    }

    /** A chest with this many slots. */
    public static final class FakeContainer implements Container {
        private final List<Item> slots = new ArrayList<>();
        private final int size;

        public FakeContainer(int size) {
            this.size = size;
        }

        @Override public List<Item> contents() { return List.copyOf(slots); }
        @Override public int size() { return size; }
        @Override public int freeSlots() { return size - slots.size(); }

        @Override
        public List<Item> add(List<Item> items) {
            List<Item> rest = new ArrayList<>();
            for (Item it : items) {
                if (it.isEmpty()) continue;
                if (slots.size() < size) slots.add(it);
                else rest.add(it);
            }
            return rest;
        }

        public int count(String id) {
            int n = 0;
            for (Item it : slots) if (it.is(id)) n += it.count();
            return n;
        }
    }

    /** An entity with the handful of properties core reads. */
    public static final class FakeEntity implements Entity {
        private final UUID id = UUID.randomUUID();
        public String typeId;
        public EntityKind kind;
        public boolean alive = true;
        public boolean creeper = false;
        public Vec3 pos;
        public Vec3 velocity = Vec3.ZERO;
        public double health = 20;
        public int fireTicks = 0;
        public Owner targeting = null;
        public Item item = null;
        public double damageTaken = 0;
        FakeWorld world;

        public static FakeEntity item(Vec3 at, Item stack) {
            FakeEntity e = new FakeEntity();
            e.typeId = "minecraft:item";
            e.kind = EntityKind.ITEM;
            e.pos = at;
            e.item = stack;
            return e;
        }

        public static FakeEntity hostile(String typeId, Vec3 at) {
            FakeEntity e = new FakeEntity();
            e.typeId = typeId;
            e.kind = EntityKind.HOSTILE;
            e.pos = at;
            return e;
        }

        @Override public UUID id() { return id; }
        @Override public String typeId() { return typeId; }
        @Override public EntityKind kind() { return kind; }
        @Override public boolean isAlive() { return alive; }
        @Override public boolean isCreeper() { return creeper; }
        @Override public WorldId world() { return world == null ? WorldId.OVERWORLD : world.id(); }
        @Override public Vec3 pos() { return pos; }
        @Override public Vec3 eyePos() { return pos.add(0, 1.5, 0); }
        @Override public Vec3 velocity() { return velocity; }
        @Override public void setVelocity(Vec3 v) { velocity = v; }
        @Override public double health() { return health; }

        @Override
        public void damage(double amount) {
            damageTaken += amount;
            health -= amount;
            if (health <= 0) alive = false;
        }

        @Override public int fireTicks() { return fireTicks; }
        @Override public void setFireTicks(int ticks) { fireTicks = ticks; }
        @Override public boolean isTargeting(Owner owner) { return targeting != null && targeting.id().equals(owner.id()); }
        @Override public Optional<Item> asItem() { return Optional.ofNullable(item); }
        @Override public void setItem(Item item) { this.item = item; }
        @Override public void remove() { alive = false; }
        @Override public boolean equals(Object o) { return o instanceof FakeEntity other && other.id.equals(id); }
        @Override public int hashCode() { return id.hashCode(); }
    }
}
