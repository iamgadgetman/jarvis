package com.gadgetman.jarvis.core.platform;

import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.BlockState;
import com.gadgetman.jarvis.core.world.Environment;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.core.world.Tag;
import com.gadgetman.jarvis.core.world.Vec3;
import com.gadgetman.jarvis.core.world.WorldId;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A loaded world. Everything here is server-thread only unless it says
 * otherwise; {@link #snapshot} is how core reads blocks off it.
 */
public interface World {

    WorldId id();

    /** The name the server calls it, which is what older records store. */
    String name();

    Environment environment();

    /** Ticks into the current day, 0 to 23999. */
    long time();

    /** Ticks since the world began. */
    long fullTime();

    boolean isRaining();

    boolean isThundering();

    int minY();

    int maxY();

    boolean isChunkLoaded(int chunkX, int chunkZ);

    Vec3 spawn();

    BlockState block(BlockPos pos);

    void setBlock(BlockPos pos, BlockState state);

    boolean isSolid(BlockPos pos);

    boolean isPassable(BlockPos pos);

    boolean isLiquid(BlockPos pos);

    boolean is(BlockPos pos, Tag tag);

    /** The highest non-air block's y at this column. */
    int highestY(int x, int z);

    int blockLight(BlockPos pos);

    int skyLight(BlockPos pos);

    /** The biome key without namespace, for prompts and similarity scores. */
    String biome(BlockPos pos);

    /**
     * Break as a tool would: drops per loot table, no animation. For cascades
     * and cleanup, not for the butler's own dig.
     */
    void breakNaturally(BlockPos pos, Item tool);

    Entity dropItem(Vec3 at, Item item);

    Optional<Container> container(BlockPos pos);

    List<Entity> nearby(Vec3 center, double radius);

    /** Entities inside a box of these half-extents, the way the game's own query works. */
    List<Entity> nearby(Vec3 center, double dx, double dy, double dz);

    List<Entity> nearby(Vec3 center, double radius, EntityKind kind);

    Optional<Entity> entity(UUID id);

    void sound(Vec3 at, String soundId, float volume, float pitch);

    void particle(Vec3 at, String particleId, int count, double spread);

    /** An immutable copy of the loaded part of a region, safe to read from any thread. */
    BlockScan snapshot(BlockPos min, BlockPos max);
}
