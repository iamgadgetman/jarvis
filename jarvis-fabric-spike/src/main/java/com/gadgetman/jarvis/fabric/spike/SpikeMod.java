package com.gadgetman.jarvis.fabric.spike;

import com.gadgetman.jarvis.fabric.spike.fake.FakePlayer;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Fabric spike from docs/dev/platform-interface.md: one fake player,
 * walked by jarvis-nav, driven from /jspike. Nothing here is the adapter;
 * it exists to prove that a client-less player can be spawned, steered
 * along an A* path and made to break a block from the server side.
 */
public final class SpikeMod implements ModInitializer {

    public static final Logger LOG = LoggerFactory.getLogger("jarvis-spike");

    /** The one fake player the spike manages, or null. */
    private static FakePlayer current;
    /** True between a spawn being asked for and the player arriving. */
    private static boolean spawning;

    @Override
    public void onInitialize() {
        LOG.info("Jarvis Fabric spike loaded; /jspike spawn to begin");
    }

    public static FakePlayer current() {
        if (current != null && current.isRemoved()) current = null;
        return current;
    }

    public static void setCurrent(FakePlayer player) {
        current = player;
        spawning = false;
    }

    public static boolean isSpawning() {
        return spawning;
    }

    public static void setSpawning(boolean value) {
        spawning = value;
    }
}
