package com.gadgetman.jarvis.platform;

import com.gadgetman.jarvis.core.platform.Events;
import com.gadgetman.jarvis.core.platform.Log;
import com.gadgetman.jarvis.core.platform.Site;
import com.gadgetman.jarvis.core.platform.Subscription;
import com.gadgetman.jarvis.core.platform.events.ButlerDamagedEvent;
import com.gadgetman.jarvis.core.platform.events.ChatEvent;
import com.gadgetman.jarvis.core.platform.events.DeathEvent;
import com.gadgetman.jarvis.core.platform.events.Event;
import com.gadgetman.jarvis.core.platform.events.JoinEvent;
import com.gadgetman.jarvis.core.platform.events.OwnerDamagedEvent;
import com.gadgetman.jarvis.core.platform.events.PortalEvent;
import com.gadgetman.jarvis.core.platform.events.QuitEvent;
import com.gadgetman.jarvis.core.world.Item;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Core's {@link Events} over Bukkit's. One listener, registered by the
 * plugin, republishes the handful of server events core subscribes to.
 */
public final class PaperEvents implements Events, Listener {

    private final Log log;
    private final Map<Class<?>, List<Consumer<Object>>> handlers = new ConcurrentHashMap<>();

    /** Whose butler an entity is, set by the NPC adapter once it exists. */
    private volatile Function<org.bukkit.entity.Entity, Optional<UUID>> butlerResolver = e -> Optional.empty();

    public PaperEvents(Log log) {
        this.log = log;
    }

    /** Tell the bridge how to recognise a butler, so his injuries reach core. */
    public void butlerResolver(Function<org.bukkit.entity.Entity, Optional<UUID>> resolver) {
        this.butlerResolver = resolver == null ? e -> Optional.empty() : resolver;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E extends Event> Subscription on(Class<E> type, Consumer<E> handler) {
        Consumer<Object> h = o -> handler.accept((E) o);
        handlers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(h);
        return () -> {
            List<Consumer<Object>> list = handlers.get(type);
            if (list != null) list.remove(h);
        };
    }

    private void publish(Event event) {
        List<Consumer<Object>> list = handlers.get(event.getClass());
        if (list == null) return;
        for (Consumer<Object> h : list) {
            try {
                h.accept(event);
            } catch (Exception e) {
                log.error("Event handler failed for " + event.getClass().getSimpleName(), e);
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!handlers.containsKey(ChatEvent.class)) return;
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        publish(new ChatEvent(new PaperOwner(event.getPlayer().getUniqueId()), text, event::setCancelled));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        publish(new JoinEvent(new PaperOwner(event.getPlayer().getUniqueId()), !event.getPlayer().hasPlayedBefore()));
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onQuit(PlayerQuitEvent event) {
        publish(new QuitEvent(new PaperOwner(event.getPlayer().getUniqueId())));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (!handlers.containsKey(DeathEvent.class)) return;
        List<Item> drops = new ArrayList<>();
        for (ItemStack s : event.getDrops()) drops.add(PaperItems.toItem(s));
        String message = event.deathMessage() == null ? null
                : PlainTextComponentSerializer.plainText().serialize(event.deathMessage());
        publish(new DeathEvent(new PaperOwner(event.getEntity().getUniqueId()),
                PaperWorlds.site(event.getEntity().getLocation()), drops, event.getKeepInventory(), message));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (!handlers.containsKey(PortalEvent.class)) return;
        if (event.getFrom() == null || event.getFrom().getWorld() == null) return;
        Site to = event.getTo() != null && event.getTo().getWorld() != null ? PaperWorlds.site(event.getTo()) : null;
        publish(new PortalEvent(new PaperOwner(event.getPlayer().getUniqueId()), PaperWorlds.site(event.getFrom()), to));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        org.bukkit.entity.Entity victim = event.getEntity();
        if (victim instanceof Player p) {
            if (!handlers.containsKey(OwnerDamagedEvent.class)) return;
            publish(new OwnerDamagedEvent(new PaperOwner(p.getUniqueId()),
                    Optional.of(new PaperEntity(event.getDamager())), event.getFinalDamage()));
            return;
        }
        if (!handlers.containsKey(ButlerDamagedEvent.class)) return;
        butlerResolver.apply(victim).ifPresent(ownerId ->
                publish(new ButlerDamagedEvent(ownerId,
                        Optional.of(new PaperEntity(event.getDamager())), event.getFinalDamage())));
    }
}
