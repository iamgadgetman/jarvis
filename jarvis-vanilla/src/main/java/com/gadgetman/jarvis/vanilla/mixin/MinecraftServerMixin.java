package com.gadgetman.jarvis.vanilla.mixin;

import com.gadgetman.jarvis.vanilla.fake.FakePlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * The multiplayer server list shows how many people are on and a sample of
 * their names; the butler is in neither.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Redirect(method = "buildPlayerStatus", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;getPlayers()Ljava/util/List;"))
    private List<ServerPlayer> jarvis$statusPeopleOnly(PlayerList list) {
        return FakePlayer.withoutFakes(list.getPlayers());
    }
}
