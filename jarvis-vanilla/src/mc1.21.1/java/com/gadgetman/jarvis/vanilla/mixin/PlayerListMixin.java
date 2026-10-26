package com.gadgetman.jarvis.vanilla.mixin;

// Minecraft 1.21.1 copy of src/main/java/com/gadgetman/jarvis/vanilla/mixin/PlayerListMixin.java: placeNewPlayer never calls level(), so the position is fixed just before setServerLevel, after the saved data loads
// It also skips NeoForge's OnDatapackSyncEvent for the butler (1.21.1 is NeoForge-only here, so this copy may name NeoForge).
// Keep it in step with that file.

import com.gadgetman.jarvis.vanilla.fake.FakePacketListener;
import com.gadgetman.jarvis.vanilla.fake.FakePlayer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts a fake player where it was asked to spawn (rather than at the world
 * spawn) and gives it a packet listener that never expects packets.
 * It also keeps the butler's login from posting OnDatapackSyncEvent: that
 * event exists to send data to a client, the butler has none, and in a big
 * pack (All the Mods 10) its listeners take seconds and can start the
 * structure searches that hang a server's shutdown.
 * Adapted from Carpet's PlayerList_fakePlayersMixin (MIT).
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Shadow
    @Final
    private MinecraftServer server;

    @Inject(method = "placeNewPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;setServerLevel(Lnet/minecraft/server/level/ServerLevel;)V"))
    private void jarvis$fixStartingPos(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        if (player instanceof FakePlayer fake) {
            fake.fixStartingPosition.run();
        }
    }

    @Redirect(method = "placeNewPlayer", at = @At(value = "NEW",
            target = "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)Lnet/minecraft/server/network/ServerGamePacketListenerImpl;"))
    private ServerGamePacketListenerImpl jarvis$replaceListener(MinecraftServer server, Connection connection,
                                                                ServerPlayer player, CommonListenerCookie cookie) {
        if (player instanceof FakePlayer fake) {
            return new FakePacketListener(this.server, connection, fake, cookie);
        }
        return new ServerGamePacketListenerImpl(this.server, connection, player, cookie);
    }

    @WrapOperation(method = "placeNewPlayer", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/bus/api/IEventBus;post(Lnet/neoforged/bus/api/Event;)Lnet/neoforged/bus/api/Event;"))
    private Event jarvis$skipDatapackSync(IEventBus bus, Event event, Operation<Event> original) {
        if (event instanceof OnDatapackSyncEvent sync && sync.getPlayer() instanceof FakePlayer) {
            return event;
        }
        return original.call(bus, event);
    }
}
