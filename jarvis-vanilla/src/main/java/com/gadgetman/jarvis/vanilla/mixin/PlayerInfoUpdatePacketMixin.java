package com.gadgetman.jarvis.vanilla.mixin;

import com.gadgetman.jarvis.vanilla.fake.FakePlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Keeps the butler off the tab list. A client still needs his player-info
 * entry to draw him with his skin, so he stays in the packet; only its
 * {@code listed} flag, which vanilla always sets, is cleared for him.
 */
@Mixin(ClientboundPlayerInfoUpdatePacket.class)
public abstract class PlayerInfoUpdatePacketMixin {

    @Shadow
    @Final
    @Mutable
    private List<ClientboundPlayerInfoUpdatePacket.Entry> entries;

    @Inject(method = "<init>(Ljava/util/EnumSet;Ljava/util/Collection;)V", at = @At("RETURN"))
    private void jarvis$unlistFakes(EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions,
                                    Collection<ServerPlayer> players, CallbackInfo ci) {
        unlist(players);
    }

    @Inject(method = "<init>(Lnet/minecraft/network/protocol/game/ClientboundPlayerInfoUpdatePacket$Action;Lnet/minecraft/server/level/ServerPlayer;)V",
            at = @At("RETURN"))
    private void jarvis$unlistFake(ClientboundPlayerInfoUpdatePacket.Action action, ServerPlayer player, CallbackInfo ci) {
        unlist(List.of(player));
    }

    private void unlist(Collection<ServerPlayer> players) {
        Set<UUID> fakes = players.stream().filter(p -> p instanceof FakePlayer)
                .map(ServerPlayer::getUUID).collect(Collectors.toSet());
        if (fakes.isEmpty()) return;
        this.entries = entries.stream()
                .map(e -> fakes.contains(e.profileId()) ? unlisted(e) : e)
                .toList();
    }

    private static ClientboundPlayerInfoUpdatePacket.Entry unlisted(ClientboundPlayerInfoUpdatePacket.Entry e) {
        return new ClientboundPlayerInfoUpdatePacket.Entry(e.profileId(), e.profile(), false, e.latency(),
                e.gameMode(), e.displayName(), e.showHat(), e.listOrder(), e.chatSession());
    }
}
