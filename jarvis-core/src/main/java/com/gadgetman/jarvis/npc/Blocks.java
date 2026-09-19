package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.Ids;

import java.util.Set;

/**
 * What the butler will and will not dig, and how he names what he finds.
 *
 * <p>Pure functions of block ids, shared by the task classes and the host.
 */
public final class Blocks {

    private Blocks() { }

    /** Blocks he will tunnel through: never valuables, never containers. */
    public static final Set<String> DIGGABLE = Set.of(
            Ids.STONE, Ids.DEEPSLATE, Ids.COBBLESTONE, Ids.COBBLED_DEEPSLATE,
            Ids.DIRT, Ids.GRASS_BLOCK, Ids.COARSE_DIRT, Ids.ROOTED_DIRT,
            Ids.PODZOL, Ids.CLAY, Ids.MUD, Ids.PACKED_MUD,
            Ids.GRAVEL, Ids.SAND, Ids.SANDSTONE, Ids.RED_SAND, Ids.RED_SANDSTONE,
            Ids.GRANITE, Ids.DIORITE, Ids.ANDESITE, Ids.TUFF, Ids.CALCITE,
            Ids.MOSSY_COBBLESTONE, Ids.DRIPSTONE_BLOCK, Ids.SNOW_BLOCK,
            Ids.NETHERRACK, Ids.BASALT, Ids.SMOOTH_BASALT, Ids.BLACKSTONE,
            Ids.SOUL_SAND, Ids.SOUL_SOIL, Ids.MAGMA_BLOCK);

    /** Drops he does not bother picking up: the rubble of getting somewhere. */
    public static final Set<String> JUNK_DROPS = Set.of(
            Ids.COBBLESTONE, Ids.COBBLED_DEEPSLATE, Ids.STONE,
            Ids.DIRT, Ids.GRAVEL, Ids.SAND, Ids.FLINT,
            Ids.GRANITE, Ids.DIORITE, Ids.ANDESITE,
            Ids.DEEPSLATE, Ids.TUFF, Ids.CALCITE,
            Ids.NETHERRACK, Ids.BASALT, Ids.BLACKSTONE);

    /** Ground he will not stand on. */
    public static final Set<String> HAZARDOUS_FOOTING = Set.of(
            Ids.LAVA, Ids.FIRE, Ids.MAGMA_BLOCK, Ids.CACTUS);

    public static boolean isFluid(String id) {
        return Ids.LAVA.equals(id) || Ids.WATER.equals(id);
    }

    public static boolean isOre(String id) {
        return id != null && (id.endsWith("_ore") || Ids.ANCIENT_DEBRIS.equals(id));
    }

    public static boolean canDig(String id) {
        return DIGGABLE.contains(id) || isOre(id);
    }

    public static boolean canDig(World world, BlockPos pos) {
        return canDig(world.block(pos).id());
    }

    /** Neither solid nor fluid: somewhere he can stand or walk. */
    public static boolean isPassable(World world, BlockPos pos) {
        return !world.isSolid(pos) && !isFluid(world.block(pos).id());
    }

    public static boolean isFluid(World world, BlockPos pos) {
        return isFluid(world.block(pos).id());
    }

    public static boolean isAir(World world, BlockPos pos) {
        return world.block(pos).isAir();
    }

    /** "minecraft:deepslate_iron_ore" reads as "Iron". */
    public static String formatOre(String id) {
        String name = Ids.key(id).replace("deepslate_", "").replace("_ore", "").replace('_', ' ');
        return name.isEmpty() ? name : name.substring(0, 1).toUpperCase() + name.substring(1).toLowerCase();
    }

    /** "minecraft:oak_log" reads as "oak log". */
    public static String pretty(String id) {
        return Ids.key(id).replace('_', ' ');
    }
}
