package com.gadgetman.jarvis.voice;

import com.gadgetman.jarvis.core.platform.Audience;
import com.gadgetman.jarvis.core.text.Colors;

/**
 * What {@code /jarvis voice} reports: where the chain from a microphone to
 * an order stands on this server. Core knows nothing of voice chat, so the
 * adapter that registered with it supplies the report; without one, the
 * answer is that there is no voice here.
 */
public interface VoiceStatus {

    /** Tell {@code to} how voice stands. May send more lines later (a probe). */
    void report(Audience to);

    /** The {@code voice.*} section changed on disk: re-read it, and register if that is what it now asks. */
    default void settingsChanged() { }

    /**
     * Say a line aloud to this player if they are on voice chat; otherwise
     * nothing (the caller has already written it in chat). FB-11's tutorial
     * and welcome use it so voice players hear them.
     */
    default void speak(com.gadgetman.jarvis.core.platform.Owner player, String line) { }

    /** Time the recogniser on this machine and tell {@code to} what was found; see {@link Speech#benchmark}. */
    default void benchmark(Audience to, int threads) {
        to.message(Colors.YELLOW + "Voice: nothing to benchmark; voice is not available on this server.");
    }

    VoiceStatus NONE = to -> to.message(Colors.YELLOW + "Voice: not available on this server. "
            + Colors.GRAY + "Simple Voice Chat is not installed, or Jarvis could not register with it.");
}
