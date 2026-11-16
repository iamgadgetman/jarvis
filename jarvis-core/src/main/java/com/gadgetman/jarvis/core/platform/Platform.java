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

    BlockTypes blockTypes();

    Ui ui();

    /** What is running, for the version screen: "Purpur 1.21.4 (API 1.21.4-R0.1-SNAPSHOT)". */
    String serverVersion();

    Optional<World> world(WorldId id);

    /** Loaded worlds. */
    Collection<World> worlds();

    /** True in a single-player (integrated) game; false on a dedicated server. */
    default boolean singleplayer() {
        return false;
    }

    /**
     * Run one command as the server console, on the server thread. Only for
     * commands an admin wrote (reward bundles, FB-08): the model never supplies
     * the text. {@code console_command} has its own path in the adapters.
     */
    default void runConsoleCommand(String command) {
        throw new UnsupportedOperationException("console commands are not supported on " + name());
    }

    /** Expand third-party placeholders (PlaceholderAPI on Paper) for a player; as-is where there are none. */
    default String expandPlaceholders(java.util.UUID player, String text) {
        return text;
    }

    /** Ticks per second over the last minute, at most 20. */
    double tps();

    /** Milliseconds per tick, averaged. */
    double mspt();
}
