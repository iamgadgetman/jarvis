package com.gadgetman.jarvis.vanilla;

import com.gadgetman.jarvis.JarvisCore;
import com.gadgetman.jarvis.vanilla.butler.FakeButlers;
import com.gadgetman.jarvis.vanilla.platform.VanillaPlatform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the loader-neutral code needs from the mod's entry point, whichever
 * loader that is. The Fabric and NeoForge entry points each implement
 * this: they register with their loader's events and hand the server to a
 * {@link VanillaPlatform}; everything from there down is shared.
 */
public interface JarvisMod {

    /** The mod's logger, shared by the whole adapter. */
    Logger LOG = LoggerFactory.getLogger("jarvis");

    /** Everything core needs from the server. Null before the server is up. */
    VanillaPlatform platform();

    /** Jarvis himself. Null before the server is up. */
    JarvisCore core();

    FakeButlers butlers();

    /** The reason the last start failed, or null. */
    String startupError();

    String version();
}
