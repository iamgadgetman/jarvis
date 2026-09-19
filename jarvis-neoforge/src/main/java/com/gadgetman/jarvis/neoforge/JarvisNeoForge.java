package com.gadgetman.jarvis.neoforge;

import com.gadgetman.jarvis.JarvisCore;
import com.gadgetman.jarvis.vanilla.JarvisMod;
import com.gadgetman.jarvis.vanilla.VanillaActionExecutor;
import com.gadgetman.jarvis.vanilla.VanillaCommands;
import com.gadgetman.jarvis.vanilla.butler.FakeButlers;
import com.gadgetman.jarvis.vanilla.platform.VanillaEvents;
import com.gadgetman.jarvis.vanilla.platform.VanillaLog;
import com.gadgetman.jarvis.vanilla.platform.VanillaPlatform;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Jarvis AI Butler, the NeoForge mod.
 *
 * <p>The same bootstrap as the Fabric mod, on NeoForge's event bus: it
 * builds the {@link VanillaPlatform} when the server is up, gives core a
 * fake-player body for the butler and an executor for the admin actions,
 * and starts {@link JarvisCore}. Everything the butler does lives in core,
 * and everything that touches the server lives in jarvis-vanilla, shared
 * with the Fabric mod; what is here is registration with NeoForge.
 */
@Mod("jarvis")
public final class JarvisNeoForge implements JarvisMod {

    private static JarvisNeoForge instance;

    private final String version;
    /** Why the last start failed, for the command to repeat; null when it did not. */
    private String startupError;
    private VanillaPlatform platform;
    private FakeButlers butlers;
    private JarvisCore core;

    /**
     * Fabric raises one event before a player dies and another after the
     * drops are on the ground; NeoForge raises one, before. The death is
     * finished on the next tick, which is when core hears of it.
     */
    private final List<Death> pendingDeaths = new ArrayList<>();

    private record Death(ServerPlayer player, DamageSource source) { }

    public static JarvisNeoForge get() {
        return instance;
    }

    public JarvisNeoForge(IEventBus modBus, ModContainer container) {
        instance = this;
        version = container.getModInfo().getVersion().toString();
        NeoForge.EVENT_BUS.register(this);
        LOG.info("Jarvis v{} loaded; waiting for the server", version);
    }

    // ---- lifecycle ----

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        start(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        stop();
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (platform == null) return;
        platform.tick();
        if (!pendingDeaths.isEmpty()) {
            List<Death> done = new ArrayList<>(pendingDeaths);
            pendingDeaths.clear();
            for (Death d : done) platform.vanillaEvents().onDeath(d.player(), d.source());
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        VanillaCommands.register(event.getDispatcher(), this);
    }

    // ---- the server's events, forwarded to the bridge once there is one ----

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) events(e -> e.onJoin(p));
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) events(e -> e.onLeave(p));
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (platform == null || !(event.getEntity() instanceof ServerPlayer p)) return;
        platform.vanillaEvents().onAboutToDie(p);
        pendingDeaths.add(new Death(p, event.getSource()));
    }

    @SubscribeEvent
    public void onDamaged(LivingDamageEvent.Post event) {
        if (event.getEntity().level().isClientSide()) return;
        events(e -> e.onDamaged(event.getEntity(), event.getSource(), event.getInflictedDamage()));
    }

    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        if (platform == null) return;
        if (!platform.vanillaEvents().onChat(event.getPlayer(), event.getRawText())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onUseItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide() || platform == null || !(event.getEntity() instanceof ServerPlayer p)) return;
        InteractionResult result = platform.vanillaEvents().onUseItem(p, event.getHand());
        if (result != InteractionResult.PASS) {
            event.setCancellationResult(result);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onUseBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || platform == null || !(event.getEntity() instanceof ServerPlayer p)) return;
        InteractionResult result = platform.vanillaEvents().onUseBlock(p, event.getHand(), event.getHitVec());
        if (result != InteractionResult.PASS) {
            event.setCancellationResult(result);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onUseEntity(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide() || platform == null || !(event.getEntity() instanceof ServerPlayer p)) return;
        InteractionResult result = platform.vanillaEvents().onUseEntity(p, event.getHand(), event.getTarget());
        if (result != InteractionResult.PASS) {
            event.setCancellationResult(result);
            event.setCanceled(true);
        }
    }

    private void events(Consumer<VanillaEvents> body) {
        if (platform != null) body.accept(platform.vanillaEvents());
    }

    // ---- start and stop ----

    private void start(MinecraftServer server) {
        LOG.info("Jarvis AI Companion v{} enabling...", version);
        try {
            Path dataDir = FMLPaths.CONFIGDIR.get().resolve("jarvis");
            platform = new VanillaPlatform(server, dataDir, new VanillaLog(LOG), "neoforge", loaderDescription());
            butlers = new FakeButlers(platform);
            platform.vanillaEvents().butlerResolver(butlers::ownerOf);
            core = new JarvisCore(platform, version, butlers, new VanillaActionExecutor(this));
            core.start();
            startupError = null;
            LOG.info("Jarvis AI Companion v{} enabled successfully!", version);
        } catch (Exception e) {
            LOG.error("Jarvis could not start", e);
            startupError = e.toString();
            if (platform != null) platform.shutdown();
            platform = null;
            core = null;
            return;
        }
        // His uniform, fetched ahead of the first summon. Never fatal.
        try {
            butlers.skins().prefetch("Jarvis");
        } catch (Exception e) {
            LOG.warn("Could not start fetching Jarvis's skin: {}", e.toString());
        }
    }

    private static String loaderDescription() {
        String neo = ModList.get().getModContainerById("neoforge")
                .map(c -> c.getModInfo().getVersion().toString()).orElse("?");
        return "NeoForge " + neo;
    }

    private void stop() {
        if (core != null) {
            try {
                core.shutdown();
            } catch (Exception e) {
                LOG.error("Jarvis did not shut down cleanly", e);
            }
        }
        if (platform != null) platform.shutdown();
        pendingDeaths.clear();
        core = null;
        platform = null;
        butlers = null;
    }

    @Override
    public String startupError() {
        return startupError;
    }

    @Override
    public VanillaPlatform platform() {
        return platform;
    }

    @Override
    public JarvisCore core() {
        return core;
    }

    @Override
    public FakeButlers butlers() {
        return butlers;
    }

    @Override
    public String version() {
        return version;
    }
}
