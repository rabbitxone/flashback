package com.moulberry.flashback.serverrecorder.camera;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.jspecify.annotations.Nullable;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.function.Consumer;

/**
 * A connection without a client. Everything the server sends to the camera is handed straight to the
 * recording (synchronously, on the sending thread) instead of being written to a socket.
 */
final class CameraConnection extends Connection {

    private final Consumer<Packet<?>> sink;
    private volatile boolean open = true;

    CameraConnection(Consumer<Packet<?>> sink) {
        super(PacketFlow.SERVERBOUND);
        this.sink = sink;
        // Some server code expects a channel/address to exist
        this.channel = new EmbeddedChannel();
        this.address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
    }

    @Override
    public void send(final Packet<?> packet, final @Nullable ChannelFutureListener listener, final boolean flush) {
        if (this.open && packet != null) {
            this.sink.accept(packet);
        }
    }

    @Override
    public void flushChannel() {
    }

    @Override
    public boolean isConnected() {
        return this.open;
    }

    @Override
    public boolean isConnecting() {
        return false;
    }

    @Override
    public void tick() {
    }

    void close() {
        this.open = false;
        this.channel.close();
    }

}
