package com.gadgetman.jarvis.voice;

import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.intent.IntentPipeline;

/**
 * How long the last spoken order took at each stage, so that "voice is
 * slow" can be answered with which part is slow. Three stages, timed from
 * the end of speech: hearing it (transcription), understanding it (the
 * intent call, usually the AI model), and answering out loud (synthesis).
 * The stage that dominates is the one to tune, and they have different
 * knobs: whisper threads and model for the first, the AI endpoint for the
 * second, the Piper voice for the third.
 */
public final class VoiceTimings {

    private volatile double audioSeconds = -1;
    private volatile long transcribeMs = -1;
    private volatile boolean heard = true;
    private volatile long intentMs = -1;
    private volatile long synthMs = -1;

    /** @param heard false when the engine failed rather than the clip being silence */
    public void transcribed(double seconds, long ms, boolean heard) {
        audioSeconds = seconds;
        transcribeMs = ms;
        this.heard = heard;
    }

    public void understood(long ms) {
        intentMs = ms;
    }

    public void synthesised(long ms) {
        synthMs = ms;
    }

    public long transcribeMs() { return transcribeMs; }
    public long intentMs() { return intentMs; }
    public long synthMs() { return synthMs; }

    /** Time the intent stage: the gap between an order going in and the first line coming back. */
    public IntentPipeline.Responder timing(IntentPipeline.Responder inner) {
        final long started = System.nanoTime();
        return new IntentPipeline.Responder() {
            private volatile boolean answered;

            private void mark() {
                if (!answered) {
                    answered = true;
                    understood((System.nanoTime() - started) / 1_000_000);
                }
            }

            @Override public void speak(Owner player, String jarvisLine) {
                mark();
                inner.speak(player, jarvisLine);
            }

            @Override public void feedback(Owner player, String line) {
                mark();
                inner.feedback(player, line);
            }
        };
    }

    public static String format(long ms) {
        if (ms < 0) return "n/a";
        return ms < 1000 ? ms + " ms" : String.format("%.1f s", ms / 1000.0);
    }

    /** One line for the status report; empty when nothing has been timed. */
    public String describe() {
        if (transcribeMs < 0 && intentMs < 0 && synthMs < 0) return "";
        StringBuilder sb = new StringBuilder();
        if (transcribeMs >= 0) {
            sb.append(heard ? "hearing " : "hearing failed after ").append(format(transcribeMs));
            if (audioSeconds >= 0) sb.append(String.format(" (%.1f s of speech)", audioSeconds));
        }
        if (intentMs >= 0) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("understanding ").append(format(intentMs));
        }
        if (synthMs >= 0) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("speaking ").append(format(synthMs));
        }
        return sb.toString();
    }
}
