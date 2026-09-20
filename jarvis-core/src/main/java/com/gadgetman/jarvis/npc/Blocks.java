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

    /** Ore priority for the seeker: highest value first. */
    public static final java.util.List<String> ORE_PRIORITY = java.util.List.of(
            Ids.ANCIENT_DEBRIS,
            Ids.DEEPSLATE_EMERALD_ORE, Ids.EMERALD_ORE,
            Ids.DEEPSLATE_DIAMOND_ORE, Ids.DIAMOND_ORE,
            Ids.DEEPSLATE_GOLD_ORE, Ids.GOLD_ORE,
            Ids.DEEPSLATE_LAPIS_ORE, Ids.LAPIS_ORE,
            Ids.DEEPSLATE_REDSTONE_ORE, Ids.REDSTONE_ORE,
            Ids.DEEPSLATE_IRON_ORE, Ids.IRON_ORE,
            Ids.DEEPSLATE_COPPER_ORE, Ids.COPPER_ORE,
            Ids.DEEPSLATE_COAL_ORE, Ids.COAL_ORE,
            Ids.NETHER_QUARTZ_ORE, Ids.NETHER_GOLD_ORE);

    /** Keyword to the ores it names, for "mine diamonds". Longer keys first. */
    public static final java.util.Map<String, Set<String>> ORE_KEYWORDS = new java.util.LinkedHashMap<>();
    static {
        ORE_KEYWORDS.put("ancient debris", Set.of(Ids.ANCIENT_DEBRIS));
        ORE_KEYWORDS.put("debris",         Set.of(Ids.ANCIENT_DEBRIS));
        ORE_KEYWORDS.put("netherite",      Set.of(Ids.ANCIENT_DEBRIS));
        ORE_KEYWORDS.put("emerald",        Set.of(Ids.EMERALD_ORE, Ids.DEEPSLATE_EMERALD_ORE));
        ORE_KEYWORDS.put("diamond",        Set.of(Ids.DIAMOND_ORE, Ids.DEEPSLATE_DIAMOND_ORE));
        ORE_KEYWORDS.put("gold",           Set.of(Ids.GOLD_ORE, Ids.DEEPSLATE_GOLD_ORE, Ids.NETHER_GOLD_ORE));
        ORE_KEYWORDS.put("lapis",          Set.of(Ids.LAPIS_ORE, Ids.DEEPSLATE_LAPIS_ORE));
        ORE_KEYWORDS.put("redstone",       Set.of(Ids.REDSTONE_ORE, Ids.DEEPSLATE_REDSTONE_ORE));
        ORE_KEYWORDS.put("iron",           Set.of(Ids.IRON_ORE, Ids.DEEPSLATE_IRON_ORE));
        ORE_KEYWORDS.put("copper",         Set.of(Ids.COPPER_ORE, Ids.DEEPSLATE_COPPER_ORE));
        ORE_KEYWORDS.put("quartz",         Set.of(Ids.NETHER_QUARTZ_ORE));
        ORE_KEYWORDS.put("coal",           Set.of(Ids.COAL_ORE, Ids.DEEPSLATE_COAL_ORE));
    }

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
