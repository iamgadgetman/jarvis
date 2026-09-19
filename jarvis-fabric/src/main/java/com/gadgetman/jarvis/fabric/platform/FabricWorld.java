package com.gadgetman.jarvis.fabric.platform;

import com.gadgetman.jarvis.core.platform.BlockScan;
import com.gadgetman.jarvis.core.platform.Container;
import com.gadgetman.jarvis.core.platform.Entity;
import com.gadgetman.jarvis.core.platform.EntityKind;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.BlockState;
import com.gadgetman.jarvis.core.world.Environment;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.core.world.Tag;
import com.gadgetman.jarvis.core.world.Vec3;
import com.gadgetman.jarvis.core.world.WorldId;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Core's {@link World} over a server level. Cheap to make; equal when the levels are. */
public final class FabricWorld implements World {

    private final ServerLevel level;

    public FabricWorld(ServerLevel level) {
        this.level = level;
    }

    public ServerLevel handle() {
        return level;
    }

    private MinecraftServer server() {
        return level.getServer();
    }

    private net.minecraft.world.level.block.state.BlockState at(BlockPos p) {
        return level.getBlockState(FabricWorlds.mc(p));
    }

    @Override public WorldId id() { return FabricWorlds.id(level); }
    @Override public String name() { return level.dimension().identifier().getPath(); }
    @Override public Environment environment() { return FabricWorlds.env(level); }
    @Override public long time() { return level.getOverworldClockTime() % 24000L; }
    @Override public long fullTime() { return level.getGameTime(); }
    @Override public boolean isRaining() { return level.isRaining(); }
    @Override public boolean isThundering() { return level.isThundering(); }
    @Override public int minY() { return level.getMinY(); }
    @Override public int maxY() { return level.getMaxY(); }
    @Override public boolean isChunkLoaded(int chunkX, int chunkZ) { return level.hasChunk(chunkX, chunkZ); }

