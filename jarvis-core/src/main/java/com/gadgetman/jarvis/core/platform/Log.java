package com.gadgetman.jarvis.core.platform;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Where core writes its log lines. */
public interface Log {

    void info(String msg);

    void warn(String msg);

    void fine(String msg);

    void error(String msg, Throwable t);

    /** A log backed by a JDK logger, which is what Paper hands a plugin. */
    static Log of(Logger logger) {
        return new Log() {
            @Override public void info(String msg) { logger.info(msg); }
            @Override public void warn(String msg) { logger.warning(msg); }
            @Override public void fine(String msg) { logger.fine(msg); }
            @Override public void error(String msg, Throwable t) { logger.log(Level.SEVERE, msg, t); }
        };
    }
}
