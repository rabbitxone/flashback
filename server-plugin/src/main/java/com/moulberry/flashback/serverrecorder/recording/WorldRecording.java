package com.moulberry.flashback.serverrecorder.recording;

import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.patches.chunk_system.player.ChunkSystemServerPlayer;
import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Pair;
import com.moulberry.flashback.serverrecorder.net.PacketCapture;
import io.papermc.paper.adventure.PaperAdventure;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
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
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.bossevents.CustomBossEvent;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.bukkit.craftbukkit.scoreboard.CraftScoreboard;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/**
 * Records a whole world (optionally limited to a {@link Region}): terrain, every entity and every player,
 * independent of any point of view. In Flashback you can then fly around freely.
 * <p>
 * Sources of data:
 * <ul>
 *     <li>Server state, every tick: entity spawns/removals, positions of all entities, the player list,
 *     player equipment/metadata/arm swings, time and weather.</li>
 *     <li>Packets sent to the players in the world: block changes, sounds, particles, entity metadata,
 *     animations... The server sends the same packet instance to everyone nearby, so duplicates are
 *     removed by identity.</li>
 *     <li>A "HUD source" player for scoreboard, tab list, boss bars, titles and chat.</li>
 * </ul>
 */
public final class WorldRecording extends Recording {

    /** Entity id of the (invisible) local player of the replay. Never used by the server. */
    private static final int LOCAL_PLAYER_ID = Integer.MAX_VALUE - 1337;
    private static final GameType LOCAL_GAME_TYPE = GameType.SPECTATOR;

    private final ServerLevel level;
    private final @Nullable Region region;
    private final GameProfile localProfile;
    private final Vec3 startPosition;
    private final int viewDistance;

    // Packet capture from players
    private final Map<ServerPlayer, Consumer<Packet<?>>> listeners = new HashMap<>();
    private volatile @Nullable ServerPlayer hudSource;
    private boolean hudSourceFixed = false;
    private final Object dedupeLock = new Object();
    private Set<Packet<?>> seenThisTick = Collections.newSetFromMap(new IdentityHashMap<>());
    private Set<Packet<?>> seenLastTick = Collections.newSetFromMap(new IdentityHashMap<>());

    // State of the replay, main thread only
    private final IntSet spawned = new IntOpenHashSet();
    private final Int2ObjectMap<Position> lastPositions = new Int2ObjectOpenHashMap<>();
    private final Map<UUID, GameType> knownGameModes = new HashMap<>();
    private final Map<UUID, @Nullable Component> knownDisplayNames = new HashMap<>();
    private final Int2ObjectMap<PlayerState> playerStates = new Int2ObjectOpenHashMap<>();
    private boolean lastRaining;
    private float lastRainLevel;
    private float lastThunderLevel;
    private int tickCounter = 0;

    private static final class PlayerState {
        final Map<Integer, Object> data = new HashMap<>();
        final Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
        boolean wasSwinging;
        float lastSwingProgress;
        boolean wasDead;
    }

    public WorldRecording(Logger logger, String name, Path tempFolder, Path outputZip, int segmentLengthTicks,
                          ServerLevel level, @Nullable Region region, @Nullable Vec3 startPosition, int viewDistance) throws IOException {
        super(logger, name, tempFolder, outputZip, segmentLengthTicks, level);
        this.level = level;
        this.region = region;
        this.viewDistance = Math.max(2, Math.min(32, viewDistance));
        this.localProfile = new GameProfile(UUID.randomUUID(), "Replay");
        this.startPosition = startPosition != null ? startPosition : this.defaultStartPosition();
    }

    private Vec3 defaultStartPosition() {
        if (this.region != null) {
            int x = (this.region.centerChunkX() << 4) + 8;
            int z = (this.region.centerChunkZ() << 4) + 8;
            int y = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 2;
            return new Vec3(x + 0.5, y, z + 0.5);
        }
        for (ServerPlayer player : this.level.players()) {
            return player.position().add(0, 1.6, 0);
        }
        var spawn = this.level.getRespawnData().pos();
        return new Vec3(spawn.getX() + 0.5, spawn.getY() + 2, spawn.getZ() + 0.5);
    }

    @Override
    public ServerLevel level() {
        return this.level;
    }

