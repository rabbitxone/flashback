package com.moulberry.flashback.serverrecorder.api;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * Public API for other plugins (e.g. a minigame) to control recordings.
 * <pre>{@code
 * FlashbackRecorderApi api = FlashbackRecorderApi.get();
 * RecordingHandle rec = api.recordArea("duel-" + gameId, arenaCenter, 64);
 * ...
 * rec.marker("Red bed destroyed", 0xFF5555);
 * ...
 * rec.stop().thenAccept(zip -> getLogger().info("Replay saved to " + zip));
 * }</pre>
 * All methods must be called on the main server thread.
 */
public interface FlashbackRecorderApi {

    static FlashbackRecorderApi get() {
        return com.moulberry.flashback.serverrecorder.FlashbackServerRecorder.instance();
    }

    /**
     * Records a whole world: terrain around all players, every entity and every player.
     * Best for worlds that only contain one game (e.g. a world per arena).
     */
    RecordingHandle recordWorld(String name, World world) throws IOException;

    /**
     * Records a square area of {@code radiusBlocks} around {@code center}: all terrain in it (as long as it's
     * loaded), every entity and every player inside it.
     */
    RecordingHandle recordArea(String name, Location center, int radiusBlocks) throws IOException;

    @Nullable RecordingHandle getRecording(String name);

    Collection<? extends RecordingHandle> getRecordings();

    interface RecordingHandle {
        String name();

        /** Number of ticks recorded so far. */
        int recordedTicks();

        /** Adds a marker to the replay timeline at the current tick. */
        void marker(@Nullable String description, int rgbColour);

        /** Adds a marker with a position (Flashback can jump the camera to it). */
        void marker(@Nullable String description, int rgbColour, Location location);

        /**
         * Sets whose scoreboard, tab list, boss bars, titles and chat end up in the replay.
         * Null picks a player in the recorded world automatically (the default).
         */
        void setHudPlayer(@Nullable Player player);

        /** Stops the recording. The future completes with the exported zip once it's written. */
        CompletableFuture<Path> stop();

        /** Discards the recording without saving anything. */
        void cancel();
    }

}
