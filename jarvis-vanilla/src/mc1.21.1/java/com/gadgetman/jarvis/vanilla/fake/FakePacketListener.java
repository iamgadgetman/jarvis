package com.gadgetman.jarvis.vanilla.fake;

// Minecraft 1.21.1 copy of src/main/java/com/gadgetman/jarvis/vanilla/fake/FakePacketListener.java: send() takes a PacketSendListener and teleport() takes coordinates
// Keep it in step with that file.

import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.RelativeMovement;

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
    public void send(Packet<?> packet, PacketSendListener listener) {
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
    public void teleport(double x, double y, double z, float yaw, float pitch, Set<RelativeMovement> relatives) {
        super.teleport(x, y, z, yaw, pitch, relatives);
        if (player.serverLevel().getPlayerByUUID(player.getUUID()) != null) {
            resetPosition();
            player.serverLevel().getChunkSource().move(player);
        }
    }
}
