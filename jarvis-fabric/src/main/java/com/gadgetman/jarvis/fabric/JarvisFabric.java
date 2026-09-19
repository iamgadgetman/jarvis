package com.gadgetman.jarvis.fabric;

import com.gadgetman.jarvis.JarvisCore;
import com.gadgetman.jarvis.fabric.butler.FakeButlers;
import com.gadgetman.jarvis.fabric.platform.FabricEvents;
import com.gadgetman.jarvis.fabric.platform.FabricLog;
import com.gadgetman.jarvis.fabric.platform.FabricPlatform;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Jarvis AI Butler, the Fabric mod.
 *
 * <p>A bootstrap, like the Paper plugin's main class: it builds the
 * {@link FabricPlatform} when the server is up, gives core a fake-player
 * body for the butler and an executor for the admin actions, and starts
 * {@link JarvisCore}. Everything the butler does lives in core; what is
 * here is registration with the loader and the server.
 */
public final class JarvisFabric implements ModInitializer {

    public static final Logger LOG = LoggerFactory.getLogger("jarvis");

    private static JarvisFabric instance;

    private String version = "unknown";
    private FabricPlatform platform;
    private FakeButlers butlers;
    private JarvisCore core;

    public static JarvisFabric get() {
        return instance;
    }

    @Override
    public void onInitialize() {
        instance = this;
        version = FabricLoader.getInstance().getModContainer("jarvis")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");

        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> FabricCommands.register(dispatcher, this));
        ServerLifecycleEvents.SERVER_STARTED.register(this::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (platform != null) platform.tick();
        });

        // The server's events, forwarded to the bridge once there is one.
        ServerPlayerEvents.JOIN.register(player -> events(e -> e.onJoin(player)));
        ServerPlayerEvents.LEAVE.register(player -> events(e -> e.onLeave(player)));
        ServerPlayerEvents.ALLOW_DEATH.register((player, source, amount) -> {
            events(e -> e.onAboutToDie(player));
            return true;
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> events(e -> e.onDeath(entity, source)));
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) ->
                events(e -> e.onDamaged(entity, source, taken)));
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) ->
                platform == null || platform.fabricEvents().onChat(sender, message.signedContent()));
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() || platform == null || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            return platform.fabricEvents().onUseItem(sp, hand);
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || platform == null || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            return platform.fabricEvents().onUseBlock(sp, hand, hit);
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() || platform == null || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            return platform.fabricEvents().onUseEntity(sp, hand, entity);
        });

        LOG.info("Jarvis v{} loaded; waiting for the server", version);
    }

    private void events(java.util.function.Consumer<FabricEvents> body) {
        if (platform != null) body.accept(platform.fabricEvents());
    }

    private void start(MinecraftServer server) {
        LOG.info("Jarvis AI Companion v{} enabling...", version);
        try {
            Path dataDir = FabricLoader.getInstance().getConfigDir().resolve("jarvis");
            platform = new FabricPlatform(server, dataDir, new FabricLog(LOG));
            butlers = new FakeButlers(platform);
            platform.fabricEvents().butlerResolver(butlers::ownerOf);
            core = new JarvisCore(platform, version, butlers, new FabricActionExecutor(this));
            core.start();
            butlers.skins().prefetch("Jarvis");
            LOG.info("Jarvis AI Companion v{} enabled successfully!", version);
        } catch (Exception e) {
            LOG.error("Jarvis could not start", e);
            if (platform != null) platform.shutdown();
            platform = null;
            core = null;
        }
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
        core = null;
        platform = null;
        butlers = null;
    }

    /** Everything core needs from the server. Null before the server is up. */
    public FabricPlatform platform() {
        return platform;
    }

    /** Jarvis himself. Null before the server is up. */
    public JarvisCore core() {
        return core;
    }

    public FakeButlers butlers() {
        return butlers;
    }

    public String version() {
        return version;
    }
}
