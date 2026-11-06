package com.moulberry.flashback.serverrecorder.format;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.slf4j.Logger;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Owns the background thread that encodes packets and writes the replay to disk.
 * <p>
 * All writes are submitted as tasks and executed strictly in submission order, which keeps the
 * replay stream consistent no matter which thread produced the data.
 * Port of {@code AsyncReplaySaver} + {@code ReplayExporter} from the Flashback mod.
 */
public final class ReplayFileWriter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Logger logger;
    private final Path recordFolder;
    private final LinkedBlockingQueue<Consumer<ReplayWriter>> tasks = new LinkedBlockingQueue<>();
    private final Thread thread;
    private final ReplayWriter writer;
    private final StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> gameCodec;
    private final StreamCodec<ByteBuf, Packet<? super ClientConfigurationPacketListener>> configurationCodec;

    private volatile boolean stopRequested = false;
    private volatile Throwable error = null;

    // Chunk cache state, only touched from the writer thread
    private final Map<String, Integer> chunkCacheIndices = new HashMap<>();
    private int totalCachedChunks = 0;
    private final MessageDigest digest;

    private int failedPacketLogBudget = 10;

    public ReplayFileWriter(Logger logger, RegistryAccess registryAccess, Path recordFolder, String threadName) throws IOException {
        this.logger = logger;
        this.recordFolder = recordFolder;
        Files.createDirectories(recordFolder);

        this.writer = new ReplayWriter(registryAccess);
        this.gameCodec = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registryAccess)).codec();
        this.configurationCodec = ConfigurationProtocols.CLIENTBOUND.codec();

        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }

        this.thread = new Thread(this::run, threadName);
        this.thread.setDaemon(true);
        this.thread.start();
    }

    private void run() {
        while (true) {
            Consumer<ReplayWriter> task;
            try {
                task = this.tasks.poll(10, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                continue;
            }
            if (task == null) {
                if (this.stopRequested && this.tasks.isEmpty()) {
                    return;
                }
                continue;
            }
            try {
                task.accept(this.writer);
            } catch (Throwable t) {
                this.error = t;
                this.logger.error("Flashback replay writer crashed, the recording is lost", t);
                this.tasks.clear();
                return;
            }
        }
    }

    public boolean hasFailed() {
        return this.error != null;
    }

    public void submit(Consumer<ReplayWriter> task) {
        if (this.stopRequested || this.error != null) {
            return;
        }
        this.tasks.add(task);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers that run on the writer thread

    public void writeGamePackets(List<? extends Packet<? super ClientGamePacketListener>> packets) {
        this.submit(writer -> {
            for (Packet<? super ClientGamePacketListener> packet : packets) {
                this.writeGamePacketNow(writer, packet);
            }
        });
    }

    public void writeGamePacket(Packet<? super ClientGamePacketListener> packet) {
        this.submit(writer -> this.writeGamePacketNow(writer, packet));
    }

    public void writeConfigurationPackets(List<? extends Packet<? super ClientConfigurationPacketListener>> packets) {
        this.submit(writer -> {
            for (Packet<? super ClientConfigurationPacketListener> packet : packets) {
                int start = writer.writerIndex();
                RegistryFriendlyByteBuf buf = writer.startAction(FlashbackFormat.ACTION_CONFIGURATION_PACKET);
                try {
                    this.configurationCodec.encode(buf, packet);
                    writer.finishAction(FlashbackFormat.ACTION_CONFIGURATION_PACKET);
                } catch (Exception e) {
                    writer.abortAction(start);
                    this.logEncodeFailure(packet, e);
                }
            }
        });
    }

    private void writeGamePacketNow(ReplayWriter writer, Packet<? super ClientGamePacketListener> packet) {
        if (packet instanceof ClientboundLevelChunkWithLightPacket) {
            this.writeCachedChunk(writer, packet);
            return;
        }

        int start = writer.writerIndex();
        RegistryFriendlyByteBuf buf = writer.startAction(FlashbackFormat.ACTION_GAME_PACKET);
        try {
            this.gameCodec.encode(buf, packet);
            writer.finishAction(FlashbackFormat.ACTION_GAME_PACKET);
        } catch (Exception e) {
            writer.abortAction(start);
            this.logEncodeFailure(packet, e);
        }
    }

    private void logEncodeFailure(Packet<?> packet, Exception e) {
        if (this.failedPacketLogBudget > 0) {
            this.failedPacketLogBudget -= 1;
            this.logger.warn("Unable to encode {} for replay, skipping it", packet.getClass().getSimpleName(), e);
        }
    }

    /**
     * Chunks are stored once in level_chunk_caches/N and referenced by index, exactly like the Flashback mod does.
     * Identical chunk data (e.g. a chunk that was re-sent without changes) is only stored once.
     */
    private void writeCachedChunk(ReplayWriter writer, Packet<? super ClientGamePacketListener> packet) {
        RegistryFriendlyByteBuf encoded = new RegistryFriendlyByteBuf(Unpooled.buffer(), writer.registryAccess());
        try {
            this.gameCodec.encode(encoded, packet);
        } catch (Exception e) {
            this.logEncodeFailure(packet, e);
            return;
        }

        byte[] bytes = new byte[encoded.writerIndex()];
        encoded.getBytes(0, bytes);
        String hash = java.util.HexFormat.of().formatHex(this.digest.digest(bytes));

        Integer index = this.chunkCacheIndices.get(hash);
        if (index == null) {
            index = this.totalCachedChunks++;
            this.chunkCacheIndices.put(hash, index);

            Path cacheFile = this.recordFolder.resolve("level_chunk_caches").resolve(String.valueOf(index / FlashbackFormat.CHUNK_CACHE_SIZE));
            try {
                Files.createDirectories(cacheFile.getParent());
                try (OutputStream os = Files.newOutputStream(cacheFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    os.write(new byte[]{(byte) (bytes.length >>> 24), (byte) (bytes.length >>> 16), (byte) (bytes.length >>> 8), (byte) bytes.length});
                    os.write(bytes);
                }
            } catch (IOException e) {
                throw new RuntimeException("Unable to write level chunk cache", e);
            }
        }

        writer.startAction(FlashbackFormat.ACTION_LEVEL_CHUNK_CACHED).writeVarInt(index);
        writer.finishAction(FlashbackFormat.ACTION_LEVEL_CHUNK_CACHED);
    }

    /** Writes the current in-memory chunk to cN.flashback and updates metadata.json. */
    public void flushReplayChunk(String chunkName, ReplayMetadata metadataSnapshot) {
        String metadataJson = GSON.toJson(metadataSnapshot.toJson());
        this.submit(writer -> {
            try {
                Files.write(this.recordFolder.resolve(chunkName), writer.popBytes());
                Path metaFile = this.recordFolder.resolve("metadata.json");
                if (Files.exists(metaFile)) {
                    Files.move(metaFile, this.recordFolder.resolve("metadata.json.old"), StandardCopyOption.REPLACE_EXISTING);
                }
                Files.writeString(metaFile, metadataJson);
            } catch (IOException e) {
                throw new RuntimeException("Unable to write replay chunk " + chunkName, e);
            }
        });
    }

    /**
     * Stops the writer thread once all queued tasks are done, then packs everything into a Flashback-compatible zip.
     * Runs asynchronously; the returned future completes with the path of the zip.
     */
    public CompletableFuture<Path> finishAndExport(ReplayMetadata finalMetadata, Path outputZip) {
        String metadataJson = GSON.toJson(finalMetadata.toJson());
        this.stopRequested = true;

        CompletableFuture<Path> future = new CompletableFuture<>();
        Thread exportThread = new Thread(() -> {
            try {
                this.thread.join();
                if (this.error != null) {
                    throw new IOException("Replay writer failed", this.error);
                }
                this.exportZip(metadataJson, outputZip);
                deleteRecursively(this.recordFolder);
                future.complete(outputZip);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }, this.thread.getName() + "-export");
        exportThread.setDaemon(true);
        exportThread.start();
        return future;
    }

    private void exportZip(String metadataJson, Path outputZip) throws IOException {
        Files.createDirectories(outputZip.toAbsolutePath().getParent());
        Path tmp = outputZip.resolveSibling(outputZip.getFileName() + ".part");

        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            zip.setLevel(Deflater.BEST_SPEED);

            zip.putNextEntry(new ZipEntry("metadata.json"));
            zip.write(metadataJson.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            Path caches = this.recordFolder.resolve("level_chunk_caches");
            if (Files.isDirectory(caches)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(caches)) {
                    for (Path path : stream) {
                        zip.putNextEntry(new ZipEntry("level_chunk_caches/" + path.getFileName()));
                        Files.copy(path, zip);
                        zip.closeEntry();
                    }
                }
            }

            Path icon = this.recordFolder.resolve("icon.png");
            if (Files.exists(icon)) {
                zip.putNextEntry(new ZipEntry("icon.png"));
                Files.copy(icon, zip);
                zip.closeEntry();
            }

            for (String chunkName : this.chunkNames(metadataJson)) {
                Path chunk = this.recordFolder.resolve(chunkName);
                zip.putNextEntry(new ZipEntry(chunkName));
                Files.copy(chunk, zip);
                zip.closeEntry();
            }
        }

        Files.move(tmp, outputZip, StandardCopyOption.REPLACE_EXISTING);
    }

    private List<String> chunkNames(String metadataJson) {
        var json = GSON.fromJson(metadataJson, com.google.gson.JsonObject.class);
        return List.copyOf(json.getAsJsonObject("chunks").keySet());
    }

    public Path recordFolder() {
        return this.recordFolder;
    }

    /** Discards the recording without exporting it. */
    public void abort() {
        this.stopRequested = true;
        this.tasks.clear();
        Thread cleanup = new Thread(() -> {
            try {
                this.thread.join();
                deleteRecursively(this.recordFolder);
            } catch (Exception ignored) {
            }
        });
        cleanup.setDaemon(true);
        cleanup.start();
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

}
