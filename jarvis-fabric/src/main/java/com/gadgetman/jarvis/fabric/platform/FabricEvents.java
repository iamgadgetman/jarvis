package com.gadgetman.jarvis.fabric.platform;

import com.gadgetman.jarvis.core.platform.Events;
import com.gadgetman.jarvis.core.platform.Log;
import com.gadgetman.jarvis.core.platform.Site;
import com.gadgetman.jarvis.core.platform.Subscription;
import com.gadgetman.jarvis.core.platform.events.ButlerDamagedEvent;
import com.gadgetman.jarvis.core.platform.events.ButlerInteractEvent;
import com.gadgetman.jarvis.core.platform.events.ChatEvent;
import com.gadgetman.jarvis.core.platform.events.DeathEvent;
import com.gadgetman.jarvis.core.platform.events.Event;
import com.gadgetman.jarvis.core.platform.events.ItemUseEvent;
import com.gadgetman.jarvis.core.platform.events.JoinEvent;
import com.gadgetman.jarvis.core.platform.events.OwnerDamagedEvent;
import com.gadgetman.jarvis.core.platform.events.PortalEvent;
import com.gadgetman.jarvis.core.platform.events.QuitEvent;
import com.gadgetman.jarvis.core.world.Ids;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.fabric.fake.FakePlayer;
import com.gadgetman.jarvis.ui.ControllerBell;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Core's {@link Events} over the server. The Fabric API callbacks are
 * registered once by the mod and forward here; this class turns them into
 * core's event records and publishes them.
 */
public final class FabricEvents implements Events {

    private final MinecraftServer server;
    private final Log log;
    private final BellRegistry bells;
    private final Map<Class<?>, List<Consumer<Object>>> handlers = new ConcurrentHashMap<>();

    /** Whose butler an entity is, set by the butler adapter once it exists. */
    private volatile Function<net.minecraft.world.entity.Entity, Optional<UUID>> butlerResolver = e -> Optional.empty();

    /** What a player was carrying and where they stood just before dying. */
    private record LastBreath(List<Item> inventory, Site where) { }
    private final Map<UUID, LastBreath> dying = new HashMap<>();

    /** Each real player's dimension and position last tick, for the portal event. */
    private record Whereabouts(ResourceKey<Level> dimension, Site site) { }
    private final Map<UUID, Whereabouts> whereabouts = new HashMap<>();

    public FabricEvents(MinecraftServer server, Log log, BellRegistry bells) {
        this.server = server;
        this.log = log;
        this.bells = bells;
    }

    public void butlerResolver(Function<net.minecraft.world.entity.Entity, Optional<UUID>> resolver) {
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

    public void publish(Event event) {
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

    private boolean wanted(Class<? extends Event> type) {
        List<Consumer<Object>> list = handlers.get(type);
        return list != null && !list.isEmpty();
    }

    private FabricOwner owner(ServerPlayer p) {
        return new FabricOwner(server, p.getUUID());
    }

    private static boolean real(net.minecraft.world.entity.Entity e) {
        return e instanceof ServerPlayer && !(e instanceof FakePlayer);
    }

    // ---- the hooks the mod's callbacks call ----

    public void onJoin(ServerPlayer p) {
        if (!real(p)) return;
        boolean first = p.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) == 0;
        publish(new JoinEvent(owner(p), first));
    }

    public void onLeave(ServerPlayer p) {
        whereabouts.remove(p.getUUID());
        if (!real(p)) return;
        publish(new QuitEvent(owner(p)));
    }

    /** True to let the message through. */
    public boolean onChat(ServerPlayer p, String text) {
        if (!real(p) || !wanted(ChatEvent.class)) return true;
        boolean[] cancelled = new boolean[1];
        publish(new ChatEvent(owner(p), text, c -> cancelled[0] = c));
        return !cancelled[0];
    }

    public void onAboutToDie(ServerPlayer p) {
        if (!real(p)) return;
        List<Item> carried = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            Item item = FabricItems.toItem(p.getInventory().getItem(i));
            if (!item.isEmpty()) carried.add(item);
        }
        dying.put(p.getUUID(), new LastBreath(carried, FabricWorlds.site(p)));
    }

