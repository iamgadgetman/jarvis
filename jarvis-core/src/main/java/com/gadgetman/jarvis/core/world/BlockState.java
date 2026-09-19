package com.gadgetman.jarvis.core.world;

import java.util.HashMap;
import java.util.Map;

/**
 * A block id with the state properties core cares about, such as
 * {@code facing}, {@code age}, {@code part} or {@code half}. Values are the
 * game's own string forms, so {@code with("facing", "north")} means the same
 * thing on every platform.
 */
public record BlockState(String id, Map<String, String> props) {

    public static final BlockState AIR = of(Ids.AIR);

    public BlockState {
        props = Map.copyOf(props);
    }

    public static BlockState of(String id) {
        return new BlockState(id, Map.of());
    }

    public BlockState with(String prop, String value) {
        Map<String, String> next = new HashMap<>(props);
        next.put(prop, value);
        return new BlockState(id, next);
    }

    public BlockState with(String prop, int value) {
        return with(prop, Integer.toString(value));
    }

    public BlockState with(String prop, boolean value) {
        return with(prop, Boolean.toString(value));
    }

    public String prop(String name) {
        return props.get(name);
    }

    public int intProp(String name, int def) {
        String v = props.get(name);
        if (v == null) return def;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean is(String id) {
        return this.id.equals(id);
    }

    public boolean isAir() {
        return Ids.AIR.equals(id) || Ids.CAVE_AIR.equals(id) || Ids.VOID_AIR.equals(id);
    }

    @Override
    public String toString() {
        return props.isEmpty() ? id : id + props;
    }
}
