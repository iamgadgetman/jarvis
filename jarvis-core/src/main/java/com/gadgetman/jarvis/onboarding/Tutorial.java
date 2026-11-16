package com.gadgetman.jarvis.onboarding;

import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Platform;
import com.gadgetman.jarvis.core.platform.Subscription;
import com.gadgetman.jarvis.core.platform.Task;
import com.gadgetman.jarvis.core.platform.events.JoinEvent;
import com.gadgetman.jarvis.core.platform.events.QuitEvent;
import com.gadgetman.jarvis.core.text.Colors;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * The interactive tutorial (FB-11). Driven by what the player does, not by
 * timers: each step says what to do, waits for its {@link Milestone}, praises
 * in character and moves on. Where they are is kept in {@code onboarding_state},
 * so it survives a restart. One reminder per step after
 * {@code onboarding.tutorial.reminder-minutes}, then quiet until they ask.
 *
 * <p>Steps that need a working AI are skipped with a note while
 * {@code aiReady} says nothing can answer, so the tutorial never dead-ends
 * on a server with no key yet. Server thread, except the database calls.
 */
public final class Tutorial {

    private final Platform platform;
    private final OnboardingStore store;
    private final BooleanSupplier aiReady;
    private final BooleanSupplier undoReady;
    private final Predicate<Owner> butlerOut;
    private final BiConsumer<Owner, String> voice;
    private final LongSupplier clock;

    /** Rows of players who are online, once loaded. */
    private final Map<UUID, OnboardingStore.State> states = new ConcurrentHashMap<>();
    private final Map<UUID, Task> reminders = new ConcurrentHashMap<>();
    private final List<Subscription> subscriptions = new ArrayList<>();

    public Tutorial(Platform platform, OnboardingStore store, Milestones milestones, BooleanSupplier aiReady,
                    BooleanSupplier undoReady, Predicate<Owner> butlerOut, BiConsumer<Owner, String> voice,
                    LongSupplier clock) {
        this.platform = platform;
        this.store = store;
        this.aiReady = aiReady;
        this.undoReady = undoReady;
        this.butlerOut = butlerOut;
        this.voice = voice == null ? (o, s) -> { } : voice;
        this.clock = clock;
        milestones.listen(this::onMilestone);
    }

    public void start() {
        subscriptions.add(platform.events().on(JoinEvent.class, e -> onJoin(e.who())));
        subscriptions.add(platform.events().on(QuitEvent.class, e -> forgetSession(e.who().id())));
    }

    public void shutdown() {
        subscriptions.forEach(Subscription::cancel);
        subscriptions.clear();
        reminders.values().forEach(Task::cancel);
        reminders.clear();
    }

    private boolean enabled() {
        return platform.config().getBoolean("onboarding.tutorial.enabled", true);
    }

    // ==================== COMMANDS ====================

    /** {@code /jarvis tutorial}: start, or carry on from where they are. */
    public void resume(Owner p) {
        if (!enabled()) { p.message(Colors.GRAY + "The tutorial is turned off on this server."); return; }
        withState(p, s -> {
            if (s.active()) {
                TutorialStep step = TutorialStep.byNumber(s.step());
                if (step != null) instruct(p, step);
                return;
            }
            if (s.step() > 0 && !s.done()) {
                say(p, "Carrying on from step " + s.step() + ".");
                begin(p, s.withStep(s.step(), true), TutorialStep.byNumber(s.step()));
                return;
            }
            startFresh(p, s);
        });
    }

    /** {@code /jarvis tutorial start}: from the top, even if done before. */
    public void startOver(Owner p) {
        if (!enabled()) { p.message(Colors.GRAY + "The tutorial is turned off on this server."); return; }
        withState(p, s -> startFresh(p, s));
    }

