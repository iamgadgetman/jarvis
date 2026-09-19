package com.gadgetman.jarvis.fabric.spike.mixin;

import com.gadgetman.jarvis.fabric.spike.fake.FakePacketListener;
import com.gadgetman.jarvis.fabric.spike.fake.FakePlayer;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
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
 * Adapted from Carpet's PlayerList_fakePlayersMixin (MIT).
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Shadow
    @Final
    private MinecraftServer server;

    @Inject(method = "placeNewPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;level()Lnet/minecraft/server/level/ServerLevel;"))
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
}
