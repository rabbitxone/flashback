package com.moulberry.flashback.serverrecorder.recording;

import com.moulberry.flashback.serverrecorder.format.FlashbackFormat;
import com.moulberry.flashback.serverrecorder.format.ReplayFileWriter;
import com.moulberry.flashback.serverrecorder.format.ReplayMarker;
import com.moulberry.flashback.serverrecorder.format.ReplayMetadata;
import com.moulberry.flashback.serverrecorder.format.ReplayWriter;
import net.minecraft.SharedConstants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Format/bookkeeping side of a recording: replay chunk files ("segments"), snapshots, ticks,
 * markers and exporting. What is recorded is decided by subclasses.
 * <p>
 * Packets may be written from any thread with {@link #writePacket(Packet)}; main-thread data is written
 * at the end of every tick by {@link #tick()}. Both go through the same lock, so the replay stream
 * keeps the order in which data arrived.
 */
public abstract class Recording {

    protected final Logger logger;
    protected final String name;
    private final ReplayFileWriter out;
    private final Path outputZip;
    private final int segmentLengthTicks;

    // Guarded by "this"
    private final ReplayMetadata metadata = new ReplayMetadata();
    private boolean acceptingPackets = false;
    private boolean stopped = false;
    private int ticksInSegment = 0;
    private int totalTicks = 0;
    private long lastRtcMillis = 0;
    private boolean forceRtc = true;

    // Main thread
    private boolean needsInitialSnapshot = true;
    private int mainTicksInSegment = 0;
    private int mainTotalTicks = 0;
    private boolean stopRequested = false;
    private @Nullable CompletableFuture<Path> result = null;

    public record Position(double x, double y, double z, float yaw, float pitch, float headYaw, boolean onGround) {
        public Position {
            yaw = Mth.wrapDegrees(yaw);
            pitch = Mth.wrapDegrees(pitch);
            headYaw = Mth.wrapDegrees(headYaw);
        }
    }

    public record IdAndPosition(int id, Position position) {
    }

    protected Recording(Logger logger, String name, Path tempFolder, Path outputZip, int segmentLengthTicks, ServerLevel level) throws IOException {
        this.logger = logger;
        this.name = name;
        this.outputZip = outputZip;
        this.segmentLengthTicks = Math.max(20 * 10, segmentLengthTicks);
        this.out = new ReplayFileWriter(logger, level.registryAccess(), tempFolder, "Flashback-Recorder-" + name);

        this.metadata.name = name;
        this.metadata.versionString = SharedConstants.getCurrentVersion().name();
        this.metadata.dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
        this.metadata.protocolVersion = SharedConstants.getProtocolVersion();
        this.metadata.worldName = level.getServer().getMotd();
    }

    public String name() {
        return this.name;
    }

    public int recordedTicks() {
        return this.mainTotalTicks;
    }

    public boolean isStopped() {
        return this.stopRequested;
    }

    public abstract ServerLevel level();

    /** Builds a full snapshot of the current state. Main thread. */
    protected abstract Snapshot buildSnapshot();

    /**
     * Collects the packets, entity positions and custom actions for the tick that just ended. Main thread.
     * Custom actions run on the writer thread after the packets.
     */
    protected abstract void collectTick(List<Packet<? super ClientGamePacketListener>> packets, List<IdAndPosition> positions,
                                        List<Consumer<ReplayWriter>> actions);

    /** @return a reason if the recording should stop by itself (e.g. the recorded player left), otherwise null */
    public @Nullable String shouldStop() {
        return null;
    }

    /** Releases listeners etc. once the recording is stopped. Main thread. */
    protected void cleanup() {
    }

    /** Called at the end of every tick, after everything was written. Main thread. */
    protected void afterTick() {
    }

    // ---------------------------------------------------------------------------------------------

    /** Writes a packet to the replay. Can be called from any thread. */
    public final void writePacket(Packet<? super ClientGamePacketListener> packet) {
        synchronized (this) {
            if (this.acceptingPackets && !this.stopped) {
                this.out.writeGamePacket(packet);
            }
        }
    }

    /** Must be called on the main thread at the end of every server tick. */
    public final void tick() {
        if (this.stopRequested) {
            return;
        }

        Snapshot initialSnapshot = null;
        if (this.needsInitialSnapshot) {
            this.needsInitialSnapshot = false;
            initialSnapshot = this.buildSnapshot();
        }

        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
        List<IdAndPosition> positions = new ArrayList<>();
        List<Consumer<ReplayWriter>> actions = new ArrayList<>();
        if (initialSnapshot == null) {
            this.collectTick(packets, positions, actions);
        }

        this.mainTicksInSegment += 1;
        this.mainTotalTicks += 1;

        Snapshot nextSegmentSnapshot = null;
        if (this.mainTicksInSegment >= this.segmentLengthTicks) {
            this.mainTicksInSegment = 0;
            nextSegmentSnapshot = this.buildSnapshot();
        }

        ResourceKey<Level> dimension = this.level().dimension();
        synchronized (this) {
            if (!this.stopped) {
                if (initialSnapshot != null) {
                    this.writeSnapshot(initialSnapshot);
                    this.acceptingPackets = true;
                }
                this.writeRealTimeClock();
                if (!packets.isEmpty()) {
                    this.out.writeGamePackets(packets);
                }
                for (Consumer<ReplayWriter> action : actions) {
                    this.out.submit(action);
                }
                if (!positions.isEmpty()) {
                    this.writePositions(positions, dimension);
                }
                this.out.submit(writer -> writer.writeEmptyAction(FlashbackFormat.ACTION_NEXT_TICK));
                this.ticksInSegment += 1;
                this.totalTicks += 1;

                if (nextSegmentSnapshot != null) {
                    this.flushSegment();
                    this.writeSnapshot(nextSegmentSnapshot);
                }
            }
        }

        this.afterTick();
    }

    public final void addMarker(ReplayMarker marker) {
        synchronized (this) {
            if (!this.stopped) {
                this.metadata.markers.put(this.totalTicks, marker);
            }
        }
    }

    /** Stops the recording and exports the zip in the background. Main thread. */
    public final CompletableFuture<Path> stop() {
        if (this.result != null) {
            return this.result;
        }
        this.stopRequested = true;
        CompletableFuture<Path> future = new CompletableFuture<>();
        this.result = future;

        try {
            this.cleanup();
        } catch (Exception e) {
            this.logger.error("Error while cleaning up recording {}", this.name, e);
        }

        synchronized (this) {
            this.stopped = true;
            if (!this.acceptingPackets) {
                this.out.abort();
                future.completeExceptionally(new IllegalStateException("Recording '" + this.name + "' didn't record anything"));
                return future;
            }
            if (this.ticksInSegment == 0) {
                this.out.submit(writer -> writer.writeEmptyAction(FlashbackFormat.ACTION_NEXT_TICK));
                this.ticksInSegment += 1;
                this.totalTicks += 1;
            }
            this.flushSegment();
            this.out.finishAndExport(this.metadata, this.outputZip).whenComplete((path, error) -> {
                if (error != null) {
                    future.completeExceptionally(error);
                } else {
                    future.complete(path);
                }
            });
        }
        return future;
    }

    /** Stops the recording and deletes everything. Main thread. */
    public final void cancel() {
        if (this.result != null) {
            return;
        }
        this.stopRequested = true;
        this.result = CompletableFuture.failedFuture(new IllegalStateException("Recording '" + this.name + "' was cancelled"));
        try {
            this.cleanup();
        } catch (Exception e) {
            this.logger.error("Error while cleaning up recording {}", this.name, e);
        }
        synchronized (this) {
            this.stopped = true;
            this.out.abort();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Called while holding the lock

    private void writeSnapshot(Snapshot snapshot) {
        this.out.submit(writer -> writer.startSnapshot());
        this.out.writeConfigurationPackets(snapshot.configurationPackets());
        this.out.writeGamePackets(snapshot.loginPackets());
        LocalPlayerData localPlayer = snapshot.localPlayer();
        this.out.submit(writer -> {
            RegistryFriendlyByteBuf buf = writer.startAction(FlashbackFormat.ACTION_CREATE_LOCAL_PLAYER);
            localPlayer.write(buf);
            writer.finishAction(FlashbackFormat.ACTION_CREATE_LOCAL_PLAYER);
        });
        this.out.writeGamePackets(snapshot.gamePackets());
        for (Consumer<ReplayWriter> action : snapshot.actions()) {
            this.out.submit(action);
        }
        this.out.submit(writer -> writer.endSnapshot());
        this.forceRtc = true;
    }

    private void flushSegment() {
        String chunkName = "c" + this.metadata.chunkDurations.size() + ".flashback";
        this.metadata.chunkDurations.put(chunkName, this.ticksInSegment);
        this.metadata.totalTicks = this.totalTicks;
        this.out.flushReplayChunk(chunkName, this.metadata);
        this.ticksInSegment = 0;
    }

    private void writeRealTimeClock() {
        long now = System.currentTimeMillis();
        long delta = now - (this.lastRtcMillis + 50);
        this.lastRtcMillis = now;
        boolean force = this.forceRtc;
        this.forceRtc = false;

        if (!force && delta == 0) {
            return;
        }
        if (force || (byte) delta != delta) {
            this.out.submit(writer -> {
                writer.startAction(FlashbackFormat.ACTION_REAL_TIME_CLOCK).writeByte(0).writeLong(now);
                writer.finishAction(FlashbackFormat.ACTION_REAL_TIME_CLOCK);
            });
        } else {
            byte byteDelta = (byte) delta;
            this.out.submit(writer -> {
                writer.startAction(FlashbackFormat.ACTION_REAL_TIME_CLOCK).writeByte(byteDelta);
                writer.finishAction(FlashbackFormat.ACTION_REAL_TIME_CLOCK);
            });
        }
    }

    private void writePositions(List<IdAndPosition> positions, ResourceKey<Level> dimension) {
        this.out.submit(writer -> {
            RegistryFriendlyByteBuf buf = writer.startAction(FlashbackFormat.ACTION_MOVE_ENTITIES);
            buf.writeVarInt(1);
            buf.writeResourceKey(dimension);
            buf.writeVarInt(positions.size());
            for (IdAndPosition entry : positions) {
                Position p = entry.position();
                buf.writeVarInt(entry.id());
                buf.writeDouble(p.x());
                buf.writeDouble(p.y());
                buf.writeDouble(p.z());
                buf.writeFloat(p.yaw());
                buf.writeFloat(p.pitch());
                buf.writeFloat(p.headYaw());
                buf.writeBoolean(p.onGround());
            }
            writer.finishAction(FlashbackFormat.ACTION_MOVE_ENTITIES);
        });
    }

}
