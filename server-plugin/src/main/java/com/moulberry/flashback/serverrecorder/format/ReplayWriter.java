package com.moulberry.flashback.serverrecorder.format;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;

/**
 * Writes a single Flashback replay chunk file (cN.flashback) into memory.
 * Port of {@code com.moulberry.flashback.io.ReplayWriter} without any client dependencies.
 * <p>
 * Layout: MAGIC, action table, [int snapshotSize, snapshot actions...], actions...
 * Every action is: varint actionId, int size, payload.
 */
public final class ReplayWriter {

    private final ByteBuf inner = Unpooled.buffer(1 << 20);
    private final RegistryFriendlyByteBuf buf;
    private final Object2IntMap<Identifier> actionIds = new Object2IntOpenHashMap<>();

    private Identifier writingAction = null;
    private int actionSizeIndex = -1;
    private int snapshotSizeIndex = -1;

    private enum State { EMPTY, SNAPSHOT, DATA }
    private State state = State.EMPTY;

    public ReplayWriter(RegistryAccess registryAccess) {
        this.buf = new RegistryFriendlyByteBuf(this.inner, registryAccess);
        this.actionIds.defaultReturnValue(-1);
        this.writeHeader();
    }

    private void writeHeader() {
        this.buf.clear();
        this.actionIds.clear();
        this.buf.writeInt(FlashbackFormat.MAGIC);
        this.buf.writeVarInt(FlashbackFormat.ACTIONS.size());
        for (Identifier action : FlashbackFormat.ACTIONS) {
            this.buf.writeIdentifier(action);
            this.actionIds.put(action, this.actionIds.size());
        }
        this.state = State.EMPTY;
    }

    public boolean isEmpty() {
        return this.state == State.EMPTY;
    }

    public void startSnapshot() {
        if (this.state != State.EMPTY) {
            throw new IllegalStateException("Can only start snapshot in an empty chunk");
        }
        this.state = State.SNAPSHOT;
        this.snapshotSizeIndex = this.buf.writerIndex();
        this.buf.writeInt(0xDEADBEEF);
    }

    public void endSnapshot() {
        if (this.state != State.SNAPSHOT) {
            throw new IllegalStateException("Can only end snapshot while writing snapshot");
        }
        this.state = State.DATA;
        int end = this.buf.writerIndex();
        this.buf.setInt(this.snapshotSizeIndex, end - this.snapshotSizeIndex - 4);
        this.snapshotSizeIndex = -1;
    }

    public RegistryFriendlyByteBuf startAction(Identifier action) {
        if (this.state == State.EMPTY) {
            throw new IllegalStateException("Cannot write action before snapshot was started");
        }
        if (this.writingAction != null) {
            throw new IllegalStateException("startAction(" + action + ") called while still writing " + this.writingAction);
        }
        int id = this.actionIds.getInt(action);
        if (id < 0) {
            throw new IllegalArgumentException("Unknown action " + action);
        }
        this.writingAction = action;
        this.buf.writeVarInt(id);
        this.actionSizeIndex = this.buf.writerIndex();
        this.buf.writeInt(0);
        return this.buf;
    }

    public void finishAction(Identifier action) {
        if (this.writingAction != action) {
            throw new IllegalStateException("finishAction(" + action + ") called but writing " + this.writingAction);
        }
        this.writingAction = null;
        int end = this.buf.writerIndex();
        this.buf.setInt(this.actionSizeIndex, end - this.actionSizeIndex - 4);
        this.actionSizeIndex = -1;
    }

    /** Aborts the action that is currently being written (e.g. because encoding a packet failed). */
    public void abortAction(int writerIndexBeforeAction) {
        this.writingAction = null;
        this.actionSizeIndex = -1;
        this.buf.writerIndex(writerIndexBeforeAction);
    }

    public int writerIndex() {
        return this.buf.writerIndex();
    }

    public void writeEmptyAction(Identifier action) {
        this.startAction(action);
        this.finishAction(action);
    }

    public byte[] popBytes() {
        if (this.writingAction != null) {
            throw new IllegalStateException("popBytes() called while still writing " + this.writingAction);
        }
        byte[] bytes = new byte[this.buf.writerIndex()];
        this.buf.getBytes(0, bytes);
        this.writeHeader();
        return bytes;
    }

    public RegistryAccess registryAccess() {
        return this.buf.registryAccess();
    }

}
