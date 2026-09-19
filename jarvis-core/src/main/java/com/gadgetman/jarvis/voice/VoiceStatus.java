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

    VoiceStatus NONE = to -> to.message(Colors.YELLOW + "Voice: not available on this server. "
            + Colors.GRAY + "Simple Voice Chat is not installed, or Jarvis could not register with it.");
}
