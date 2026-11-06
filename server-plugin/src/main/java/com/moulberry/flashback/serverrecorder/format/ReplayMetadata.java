package com.moulberry.flashback.serverrecorder.format;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The metadata.json of a Flashback replay. Mirrors {@code com.moulberry.flashback.record.FlashbackMeta}.
 */
public final class ReplayMetadata {

    public final UUID replayIdentifier = UUID.randomUUID();
    public String name = "Unnamed";
    public String versionString;
    public String worldName;
    public int dataVersion;
    public int protocolVersion;
    public int totalTicks = -1;
    public final LinkedHashMap<String, Integer> chunkDurations = new LinkedHashMap<>();
    public final TreeMap<Integer, ReplayMarker> markers = new TreeMap<>();

    public JsonObject toJson() {
        JsonObject meta = new JsonObject();
        meta.addProperty("uuid", this.replayIdentifier.toString());
        meta.addProperty("name", this.name);
        if (this.versionString != null) {
            meta.addProperty("version_string", this.versionString);
        }
        if (this.worldName != null) {
            meta.addProperty("world_name", this.worldName);
        }
        if (this.dataVersion != 0) {
            meta.addProperty("data_version", this.dataVersion);
        }
        if (this.protocolVersion != 0) {
            meta.addProperty("protocol_version", this.protocolVersion);
        }
        if (this.totalTicks > 0) {
            meta.addProperty("total_ticks", this.totalTicks);
        }

        if (!this.markers.isEmpty()) {
            JsonObject markersJson = new JsonObject();
            for (Map.Entry<Integer, ReplayMarker> entry : this.markers.entrySet()) {
                ReplayMarker marker = entry.getValue();
                JsonObject markerJson = new JsonObject();
                markerJson.addProperty("colour", marker.colour());
                if (marker.dimension() != null) {
                    JsonObject position = new JsonObject();
                    JsonArray xyz = new JsonArray();
                    xyz.add((float) marker.x());
                    xyz.add((float) marker.y());
                    xyz.add((float) marker.z());
                    position.add("position", xyz);
                    position.addProperty("dimension", marker.dimension());
                    markerJson.add("position", position);
                }
                if (marker.description() != null) {
                    markerJson.addProperty("description", marker.description());
                }
                markersJson.add(String.valueOf(entry.getKey()), markerJson);
            }
            meta.add("markers", markersJson);
        }

        JsonObject chunks = new JsonObject();
        for (Map.Entry<String, Integer> entry : this.chunkDurations.entrySet()) {
            JsonObject chunk = new JsonObject();
            chunk.addProperty("duration", entry.getValue());
            chunk.addProperty("forcePlaySnapshot", false);
            chunks.add(entry.getKey(), chunk);
        }
        meta.add("chunks", chunks);
        return meta;
    }

}
