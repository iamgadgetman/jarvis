package com.gadgetman.jarvis.core.world;

/**
 * A block or item tag, such as {@code minecraft:logs}. Tags are the game's own
 * groupings and exist on every platform, which is why they replace the
 * name-suffix checks the behaviour classes used to do.
 */
public record Tag(String id) {

    public static final Tag LOGS = new Tag("minecraft:logs");
    public static final Tag LEAVES = new Tag("minecraft:leaves");
    public static final Tag SAPLINGS = new Tag("minecraft:saplings");
    public static final Tag DIRT = new Tag("minecraft:dirt");
    public static final Tag CROPS = new Tag("minecraft:crops");
    public static final Tag COAL_ORES = new Tag("minecraft:coal_ores");
    public static final Tag IRON_ORES = new Tag("minecraft:iron_ores");
    public static final Tag COPPER_ORES = new Tag("minecraft:copper_ores");
    public static final Tag GOLD_ORES = new Tag("minecraft:gold_ores");
    public static final Tag REDSTONE_ORES = new Tag("minecraft:redstone_ores");
    public static final Tag LAPIS_ORES = new Tag("minecraft:lapis_ores");
    public static final Tag DIAMOND_ORES = new Tag("minecraft:diamond_ores");
    public static final Tag EMERALD_ORES = new Tag("minecraft:emerald_ores");
    public static final Tag DOORS = new Tag("minecraft:doors");
    public static final Tag BEDS = new Tag("minecraft:beds");
    public static final Tag CLIMBABLE = new Tag("minecraft:climbable");

    @Override
    public String toString() {
        return id;
    }
}