    public void skip(Owner p) {
        OnboardingStore.State s = states.get(p.id());
        if (s == null || !s.active()) {
            p.message(Colors.GRAY + "Jarvis: No tutorial running, sir. " + Colors.WHITE + "/jarvis tutorial" + Colors.GRAY + " to start.");
            return;
        }
        say(p, "Skipped.");
        advance(p, s);
    }

    /** {@code /jarvis tutorial later}: no more asking; the command still works. */
    public void later(Owner p) {
        withState(p, s -> {
            put(p.id(), s.withStep(s.step(), false));
            say(p, "Whenever you like, sir. " + Colors.AQUA + "/jarvis tutorial" + Colors.WHITE + " starts it.");
        });
    }

    public void stop(Owner p) {
        OnboardingStore.State s = states.get(p.id());
        if (s == null || !s.active()) {
            p.message(Colors.GRAY + "Jarvis: No tutorial running, sir.");
            return;
        }
        cancelReminder(p.id());
        put(p.id(), s.withStep(s.step(), false));
        say(p, "Stopped. " + Colors.AQUA + "/jarvis tutorial" + Colors.WHITE + " picks up at step " + s.step() + ".");
    }

    /**
     * Forget a player's welcome and tutorial: their next join is a first join
     * again. Saved as a row with welcomedAt 0 rather than deleted, so it
     * survives a restart and isn't mistaken for a player who was here before
     * onboarding existed.
     */
    public void reset(UUID player) {
        cancelReminder(player);
        states.remove(player);
        OnboardingStore.State again = new OnboardingStore.State(0, 0, false, 0);
        platform.scheduler().async(() -> store.save(player, again));
    }

    /**
     * Whether the player has finished the tutorial, as far as is known now
     * (for the bell menu, which can't wait for the database). Unknown = no.
     */
    public boolean isDone(Owner p) {
        OnboardingStore.State s = states.get(p.id());
        return s != null && s.done();
    }

    /** The row is known for this online player (Welcome has loaded or written it). */
    void remember(UUID player, OnboardingStore.State s) {
        states.put(player, s);
    }

    /** The step the player is on while the tutorial runs, else null. Tests and the menu. */
    public TutorialStep current(Owner p) {
        OnboardingStore.State s = states.get(p.id());
        return s != null && s.active() ? TutorialStep.byNumber(s.step()) : null;
    }

    // ==================== FLOW ====================

    private void onJoin(Owner p) {
        if (states.containsKey(p.id())) return;
        platform.scheduler().async(() -> store.load(p.id()), row -> row.ifPresent(s -> {
            states.putIfAbsent(p.id(), s);
            if (s.active() && enabled()) {
                long delay = platform.config().getLong("steward.report-join-delay-ticks", 60L) + 40L;
                platform.scheduler().later(delay, () -> {
                    if (!p.isOnline()) return;
                    TutorialStep step = TutorialStep.byNumber(s.step());
                    if (step == null) return;
                    say(p, "Welcome back. We were on step " + step.number() + ".");
                    instruct(p, step);
                    scheduleReminder(p, step);
                });
            }
        }));
    }

    private void forgetSession(UUID id) {
        cancelReminder(id);
        states.remove(id);
    }

    /** Run with the player's row: the one in memory, else the database's, else a fresh one. */
    private void withState(Owner p, java.util.function.Consumer<OnboardingStore.State> then) {
        OnboardingStore.State known = states.get(p.id());
        if (known != null) { then.accept(known); return; }
        platform.scheduler().async(() -> store.load(p.id()), row -> {
            OnboardingStore.State s = row.orElse(new OnboardingStore.State(clock.getAsLong(), 0, false, 0));
            states.putIfAbsent(p.id(), s);
            then.accept(states.get(p.id()));
        });
    }

    private void startFresh(Owner p, OnboardingStore.State s) {
        say(p, "Right then: a few minutes, seven steps, and you'll know your way around me. "
                + Colors.GRAY + "(/jarvis tutorial skip passes a step, /jarvis tutorial stop ends it.)");
        TutorialStep first = next(p, 0);
        if (first == null) { finish(p, s); return; }
        begin(p, s.withStep(first.number(), true), first);
    }

