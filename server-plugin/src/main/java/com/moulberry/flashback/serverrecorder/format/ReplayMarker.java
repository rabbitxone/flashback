package com.moulberry.flashback.serverrecorder.format;

import org.jspecify.annotations.Nullable;

/**
 * A marker shown on the Flashback timeline.
 *
 * @param colour      RGB colour, e.g. 0xFF5555
 * @param description text shown when hovering the marker, may be null
 * @param x           optional position (only used when {@code dimension} is non-null)
 * @param dimension   dimension id (e.g. "minecraft:overworld") or null for no position
 */
public record ReplayMarker(int colour, @Nullable String description, double x, double y, double z, @Nullable String dimension) {

    public static ReplayMarker of(int colour, @Nullable String description) {
        return new ReplayMarker(colour, description, 0, 0, 0, null);
    }

}
