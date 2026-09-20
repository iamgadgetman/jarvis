package com.gadgetman.jarvis.voice;

import java.util.List;

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

    /**
     * Why the last call returned nothing, when the reason was the engine
     * rather than silence: a server that did not answer, a model that is not
     * here. Null when the last call worked or simply heard nothing. Lets the
     * listener tell the player, instead of leaving an order unanswered.
     */
    default String lastProblem() { return null; }

    /** True when the work happens on a server elsewhere, which can be down. */
    default boolean needsServer() { return false; }

    /** Get ready ahead of the first order: fetch models, load them. Never blocks. */
    default void warmUp() { }

    /**
     * Time recognition on this machine and say what was found, one line per
     * finding. Blocks for a few seconds. {@code threads} tries that count
     * alone; zero tries several and names the fastest.
     */
    default List<String> benchmark(int threads) {
        return List.of("There is nothing to time in this engine; the work happens on the speech server.");
    }

    default void close() { }
}