    private void begin(Owner p, OnboardingStore.State s, TutorialStep step) {
        if (step == null) { finish(p, s); return; }
        put(p.id(), s);
        instruct(p, step);
        // Already done before we asked: the butler is out, say.
        if (step.completesOn() == Milestone.SUMMONED && butlerOut.test(p)) {
            say(p, "You've already got me out, so that one's done.");
            advance(p, states.get(p.id()));
            return;
        }
        scheduleReminder(p, step);
    }

    private void onMilestone(Owner p, Milestone m) {
        OnboardingStore.State s = states.get(p.id());
        if (s == null || !s.active()) return;
        TutorialStep step = TutorialStep.byNumber(s.step());
        if (step == null || step.completesOn() != m) return;
        // After the job's own reply, not in front of it.
        platform.scheduler().later(20L, () -> {
            OnboardingStore.State now = states.get(p.id());
            if (now == null || !now.active() || now.step() != step.number()) return;
            say(p, step.praise());
            advance(p, now);
        });
    }

    private void advance(Owner p, OnboardingStore.State s) {
        cancelReminder(p.id());
        TutorialStep nextStep = next(p, s.step());
        if (nextStep == null) { finish(p, s); return; }
        begin(p, s.withStep(nextStep.number(), true), nextStep);
    }

    /** The first step after {@code after} that can run here, noting the ones that can't. */
    private TutorialStep next(Owner p, int after) {
        for (int n = after + 1; ; n++) {
            TutorialStep step = TutorialStep.byNumber(n);
            if (step == null) return null;
            if (step == TutorialStep.BUILD && !undoReady.getAsBoolean()) continue;
            if (step.needsAi() && !aiReady.getAsBoolean()) {
                p.message(Colors.GRAY + "(Skipping step " + n + ": it needs an AI, and none is set up on this server yet.)");
                continue;
            }
            return step;
        }
    }

    private void finish(Owner p, OnboardingStore.State s) {
        cancelReminder(p.id());
        put(p.id(), s.finished(clock.getAsLong()));
        say(p, "That's the lot. " + Colors.AQUA + "/jarvis help" + Colors.WHITE
                + " lists everything, and the bell has it all as buttons.");
    }

    // ==================== REMINDERS ====================

    private void scheduleReminder(Owner p, TutorialStep step) {
        cancelReminder(p.id());
        long minutes = platform.config().getLong("onboarding.tutorial.reminder-minutes", 10L);
        if (minutes <= 0) return;
        Task t = platform.scheduler().later(minutes * 60L * 20L, () -> {
            reminders.remove(p.id());
            OnboardingStore.State s = states.get(p.id());
            if (!p.isOnline() || s == null || !s.active() || s.step() != step.number()) return;
            say(p, "Whenever you're ready, sir. " + step.instruction());
            // One nudge only; the next comes with the next step.
        });
        reminders.put(p.id(), t);
    }

    private void cancelReminder(UUID id) {
        Task t = reminders.remove(id);
        if (t != null) t.cancel();
    }

    // ==================== OUTPUT ====================

    private void instruct(Owner p, TutorialStep step) {
        say(p, Colors.GOLD + "Step " + step.number() + " of " + TutorialStep.values().length + ": "
                + Colors.WHITE + step.instruction());
    }

    private void say(Owner p, String line) {
        if (!p.isOnline()) return;
        p.message(Colors.AQUA + "Jarvis: " + Colors.WHITE + line);
        voice.accept(p, Colors.strip(line));
    }

    private void put(UUID id, OnboardingStore.State s) {
        states.put(id, s);
        platform.scheduler().async(() -> store.save(id, s));
    }

    /** The row as it stands in memory, for tests. */
    Optional<OnboardingStore.State> state(UUID id) {
        return Optional.ofNullable(states.get(id));
    }
}
