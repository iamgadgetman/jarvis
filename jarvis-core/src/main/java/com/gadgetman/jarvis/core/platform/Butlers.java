package com.gadgetman.jarvis.core.platform;

import java.util.Collection;

/** The butlers on this server, one per owner. */
public interface Butlers {

    /** A handle for this owner's butler; cheap, and valid whether or not he is spawned. */
    Butler of(Owner owner);

    /** The butlers currently in the world. */
    Collection<Butler> spawned();
}
