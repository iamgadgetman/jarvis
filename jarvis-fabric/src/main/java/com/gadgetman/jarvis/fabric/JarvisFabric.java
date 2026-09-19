package com.gadgetman.jarvis.fabric;

import com.gadgetman.jarvis.JarvisCore;
import com.gadgetman.jarvis.vanilla.JarvisMod;
import com.gadgetman.jarvis.vanilla.VanillaActionExecutor;
import com.gadgetman.jarvis.vanilla.VanillaCommands;
import com.gadgetman.jarvis.vanilla.butler.FakeButlers;
import com.gadgetman.jarvis.vanilla.platform.VanillaEvents;
import com.gadgetman.jarvis.vanilla.platform.VanillaLog;
import com.gadgetman.jarvis.vanilla.platform.VanillaPlatform;
import com.gadgetman.jarvis.vanilla.voice.VanillaVoiceHost;
import com.gadgetman.jarvis.vanilla.voice.VoiceHooks;
import com.gadgetman.jarvis.voice.svc.VoiceHost;
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

import java.nio.file.Path;

/**
 * Jarvis AI Butler, the Fabric mod.
 *
 * <p>A bootstrap, like the Paper plugin's main class: it builds the
 * {@link VanillaPlatform} when the server is up, gives core a fake-player
 * body for the butler and an executor for the admin actions, and starts
 * {@link JarvisCore}. Everything the butler does lives in core, and
 * everything that touches the server lives in jarvis-vanilla, shared with
 * the NeoForge mod; what is here is registration with Fabric.
 */
public final class JarvisFabric implements ModInitializer, JarvisMod {

    private static JarvisFabric instance;

    private String version = "unknown";
    /** Why the last start failed, for the command to repeat; null when it did not. */
    private String startupError;
    private VanillaPlatform platform;
    private FakeButlers butlers;
    private JarvisCore core;
    private VoiceHost voiceHost;

    public static JarvisFabric get() {
        return instance;
    }

    @Override
    public void onInitialize() {
        instance = this;
        version = FabricLoader.getInstance().getModContainer("jarvis")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");

        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> VanillaCommands.register(dispatcher, this));
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
                platform == null || platform.vanillaEvents().onChat(sender, message.signedContent()));
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() || platform == null || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            return platform.vanillaEvents().onUseItem(sp, hand);
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || platform == null || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            return platform.vanillaEvents().onUseBlock(sp, hand, hit);
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() || platform == null || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            return platform.vanillaEvents().onUseEntity(sp, hand, entity);
        });

        LOG.info("Jarvis v{} loaded; waiting for the server", version);
    }

    private void events(java.util.function.Consumer<VanillaEvents> body) {
        if (platform != null) body.accept(platform.vanillaEvents());
    }

    private void start(MinecraftServer server) {
        LOG.info("Jarvis AI Companion v{} enabling...", version);
        try {
            Path dataDir = FabricLoader.getInstance().getConfigDir().resolve("jarvis");
            platform = new VanillaPlatform(server, dataDir, new VanillaLog(LOG), "fabric", loaderDescription());
            butlers = new FakeButlers(platform);
            platform.vanillaEvents().butlerResolver(butlers::ownerOf);
            core = new JarvisCore(platform, version, butlers, new VanillaActionExecutor(this));
            core.start();
            voiceHost = new VanillaVoiceHost(this);
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
        // Ears, when Simple Voice Chat is here to lend them.
        if (voiceChatLoaded()) {
            VoiceHooks.serverStarted();
        } else {
            LOG.info("Voice: Simple Voice Chat is not installed, so he cannot hear you. /jarvis voice explains.");
        }
    }

    /** Only then may anything that names the voice chat API be touched. */
    private static boolean voiceChatLoaded() {
        return FabricLoader.getInstance().isModLoaded("voicechat");
    }

    private static String loaderDescription() {
        String loader = FabricLoader.getInstance().getModContainer("fabricloader")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        String api = FabricLoader.getInstance().getModContainer("fabric-api")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        return "Fabric Loader " + loader + " with Fabric API " + api;
    }

    @Override
    public String startupError() {
        return startupError;
    }

    private void stop() {
        if (voiceChatLoaded()) VoiceHooks.serverStopping();
        voiceHost = null;
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
    public VoiceHost voiceHost() {
        return voiceHost;
    }

    @Override
    public String version() {
        return version;
    }
}
