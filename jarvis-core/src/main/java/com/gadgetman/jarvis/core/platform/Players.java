package com.gadgetman.jarvis.core.platform;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** The players on the server, and the console. */
public interface Players {

    /** A handle for this player, whether or not they are online right now. */
    Owner owner(UUID id);

    /** Present only while the player is online. */
    Optional<Owner> byId(UUID id);

    Collection<Owner> online();

    Audience console();

    /** Send to every online player and the console. */
    void broadcast(String text);
}
