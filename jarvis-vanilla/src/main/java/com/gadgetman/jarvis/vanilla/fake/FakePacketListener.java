package com.gadgetman.jarvis.vanilla.fake;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;

import java.util.Set;

/**
 * The game-phase packet listener for a fake player: it never has packets to
 * read, and an idle kick becomes a despawn. Adapted from Carpet's
 * NetHandlerPlayServerFake (MIT, see THIRD-PARTY-LICENSES.md).
 */
public class FakePacketListener extends ServerGamePacketListenerImpl {

    public FakePacketListener(MinecraftServer server, Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
        super(server, connection, player, cookie);
    }

    /**
     * Nobody is listening, so nothing is sent. Dropping packets here rather
     * than in {@link FakeConnection} also skips NeoForge's channel check,
     * which throws for a mod's payload (Apothic Attributes syncs its config
     * on login) because a fake player never negotiated any channels, and
     * that throw used to abort the summon.
     */
    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener) {
    }

    @Override
    public void disconnect(Component message) {
        if (message.getContents() instanceof TranslatableContents text
                && (text.getKey().equals("multiplayer.disconnect.idling")
                || text.getKey().equals("multiplayer.disconnect.duplicate_login"))) {
            ((FakePlayer) player).kill(message);
        }
    }

    @Override
    public void teleport(PositionMoveRotation positionMoveRotation, Set<Relative> relatives) {
        super.teleport(positionMoveRotation, relatives);
        if (player.level().getPlayerByUUID(player.getUUID()) != null) {
            resetPosition();
            player.level().getChunkSource().move(player);
        }
    }
}
