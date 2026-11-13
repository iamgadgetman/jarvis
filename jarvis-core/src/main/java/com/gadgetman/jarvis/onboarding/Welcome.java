package com.gadgetman.jarvis.onboarding;

import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Platform;
import com.gadgetman.jarvis.core.platform.Subscription;
import com.gadgetman.jarvis.core.platform.events.JoinEvent;
import com.gadgetman.jarvis.core.text.Colors;
import com.gadgetman.jarvis.core.text.RichLine;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.ui.ControllerBell;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * A player's first join, handled once (FB-11): the controller bell, two
 * written lines (no model call) and an offer of the tutorial. "First" means
 * no row in {@code onboarding_state}; the platform's first-join flag is only
 * a hint, because it comes back when player data is wiped.
 *
 * <p>An admin who joins while no AI provider can answer gets one line and
 * three buttons to fix that instead (once per restart, per
 * {@code onboarding.admin-nag}), and no welcome row, so the ordinary welcome
 * still comes once something works.
 */
public final class Welcome {

    public static final String NAG_ONCE = "once-per-restart";

    private final Platform platform;
    private final OnboardingStore store;
    private final Tutorial tutorial;
    /** TRUE: something can answer; FALSE: nothing can; null: not checked yet. */
    private final Supplier<Boolean> aiUsable;
    private final BiConsumer<Owner, String> voice;
    private final LongSupplier clock;

    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<UUID> naggedThisRun = ConcurrentHashMap.newKeySet();
    private final List<Subscription> subscriptions = new ArrayList<>();

    public Welcome(Platform platform, OnboardingStore store, Tutorial tutorial, Supplier<Boolean> aiUsable,
                   BiConsumer<Owner, String> voice, LongSupplier clock) {
        this.platform = platform;
        this.store = store;
        this.tutorial = tutorial;
        this.aiUsable = aiUsable;
        this.voice = voice == null ? (o, s) -> { } : voice;
        this.clock = clock;
    }

    public void start() {
        subscriptions.add(platform.events().on(JoinEvent.class, e -> onJoin(e.who())));
    }

    public void shutdown() {
        subscriptions.forEach(Subscription::cancel);
        subscriptions.clear();
    }

    private void onJoin(Owner p) {
        if (!inFlight.add(p.id())) return;   // a quick reconnect while the last join is still pending
        long delay = platform.config().getLong("steward.report-join-delay-ticks", 60L);
        platform.scheduler().later(delay, () -> {
            if (!p.isOnline()) { inFlight.remove(p.id()); return; }
            platform.scheduler().async(() -> store.load(p.id()), row -> {
                try {
                    decide(p, row.orElse(null));
                } finally {
                    inFlight.remove(p.id());
                }
            });
        });
    }

    private void decide(Owner p, OnboardingStore.State row) {
        if (!p.isOnline()) return;
        if (row != null) tutorial.remember(p.id(), row);

        if (isAdmin(p) && Boolean.FALSE.equals(aiUsable.get()) && nagMode().equals(NAG_ONCE)) {
            if (naggedThisRun.add(p.id())) nag(p);
            return;   // the welcome waits until the AI works
        }
        if (row != null) return;
        if (!platform.config().getBoolean("onboarding.welcome.enabled", true)) return;
        welcome(p);
    }

    private void welcome(Owner p) {
        boolean bell = platform.config().getBoolean("onboarding.welcome.give-bell", true);
        if (bell && !holdsBell(p)) p.give(ControllerBell.create());

        say(p, "Good day, " + p.name() + ". I'm Jarvis, the butler on this server: I mine, farm, fight, build and fetch.");
        say(p, bell ? "That bell is my controls. Right-click it to see what I can do."
                : "Type /jarvis help to see what I can do, or just talk to me.");

        boolean offer = platform.config().getBoolean("onboarding.welcome.offer-tutorial", true)
                && platform.config().getBoolean("onboarding.tutorial.enabled", true);
        if (offer) {
            p.rich(new RichLine()
                    .text(Colors.AQUA + "Jarvis: " + Colors.WHITE + "Five minutes to learn the ropes? ")
                    .link(Colors.GREEN + "[Start the tutorial]", "/jarvis tutorial start", "A short walk through what I do")
                    .text(" ")
                    .link(Colors.GRAY + "[Maybe later]", "/jarvis tutorial later", "/jarvis tutorial works any time"));
        }

        OnboardingStore.State s = new OnboardingStore.State(clock.getAsLong(), 0, false, 0);
        tutorial.remember(p.id(), s);
        platform.scheduler().async(() -> store.save(p.id(), s));
    }

    /** The three ways to get the AI running, as buttons. */
    private void nag(Owner p) {
        p.message(Colors.AQUA + "Jarvis: " + Colors.YELLOW + "I'm here, but I can't think yet: no AI is set up.");
        p.rich(setupButtons());
    }

    /** Also used by the bell menu's admin page, in its own way. */
    public static RichLine setupButtons() {
        return new RichLine()
                .link(Colors.GREEN + "[Use a free local model]", "/jarvis setup ollama", "Ollama on your own machine: what to install and set")
                .text(" ")
                .link(Colors.AQUA + "[Use a free Gemini key]", "/jarvis setup gemini", "Where to get the key and where it goes")
                .text(" ")
                .link(Colors.GOLD + "[Try Jarvis Cloud free]", "/jarvis cloud activate", "A link to click; no key to copy");
    }

    private String nagMode() {
        return platform.config().getString("onboarding.admin-nag", NAG_ONCE).trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isAdmin(Owner p) {
        return p.isOp() || p.hasPermission("jarvis.admin");
    }

    private static boolean holdsBell(Owner p) {
        if (ControllerBell.isController(p.heldItem())) return true;
        for (Item it : p.inventory()) {
            if (ControllerBell.isController(it)) return true;
        }
        return false;
    }

    private void say(Owner p, String line) {
        p.message(Colors.AQUA + "Jarvis: " + Colors.WHITE + line);
        voice.accept(p, line);
    }
}
