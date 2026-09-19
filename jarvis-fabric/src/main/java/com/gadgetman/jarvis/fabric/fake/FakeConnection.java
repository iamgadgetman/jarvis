package com.gadgetman.jarvis.fabric.fake;

import com.gadgetman.jarvis.fabric.mixin.ConnectionAccessor;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * A connection with nobody on the other end. Adapted from Carpet's
 * FakeClientConnection (MIT, see THIRD-PARTY-LICENSES.md).
 */
public class FakeConnection extends Connection {

    public FakeConnection(PacketFlow flow) {
        super(flow);
        // Gives the connection a channel so isOpen() is true, without any of
        // the vanilla channel setup.
        ((ConnectionAccessor) this).setChannel(new EmbeddedChannel());
    }

    @Override
    public void setReadOnly() {
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
    }

    @Override
    public void handleDisconnection() {
    }

    @Override
    public void setListenerForServerboundHandshake(PacketListener listener) {
    }

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocolInfo, T listener) {
    }
}
