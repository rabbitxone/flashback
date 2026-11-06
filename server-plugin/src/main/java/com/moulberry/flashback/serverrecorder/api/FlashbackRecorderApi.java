package com.moulberry.flashback.serverrecorder.api;

import org.bukkit.Location;
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
 * RecordingHandle rec = api.recordCamera("bedwars-" + gameId, arenaCenter, 8, null);
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

    /** Records what a single player sees, like the Flashback mod would on their client. */
    RecordingHandle recordPlayer(String name, Player player) throws IOException;

    /**
     * Records from an invisible camera placed at {@code location}. Everything within {@code viewDistance}
     * chunks is recorded, no matter where players are.
     *
     * @param hudSource optional player whose scoreboard, tab list, boss bars, titles and chat are copied
     */
    RecordingHandle recordCamera(String name, Location location, int viewDistance, @Nullable Player hudSource) throws IOException;

    @Nullable RecordingHandle getRecording(String name);

    Collection<? extends RecordingHandle> getRecordings();

    /** @return true if the player is a recording camera (cameras aren't in the online player list, but are in their world) */
    boolean isCamera(org.bukkit.entity.Entity entity);

    interface RecordingHandle {
        String name();

        /** Number of ticks recorded so far. */
        int recordedTicks();

        /** Adds a marker to the replay timeline at the current tick. */
        void marker(@Nullable String description, int rgbColour);

        /** Adds a marker with a position (Flashback can jump the camera to it). */
        void marker(@Nullable String description, int rgbColour, Location location);

        /** Stops the recording. The future completes with the exported zip once it's written. */
        CompletableFuture<Path> stop();

        /** Discards the recording without saving anything. */
        void cancel();
    }

}
