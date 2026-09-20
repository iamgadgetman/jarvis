package com.gadgetman.jarvis.core.platform;

import java.util.Optional;

/** Facts about item kinds that only a registry knows. */
public interface Items {

    boolean isEdible(String id);

    int maxStackSize(String id);

    /**
     * The namespaced id for a name a player typed, such as {@code diamond}
     * or {@code minecraft:oak_log}, when the registry knows it.
     */
    Optional<String> resolve(String name);
}
