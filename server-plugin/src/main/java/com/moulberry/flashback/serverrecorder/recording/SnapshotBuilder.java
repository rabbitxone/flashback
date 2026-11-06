package com.moulberry.flashback.serverrecorder.recording;

import ca.spottedleaf.moonrise.patches.chunk_system.player.ChunkSystemServerPlayer;
import io.papermc.paper.adventure.PaperAdventure;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.network.protocol.configuration.ClientboundUpdateEnabledFeaturesPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.bossevents.CustomBossEvent;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.bukkit.craftbukkit.scoreboard.CraftScoreboard;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Builds a {@link Snapshot} from the live server state, i.e. what the viewer's client would currently know.
 * Server-side equivalent of {@code Recorder#writeSnapshot} in the Flashback mod. Must run on the main thread.
 */
final class SnapshotBuilder {

    private SnapshotBuilder() {
    }

    /**
     * @param viewer      the player (or camera) whose view is being recorded
     * @param localGameType the game mode to give the replay's local player
     * @param hudSource   whose scoreboard/tab list/boss bars to use, may be the viewer itself or null
     * @param isCamera    whether the viewer is a recorder camera (doesn't exist in the player list)
     */
    static Snapshot build(ServerPlayer viewer, GameType localGameType, @Nullable ServerPlayer hudSource, boolean isCamera) {
        MinecraftServer server = viewer.level().getServer();
        ServerLevel level = viewer.level();

        // --- Configuration phase: enabled features, registries and tags. We pretend the client
        // knows no data packs so that the replay is fully self-contained.
        List<Packet<? super ClientConfigurationPacketListener>> config = new ArrayList<>();
        config.add(new ClientboundUpdateEnabledFeaturesPacket(FeatureFlags.REGISTRY.toNames(server.getWorldData().enabledFeatures())));
        var ops = server.registries().compositeAccess().createSerializationContext(NbtOps.INSTANCE);
        RegistrySynchronization.packRegistries((com.mojang.serialization.DynamicOps<Tag>) ops,
            server.registries().getAccessFrom(net.minecraft.server.RegistryLayer.WORLD), Set.of(),
            (key, entries) -> config.add(new ClientboundRegistryDataPacket(key, entries)));
        config.add(new ClientboundUpdateTagsPacket(TagNetworkSerialization.serializeTagsToNetwork(server.registries())));

        // --- Login
        CommonPlayerSpawnInfo spawnInfo = viewer.createCommonSpawnInfo(level);
        spawnInfo = new CommonPlayerSpawnInfo(spawnInfo.dimensionType(), spawnInfo.dimension(), spawnInfo.seed(), localGameType,
            spawnInfo.previousGameType(), spawnInfo.isDebug(), spawnInfo.isFlat(), spawnInfo.lastDeathLocation(),
            spawnInfo.portalCooldown(), spawnInfo.seaLevel());
        int viewDistance = Math.max(2, viewer.getBukkitEntity().getSendViewDistance());
        ClientboundLoginPacket login = new ClientboundLoginPacket(viewer.getId(), level.getLevelData().isHardcore(), server.levelKeys(),
            server.getPlayerList().getMaxPlayers(), viewDistance, viewDistance, false, true, false, spawnInfo, false, false);

        List<Packet<? super ClientGamePacketListener>> loginPackets = List.of(login);
        LocalPlayerData localPlayer = LocalPlayerData.of(viewer, localGameType);

        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();

        // --- Player info (tab list entries + skins)
        packets.add(createPlayerInfo(server.getPlayerList(), viewer, localGameType, isCamera));

        // --- HUD: tab list header/footer, boss bars, scoreboard
        if (hudSource != null) {
            var bukkit = hudSource.getBukkitEntity();
            net.kyori.adventure.text.Component header = bukkit.playerListHeader();
            net.kyori.adventure.text.Component footer = bukkit.playerListFooter();
            if (header != null || footer != null) {
                packets.add(new ClientboundTabListPacket(
                    header == null ? Component.empty() : PaperAdventure.asVanilla(header),
                    footer == null ? Component.empty() : PaperAdventure.asVanilla(footer)));
            }
            for (CustomBossEvent event : server.getCustomBossEvents().getEvents()) {
                if (event.getPlayers().contains(hudSource)) {
                    packets.add(ClientboundBossEventPacket.createAddPacket(event));
                }
            }
            Scoreboard scoreboard = ((CraftScoreboard) bukkit.getScoreboard()).getHandle();
            addScoreboard(scoreboard, packets);
        } else {
            // Without a HUD source we still want team colours/name tag visibility
            for (PlayerTeam team : server.getScoreboard().getPlayerTeams()) {
                packets.add(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true));
            }
        }

        // --- Level info
        packets.add(new ClientboundInitializeBorderPacket(level.getWorldBorder()));
        packets.add(level.clockManager().createFullSyncPacket(isCamera ? null : viewer));
        packets.add(new ClientboundSetDefaultSpawnPositionPacket(level.getRespawnData()));
        packets.add(new ClientboundChangeDifficultyPacket(level.getDifficulty(), level.getLevelData().isDifficultyLocked()));
        if (level.isRaining()) {
            packets.add(new ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 0.0f));
        } else {
            packets.add(new ClientboundGameEventPacket(ClientboundGameEventPacket.STOP_RAINING, 0.0f));
        }
        packets.add(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, level.getRainLevel(1.0f)));
        packets.add(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, level.getThunderLevel(1.0f)));

        // --- Chunks the viewer currently has loaded, closest first
        addChunks(viewer, level, packets);

        // --- Entities the viewer is currently tracking. ServerEntity#sendPairingData produces exactly the
        // packets the server would send to a player that just started tracking the entity.
        ChunkMap chunkMap = level.getChunkSource().chunkMap;
        for (ChunkMap.TrackedEntity tracked : chunkMap.entityMap.values()) {
            if (tracked.seenBy.contains(viewer.connection)) {
                tracked.serverEntity.sendPairingData(viewer, packets::add);
            }
        }

        // --- The viewer itself (only for real players, the camera is invisible)
        if (!isCamera) {
            ChunkMap.TrackedEntity self = chunkMap.entityMap.get(viewer.getId());
            if (self != null) {
                List<Packet<? super ClientGamePacketListener>> selfPackets = new ArrayList<>();
                self.serverEntity.sendPairingData(viewer, selfPackets::add);
                // The local player is spawned by create_local_player instead
                selfPackets.removeIf(packet -> packet instanceof ClientboundAddEntityPacket);
                packets.addAll(selfPackets);
            }

        } else {
            // Make the camera invisible in the replay
            packets.add(new ClientboundSetEntityDataPacket(viewer.getId(), viewer.getEntityData().packAll()));
        }

        return new Snapshot(config, loginPackets, localPlayer, packets);
    }

    private static ClientboundPlayerInfoUpdatePacket createPlayerInfo(PlayerList playerList, ServerPlayer viewer, GameType localGameType, boolean isCamera) {
        List<ServerPlayer> visible = new ArrayList<>();
        for (ServerPlayer player : playerList.getPlayers()) {
            if (player == viewer || isCamera || viewer.getBukkitEntity().canSee(player.getBukkitEntity())) {
                visible.add(player);
            }
        }
        ClientboundPlayerInfoUpdatePacket base = ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(visible);
        List<ClientboundPlayerInfoUpdatePacket.Entry> entries = new ArrayList<>(base.entries());
        if (isCamera) {
            entries.add(new ClientboundPlayerInfoUpdatePacket.Entry(viewer.getUUID(), viewer.getGameProfile(), false, 0,
                localGameType, null, true, 0, null));
        }
        return new ClientboundPlayerInfoUpdatePacket(EnumSet.copyOf(base.actions()), entries);
    }

    private static void addScoreboard(Scoreboard scoreboard, List<Packet<? super ClientGamePacketListener>> packets) {
        for (PlayerTeam team : scoreboard.getPlayerTeams()) {
            packets.add(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true));
        }
        Set<Objective> handled = new HashSet<>();
        for (DisplaySlot slot : DisplaySlot.values()) {
            Objective objective = scoreboard.getDisplayObjective(slot);
            if (objective == null || !handled.add(objective)) {
                continue;
            }
            packets.add(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_ADD));
            for (DisplaySlot other : DisplaySlot.values()) {
                if (scoreboard.getDisplayObjective(other) == objective) {
                    packets.add(new ClientboundSetDisplayObjectivePacket(other, objective));
                }
            }
            for (PlayerScoreEntry score : scoreboard.listPlayerScores(objective)) {
                packets.add(new ClientboundSetScorePacket(score.owner(), objective.getName(), score.value(),
                    Optional.ofNullable(score.display()), Optional.ofNullable(score.numberFormatOverride())));
            }
        }
    }

    private static void addChunks(ServerPlayer viewer, ServerLevel level, List<Packet<? super ClientGamePacketListener>> packets) {
        var loader = ((ChunkSystemServerPlayer) viewer).moonrise$getChunkLoader();
        if (loader == null) {
            return;
        }
        LongList positions = new LongArrayList(loader.getSentChunksRaw());
        int centerX = viewer.getBlockX() >> 4;
        int centerZ = viewer.getBlockZ() >> 4;
        positions.sort((a, b) -> Integer.compare(distanceSq(a, centerX, centerZ), distanceSq(b, centerX, centerZ)));

        for (long pos : positions) {
            int x = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkX(pos);
            int z = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkZ(pos);
            LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
            if (chunk != null) {
                packets.add(new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null, false));
            }
        }
    }

    private static int distanceSq(long pos, int centerX, int centerZ) {
        int dx = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkX(pos) - centerX;
        int dz = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkZ(pos) - centerZ;
        return dx * dx + dz * dz;
    }

}
