package com.moulberry.flashback.serverrecorder.camera;

import com.mojang.authlib.GameProfile;
import com.moulberry.flashback.serverrecorder.net.PacketCapture;
import com.moulberry.flashback.serverrecorder.recording.PacketFilter;
import com.moulberry.flashback.serverrecorder.recording.Recording;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Records from a fixed point in the world using an invisible "camera" player that only exists on the server.
 * <p>
 * The camera is a real {@link ServerPlayer} in spectator mode that is added to the world (so the server
 * sends it chunks, entities, block changes, sounds, particles...) but never to the player list, so other
 * players and plugins using {@code Bukkit.getOnlinePlayers()} don't see it. Packets sent to it are
 * recorded instead of being sent over the network.
 * <p>
 * Things that are only broadcast to the player list (tab list, chat, scoreboards...) are either generated
 * here (player list entries, time) or copied from an optional "HUD source" player.
 */
public final class CameraRecording extends Recording {

    private static final GameType REPLAY_GAME_TYPE = GameType.ADVENTURE;

    private final ServerPlayer camera;
    private final CameraConnection connection;
    private @Nullable ServerPlayer hudSource;
    private final Consumer<Packet<?>> hudListener = this::onHudPacket;

    // Sounds & level events are broadcast through the player list, which the camera isn't part of.
    // We copy them from real players in the same world instead ("sound bridge").
    private final Consumer<Packet<?>> soundListener = this::onBridgedPacket;
    private final java.util.Set<ServerPlayer> bridgedPlayers = new java.util.HashSet<>();
    private final Packet<?>[] recentBridged = new Packet<?>[256];
    private int recentBridgedIndex = 0;

    // Player list state as seen by the replay, main thread only
    private final Map<UUID, GameType> knownGameModes = new HashMap<>();
    private final Map<UUID, @Nullable Component> knownDisplayNames = new HashMap<>();
    private int ticks = 0;

    public CameraRecording(Logger logger, String name, Path tempFolder, Path outputZip, int segmentLengthTicks,
                           ServerLevel level, double x, double y, double z, float yaw, float pitch, int viewDistance,
                           @Nullable ServerPlayer hudSource) throws IOException {
        super(logger, name, tempFolder, outputZip, segmentLengthTicks, level);
        MinecraftServer server = level.getServer();

        String cameraName = "fb-cam-" + Integer.toHexString(new java.util.Random().nextInt(0x10000));
        GameProfile profile = new GameProfile(UUID.randomUUID(), cameraName);
        ClientInformation info = new ClientInformation("en_us", viewDistance, ChatVisiblity.HIDDEN, false, 0,
            HumanoidArm.RIGHT, false, false, ParticleStatus.ALL);

        this.camera = new ServerPlayer(server, level, profile, info);
        this.connection = new CameraConnection(this::onPacket);
        new ServerGamePacketListenerImpl(server, this.connection, this.camera, CommonListenerCookie.createInitial(profile, false));

        this.camera.isRealPlayer = true; // Paper only loads/sends chunks for "real" players
        this.camera.visibleByDefault = false; // Never show the camera to anyone
        this.camera.setInvisible(true);
        this.camera.setSilent(true);
        this.camera.setNoGravity(true);
        setGameModeSilently(this.camera, GameType.SPECTATOR);
        this.camera.snapTo(x, y, z, yaw, pitch);
        this.camera.setYHeadRot(yaw);

        level.addNewPlayer(this.camera);
        this.camera.getBukkitEntity().setViewDistance(viewDistance);
        this.camera.getBukkitEntity().setSendViewDistance(viewDistance);

        this.setHudSource(hudSource);
    }

    /** Sets the game mode without firing Bukkit events or broadcasting it to the tab list. */
    private static void setGameModeSilently(ServerPlayer player, GameType gameType) {
        try {
            Method method = ServerPlayerGameMode.class.getDeclaredMethod("setGameModeForPlayer", GameType.class, GameType.class);
            method.setAccessible(true);
            method.invoke(player.gameMode, gameType, null);
        } catch (ReflectiveOperationException e) {
            player.setGameMode(gameType);
        }
    }

    public ServerPlayer camera() {
        return this.camera;
    }

