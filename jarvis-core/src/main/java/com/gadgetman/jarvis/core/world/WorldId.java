package com.gadgetman.jarvis.core.world;

/**
 * Identifies a world (dimension) by its namespaced id, such as
 * {@code minecraft:overworld}. Adapters map it to their own world handle.
 */
public record WorldId(String id) {

    public static final WorldId OVERWORLD = new WorldId("minecraft:overworld");
    public static final WorldId NETHER = new WorldId("minecraft:the_nether");
    public static final WorldId END = new WorldId("minecraft:the_end");

    public boolean isNether() { return NETHER.equals(this); }
    public boolean isEnd() { return END.equals(this); }

    @Override
    public String toString() {
        return id;
    }
}
