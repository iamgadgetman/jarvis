package com.gadgetman.jarvis.intent;

import com.gadgetman.jarvis.core.platform.Butler;
import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Scheduler;
import com.gadgetman.jarvis.core.platform.Task;

/**
 * Shows the player that Jarvis is working while a sentence is with the AI.
 *
 * <p>From {@link #start} until {@link #stop}: an action-bar line reads
 * "Jarvis is thinking." with cycling dots, and, if he is out in the player's
 * world, Jarvis turns to face them and swings an arm now and then. His
 * current task carries on underneath; this is feedback only.
 *
 * <p>Server thread only. {@link #stop} is safe to call more than once, and a
 * safety cutoff ends the feedback by itself if a reply never comes back, so
 * he can never be left fidgeting.
 *
 * <p>Config, under {@code intent.thinking-feedback}: {@code enabled},
 * {@code action-bar}, {@code animate}, {@code text}.
 */
final class ThinkingFeedback {

    /** Refresh the action bar every second: the client fades it after about two. */
    static final long BAR_PERIOD_TICKS = 20;
    /** An arm swing about every three seconds. */
    static final long SWING_PERIOD_TICKS = 60;
    /**
     * Longest a reply can take before the feedback ends on its own. The light
     * timeout is per provider (5 s default; 35 s with a slow local model), so
     * Ollama then Claude can take ~70 s; 90 s leaves room without risking a stuck bar.
     */
    static final long CUTOFF_TICKS = 90 * 20;

    private static final String[] DOTS = {".", "..", "..."};

    /** Returned when feedback is switched off, so callers can always call stop(). */
    static final ThinkingFeedback NONE = new ThinkingFeedback();

    private final Owner player;
    private final Butler butler;
    private final boolean actionBar;
    private final String text;
    private Task bar;
    private Task fidget;
    private Task cutoff;
    private boolean stopped;
    private int frame;

    private ThinkingFeedback() {
        this.player = null;
        this.butler = null;
        this.actionBar = false;
        this.text = "";
        this.stopped = true;
    }

    private ThinkingFeedback(Owner player, Butler butler, boolean actionBar, String text) {
        this.player = player;
        this.butler = butler;
        this.actionBar = actionBar;
        this.text = text;
    }

    /**
     * Start the feedback for {@code player}. {@code butler} may be null or not
     * spawned (then there is no animation, only the action bar).
     */
    static ThinkingFeedback start(Owner player, Butler butler, Config config, Scheduler scheduler) {
        if (!config.getBoolean("intent.thinking-feedback.enabled", true)) return NONE;
        boolean bar = config.getBoolean("intent.thinking-feedback.action-bar", true);
        boolean animate = config.getBoolean("intent.thinking-feedback.animate", true);
        String text = config.getString("intent.thinking-feedback.text", "Jarvis is thinking");
        Butler body = animate && inPlayersWorld(butler, player) ? butler : null;
        if (!bar && body == null) return NONE;

        ThinkingFeedback f = new ThinkingFeedback(player, body, bar, text);
        f.begin(scheduler);
        return f;
    }

    private void begin(Scheduler scheduler) {
        if (actionBar) {
            showBar();
            bar = scheduler.every(BAR_PERIOD_TICKS, BAR_PERIOD_TICKS, t -> showBar());
        }
        if (butler != null) {
            butler.lookAt(player.eyePos());
            butler.swing();
            fidget = scheduler.every(SWING_PERIOD_TICKS, SWING_PERIOD_TICKS, t -> {
                if (!butler.isSpawned()) return;
                butler.lookAt(player.eyePos());
                butler.swing();
            });
        }
        cutoff = scheduler.later(CUTOFF_TICKS, this::stop);
    }

    private void showBar() {
        player.actionBar(text + DOTS[frame++ % DOTS.length]);
    }

    /** End the feedback and clear the action bar. Idempotent. */
    void stop() {
        if (stopped) return;
        stopped = true;
        if (bar != null) bar.cancel();
        if (fidget != null) fidget.cancel();
        if (cutoff != null) cutoff.cancel();
        if (actionBar) player.actionBar("");
    }

    boolean isRunning() {
        return !stopped;
    }

    private static boolean inPlayersWorld(Butler butler, Owner player) {
        return butler != null && butler.isSpawned()
                && butler.world().map(w -> w.id().equals(player.world())).orElse(false);
    }
}
