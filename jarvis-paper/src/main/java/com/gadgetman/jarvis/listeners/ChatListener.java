package com.gadgetman.jarvis.listeners;

import com.gadgetman.jarvis.Jarvis;
import com.gadgetman.jarvis.intent.IntentPipeline;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChatListener — decides whether a chat line was aimed at Jarvis, and if so
 * hands it to the {@link IntentPipeline}.
 *
 * <p>Everything after "was this for Jarvis?" now lives in the pipeline, which
 * voice shares. This class is deliberately only the trigger: prefix handling,
 * keyword sniffing and the per-player cooldown.
 */
public class ChatListener implements Listener {

    private final Jarvis plugin;
    private final boolean enabled;
    private final String prefix;
    private final boolean requirePrefix;
    private final long cooldownMs;

    private final Map<UUID, Long> lastCommandTime = new ConcurrentHashMap<>();

    public ChatListener(Jarvis plugin) {
        this.plugin        = plugin;
        this.enabled       = plugin.getConfig().getBoolean("natural-language.enabled", true);
        this.prefix        = plugin.getConfig().getString("natural-language.prefix", "jarvis").toLowerCase();
        this.requirePrefix = plugin.getConfig().getBoolean("natural-language.require-prefix", false);
        this.cooldownMs    = plugin.getConfig().getLong("natural-language.cooldown-ms", 2000);
        startCleanupTask();
    }

    private void startCleanupTask() {
        new BukkitRunnable() {
            @Override public void run() {
                long cutoff = System.currentTimeMillis() - 60000;
                lastCommandTime.entrySet().removeIf(e -> e.getValue() < cutoff);
            }
        }.runTaskTimer(plugin, 1200L, 1200L);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerChat(AsyncChatEvent event) {
        if (!enabled) return;

        Player player  = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText()
                .serialize(event.message()).toLowerCase().trim();

        boolean shouldProcess;
        String processedMessage = message;

        if (requirePrefix) {
            shouldProcess = message.startsWith(prefix + " ") || message.startsWith(prefix + ",");
            if (shouldProcess) {
                processedMessage = message.substring(prefix.length()).trim();
                event.setCancelled(true);
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

        plugin.getIntentPipeline().submit(player, processedMessage, IntentPipeline.Source.CHAT);
    }

    /** Per-player rate limit, shared shape with the voice path. */
    private boolean onCooldown(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastCommandTime.get(player.getUniqueId());
        if (last != null && (now - last) < cooldownMs) return true;
        lastCommandTime.put(player.getUniqueId(), now);
        return false;
    }
}
