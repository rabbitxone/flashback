package com.moulberry.flashback.serverrecorder.recording;

/** A square area of chunks that a world recording is limited to. */
public record Region(int centerChunkX, int centerChunkZ, int radiusChunks) {

    public static Region around(double blockX, double blockZ, int radiusBlocks) {
        return new Region((int) Math.floor(blockX) >> 4, (int) Math.floor(blockZ) >> 4, Math.max(0, (radiusBlocks + 15) >> 4));
    }

    public boolean containsChunk(int chunkX, int chunkZ) {
        return Math.abs(chunkX - this.centerChunkX) <= this.radiusChunks && Math.abs(chunkZ - this.centerChunkZ) <= this.radiusChunks;
    }

}
