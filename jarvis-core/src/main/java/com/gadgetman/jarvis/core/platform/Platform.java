package com.gadgetman.jarvis.core.platform;

import com.gadgetman.jarvis.core.world.WorldId;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;

/**
 * Everything core needs from the server it runs on.
 *
 * <p>One instance per adapter. Handed to core at start-up and threaded
 * through; core never reaches for a static.
 */
public interface Platform {

    /** "paper", "fabric". */
    String name();

    Config config();

    Log log();

    Scheduler scheduler();

    /** Where config and data files live. */
    Path dataDir();

    Players players();

    Events events();

    Items items();

    Optional<World> world(WorldId id);

    /** Loaded worlds. */
    Collection<World> worlds();

    /** Ticks per second over the last minute, at most 20. */
    double tps();

    /** Milliseconds per tick, averaged. */
    double mspt();
}
