package com.moulberry.flashback.serverrecorder.recording;

import com.moulberry.flashback.serverrecorder.format.ReplayWriter;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.game.ClientGamePacketListener;

import java.util.List;
import java.util.function.Consumer;

/**
 * Everything needed to reconstruct the state of the recording at the start of a replay chunk.
 * Built on the main thread, written later by the replay writer thread.
 */
record Snapshot(
    List<Packet<? super ClientConfigurationPacketListener>> configurationPackets,
    List<Packet<? super ClientGamePacketListener>> loginPackets,
    LocalPlayerData localPlayer,
    List<Packet<? super ClientGamePacketListener>> gamePackets,
    List<Consumer<ReplayWriter>> actions
) {
}
