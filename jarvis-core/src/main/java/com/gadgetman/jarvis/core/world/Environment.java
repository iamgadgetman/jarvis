package com.gadgetman.jarvis.core.world;

/** The three kinds of dimension. */
public enum Environment {
    NORMAL, NETHER, END;

    /** The lower-case name the plugin has always written into records and prompts. */
    public String key() {
        return switch (this) {
            case NORMAL -> "normal";
            case NETHER -> "nether";
            case END -> "the_end";
        };
    }
}
