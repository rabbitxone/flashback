package com.moulberry.flashback.serverrecorder.recording;

import com.moulberry.flashback.serverrecorder.format.FlashbackFormat;
import com.moulberry.flashback.serverrecorder.format.ReplayFileWriter;
import com.moulberry.flashback.serverrecorder.format.ReplayMarker;
import com.moulberry.flashback.serverrecorder.format.ReplayMetadata;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.SharedConstants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A single server-side recording that produces one Flashback replay.
 * <p>
 * Data comes from two places:
 * <ul>
 *     <li>clientbound packets sent to the {@link #viewer()} (captured on whatever thread sends them), and</li>
 *     <li>data generated on the main thread at the end of every tick (entity positions, snapshots, ...).</li>
 * </ul>
 * To keep both in the right order, main thread data is handed over with {@link #runSequenced(Runnable)},
 * which for real players runs on the player's netty event loop (the same thread that sees the packets).
 */
public abstract class Recording {

    protected final Logger logger;
    protected final String name;
    private final ReplayFileWriter out;
    private final Path outputZip;
    private final int segmentLengthTicks;

    // --- State owned by the sequenced side (guarded by "this")
    private final ReplayMetadata metadata = new ReplayMetadata();
    private boolean acceptingPackets = false;
    private boolean stopped = false;
    private int ticksInSegment = 0;
    private int totalTicks = 0;
    private long lastRtcMillis = 0;
    private boolean forceRtc = true;

    // --- State owned by the main thread
    private boolean needsInitialSnapshot = true;
    private int mainTicksInSegment = 0;
    private int mainTotalTicks = 0;
    private boolean stopRequested = false;
    private @Nullable ResourceKey<Level> lastDimension = null;
    private final Int2ObjectMap<Position> lastPositions = new Int2ObjectOpenHashMap<>();
    private @Nullable CompletableFuture<Path> result = null;

    private record Position(double x, double y, double z, float yaw, float pitch, float headYaw, boolean onGround) {
        Position {
            yaw = Mth.wrapDegrees(yaw);
            pitch = Mth.wrapDegrees(pitch);
            headYaw = Mth.wrapDegrees(headYaw);
        }
    }
    private record IdAndPosition(int id, Position position) {}

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

    /** @return the number of ticks recorded so far */
    public int recordedTicks() {
        return this.mainTotalTicks;
    }

    public boolean isStopped() {
        return this.stopRequested;
    }

    /** The player whose point of view is recorded. */
    public abstract ServerPlayer viewer();

    /** Runs the task in order with the packets sent to {@link #viewer()}. */
    protected abstract void runSequenced(Runnable runnable);

    /** Game mode of the local player inside the replay. */
    protected abstract GameType localGameType();

    /** Player whose HUD (scoreboard, tab list, boss bars) is recorded, may be null. */
    public abstract @Nullable ServerPlayer hudSource();

    protected abstract boolean isCamera();

    /** Extra main-thread packets to write this tick (e.g. local player state). */
    protected void collectTickPackets(ServerPlayer viewer, List<Packet<? super ClientGamePacketListener>> packets) {
    }

    /** Extra mode specific filtering of captured packets. */
    protected boolean shouldRecord(Packet<?> packet) {
        return true;
    }

    /** Called when the recording has been stopped, to release resources (handlers, cameras, ...). */
    protected void cleanup() {
    }

    // ---------------------------------------------------------------------------------------------
    // Packet capture

    /**
     * Called for every clientbound packet sent to the viewer, on the thread that writes it.
     */
    public final void onPacket(Packet<?> packet) {
        if (packet instanceof ClientboundBundlePacket bundle) {
            for (Packet<? super ClientGamePacketListener> subPacket : bundle.subPackets()) {
                this.onPacket(subPacket);
            }
            return;
        }
        if (packet instanceof ClientboundStartConfigurationPacket) {
            this.onConfigurationPhase();
            return;
        }
        if (PacketFilter.isIgnored(packet) || !this.shouldRecord(packet)) {
            return;
        }
        Packet<?> transformed = PacketFilter.transform(packet);
        if (transformed == null) {
            return;
        }

        synchronized (this) {
            if (!this.acceptingPackets || this.stopped) {
                return;
            }
            //noinspection unchecked
            this.out.writeGamePacket((Packet<? super ClientGamePacketListener>) transformed);
        }
    }

    /** The viewer is being sent back into the configuration phase, which can't be represented. */
    protected void onConfigurationPhase() {
        synchronized (this) {
            this.acceptingPackets = false;
        }
    }

    /** Writes a packet generated on the main thread in order with the captured packets. */
    public final void writeSynthetic(Packet<? super ClientGamePacketListener> packet) {
        this.runSequenced(() -> {
            synchronized (this) {
                if (this.acceptingPackets && !this.stopped) {
                    this.out.writeGamePacket(packet);
                }
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Main thread

    /** Must be called on the main thread at the end of every server tick. */
    public final void tick() {
        if (this.stopRequested) {
            return;
        }
        ServerPlayer viewer = this.viewer();
        if (viewer == null || viewer.isRemoved() || viewer.hasDisconnected()) {
            this.logger.info("Viewer of recording '{}' is gone, stopping", this.name);
            this.stop();
            return;
        }

        ResourceKey<Level> dimension = viewer.level().dimension();
        boolean changedDimension = this.lastDimension != null && this.lastDimension != dimension;
        this.lastDimension = dimension;

        Snapshot initialSnapshot = null;
        if (this.needsInitialSnapshot) {
            this.needsInitialSnapshot = false;
            initialSnapshot = this.buildSnapshot(viewer);
        }

        List<Packet<? super ClientGamePacketListener>> tickPackets = new ArrayList<>();
        this.collectTickPackets(viewer, tickPackets);
        List<IdAndPosition> positions = this.collectPositions(viewer);

        this.mainTicksInSegment += 1;
        this.mainTotalTicks += 1;

        Snapshot nextSegmentSnapshot = null;
        if (this.mainTicksInSegment >= this.segmentLengthTicks || changedDimension) {
            this.mainTicksInSegment = 0;
            nextSegmentSnapshot = this.buildSnapshot(viewer);
        }

        Snapshot finalInitialSnapshot = initialSnapshot;
        Snapshot finalNextSegmentSnapshot = nextSegmentSnapshot;
        boolean markDimensionChange = changedDimension;
        String dimensionName = dimension.identifier().toString();
        this.runSequenced(() -> {
            synchronized (this) {
                if (this.stopped) {
                    return;
                }
                if (finalInitialSnapshot != null) {
                    this.writeSnapshot(finalInitialSnapshot);
                    this.acceptingPackets = true;
                }
                if (!this.acceptingPackets) {
                    // Viewer is in the configuration phase, nothing sensible can be recorded
                    return;
                }
                this.writeRealTimeClock();
                if (!tickPackets.isEmpty()) {
                    this.out.writeGamePackets(tickPackets);
                }
                if (!positions.isEmpty()) {
                    this.writePositions(positions, dimension);
                }
                this.out.submit(writer -> writer.writeEmptyAction(FlashbackFormat.ACTION_NEXT_TICK));
                this.ticksInSegment += 1;
                this.totalTicks += 1;

                if (finalNextSegmentSnapshot != null) {
                    if (markDimensionChange) {
                        this.metadata.markers.put(this.totalTicks, ReplayMarker.of(0xAA00AA, "Changed dimension to " + dimensionName));
                    }
                    this.flushSegment();
                    this.writeSnapshot(finalNextSegmentSnapshot);
                }
            }
        });
    }

    private Snapshot buildSnapshot(ServerPlayer viewer) {
        // Positions are part of the snapshot, so everything needs to be re-sent afterwards
        this.lastPositions.clear();
        return SnapshotBuilder.build(viewer, this.localGameType(), this.hudSource(), this.isCamera());
    }

    private List<IdAndPosition> collectPositions(ServerPlayer viewer) {
        ServerLevel level = viewer.level();
        ChunkMap chunkMap = level.getChunkSource().chunkMap;
        List<IdAndPosition> changed = new ArrayList<>();
        IntSet seen = new IntOpenHashSet();

        for (Int2ObjectMap.Entry<ChunkMap.TrackedEntity> entry : chunkMap.entityMap.int2ObjectEntrySet()) {
            int id = entry.getIntKey();
            boolean self = id == viewer.getId();
            if (self) {
                if (this.isCamera()) {
                    continue;
                }
            } else if (!entry.getValue().seenBy.contains(viewer.connection)) {
                continue;
            }

            Entity entity = level.getEntity(id);
            if (entity == null || entity.isRemoved() || entity instanceof EnderDragonPart) {
                continue;
            }

            Vec3 pos = entity.trackingPosition();
            Position position = new Position(pos.x, pos.y, pos.z, entity.getYRot(), entity.getXRot(), entity.getYHeadRot(), entity.onGround());
            seen.add(id);
            Position last = this.lastPositions.put(id, position);
            if (!position.equals(last)) {
                changed.add(new IdAndPosition(id, position));
            }
        }

        this.lastPositions.keySet().retainAll(seen);
        return changed;
    }

    /** Adds a marker at the current tick. Main thread only. */
    public final void addMarker(ReplayMarker marker) {
        this.runSequenced(() -> {
            synchronized (this) {
                if (!this.stopped) {
                    this.metadata.markers.put(this.totalTicks, marker);
                }
            }
        });
    }

    /**
     * Stops the recording and exports the replay zip in the background. Main thread only.
     *
     * @return a future completed with the path of the exported zip
     */
    public final CompletableFuture<Path> stop() {
        if (this.result != null) {
            return this.result;
        }
        this.stopRequested = true;
        CompletableFuture<Path> future = new CompletableFuture<>();
        this.result = future;

        this.runSequenced(() -> {
            synchronized (this) {
                if (this.stopped) {
                    return;
                }
                this.stopped = true;
                if (this.totalTicks == 0 && !this.acceptingPackets) {
                    this.out.abort();
                    future.completeExceptionally(new IllegalStateException("Recording '" + this.name + "' didn't record anything"));
                    return;
                }
                if (this.ticksInSegment == 0) {
                    // Avoid empty replay chunks, Flashback doesn't like them
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
        });

        try {
            this.cleanup();
        } catch (Exception e) {
            this.logger.error("Error while cleaning up recording {}", this.name, e);
        }
        return future;
    }

    /** Stops the recording and deletes everything that was recorded. Main thread only. */
    public final void cancel() {
        if (this.result != null) {
            return;
        }
        this.stopRequested = true;
        this.result = CompletableFuture.failedFuture(new IllegalStateException("Recording '" + this.name + "' was cancelled"));
        this.runSequenced(() -> {
            synchronized (this) {
                this.stopped = true;
                this.out.abort();
            }
        });
        try {
            this.cleanup();
        } catch (Exception e) {
            this.logger.error("Error while cleaning up recording {}", this.name, e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Sequenced side (always called while holding the lock)

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
