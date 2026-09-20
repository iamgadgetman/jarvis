package com.gadgetman.jarvis.core.platform;

import java.util.Collection;

/** The butlers on this server, one per owner. */
public interface Butlers {

    /** The backend's name, for the debug screen: "Citizens", "fake player". */
    String name();

    /** A handle for this owner's butler; cheap, and valid whether or not he is spawned. */
    Butler of(Owner owner);

    /** The butlers currently in the world. */
    Collection<Butler> spawned();

    /** Every butler the adapter has a record of, spawned or not. */
    Collection<Butler> all();
}
