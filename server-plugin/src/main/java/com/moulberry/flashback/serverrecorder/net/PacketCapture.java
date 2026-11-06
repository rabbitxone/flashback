package com.moulberry.flashback.serverrecorder.net;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Netty handler that sees every clientbound packet written to a player's connection, before it is encoded.
 * Several listeners (recordings) can share one handler.
 */
public final class PacketCapture extends ChannelDuplexHandler {

    public static final String HANDLER_NAME = "flashback_server_recorder";

    private final Logger logger;
    private final CopyOnWriteArrayList<Consumer<Packet<?>>> listeners = new CopyOnWriteArrayList<>();

    private PacketCapture(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof Packet<?> packet) {
            for (Consumer<Packet<?>> listener : this.listeners) {
                try {
                    listener.accept(packet);
                } catch (Throwable t) {
                    this.logger.error("Error while recording packet {}", packet.getClass().getSimpleName(), t);
                }
            }
        }
        super.write(ctx, msg, promise);
    }

    public static @Nullable Channel channel(ServerPlayer player) {
        Connection connection = player.connection.connection;
        return connection.channel;
    }

    /** Adds a listener to the player's connection, installing the handler if necessary. */
    public static synchronized boolean addListener(Logger logger, ServerPlayer player, Consumer<Packet<?>> listener) {
        Channel channel = channel(player);
        if (channel == null || !channel.isOpen()) {
            return false;
        }
        PacketCapture capture = (PacketCapture) channel.pipeline().get(HANDLER_NAME);
        if (capture == null) {
            capture = new PacketCapture(logger);
            if (channel.pipeline().get("packet_handler") != null) {
                channel.pipeline().addBefore("packet_handler", HANDLER_NAME, capture);
            } else {
                channel.pipeline().addLast(HANDLER_NAME, capture);
            }
        }
        capture.listeners.add(listener);
        return true;
    }

    public static synchronized void removeListener(ServerPlayer player, Consumer<Packet<?>> listener) {
        Channel channel = channel(player);
        if (channel == null) {
            return;
        }
        PacketCapture capture = (PacketCapture) channel.pipeline().get(HANDLER_NAME);
        if (capture != null) {
            capture.listeners.remove(listener);
            if (capture.listeners.isEmpty() && channel.isOpen()) {
                try {
                    channel.pipeline().remove(capture);
                } catch (Exception ignored) {
                }
            }
        }
    }

}
