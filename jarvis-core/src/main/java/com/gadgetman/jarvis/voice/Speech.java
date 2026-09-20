package com.gadgetman.jarvis.voice;

/**
 * Turning audio into text and text into audio: Jarvis's ears and mouth,
 * whichever engine is doing the work. Audio is 48 kHz mono 16-bit PCM on
 * both sides, which is what voice chat hands over and takes back.
 *
 * <p>Every call blocks; callers stay off the server thread.
 */
public interface Speech {

    /** @return the recognised text, or null if nothing was heard or the engine failed */
    String transcribe(short[] pcm48k);

    /** @return 48 kHz samples, or null on failure */
    short[] synthesize(String text);

    /** One line naming the engine, for the log and the status report. */
    String describe();

    /** @return null when the engine is ready to work, otherwise what stands in the way */
    String probe();

    /** Get ready ahead of the first order: fetch models, load them. Never blocks. */
    default void warmUp() { }

    default void close() { }
}