    /** Copies scoreboard, tab list, boss bars, titles and chat of this player. Null = pick automatically. */
    public void setHudSource(@Nullable ServerPlayer player) {
        this.hudSource = player;
        this.hudSourceFixed = player != null;
    }

    public @Nullable ServerPlayer hudSource() {
        return this.hudSource;
    }

    // ---------------------------------------------------------------------------------------------
    // Packet capture

    private void updateListeners() {
        Iterator<Map.Entry<ServerPlayer, Consumer<Packet<?>>>> iterator = this.listeners.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            ServerPlayer player = entry.getKey();
            if (player.hasDisconnected() || player.level() != this.level) {
                PacketCapture.removeListener(player, entry.getValue());
                iterator.remove();
            }
        }
        for (ServerPlayer player : this.level.players()) {
            if (!this.listeners.containsKey(player)) {
                Consumer<Packet<?>> listener = packet -> this.onPlayerPacket(player, packet);
                if (PacketCapture.addListener(this.logger, player, listener)) {
                    this.listeners.put(player, listener);
                }
            }
        }

        ServerPlayer hud = this.hudSource;
        if (hud != null && (hud.hasDisconnected() || (!this.hudSourceFixed && hud.level() != this.level))) {
            this.hudSource = hud = null;
            this.hudSourceFixed = false;
        }
        if (hud == null && !this.level.players().isEmpty()) {
            this.hudSource = this.level.players().getFirst();
        }
    }

    private void onPlayerPacket(ServerPlayer source, Packet<?> packet) {
        if (packet instanceof ClientboundBundlePacket bundle) {
            for (Packet<?> subPacket : bundle.subPackets()) {
                this.onPlayerPacket(source, subPacket);
            }
            return;
        }

        if (PacketFilter.isWorldPacket(packet)) {
            if (packet instanceof ClientboundLevelChunkWithLightPacket chunk && this.region != null && !this.region.containsChunk(chunk.x(), chunk.z())) {
                return;
            }
            synchronized (this.dedupeLock) {
                if (this.seenLastTick.contains(packet) || !this.seenThisTick.add(packet)) {
                    return;
                }
            }
            //noinspection unchecked
            this.writePacket((Packet<? super ClientGamePacketListener>) packet);
        } else if (source == this.hudSource && PacketFilter.isHudPacket(packet)) {
            Packet<?> transformed = PacketFilter.transform(packet);
            if (transformed != null) {
                //noinspection unchecked
                this.writePacket((Packet<? super ClientGamePacketListener>) transformed);
            }
        }
    }

    @Override
    protected void afterTick() {
        synchronized (this.dedupeLock) {
            Set<Packet<?>> recycled = this.seenLastTick;
            recycled.clear();
            this.seenLastTick = this.seenThisTick;
            this.seenThisTick = recycled;
        }
    }

    @Override
    protected void cleanup() {
        for (var entry : this.listeners.entrySet()) {
            PacketCapture.removeListener(entry.getKey(), entry.getValue());
        }
        this.listeners.clear();
    }

    // ---------------------------------------------------------------------------------------------
    // Per tick state

    private boolean isRecorded(Entity entity, ChunkMap chunkMap) {
        if (entity.isRemoved() || entity instanceof EnderDragonPart || !chunkMap.entityMap.containsKey(entity.getId())) {
            return false;
        }
        return this.region == null || this.region.containsChunk(entity.chunkPosition().x(), entity.chunkPosition().z());
    }

    @Override
    protected void collectTick(List<Packet<? super ClientGamePacketListener>> packets, List<IdAndPosition> positions) {
        this.updateListeners();
        ChunkMap chunkMap = this.level.getChunkSource().chunkMap;

        // Time (otherwise the sky only updates on segment boundaries)
        if (++this.tickCounter % 20 == 0) {
            packets.add(this.level.clockManager().createFullSyncPacket());
        }

        // Weather
        if (this.level.isRaining() != this.lastRaining) {
            this.lastRaining = this.level.isRaining();
            packets.add(new ClientboundGameEventPacket(this.lastRaining ? ClientboundGameEventPacket.START_RAINING : ClientboundGameEventPacket.STOP_RAINING, 0));
        }
        if (this.level.getRainLevel(1.0f) != this.lastRainLevel) {
            this.lastRainLevel = this.level.getRainLevel(1.0f);
            packets.add(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, this.lastRainLevel));
        }
        if (this.level.getThunderLevel(1.0f) != this.lastThunderLevel) {
            this.lastThunderLevel = this.level.getThunderLevel(1.0f);
            packets.add(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, this.lastThunderLevel));
        }

        // Player list. Joins must come before the player entity is spawned, leaves after it was removed.
        List<ServerPlayer> joined = new ArrayList<>();
        List<ClientboundPlayerInfoUpdatePacket.Entry> gameModeChanges = new ArrayList<>();
        List<ClientboundPlayerInfoUpdatePacket.Entry> displayNameChanges = new ArrayList<>();
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : this.level.getServer().getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            online.add(uuid);
            if (!this.knownGameModes.containsKey(uuid)) {
                joined.add(player);
                this.knownGameModes.put(uuid, player.gameMode());
                this.knownDisplayNames.put(uuid, player.getTabListDisplayName());
                continue;
            }
            if (this.knownGameModes.get(uuid) != player.gameMode()) {
                this.knownGameModes.put(uuid, player.gameMode());
                gameModeChanges.add(infoEntry(player));
            }
            Component displayName = player.getTabListDisplayName();
            if (!Objects.equals(this.knownDisplayNames.get(uuid), displayName)) {
                this.knownDisplayNames.put(uuid, displayName);
                displayNameChanges.add(infoEntry(player));
            }
        }
        List<UUID> left = new ArrayList<>();
        for (UUID uuid : List.copyOf(this.knownGameModes.keySet())) {
            if (!online.contains(uuid)) {
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

        // Entities: spawn new ones, write positions, remove the ones that are gone
        IntSet present = new IntOpenHashSet();
        List<Packet<? super ClientGamePacketListener>> links = new ArrayList<>();
        for (Entity entity : this.level.getAllEntities()) {
            if (!this.isRecorded(entity, chunkMap)) {
                continue;
            }
            int id = entity.getId();
            present.add(id);

            if (entity instanceof ServerPlayer player) {
                PlayerState state = this.playerStates.computeIfAbsent(id, k -> new PlayerState());
                boolean dead = player.isDeadOrDying();
                if (state.wasDead && !dead && this.spawned.contains(id)) {
                    // Respawned: Paper reuses the entity, but clients get it re-spawned
                    packets.add(new ClientboundRemoveEntitiesPacket(id));
                    this.spawned.remove(id);
                    this.lastPositions.remove(id);
                    this.playerStates.put(id, state = new PlayerState());
                }
                state.wasDead = dead;
            }

            if (this.spawned.add(id)) {
                pairing(entity, chunkMap, packets, links);
            } else if (entity instanceof ServerPlayer player) {
                this.collectPlayerChanges(player, packets);
            }

            Vec3 pos = entity.trackingPosition();
            Position position = new Position(pos.x, pos.y, pos.z, entity.getYRot(), entity.getXRot(), entity.getYHeadRot(), entity.onGround());
            Position last = this.lastPositions.put(id, position);
            if (!position.equals(last)) {
                positions.add(new IdAndPosition(id, position));
            }
        }
        packets.addAll(links);

        IntArrayList removed = new IntArrayList();
        for (int id : this.spawned) {
            if (!present.contains(id)) {
                removed.add(id);
            }
        }
        if (!removed.isEmpty()) {
            for (int id : removed) {
                this.spawned.remove(id);
                this.lastPositions.remove(id);
                this.playerStates.remove(id);
            }
            packets.add(new ClientboundRemoveEntitiesPacket(removed));
        }

        if (!left.isEmpty()) {
            packets.add(new ClientboundPlayerInfoRemovePacket(left));
        }
    }

    /**
     * Things about players that the server doesn't always send to anyone (e.g. when nobody else is
     * nearby) or that only the player itself receives.
     */
    private void collectPlayerChanges(ServerPlayer player, List<Packet<? super ClientGamePacketListener>> packets) {
        PlayerState state = this.playerStates.computeIfAbsent(player.getId(), k -> new PlayerState());

        List<SynchedEntityData.DataValue<?>> changedData = new ArrayList<>();
        for (SynchedEntityData.DataValue<?> value : player.getEntityData().packAll()) {
            Object last = state.data.get(value.id());
            if (!state.data.containsKey(value.id()) || !Objects.equals(last, value.value())) {
                state.data.put(value.id(), value.value());
                changedData.add(value);
            }
        }
        if (!changedData.isEmpty()) {
            packets.add(new ClientboundSetEntityDataPacket(player.getId(), changedData));
        }

        List<Pair<EquipmentSlot, ItemStack>> changedSlots = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            ItemStack stack = player.getItemBySlot(slot);
            ItemStack last = state.equipment.get(slot);
            if (last == null || !ItemStack.matches(last, stack)) {
                ItemStack copy = stack.copy();
                state.equipment.put(slot, copy);
                changedSlots.add(Pair.of(slot, copy));
            }
        }
        if (!changedSlots.isEmpty()) {
            packets.add(new ClientboundSetEquipmentPacket(player.getId(), changedSlots));
        }

        var swing = player.getCurrentSwing();
        float progress = player.getSwingAnimation(1.0f);
        if (swing != null && (!state.wasSwinging || progress < state.lastSwingProgress)) {
            packets.add(new ClientboundSwingAnimationPacket(player, swing.hand(), swing.animation()));
        }
        state.wasSwinging = swing != null;
        state.lastSwingProgress = progress;
    }

    /** Packets that make a client aware of an entity, like ServerEntity#sendPairingData. */
    private void pairing(Entity entity, ChunkMap chunkMap, List<Packet<? super ClientGamePacketListener>> packets,
                         List<Packet<? super ClientGamePacketListener>> links) {
        ChunkMap.TrackedEntity tracked = chunkMap.entityMap.get(entity.getId());
        if (tracked == null) {
            return;
        }
        ServerEntity serverEntity = tracked.serverEntity;
        try {
            packets.add(entity.getAddEntityPacket(serverEntity));
        } catch (Exception e) {
            this.logger.debug("Unable to create spawn packet for {}", entity, e);
            return;
        }

        List<SynchedEntityData.DataValue<?>> data = entity.getEntityData().getNonDefaultValues();
        if (data != null && !data.isEmpty()) {
            packets.add(new ClientboundSetEntityDataPacket(entity.getId(), data));
        }

        if (entity instanceof LivingEntity living) {
            var attributes = living.getAttributes().getSyncableAttributes();
            if (!attributes.isEmpty()) {
                packets.add(new ClientboundUpdateAttributesPacket(entity.getId(), attributes));
            }
            List<Pair<EquipmentSlot, ItemStack>> slots = new ArrayList<>();
            for (EquipmentSlot slot : EquipmentSlot.VALUES) {
                ItemStack stack = living.getItemBySlot(slot);
                if (!stack.isEmpty()) {
                    slots.add(Pair.of(slot, stack.copy()));
                }
            }
            if (!slots.isEmpty()) {
                packets.add(new ClientboundSetEquipmentPacket(entity.getId(), slots));
            }
        }

        if (entity instanceof ServerPlayer player) {
            // Remember what was sent so only changes are written later
            PlayerState state = this.playerStates.computeIfAbsent(player.getId(), k -> new PlayerState());
            state.data.clear();
            for (SynchedEntityData.DataValue<?> value : player.getEntityData().packAll()) {
                state.data.put(value.id(), value.value());
            }
            state.equipment.clear();
            for (EquipmentSlot slot : EquipmentSlot.VALUES) {
                state.equipment.put(slot, player.getItemBySlot(slot).copy());
            }
        }

        if (!entity.getPassengers().isEmpty()) {
            links.add(new ClientboundSetPassengersPacket(entity));
        }
        if (entity instanceof Leashable leashable && leashable.isLeashed() && leashable.getLeashHolder() != null) {
            links.add(new ClientboundSetEntityLinkPacket(entity, leashable.getLeashHolder()));
        }
    }

    private static ClientboundPlayerInfoUpdatePacket.Entry infoEntry(ServerPlayer player) {
        return new ClientboundPlayerInfoUpdatePacket.Entry(player.getUUID(), player.getGameProfile(), true, player.connection.latency(),
            player.gameMode(), player.getTabListDisplayName(), true, player.getTabListOrder(), null);
    }

    // ---------------------------------------------------------------------------------------------
    // Snapshot

    @Override
    protected Snapshot buildSnapshot() {
        this.updateListeners();
        MinecraftServer server = this.level.getServer();
        ChunkMap chunkMap = this.level.getChunkSource().chunkMap;

        // Configuration: features, registries and tags, fully included (no "known packs")
        List<Packet<? super ClientConfigurationPacketListener>> config = new ArrayList<>();
        config.add(new ClientboundUpdateEnabledFeaturesPacket(FeatureFlags.REGISTRY.toNames(server.getWorldData().enabledFeatures())));
        var ops = server.registries().compositeAccess().createSerializationContext(NbtOps.INSTANCE);
        RegistrySynchronization.packRegistries((com.mojang.serialization.DynamicOps<Tag>) ops, server.registries().getAccessFrom(RegistryLayer.WORLD),
            Set.of(), (key, entries) -> config.add(new ClientboundRegistryDataPacket(key, entries)));
        config.add(new ClientboundUpdateTagsPacket(TagNetworkSerialization.serializeTagsToNetwork(server.registries())));

        // Login of the (invisible) local player
        CommonPlayerSpawnInfo spawnInfo = new CommonPlayerSpawnInfo(this.level.dimensionTypeRegistration(), this.level.dimension(),
            net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(this.level.getSeed()), LOCAL_GAME_TYPE, Optional.empty(),
            this.level.isDebug(), this.level.isFlat(), Optional.empty(), 0, this.level.getSeaLevel());
        ClientboundLoginPacket login = new ClientboundLoginPacket(LOCAL_PLAYER_ID, this.level.getLevelData().isHardcore(), server.levelKeys(),
            server.getPlayerList().getMaxPlayers(), this.viewDistance, this.viewDistance, false, true, false, spawnInfo, false, false);
        LocalPlayerData localPlayer = new LocalPlayerData(this.localProfile.id(), this.startPosition.x, this.startPosition.y, this.startPosition.z,
            30f, 0f, 0f, Vec3.ZERO, this.localProfile, LOCAL_GAME_TYPE);

        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();

        // Player list (all players on the server + the hidden local player)
        this.knownGameModes.clear();
        this.knownDisplayNames.clear();
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        for (ServerPlayer player : players) {
            this.knownGameModes.put(player.getUUID(), player.gameMode());
            this.knownDisplayNames.put(player.getUUID(), player.getTabListDisplayName());
        }
        ClientboundPlayerInfoUpdatePacket base = ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(players);
        List<ClientboundPlayerInfoUpdatePacket.Entry> entries = new ArrayList<>(base.entries());
        entries.add(new ClientboundPlayerInfoUpdatePacket.Entry(this.localProfile.id(), this.localProfile, false, 0, LOCAL_GAME_TYPE, null, true, 0, null));
        packets.add(new ClientboundPlayerInfoUpdatePacket(EnumSet.copyOf(base.actions()), entries));

        // Make the local player invisible
        packets.add(new ClientboundSetEntityDataPacket(LOCAL_PLAYER_ID, List.of(SynchedEntityData.DataValue.create(sharedFlagsAccessor(), (byte) 0x20))));

        // HUD
        ServerPlayer hud = this.hudSource;
        if (hud != null) {
            var bukkit = hud.getBukkitEntity();
            net.kyori.adventure.text.Component header = bukkit.playerListHeader();
            net.kyori.adventure.text.Component footer = bukkit.playerListFooter();
            if (header != null || footer != null) {
                packets.add(new ClientboundTabListPacket(header == null ? Component.empty() : PaperAdventure.asVanilla(header),
                    footer == null ? Component.empty() : PaperAdventure.asVanilla(footer)));
            }
            for (CustomBossEvent event : server.getCustomBossEvents().getEvents()) {
                if (event.getPlayers().contains(hud)) {
                    packets.add(ClientboundBossEventPacket.createAddPacket(event));
                }
            }
            addScoreboard(((CraftScoreboard) bukkit.getScoreboard()).getHandle(), packets);
        } else {
            addScoreboard(server.getScoreboard(), packets);
        }

        // Level info
        packets.add(new ClientboundInitializeBorderPacket(this.level.getWorldBorder()));
        packets.add(this.level.clockManager().createFullSyncPacket());
        packets.add(new ClientboundSetDefaultSpawnPositionPacket(this.level.getRespawnData()));
        packets.add(new ClientboundChangeDifficultyPacket(this.level.getDifficulty(), this.level.getLevelData().isDifficultyLocked()));
        this.lastRaining = this.level.isRaining();
        this.lastRainLevel = this.level.getRainLevel(1.0f);
        this.lastThunderLevel = this.level.getThunderLevel(1.0f);
        packets.add(new ClientboundGameEventPacket(this.lastRaining ? ClientboundGameEventPacket.START_RAINING : ClientboundGameEventPacket.STOP_RAINING, 0));
        packets.add(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, this.lastRainLevel));
        packets.add(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, this.lastThunderLevel));

        // Terrain
        int chunkCount = 0;
        for (long pos : this.snapshotChunks()) {
            LevelChunk chunk = this.level.getChunkSource().getChunkNow(CoordinateUtils.getChunkX(pos), CoordinateUtils.getChunkZ(pos));
            if (chunk != null) {
                packets.add(new ClientboundLevelChunkWithLightPacket(chunk, this.level.getLightEngine(), null, null, false));
                chunkCount++;
            }
        }

        // Entities
        this.spawned.clear();
        this.lastPositions.clear();
        this.playerStates.clear();
        List<Packet<? super ClientGamePacketListener>> links = new ArrayList<>();
        for (Entity entity : this.level.getAllEntities()) {
            if (this.isRecorded(entity, chunkMap)) {
                this.spawned.add(entity.getId());
                pairing(entity, chunkMap, packets, links);
                if (entity instanceof ServerPlayer player) {
                    this.playerStates.get(player.getId()).wasDead = player.isDeadOrDying();
                }
            }
        }
        packets.addAll(links);

        this.logger.info("[{}] Snapshot: {} chunks, {} entities", this.name, chunkCount, this.spawned.size());
        return new Snapshot(config, List.of(login), localPlayer, packets);
    }

    /** Chunks to include in a snapshot, closest to the start position first. */
    private LongArrayList snapshotChunks() {
        LongOpenHashSet chunks = new LongOpenHashSet();
        if (this.region != null) {
            for (int x = -this.region.radiusChunks(); x <= this.region.radiusChunks(); x++) {
                for (int z = -this.region.radiusChunks(); z <= this.region.radiusChunks(); z++) {
                    chunks.add(CoordinateUtils.getChunkKey(this.region.centerChunkX() + x, this.region.centerChunkZ() + z));
                }
            }
        } else {
            // Everything the players in this world can currently see
            for (ServerPlayer player : this.level.players()) {
                var loader = ((ChunkSystemServerPlayer) player).moonrise$getChunkLoader();
                if (loader != null) {
                    chunks.addAll(loader.getSentChunksRaw());
                }
            }
        }
        // Always include the area around the start position, so the replay never starts in the void
        int startX = (int) Math.floor(this.startPosition.x) >> 4;
        int startZ = (int) Math.floor(this.startPosition.z) >> 4;
        int around = Math.min(this.viewDistance, 8);
        for (int x = -around; x <= around; x++) {
            for (int z = -around; z <= around; z++) {
                if (this.region == null || this.region.containsChunk(startX + x, startZ + z)) {
                    chunks.add(CoordinateUtils.getChunkKey(startX + x, startZ + z));
                }
            }
        }

        LongArrayList sorted = new LongArrayList(chunks);
        sorted.sort((a, b) -> Integer.compare(distanceSq(a, startX, startZ), distanceSq(b, startX, startZ)));
        return sorted;
    }

    private static int distanceSq(long pos, int x, int z) {
        int dx = CoordinateUtils.getChunkX(pos) - x;
        int dz = CoordinateUtils.getChunkZ(pos) - z;
        return dx * dx + dz * dz;
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

    @SuppressWarnings("unchecked")
    private static EntityDataAccessor<Byte> sharedFlagsAccessor() {
        try {
            var field = Entity.class.getDeclaredField("DATA_SHARED_FLAGS_ID");
            field.setAccessible(true);
            return (EntityDataAccessor<Byte>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

}