    public void onDeath(LivingEntity entity, DamageSource source) {
        if (!(entity instanceof ServerPlayer p) || !real(p)) return;
        LastBreath last = dying.remove(p.getUUID());
        if (!wanted(DeathEvent.class)) return;
        boolean kept = !p.getInventory().isEmpty();
        List<Item> drops = kept || last == null ? List.of() : last.inventory();
        Site where = last == null ? FabricWorlds.site(p) : last.where();
        String message = p.getCombatTracker().getDeathMessage().getString();
        publish(new DeathEvent(owner(p), where, drops, kept, message));
    }

    public void onDamaged(LivingEntity entity, DamageSource source, float amount) {
        Optional<com.gadgetman.jarvis.core.platform.Entity> attacker =
                source.getEntity() == null ? Optional.empty() : Optional.of(new FabricEntity(source.getEntity()));
        if (entity instanceof FakePlayer fake) {
            if (!wanted(ButlerDamagedEvent.class)) return;
            butlerResolver.apply(fake).ifPresent(ownerId -> publish(new ButlerDamagedEvent(ownerId, attacker, amount)));
        } else if (entity instanceof ServerPlayer p) {
            if (!wanted(OwnerDamagedEvent.class)) return;
            publish(new OwnerDamagedEvent(owner(p), attacker, amount));
        }
    }

    /** A right-click with something in hand. PASS to let the game carry on. */
    public InteractionResult onUseItem(ServerPlayer p, InteractionHand hand) {
        if (!real(p) || hand != InteractionHand.MAIN_HAND || !wanted(ItemUseEvent.class)) return InteractionResult.PASS;
        Item item = FabricItems.toItem(p.getItemInHand(hand));
        if (item.marker() == null) return InteractionResult.PASS;
        return raiseItemUse(p, item);
    }

    /** A right-click on a block: a placed bell rung, or a marked item used on something. */
    public InteractionResult onUseBlock(ServerPlayer p, InteractionHand hand, BlockHitResult hit) {
        if (!real(p) || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        ServerLevel level = p.level();
        if (level.getBlockState(hit.getBlockPos()).getBlock() instanceof BellBlock
                && bells.isPlaced(level, hit.getBlockPos()) && wanted(ItemUseEvent.class)) {
            return raiseItemUse(p, Item.of(Ids.BELL).marked(ControllerBell.MARKER));
        }
        ItemStack held = p.getItemInHand(hand);
        Item item = FabricItems.toItem(held);
        if (item.marker() == null) return InteractionResult.PASS;
        if (ControllerBell.isController(item)) {
            // Should the bell end up placed, remember where.
            BlockPos target = hit.getBlockPos().relative(hit.getDirection());
            server.execute(() -> {
                if (level.getBlockState(target).getBlock() instanceof BellBlock) bells.add(level, target);
            });
        }
        return wanted(ItemUseEvent.class) ? raiseItemUse(p, item) : InteractionResult.PASS;
    }

    private InteractionResult raiseItemUse(ServerPlayer p, Item item) {
        boolean[] cancelled = new boolean[1];
        publish(new ItemUseEvent(owner(p), item, c -> cancelled[0] = c));
        return cancelled[0] ? InteractionResult.FAIL : InteractionResult.PASS;
    }

    /** A right-click on an entity: a butler clicked. */
    public InteractionResult onUseEntity(ServerPlayer p, InteractionHand hand, net.minecraft.world.entity.Entity target) {
        if (!real(p) || hand != InteractionHand.MAIN_HAND || !(target instanceof FakePlayer)) return InteractionResult.PASS;
        if (!wanted(ButlerInteractEvent.class)) return InteractionResult.PASS;
        Optional<UUID> ownerId = butlerResolver.apply(target);
        if (ownerId.isEmpty()) return InteractionResult.PASS;
        boolean[] cancelled = new boolean[1];
        publish(new ButlerInteractEvent(ownerId.get(), owner(p), p.isShiftKeyDown(), c -> cancelled[0] = c));
        return cancelled[0] ? InteractionResult.FAIL : InteractionResult.PASS;
    }

    /** Once per tick: notice players who changed dimension since last tick. */
    public void tick() {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!real(p)) continue;
            Whereabouts now = new Whereabouts(p.level().dimension(), FabricWorlds.site(p));
            Whereabouts before = whereabouts.put(p.getUUID(), now);
            if (before != null && before.dimension() != now.dimension() && wanted(PortalEvent.class)) {
                publish(new PortalEvent(owner(p), before.site(), now.site()));
            }
        }
    }
}
