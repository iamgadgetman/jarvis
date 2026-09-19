package com.gadgetman.jarvis.core.platform;

/**
 * Someone core can talk to: a player or the console.
 *
 * <p>Text may carry {@link com.gadgetman.jarvis.core.text.Colors} codes.
 */
public interface Audience {

    void message(String text);

    default void actionBar(String text) {
        message(text);
    }

    /** Play a sound at the audience's own position. No-op for the console. */
    default void sound(String soundId, float volume, float pitch) { }
}