    /** Moves the camera inside its current world. Main thread only. */
    public void moveTo(double x, double y, double z, float yaw, float pitch) {
        this.camera.snapTo(x, y, z, yaw, pitch);
        this.camera.setYHeadRot(yaw);
        this.camera.level().getChunkSource().move(this.camera);
    }

    /** Copies scoreboard, tab list, boss bars, titles and chat of this player into the replay. Main thread only. */
    public void setHudSource(@Nullable ServerPlayer player) {
        if (this.hudSource != null) {
            PacketCapture.removeListener(this.hudSource, this.hudListener);
        }
        this.hudSource = null;
        if (player != null && PacketCapture.addListener(this.logger, player, this.hudListener)) {
            this.hudSource = player;
        }
    }

    @Override
    public ServerPlayer viewer() {
        return this.camera;
    }

    @Override
    protected void runSequenced(Runnable runnable) {
        // Packets to the camera are captured synchronously, so running directly keeps the order
        runnable.run();
    }

    @Override
    protected GameType localGameType() {
        return REPLAY_GAME_TYPE;
    }

    @Override
    public @Nullable ServerPlayer hudSource() {
        return this.hudSource;
    }

    @Override
    protected boolean isCamera() {
        return true;
    }

    @Override
    protected boolean shouldRecord(Packet<?> packet) {
        int id = this.camera.getId();
        // The camera is in spectator mode on the server, but should be an invisible normal player in the replay
        if (packet instanceof ClientboundSetEntityDataPacket p) return p.id() != id;
        if (packet instanceof ClientboundUpdateAttributesPacket p) return p.getEntityId() != id;
        if (packet instanceof ClientboundSetEntityMotionPacket p) return p.id() != id;
        if (packet instanceof ClientboundUpdateMobEffectPacket p) return p.getEntityId() != id;
        if (packet instanceof ClientboundRemoveMobEffectPacket p) return !(p.getEntity(this.camera.level()) instanceof ServerPlayer sp && sp == this.camera);
        if (packet instanceof ClientboundGameEventPacket p) return p.getEvent() != ClientboundGameEventPacket.CHANGE_GAME_MODE;
        if (packet instanceof ClientboundSetTimePacket) return false; // written by us, see collectTickPackets
        // HUD packets come from the HUD source instead
        return !PacketFilter.isHudPacket(packet);
    }

    private void onHudPacket(Packet<?> packet) {
        if (packet instanceof ClientboundBundlePacket bundle) {
            for (Packet<?> subPacket : bundle.subPackets()) {
                this.onHudPacket(subPacket);
            }
            return;
        }
        if (!PacketFilter.isHudPacket(packet)) {
            return;
        }
        Packet<?> transformed = PacketFilter.transform(packet);
        if (transformed != null) {
            //noinspection unchecked
            this.writeSynthetic((Packet<? super ClientGamePacketListener>) transformed);
        }
    }

    private void onBridgedPacket(Packet<?> packet) {
        if (packet instanceof ClientboundBundlePacket bundle) {
            for (Packet<?> subPacket : bundle.subPackets()) {
                this.onBridgedPacket(subPacket);
            }
            return;
        }
        double x, y, z;
        if (packet instanceof ClientboundSoundPacket sound) {
            x = sound.getX();
            y = sound.getY();
            z = sound.getZ();
        } else if (packet instanceof ClientboundLevelEventPacket event && !event.isGlobalEvent()) {
            x = event.getPos().getX();
            y = event.getPos().getY();
            z = event.getPos().getZ();
        } else if (packet instanceof ClientboundSoundEntityPacket) {
            x = Double.NaN;
            y = 0;
            z = 0;
        } else {
            return;
        }

        if (!Double.isNaN(x)) {
            double range = this.camera.getBukkitEntity().getViewDistance() * 16 + 32;
            if (this.camera.distanceToSqr(x, y, z) > range * range) {
                return;
            }
        }

        // The same packet instance is sent to every nearby player, only record it once
        synchronized (this.recentBridged) {
            for (Packet<?> recent : this.recentBridged) {
                if (recent == packet) {
                    return;
                }
            }
            this.recentBridged[this.recentBridgedIndex] = packet;
            this.recentBridgedIndex = (this.recentBridgedIndex + 1) % this.recentBridged.length;
        }
        //noinspection unchecked
        this.writeSynthetic((Packet<? super ClientGamePacketListener>) packet);
    }

