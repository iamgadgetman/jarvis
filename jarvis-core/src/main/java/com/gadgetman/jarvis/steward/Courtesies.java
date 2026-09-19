package com.gadgetman.jarvis.steward;

import com.gadgetman.jarvis.ai.AIConnector;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Platform;
import com.gadgetman.jarvis.core.platform.Subscription;
import com.gadgetman.jarvis.core.platform.events.DeathEvent;
import com.gadgetman.jarvis.core.platform.events.JoinEvent;
import com.gadgetman.jarvis.core.text.Colors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Join greetings and death commentary. Both are optional and controlled by
 * config, and both are AI lines, so they are asked for off-thread and
 * delivered back on it.
 */
public class Courtesies {

    private final Platform platform;
    private final AIConnector ai;
    private final boolean greetEnabled;
    private final boolean deathEnabled;

    // Prevent double-greeting in quick reconnects
    private final Set<UUID> recentlyGreeted = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final List<Subscription> subscriptions = new ArrayList<>();

    public Courtesies(Platform platform, AIConnector ai) {
        this.platform = platform;
        this.ai = ai;
        this.greetEnabled = platform.config().getBoolean("butler.auto-greet", true);
        this.deathEnabled = platform.config().getBoolean("butler.death-commentary", true);
    }

    public void start() {
        if (greetEnabled) subscriptions.add(platform.events().on(JoinEvent.class, this::onJoin));
        if (deathEnabled) subscriptions.add(platform.events().on(DeathEvent.class, this::onDeath));
    }

    public void shutdown() {
        subscriptions.forEach(Subscription::cancel);
        subscriptions.clear();
    }

    private void onJoin(JoinEvent event) {
        Owner player = event.who();
        UUID uuid = player.id();

        if (recentlyGreeted.contains(uuid)) return;
        recentlyGreeted.add(uuid);

        // Remove from cooldown set after 10 s
        platform.scheduler().later(200L, () -> recentlyGreeted.remove(uuid));

        boolean firstJoin = event.firstJoin();
        int onlineCount = platform.players().online().size();

        platform.scheduler().async(() -> {
            try {
                String context = "Player " + player.name() + " joined the server. "
                        + (firstJoin ? "This is their FIRST time here — make it special." : "They have played before.")
                        + " There are now " + onlineCount + " players online.";

                String greeting = ai.generateDialogue("greet " + player.name() + " who just joined", context);

                platform.scheduler().sync(() -> {
                    if (player.isOnline()) {
                        player.message(Colors.AQUA + "Jarvis: " + Colors.WHITE + greeting);
                    }
                });

            } catch (Exception ignored) { /* greeting is non-critical */ }
        });
    }

    private void onDeath(DeathEvent event) {
        String deathCause = event.message() != null
                ? event.message()
                : event.who().name() + " died somehow";

        platform.scheduler().async(() -> {
            try {
                String comment = ai.generateDialogue(
                        "deliver a short snarky butler comment on this death: " + deathCause,
                        "Death event in Minecraft. Keep it to one sentence.");

                platform.scheduler().sync(() ->
                        platform.players().broadcast(Colors.AQUA + "Jarvis: " + Colors.GRAY + comment));

            } catch (Exception ignored) { /* non-critical */ }
        });
    }
}
