package com.gadgetman.jarvis.core.platform;

import com.gadgetman.jarvis.core.world.BlockState;

import java.util.Optional;
import java.util.Set;

/**
 * The block registry, as core needs it: which ids are placeable blocks, and
 * whether a spec such as {@code oak_stairs[facing=north]} parses.
 *
 * <p>Both answers come from the server's registry, so they are only right on
 * the server thread. Core reads {@link #placeableIds} once at start-up and
 * validates plans with {@link #parse} before queueing them.
 */
public interface BlockTypes {

    /**
     * Resolve a block spec through the registry. The id may be bare or
     * namespaced; properties are in the game's bracket form. Empty when the
     * id is unknown, not a block, or a property does not apply.
     */
    Optional<BlockState> parse(String spec);

    /** Every placeable block id, without namespace, for validating scripts off-thread. */
    Set<String> placeableIds();
}