    private void updateSoundBridge() {
        ServerLevel level = this.camera.level();
        this.bridgedPlayers.removeIf(player -> {
            if (player.hasDisconnected() || player.level() != level) {
                PacketCapture.removeListener(player, this.soundListener);
                return true;
            }
            return false;
        });
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player.level() == level && !this.bridgedPlayers.contains(player)
                    && PacketCapture.addListener(this.logger, player, this.soundListener)) {
                this.bridgedPlayers.add(player);
            }
        }
    }

    /** Called from the plugin's join listener so the tab list entry is written before the player's entity. */
    public void onPlayerJoin(ServerPlayer player) {
        if (this.isStopped()) {
            return;
        }
        this.knownGameModes.put(player.getUUID(), player.gameMode());
        this.knownDisplayNames.put(player.getUUID(), player.getTabListDisplayName());
        this.writeSynthetic(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player)));
    }

    @Override
    protected void collectTickPackets(ServerPlayer viewer, List<Packet<? super ClientGamePacketListener>> packets) {
        ServerLevel level = viewer.level();
        this.updateSoundBridge();

        // Time isn't sent to the camera because it's not in the player list
        if (this.ticks++ % 20 == 0) {
            packets.add(level.clockManager().createFullSyncPacket());
        }

        // Keep the replay's player list in sync with the server
        List<ServerPlayer> joined = new ArrayList<>();
        List<ClientboundPlayerInfoUpdatePacket.Entry> gameModeChanges = new ArrayList<>();
        List<ClientboundPlayerInfoUpdatePacket.Entry> displayNameChanges = new ArrayList<>();
        Map<UUID, ServerPlayer> online = new HashMap<>();
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            online.put(uuid, player);
            if (!this.knownGameModes.containsKey(uuid)) {
                joined.add(player);
                this.knownGameModes.put(uuid, player.gameMode());
                this.knownDisplayNames.put(uuid, player.getTabListDisplayName());
                continue;
            }
            if (this.knownGameModes.get(uuid) != player.gameMode()) {
                this.knownGameModes.put(uuid, player.gameMode());
                gameModeChanges.add(entry(player));
            }
            Component displayName = player.getTabListDisplayName();
            if (!Objects.equals(this.knownDisplayNames.get(uuid), displayName)) {
                this.knownDisplayNames.put(uuid, displayName);
                displayNameChanges.add(entry(player));
            }
        }
        List<UUID> left = new ArrayList<>();
        for (UUID uuid : List.copyOf(this.knownGameModes.keySet())) {
            if (!online.containsKey(uuid)) {
                left.add(uuid);
                this.knownGameModes.remove(uuid);
                this.knownDisplayNames.remove(uuid);
            }
        }

        if (!joined.isEmpty()) {
            packets.add(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(joined));
        }
        if (!gameModeChanges.isEmpty()) {
            packets.add(new ClientboundPlayerInfoUpdatePacket(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE), gameModeChanges));
        }
        if (!displayNameChanges.isEmpty()) {
            packets.add(new ClientboundPlayerInfoUpdatePacket(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME), displayNameChanges));
        }
        if (!left.isEmpty()) {
            packets.add(new ClientboundPlayerInfoRemovePacket(left));
        }
    }

    private static ClientboundPlayerInfoUpdatePacket.Entry entry(ServerPlayer player) {
        return new ClientboundPlayerInfoUpdatePacket.Entry(player.getUUID(), player.getGameProfile(), true, player.connection.latency(),
            player.gameMode(), player.getTabListDisplayName(), true, player.getTabListOrder(), null);
    }

    @Override
    protected void cleanup() {
        this.setHudSource(null);
        for (ServerPlayer player : this.bridgedPlayers) {
            PacketCapture.removeListener(player, this.soundListener);
        }
        this.bridgedPlayers.clear();
        this.connection.close();
        if (!this.camera.isRemoved()) {
            this.camera.remove(Entity.RemovalReason.DISCARDED, org.bukkit.event.entity.EntityRemoveEvent.Cause.PLUGIN);
        }
    }

}
