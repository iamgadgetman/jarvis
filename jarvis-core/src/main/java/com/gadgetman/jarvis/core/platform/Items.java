package com.gadgetman.jarvis.core.platform;

/** Facts about item kinds that only a registry knows. */
public interface Items {

    boolean isEdible(String id);

    int maxStackSize(String id);
}
