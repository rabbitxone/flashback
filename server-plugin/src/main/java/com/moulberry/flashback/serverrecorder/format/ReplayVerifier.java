package com.moulberry.flashback.serverrecorder.format;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * Reads a replay zip back the same way Flashback's ReplayReader does and decodes every packet.
 * Used by {@code /fbrec verify} to check that recordings are valid without starting a client.
 */
public final class ReplayVerifier {

    public record Result(int totalTicks, int chunkFiles, int actions, int cachedChunks, Map<String, Integer> packetCounts,
                         List<String> errors) {
    }

    public static Result verify(Path zipPath, RegistryAccess registryAccess) throws IOException {
        StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> gameCodec =
            GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registryAccess)).codec();
        StreamCodec<ByteBuf, Packet<? super ClientConfigurationPacketListener>> configCodec = ConfigurationProtocols.CLIENTBOUND.codec();

        Object2IntOpenHashMap<String> counts = new Object2IntOpenHashMap<>();
        List<String> errors = new ArrayList<>();
        int actions = 0;
        int totalTicks = 0;

        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            JsonObject meta;
            try (InputStream is = zip.getInputStream(zip.getEntry("metadata.json"))) {
                meta = JsonParser.parseString(new String(is.readAllBytes())).getAsJsonObject();
            }

            // Level chunk caches
            int cachedChunks = 0;
            for (int cacheIndex = 0; ; cacheIndex++) {
                var entry = zip.getEntry("level_chunk_caches/" + cacheIndex);
                if (entry == null) {
                    break;
                }
                byte[] data;
                try (InputStream is = zip.getInputStream(entry)) {
                    data = is.readAllBytes();
                }
                ByteBuf buf = Unpooled.wrappedBuffer(data);
                while (buf.readableBytes() >= 4) {
                    int size = buf.readInt();
                    RegistryFriendlyByteBuf slice = new RegistryFriendlyByteBuf(buf.readSlice(size), registryAccess);
                    try {
                        Packet<?> packet = gameCodec.decode(slice);
                        counts.addTo("cache:" + packet.type().id().getPath(), 1);
                        if (slice.isReadable()) {
                            errors.add("Cached chunk " + cachedChunks + " has " + slice.readableBytes() + " trailing bytes");
                        }
                    } catch (Exception e) {
                        errors.add("Cached chunk " + cachedChunks + " failed to decode: " + e);
                    }
                    cachedChunks++;
                }
            }

            Map<String, Integer> durations = new HashMap<>();
            for (var entry : meta.getAsJsonObject("chunks").entrySet()) {
                durations.put(entry.getKey(), entry.getValue().getAsJsonObject().get("duration").getAsInt());
            }

            for (String chunkName : meta.getAsJsonObject("chunks").keySet()) {
                byte[] data;
                try (InputStream is = zip.getInputStream(zip.getEntry(chunkName))) {
                    data = is.readAllBytes();
                }
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
                if (buf.readInt() != FlashbackFormat.MAGIC) {
                    errors.add(chunkName + ": bad magic");
                    continue;
                }
                int actionCount = buf.readVarInt();
                List<Identifier> actionTable = new ArrayList<>();
                for (int i = 0; i < actionCount; i++) {
                    actionTable.add(buf.readIdentifier());
                }
                int snapshotSize = buf.readInt();
                int snapshotEnd = buf.readerIndex() + snapshotSize;
                int ticksInChunk = 0;
                boolean sawLogin = false;
                boolean sawLocalPlayer = false;

                while (buf.isReadable()) {
                    boolean inSnapshot = buf.readerIndex() < snapshotEnd;
                    Identifier action = actionTable.get(buf.readVarInt());
                    int size = buf.readInt();
                    RegistryFriendlyByteBuf payload = new RegistryFriendlyByteBuf(buf.readSlice(size), registryAccess);
                    actions++;
                    try {
                        if (action.equals(FlashbackFormat.ACTION_NEXT_TICK)) {
                            if (inSnapshot) {
                                errors.add(chunkName + ": next_tick inside snapshot");
                            }
                            ticksInChunk++;
                        } else if (action.equals(FlashbackFormat.ACTION_GAME_PACKET)) {
                            Packet<?> packet = gameCodec.decode(payload);
                            String id = packet.type().id().getPath();
                            if (id.equals("login")) {
                                sawLogin = true;
                            }
                            counts.addTo((inSnapshot ? "snapshot:" : "") + id, 1);
                        } else if (action.equals(FlashbackFormat.ACTION_CONFIGURATION_PACKET)) {
                            Packet<?> packet = configCodec.decode(payload);
                            counts.addTo("config:" + packet.type().id().getPath(), 1);
                        } else if (action.equals(FlashbackFormat.ACTION_CREATE_LOCAL_PLAYER)) {
                            payload.readUUID();
                            payload.readDouble(); payload.readDouble(); payload.readDouble();
                            payload.readFloat(); payload.readFloat(); payload.readFloat();
                            payload.readDouble(); payload.readDouble(); payload.readDouble();
                            ByteBufCodecs.GAME_PROFILE.decode(payload);
                            payload.readVarInt();
                            sawLocalPlayer = true;
                            counts.addTo("create_local_player", 1);
                        } else if (action.equals(FlashbackFormat.ACTION_MOVE_ENTITIES)) {
                            int levels = payload.readVarInt();
                            for (int l = 0; l < levels; l++) {
                                payload.readIdentifier();
                                int count = payload.readVarInt();
                                for (int e = 0; e < count; e++) {
                                    payload.readVarInt();
                                    payload.readDouble(); payload.readDouble(); payload.readDouble();
                                    payload.readFloat(); payload.readFloat(); payload.readFloat();
                                    payload.readBoolean();
                                }
                                counts.addTo("move_entities.entries", count);
                            }
                        } else if (action.equals(FlashbackFormat.ACTION_LEVEL_CHUNK_CACHED)) {
                            int index = payload.readVarInt();
                            if (index >= cachedChunks) {
                                errors.add(chunkName + ": references missing cached chunk " + index);
                            }
                            counts.addTo((inSnapshot ? "snapshot:" : "") + "level_chunk_cached", 1);
                        } else if (action.equals(FlashbackFormat.ACTION_REAL_TIME_CLOCK)) {
                            if (payload.readByte() == 0) {
                                payload.readLong();
                            }
                        } else {
                            errors.add(chunkName + ": unknown action " + action);
                            payload.skipBytes(payload.readableBytes());
                        }
                        if (payload.isReadable()) {
                            errors.add(chunkName + ": action " + action + " has " + payload.readableBytes() + " unread bytes");
                        }
                    } catch (Exception e) {
                        errors.add(chunkName + ": failed to decode " + action + ": " + e);
                    }
                }

                if (!sawLogin || !sawLocalPlayer) {
                    errors.add(chunkName + ": snapshot is missing the login packet or local player");
                }
                Integer expected = durations.get(chunkName);
                if (expected == null || expected != ticksInChunk) {
                    errors.add(chunkName + ": metadata says " + expected + " ticks but chunk contains " + ticksInChunk);
                }
                totalTicks += ticksInChunk;
            }

            if (meta.has("total_ticks") && meta.get("total_ticks").getAsInt() != totalTicks) {
                errors.add("total_ticks is " + meta.get("total_ticks").getAsInt() + " but chunks contain " + totalTicks);
            }

            return new Result(totalTicks, durations.size(), actions, cachedChunks, counts, errors);
        }
    }

    private ReplayVerifier() {
    }

}
