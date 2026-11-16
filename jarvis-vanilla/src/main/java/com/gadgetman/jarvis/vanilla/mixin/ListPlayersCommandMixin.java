package com.gadgetman.jarvis.vanilla.mixin;

import com.gadgetman.jarvis.vanilla.fake.FakePlayer;
import net.minecraft.server.commands.ListPlayersCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/** {@code /list} names the people online, not the butler. */
@Mixin(ListPlayersCommand.class)
public abstract class ListPlayersCommandMixin {

    @Redirect(method = "format", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;getPlayers()Ljava/util/List;"))
    private static List<ServerPlayer> jarvis$listPeopleOnly(PlayerList list) {
        return FakePlayer.withoutFakes(list.getPlayers());
    }
}
