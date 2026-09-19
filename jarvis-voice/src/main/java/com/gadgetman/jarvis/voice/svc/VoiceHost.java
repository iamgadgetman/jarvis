package com.gadgetman.jarvis.voice.svc;

import com.gadgetman.jarvis.JarvisCore;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Platform;

import java.util.Optional;

/**
 * What the voice plugin needs from whichever adapter it is running in.
 *
 * <p>Simple Voice Chat's API is the same on every platform, down to taking
 * the loader's own entity and level objects as {@code Object}. So the two
 * things an adapter has to supply are exactly those objects: the butler's
 * body, for his voice to come out of, and the owner's world, for the
 * channel that speaks in their ear when he is away.
 */
public interface VoiceHost {

    /** Jarvis himself. Null before the server is up. */
    JarvisCore core();

    Platform platform();

    /**
     * The butler's entity, as the loader's own object, when he is spawned in
     * the owner's world and within {@code distance} blocks of them. Empty
     * otherwise: he is away, or not summoned, and speaks over the intercom.
     */
    Optional<Object> butlerEntityNear(Owner owner, double distance);

    /** The owner's level, as the loader's own object. Empty when offline. */
    Optional<Object> levelOf(Owner owner);
}
