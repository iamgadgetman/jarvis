package com.gadgetman.jarvis.vanilla.platform;

import com.gadgetman.jarvis.core.platform.Log;
import org.slf4j.Logger;

/** Core's {@link Log} over the mod's SLF4J logger. */
public final class VanillaLog implements Log {

    private final Logger logger;

    public VanillaLog(Logger logger) {
        this.logger = logger;
    }

    @Override public void info(String msg) { logger.info(msg); }
    @Override public void warn(String msg) { logger.warn(msg); }
    @Override public void fine(String msg) { logger.debug(msg); }
    @Override public void error(String msg, Throwable t) { logger.error(msg, t); }
}
