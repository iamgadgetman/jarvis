package com.gadgetman.jarvis.intent;

import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Platform;
import com.gadgetman.jarvis.core.platform.Subscription;
import com.gadgetman.jarvis.core.platform.Task;
import com.gadgetman.jarvis.core.platform.events.ChatEvent;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides whether a chat line was aimed at Jarvis, and if so hands it to
 * the {@link IntentPipeline}.
 *
 * <p>Everything after "was this for Jarvis?" lives in the pipeline, which
 * voice shares. This is deliberately only the trigger: prefix handling,
 * keyword sniffing and the per-player cooldown.
 */
public class ChatTrigger {

    private final Platform platform;
    private final IntentPipeline pipeline;
    private final boolean enabled;
    private final String prefix;
    private final boolean requirePrefix;
    private final long cooldownMs;

    private final Map<UUID, Long> lastCommandTime = new ConcurrentHashMap<>();
    private Subscription subscription;
    private Task cleanup;

    public ChatTrigger(Platform platform, IntentPipeline pipeline) {
        this.platform = platform;
        this.pipeline = pipeline;
        Config cfg = platform.config();
        this.enabled       = cfg.getBoolean("natural-language.enabled", true);
        this.prefix        = cfg.getString("natural-language.prefix", "jarvis").toLowerCase(Locale.ROOT);
        this.requirePrefix = cfg.getBoolean("natural-language.require-prefix", false);
        this.cooldownMs    = cfg.getLong("natural-language.cooldown-ms", 2000);
    }

    public void start() {
        if (!enabled) return;
        subscription = platform.events().on(ChatEvent.class, this::onChat);
        cleanup = platform.scheduler().every(1200L, 1200L, self -> {
            long cutoff = System.currentTimeMillis() - 60000;
            lastCommandTime.entrySet().removeIf(e -> e.getValue() < cutoff);
        });
    }

    public void shutdown() {
        if (subscription != null) subscription.cancel();
        if (cleanup != null) cleanup.cancel();
    }

    /** May arrive off the server thread; the pipeline hops where it needs to be. */
    private void onChat(ChatEvent event) {
        Owner player = event.who();
        String message = event.text().toLowerCase(Locale.ROOT).trim();

        boolean shouldProcess;
        String processedMessage = message;

        if (requirePrefix) {
            shouldProcess = message.startsWith(prefix + " ") || message.startsWith(prefix + ",");
            if (shouldProcess) {
                processedMessage = message.substring(prefix.length()).trim();
                event.cancel().accept(true);
            }
        } else {
            shouldProcess = message.contains(prefix)
                    || message.contains("summon") || message.contains("dismiss")
                    || message.contains("mine")   || message.contains("attack")
                    || message.contains("build")  || message.contains("come here")
                    || message.contains("follow") || message.contains("give me")
                    || message.contains("heal")   || message.contains("feed me")
                    || message.contains("time")   || message.contains("weather");
        }

        if (!shouldProcess) return;
        if (onCooldown(player)) return;

        pipeline.submit(player, processedMessage, IntentPipeline.Source.CHAT);
    }

    /** Per-player rate limit, shared shape with the voice path. */
    private boolean onCooldown(Owner player) {
        long now = System.currentTimeMillis();
        Long last = lastCommandTime.get(player.id());
        if (last != null && (now - last) < cooldownMs) return true;
        lastCommandTime.put(player.id(), now);
        return false;
    }
}