    @Override
    public Vec3 spawn() {
        net.minecraft.core.BlockPos p = level.getRespawnData().pos();
        return new Vec3(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
    }

    @Override public BlockState block(BlockPos pos) { return FabricWorlds.state(at(pos)); }

    @Override
    public void setBlock(BlockPos pos, BlockState state, boolean physics) {
        net.minecraft.world.level.block.state.BlockState s = FabricWorlds.data(server(), state);
        if (s == null) return;
        level.setBlock(FabricWorlds.mc(pos), s, physics ? Block.UPDATE_ALL : Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    @Override public boolean isSolid(BlockPos pos) { return !at(pos).getCollisionShape(level, FabricWorlds.mc(pos)).isEmpty(); }
    @Override public boolean isPassable(BlockPos pos) { return at(pos).getCollisionShape(level, FabricWorlds.mc(pos)).isEmpty(); }
    @Override public boolean isLiquid(BlockPos pos) { return !level.getFluidState(FabricWorlds.mc(pos)).isEmpty(); }
    @Override public boolean isOccluding(BlockPos pos) { return at(pos).canOcclude(); }

    @Override
    public boolean is(BlockPos pos, Tag tag) {
        Identifier key = Identifier.tryParse(tag.id());
        return key != null && at(pos).is(TagKey.create(Registries.BLOCK, key));
    }

    @Override public int highestY(int x, int z) { return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1; }
    @Override public int blockLight(BlockPos pos) { return level.getBrightness(LightLayer.BLOCK, FabricWorlds.mc(pos)); }
    @Override public int skyLight(BlockPos pos) { return level.getBrightness(LightLayer.SKY, FabricWorlds.mc(pos)); }

    @Override
    public String biome(BlockPos pos) {
        String name = level.getBiome(FabricWorlds.mc(pos)).getRegisteredName();
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    @Override
    public void breakNaturally(BlockPos pos, Item tool) {
        net.minecraft.core.BlockPos p = FabricWorlds.mc(pos);
        net.minecraft.world.level.block.state.BlockState s = level.getBlockState(p);
        if (s.isAir()) return;
        Block.dropResources(s, level, p, level.getBlockEntity(p), null, FabricItems.toStack(server(), tool));
        level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    @Override
    public Entity dropItem(Vec3 at, Item item) {
        ItemEntity drop = new ItemEntity(level, at.x(), at.y(), at.z(), FabricItems.toStack(server(), item));
        drop.setDefaultPickUpDelay();
        level.addFreshEntity(drop);
        return new FabricEntity(drop);
    }

    @Override
    public Optional<Container> container(BlockPos pos) {
        return level.getBlockEntity(FabricWorlds.mc(pos)) instanceof net.minecraft.world.Container c
                ? Optional.of(new FabricContainer(server(), c))
                : Optional.empty();
    }

    @Override
    public List<Entity> nearby(Vec3 center, double radius) {
        List<Entity> out = new ArrayList<>();
        net.minecraft.world.phys.Vec3 c = FabricWorlds.mc(center);
        double r2 = radius * radius;
        for (net.minecraft.world.entity.Entity e : level.getEntities((net.minecraft.world.entity.Entity) null, box(center, radius, radius, radius))) {
            if (e.position().distanceToSqr(c) <= r2) out.add(new FabricEntity(e));
        }
        return out;
    }

    @Override
    public List<Entity> nearby(Vec3 center, double dx, double dy, double dz) {
        List<Entity> out = new ArrayList<>();
        for (net.minecraft.world.entity.Entity e : level.getEntities((net.minecraft.world.entity.Entity) null, box(center, dx, dy, dz))) {
            out.add(new FabricEntity(e));
        }
        return out;
    }

    private static AABB box(Vec3 c, double dx, double dy, double dz) {
        return new AABB(c.x() - dx, c.y() - dy, c.z() - dz, c.x() + dx, c.y() + dy, c.z() + dz);
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
        net.minecraft.world.entity.Entity e = level.getEntity(id);
        return e == null ? Optional.empty() : Optional.of(new FabricEntity(e));
    }

    @Override
    public void sound(Vec3 at, String soundId, float volume, float pitch) {
        SoundEvent sound = sound(soundId);
        if (sound != null) level.playSound(null, at.x(), at.y(), at.z(), sound, SoundSource.NEUTRAL, volume, pitch);
    }

    static SoundEvent sound(String id) {
        Identifier key = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
        return key == null ? null : BuiltInRegistries.SOUND_EVENT.getValue(key);
    }

    @Override
    public void particle(Vec3 at, String particleId, int count, double spread) {
        Identifier key = Identifier.tryParse(particleId.contains(":") ? particleId : "minecraft:" + particleId);
        if (key == null) return;
        ParticleType<?> type = BuiltInRegistries.PARTICLE_TYPE.getValue(key);
        if (type instanceof SimpleParticleType simple) {
            level.sendParticles(simple, at.x(), at.y(), at.z(), count, spread, spread, spread, 0.0);
        }
    }

    @Override
    public BlockScan snapshot(BlockPos min, BlockPos max) {
        int lo = Math.max(min.y(), minY());
        int hi = Math.min(max.y(), maxY());
        BlockPos from = new BlockPos(Math.min(min.x(), max.x()), lo, Math.min(min.z(), max.z()));
        BlockPos to = new BlockPos(Math.max(min.x(), max.x()), Math.max(lo, hi), Math.max(min.z(), max.z()));
        int sizeX = to.x() - from.x() + 1, sizeY = to.y() - from.y() + 1, sizeZ = to.z() - from.z() + 1;
        BlockState[] states = new BlockState[sizeX * sizeY * sizeZ];
        Set<Long> loaded = new HashSet<>();
        java.util.Map<net.minecraft.world.level.block.state.BlockState, BlockState> cache = new java.util.HashMap<>();
        FabricBlockScan scan = new FabricBlockScan(from, to, states, loaded);
        for (int cx = from.x() >> 4; cx <= to.x() >> 4; cx++) {
            for (int cz = from.z() >> 4; cz <= to.z() >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                LevelChunk chunk = level.getChunk(cx, cz);
                loaded.add(FabricBlockScan.key(cx, cz));
                int x0 = Math.max(from.x(), cx << 4), x1 = Math.min(to.x(), (cx << 4) + 15);
                int z0 = Math.max(from.z(), cz << 4), z1 = Math.min(to.z(), (cz << 4) + 15);
                net.minecraft.core.BlockPos.MutableBlockPos cursor = new net.minecraft.core.BlockPos.MutableBlockPos();
                for (int y = from.y(); y <= to.y(); y++) {
                    for (int z = z0; z <= z1; z++) {
                        for (int x = x0; x <= x1; x++) {
                            net.minecraft.world.level.block.state.BlockState s = chunk.getBlockState(cursor.set(x, y, z));
                            states[scan.index(x, y, z)] = cache.computeIfAbsent(s,
                                    st -> BlockState.of(BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString()));
                        }
                    }
                }
            }
        }
        return scan;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FabricWorld other && other.level.dimension().equals(level.dimension());
    }

    @Override
    public int hashCode() {
        return level.dimension().hashCode();
    }

    @Override
    public String toString() {
        return name();
    }
}
