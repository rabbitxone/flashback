package com.moulberry.flashback.serverrecorder;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.moulberry.flashback.serverrecorder.api.FlashbackRecorderApi;
import com.moulberry.flashback.serverrecorder.camera.CameraRecording;
import com.moulberry.flashback.serverrecorder.format.ReplayMarker;
import com.moulberry.flashback.serverrecorder.recording.PlayerRecording;
import com.moulberry.flashback.serverrecorder.recording.Recording;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class FlashbackServerRecorder extends JavaPlugin implements Listener, FlashbackRecorderApi {

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private static FlashbackServerRecorder instance;

    private final Map<String, Handle> recordings = new LinkedHashMap<>();
    private final List<CompletableFuture<Path>> pendingExports = new ArrayList<>();
    private final Map<String, Handle> autoRecordings = new LinkedHashMap<>();
    private Logger log;

    public static FlashbackServerRecorder instance() {
        if (instance == null) {
            throw new IllegalStateException("FlashbackServerRecorder is not enabled");
        }
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        this.log = this.getSLF4JLogger();
        this.saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        for (Handle handle : List.copyOf(this.recordings.values())) {
            handle.stop();
        }
        // Give exports a chance to finish so nothing is lost on shutdown
        for (CompletableFuture<Path> future : List.copyOf(this.pendingExports)) {
            try {
                future.get(60, TimeUnit.SECONDS);
            } catch (Exception e) {
                this.log.warn("Replay export didn't finish during shutdown", e);
            }
        }
        instance = null;
    }

    // ---------------------------------------------------------------------------------------------
    // API

    @Override
    public Handle recordPlayer(String name, Player player) throws IOException {
        this.checkName(name);
        ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();
        Recording recording = new PlayerRecording(this.log, name, this.tempFolder(), this.outputFile(name),
            this.segmentLengthTicks(), serverPlayer);
        return this.register(recording);
    }

    @Override
    public Handle recordCamera(String name, Location location, int viewDistance, @Nullable Player hudSource) throws IOException {
        this.checkName(name);
        ServerLevel level = ((CraftWorld) location.getWorld()).getHandle();
        CameraRecording recording = new CameraRecording(this.log, name, this.tempFolder(), this.outputFile(name),
            this.segmentLengthTicks(), level, location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch(),
            Math.max(2, Math.min(32, viewDistance)), hudSource == null ? null : ((CraftPlayer) hudSource).getHandle());
        return this.register(recording);
    }

    @Override
    public @Nullable Handle getRecording(String name) {
        return this.recordings.get(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public Collection<Handle> getRecordings() {
        return List.copyOf(this.recordings.values());
    }

    @Override
    public boolean isCamera(Entity entity) {
        net.minecraft.world.entity.Entity handle = ((CraftEntity) entity).getHandle();
        for (Handle h : this.recordings.values()) {
            if (h.recording instanceof CameraRecording camera && camera.camera() == handle) {
                return true;
            }
        }
        return false;
    }

    private void checkName(String name) {
        if (!name.matches("[A-Za-z0-9_.\\-]{1,64}")) {
            throw new IllegalArgumentException("Recording names may only contain letters, digits, '_', '-' and '.'");
        }
        if (this.recordings.containsKey(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("A recording called '" + name + "' is already running");
        }
    }

    private Handle register(Recording recording) {
        Handle handle = new Handle(recording);
        this.recordings.put(recording.name().toLowerCase(Locale.ROOT), handle);
        this.log.info("Started recording '{}'", recording.name());
        return handle;
    }

    private Path tempFolder() {
        return this.getDataFolder().toPath().resolve("temp").resolve(UUID.randomUUID().toString());
    }

    private Path outputFile(String name) {
        Path folder = Path.of(this.getConfig().getString("output-folder", "replays"));
        if (!folder.isAbsolute()) {
            folder = this.getDataFolder().toPath().resolve(folder);
        }
        return folder.resolve(name + "_" + LocalDateTime.now().format(FILE_DATE) + ".zip");
    }

    private int segmentLengthTicks() {
        return Math.max(10, this.getConfig().getInt("segment-length-seconds", 300)) * 20;
    }

    public final class Handle implements RecordingHandle {
        private final Recording recording;

        private Handle(Recording recording) {
            this.recording = recording;
        }

        @Override
        public String name() {
            return this.recording.name();
        }

        @Override
        public int recordedTicks() {
            return this.recording.recordedTicks();
        }

        @Override
        public void marker(@Nullable String description, int rgbColour) {
            this.recording.addMarker(ReplayMarker.of(rgbColour, description));
        }

        @Override
        public void marker(@Nullable String description, int rgbColour, Location location) {
            String dimension = ((CraftWorld) location.getWorld()).getHandle().dimension().identifier().toString();
            this.recording.addMarker(new ReplayMarker(rgbColour, description, location.getX(), location.getY(), location.getZ(), dimension));
        }

        @Override
        public CompletableFuture<Path> stop() {
            FlashbackServerRecorder.this.recordings.remove(this.name().toLowerCase(Locale.ROOT));
            CompletableFuture<Path> future = this.recording.stop();
            FlashbackServerRecorder.this.pendingExports.add(future);
            future.whenComplete((path, error) -> {
                FlashbackServerRecorder.this.pendingExports.remove(future);
                if (error != null) {
                    FlashbackServerRecorder.this.log.error("Failed to save recording '{}'", this.name(), error);
                } else {
                    FlashbackServerRecorder.this.log.info("Saved recording '{}' ({} ticks) to {}", this.name(), this.recordedTicks(), path);
                }
            });
            return future;
        }

        @Override
        public void cancel() {
            FlashbackServerRecorder.this.recordings.remove(this.name().toLowerCase(Locale.ROOT));
            this.recording.cancel();
        }

        Recording recording() {
            return this.recording;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Events

    @EventHandler
    public void onTickEnd(ServerTickEndEvent event) {
        for (Handle handle : List.copyOf(this.recordings.values())) {
            try {
                handle.recording.tick();
            } catch (Throwable t) {
                this.log.error("Error while recording '{}', stopping it", handle.name(), t);
                handle.stop();
                continue;
            }
            if (handle.recording.isStopped() && this.recordings.containsValue(handle)) {
                // Stopped by itself (e.g. viewer disconnected)
                handle.stop();
            }
        }
        if (this.getConfig().getBoolean("auto-record.enabled", false) && Bukkit.getCurrentTick() % 20 == 0) {
            this.tickAutoRecord();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        ServerPlayer player = ((CraftPlayer) event.getPlayer()).getHandle();
        for (Handle handle : this.recordings.values()) {
            if (handle.recording instanceof CameraRecording camera) {
                camera.onPlayerJoin(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        ServerPlayer player = ((CraftPlayer) event.getPlayer()).getHandle();
        for (Handle handle : this.recordings.values()) {
            if (handle.recording instanceof CameraRecording camera && camera.hudSource() == player) {
                camera.setHudSource(null);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (!this.getConfig().getBoolean("death-markers", true)) {
            return;
        }
        Player player = event.getPlayer();
        Component message = event.deathMessage();
        String text = message == null ? player.getName() + " died" : PlainTextComponentSerializer.plainText().serialize(message);
        for (Handle handle : this.recordings.values()) {
            if (handle.recording.viewer().level() == ((CraftWorld) player.getWorld()).getHandle()) {
                handle.marker(text, 0xFF5555, player.getLocation());
            }
        }
    }

    /**
     * Block breaking progress is sent by looping over the player list (so cameras miss it) and never
     * to the breaking player itself (so point-of-view recordings miss it). Recreate it from Paper's event.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreakProgress(io.papermc.paper.event.block.BlockBreakProgressUpdateEvent event) {
        if (this.recordings.isEmpty()) {
            return;
        }
        net.minecraft.world.entity.Entity breaker = ((CraftEntity) event.getEntity()).getHandle();
        int stage = Math.round(event.getProgress() * 10.0f);
        if (stage <= 0 || stage >= 10) {
            stage = -1;
        } else {
            stage = Math.min(9, stage);
        }
        var pos = new net.minecraft.core.BlockPos(event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ());
        var packet = new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(breaker.getId(), pos, stage);
        for (Handle handle : this.recordings.values()) {
            ServerPlayer viewer = handle.recording.viewer();
            boolean camera = handle.recording instanceof CameraRecording && viewer.level() == breaker.level()
                && viewer.distanceToSqr(pos.getX(), pos.getY(), pos.getZ()) < 1024.0 * 16;
            if (camera || viewer == breaker) {
                handle.recording.writeSynthetic(packet);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Automatic arena recording

    private void tickAutoRecord() {
        ConfigurationSection arenas = this.getConfig().getConfigurationSection("auto-record.arenas");
        if (arenas == null) {
            return;
        }
        for (String arena : arenas.getKeys(false)) {
            ConfigurationSection section = arenas.getConfigurationSection(arena);
            if (section == null) {
                continue;
            }
            World world = Bukkit.getWorld(section.getString("world", "world"));
            if (world == null) {
                continue;
            }
            int players = world.getPlayers().stream().filter(p -> !this.isCamera(p)).toList().size();
            Handle running = this.autoRecordings.get(arena);
            if (running != null && !this.recordings.containsValue(running)) {
                this.autoRecordings.remove(arena);
                running = null;
            }

            if (running == null && players >= Math.max(1, section.getInt("min-players", 1))) {
                Location location = new Location(world, section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
                    (float) section.getDouble("yaw"), (float) section.getDouble("pitch"));
                String name = arena + "-" + System.currentTimeMillis() / 1000;
                try {
                    Player hud = world.getPlayers().stream().filter(p -> !this.isCamera(p)).findFirst().orElse(null);
                    this.autoRecordings.put(arena, this.recordCamera(name, location,
                        section.getInt("view-distance", this.getConfig().getInt("camera-view-distance", 8)), hud));
                } catch (Exception e) {
                    this.log.error("Unable to start automatic recording for arena {}", arena, e);
                }
            } else if (running != null && players == 0) {
                this.autoRecordings.remove(arena);
                running.stop();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return false;
        }
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "player" -> {
                    if (args.length < 2) {
                        sender.sendMessage(Component.text("/" + label + " player <player> [name]", NamedTextColor.RED));
                        return true;
                    }
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        sender.sendMessage(Component.text("Player not found", NamedTextColor.RED));
                        return true;
                    }
                    String name = args.length >= 3 ? args[2] : target.getName();
                    this.recordPlayer(name, target);
                    sender.sendMessage(Component.text("Recording '" + name + "' started (point of view of " + target.getName() + ")", NamedTextColor.GREEN));
                }
                case "camera" -> {
                    // /fbrec camera [name] [viewDistance] [hudPlayer|-] [world x y z [yaw pitch]]
                    Location location;
                    if (args.length >= 8) {
                        World world = Bukkit.getWorld(args[4]);
                        if (world == null) {
                            sender.sendMessage(Component.text("Unknown world " + args[4], NamedTextColor.RED));
                            return true;
                        }
                        location = new Location(world, Double.parseDouble(args[5]), Double.parseDouble(args[6]), Double.parseDouble(args[7]),
                            args.length >= 9 ? Float.parseFloat(args[8]) : 0, args.length >= 10 ? Float.parseFloat(args[9]) : 0);
                    } else if (sender instanceof Player player) {
                        location = player.getLocation();
                    } else {
                        sender.sendMessage(Component.text("/" + label + " camera <name> <viewDistance> <hudPlayer|-> <world> <x> <y> <z> [yaw] [pitch]", NamedTextColor.RED));
                        return true;
                    }
                    String name = args.length >= 2 ? args[1] : "camera-" + System.currentTimeMillis() / 1000;
                    int viewDistance = args.length >= 3 ? Integer.parseInt(args[2]) : this.getConfig().getInt("camera-view-distance", 8);
                    Player hud = args.length >= 4 ? Bukkit.getPlayerExact(args[3]) : (sender instanceof Player p ? p : null);
                    this.recordCamera(name, location, viewDistance, hud);
                    sender.sendMessage(Component.text("Camera recording '" + name + "' started here (view distance " + viewDistance + ")", NamedTextColor.GREEN));
                }
                case "stop" -> {
                    List<Handle> toStop = new ArrayList<>();
                    if (args.length < 2 || args[1].equalsIgnoreCase("all")) {
                        toStop.addAll(this.recordings.values());
                    } else {
                        Handle handle = this.getRecording(args[1]);
                        if (handle == null) {
                            sender.sendMessage(Component.text("No recording called " + args[1], NamedTextColor.RED));
                            return true;
                        }
                        toStop.add(handle);
                    }
                    for (Handle handle : toStop) {
                        handle.stop().whenComplete((path, error) -> Bukkit.getScheduler().runTask(this, () -> {
                            if (error != null) {
                                sender.sendMessage(Component.text("Failed to save '" + handle.name() + "': " + error.getMessage(), NamedTextColor.RED));
                            } else {
                                sender.sendMessage(Component.text("Saved '" + handle.name() + "' to " + path, NamedTextColor.GREEN));
                            }
                        }));
                    }
                    if (toStop.isEmpty()) {
                        sender.sendMessage(Component.text("Nothing is being recorded", NamedTextColor.YELLOW));
                    }
                }
                case "list" -> {
                    if (this.recordings.isEmpty()) {
                        sender.sendMessage(Component.text("Nothing is being recorded", NamedTextColor.YELLOW));
                    }
                    for (Handle handle : this.recordings.values()) {
                        String type = handle.recording instanceof CameraRecording ? "camera" : "player " + handle.recording.viewer().getPlainTextName();
                        sender.sendMessage(Component.text(handle.name() + " (" + type + ", " + handle.recordedTicks() / 20 + "s)", NamedTextColor.AQUA));
                    }
                }
                case "marker" -> {
                    if (args.length < 2) {
                        sender.sendMessage(Component.text("/" + label + " marker <name> [description]", NamedTextColor.RED));
                        return true;
                    }
                    Handle handle = this.getRecording(args[1]);
                    if (handle == null) {
                        sender.sendMessage(Component.text("No recording called " + args[1], NamedTextColor.RED));
                        return true;
                    }
                    String description = args.length >= 3 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : null;
                    if (sender instanceof Player player) {
                        handle.marker(description, 0x55FFFF, player.getLocation());
                    } else {
                        handle.marker(description, 0x55FFFF);
                    }
                    sender.sendMessage(Component.text("Marker added", NamedTextColor.GREEN));
                }
                case "hud" -> {
                    if (args.length < 3) {
                        sender.sendMessage(Component.text("/" + label + " hud <camera recording> <player|none>", NamedTextColor.RED));
                        return true;
                    }
                    Handle handle = this.getRecording(args[1]);
                    if (handle == null || !(handle.recording instanceof CameraRecording camera)) {
                        sender.sendMessage(Component.text("No camera recording called " + args[1], NamedTextColor.RED));
                        return true;
                    }
                    Player hud = Bukkit.getPlayerExact(args[2]);
                    camera.setHudSource(hud == null ? null : ((CraftPlayer) hud).getHandle());
                    sender.sendMessage(Component.text("HUD source set to " + (hud == null ? "nobody" : hud.getName()), NamedTextColor.GREEN));
                }
                case "verify" -> {
                    if (args.length < 2) {
                        sender.sendMessage(Component.text("/" + label + " verify <replay zip>", NamedTextColor.RED));
                        return true;
                    }
                    Path path = Path.of(args[1]);
                    if (!path.isAbsolute()) {
                        path = this.outputFile("x").getParent().resolve(args[1]);
                    }
                    var registryAccess = ((org.bukkit.craftbukkit.CraftServer) Bukkit.getServer()).getServer().registryAccess();
                    Path finalPath = path;
                    Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                        try {
                            var result = com.moulberry.flashback.serverrecorder.format.ReplayVerifier.verify(finalPath, registryAccess);
                            sender.sendMessage(Component.text("Ticks: " + result.totalTicks() + ", chunk files: " + result.chunkFiles()
                                + ", actions: " + result.actions() + ", cached chunks: " + result.cachedChunks(), NamedTextColor.AQUA));
                            result.packetCounts().entrySet().stream().sorted(Map.Entry.comparingByKey())
                                .forEach(e -> sender.sendMessage(Component.text("  " + e.getKey() + " x" + e.getValue(), NamedTextColor.GRAY)));
                            if (result.errors().isEmpty()) {
                                sender.sendMessage(Component.text("Replay is valid", NamedTextColor.GREEN));
                            } else {
                                result.errors().stream().limit(30).forEach(e -> sender.sendMessage(Component.text(e, NamedTextColor.RED)));
                            }
                        } catch (Exception e) {
                            sender.sendMessage(Component.text("Unable to read replay: " + e, NamedTextColor.RED));
                        }
                    });
                }
                default -> {
                    return false;
                }
            }
        } catch (IllegalArgumentException | IOException e) {
            sender.sendMessage(Component.text(e.getMessage(), NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("player", "camera", "stop", "list", "marker", "hud", "verify"), args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (sub.equals("stop") || sub.equals("marker") || sub.equals("hud"))) {
            List<String> names = new ArrayList<>(this.recordings.values().stream().map(Handle::name).toList());
            if (sub.equals("stop")) {
                names.add("all");
            }
            return filter(names, args[1]);
        }
        if ((args.length == 2 && sub.equals("player")) || (args.length == 3 && sub.equals("hud")) || (args.length == 4 && sub.equals("camera"))) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[args.length - 1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).toList();
    }

}
