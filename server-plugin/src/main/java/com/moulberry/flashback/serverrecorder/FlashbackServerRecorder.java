package com.moulberry.flashback.serverrecorder;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.moulberry.flashback.serverrecorder.api.FlashbackRecorderApi;
import com.moulberry.flashback.serverrecorder.format.ReplayMarker;
import com.moulberry.flashback.serverrecorder.format.ReplayVerifier;
import com.moulberry.flashback.serverrecorder.recording.Region;
import com.moulberry.flashback.serverrecorder.recording.WorldRecording;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class FlashbackServerRecorder extends JavaPlugin implements Listener, FlashbackRecorderApi {

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private static @Nullable FlashbackServerRecorder instance;

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
    public Handle recordWorld(String name, World world) throws IOException {
        return this.start(name, world, null, null, null, 0, 30);
    }

    @Override
    public Handle recordArea(String name, Location center, int radiusBlocks) throws IOException {
        return this.start(name, center.getWorld(), region(center, radiusBlocks), null, null, 0, 30);
    }

    @Override
    public Handle recordCamera(String name, Location camera, int radiusBlocks) throws IOException {
        Region region = radiusBlocks > 0 ? region(camera, radiusBlocks) : null;
        return this.start(name, camera.getWorld(), region, null, new Vec3(camera.getX(), camera.getY(), camera.getZ()),
            camera.getYaw(), camera.getPitch());
    }

    @Override
    public Handle recordPlayer(String name, Player player) throws IOException {
        return this.start(name, player.getWorld(), null, ((CraftPlayer) player).getHandle(), null, 0, 0);
    }

    private static Region region(Location center, int radiusBlocks) {
        return Region.around(center.getX(), center.getZ(), Math.max(16, Math.min(512, radiusBlocks)));
    }

    private Handle start(String name, World world, @Nullable Region region, net.minecraft.server.level.@Nullable ServerPlayer focus,
                         @Nullable Vec3 startPosition, float yaw, float pitch) throws IOException {
        this.checkName(name);
        ServerLevel level = ((CraftWorld) world).getHandle();
        WorldRecording recording = new WorldRecording(this.log, name, this.tempFolder(), this.outputFile(name), this.segmentLengthTicks(),
            level, region, focus, startPosition, yaw, pitch, this.getConfig().getInt("view-distance", 12));
        Handle handle = new Handle(recording);
        this.recordings.put(name.toLowerCase(Locale.ROOT), handle);
        this.log.info("Started recording '{}' of world {}{}", name, world.getName(), region == null ? "" : " (" + region + ")");
        return handle;
    }

    @Override
    public @Nullable Handle getRecording(String name) {
        return this.recordings.get(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public List<Handle> getRecordings() {
        return List.copyOf(this.recordings.values());
    }

    private void checkName(String name) {
        if (!name.matches("[A-Za-z0-9_.\\-]{1,64}")) {
            throw new IllegalArgumentException("Recording names may only contain letters, digits, '_', '-' and '.'");
        }
        if (this.recordings.containsKey(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("A recording called '" + name + "' is already running");
        }
    }

    private Path tempFolder() {
        return this.getDataFolder().toPath().resolve("temp").resolve(UUID.randomUUID().toString());
    }

    private Path outputFolder() {
        Path folder = Path.of(this.getConfig().getString("output-folder", "replays"));
        return folder.isAbsolute() ? folder : this.getDataFolder().toPath().resolve(folder);
    }

    private Path outputFile(String name) {
        return this.outputFolder().resolve(name + "_" + LocalDateTime.now().format(FILE_DATE) + ".zip");
    }

    private int segmentLengthTicks() {
        return Math.max(10, this.getConfig().getInt("segment-length-seconds", 300)) * 20;
    }

    public final class Handle implements RecordingHandle {
        private final WorldRecording recording;

        private Handle(WorldRecording recording) {
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
        public void setHudPlayer(@Nullable Player player) {
            this.recording.setHudSource(player == null ? null : ((CraftPlayer) player).getHandle());
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
    }

    // ---------------------------------------------------------------------------------------------
    // Events

    @EventHandler
    public void onTickEnd(ServerTickEndEvent event) {
        for (Handle handle : List.copyOf(this.recordings.values())) {
            try {
                String stopReason = handle.recording.shouldStop();
                if (stopReason != null) {
                    this.log.info("Stopping recording '{}': {}", handle.name(), stopReason);
                    handle.stop();
                    continue;
                }
                handle.recording.tick();
            } catch (Throwable t) {
                this.log.error("Error while recording '{}', stopping it", handle.name(), t);
                handle.stop();
            }
        }
        if (this.getConfig().getBoolean("auto-record.enabled", false) && Bukkit.getCurrentTick() % 20 == 0) {
            this.tickAutoRecord();
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
        ServerLevel level = ((CraftWorld) player.getWorld()).getHandle();
        for (Handle handle : this.recordings.values()) {
            if (handle.recording.level() == level) {
                handle.marker(text, 0xFF5555, player.getLocation());
            }
        }
    }

    /** Block breaking progress is never sent to the breaking player itself, so recreate it from Paper's event. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreakProgress(io.papermc.paper.event.block.BlockBreakProgressUpdateEvent event) {
        if (this.recordings.isEmpty()) {
            return;
        }
        net.minecraft.world.entity.Entity breaker = ((CraftEntity) event.getEntity()).getHandle();
        int stage = Math.round(event.getProgress() * 10.0f);
        stage = stage <= 0 || stage >= 10 ? -1 : Math.min(9, stage);
        BlockPos pos = new BlockPos(event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ());
        ClientboundBlockDestructionPacket packet = new ClientboundBlockDestructionPacket(breaker.getId(), pos, stage);
        for (Handle handle : this.recordings.values()) {
            if (handle.recording.level() == breaker.level()) {
                handle.recording.writePacket(packet);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Automatic recording

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
            int players = world.getPlayers().size();
            Handle running = this.autoRecordings.get(arena);
            if (running != null && !this.recordings.containsValue(running)) {
                this.autoRecordings.remove(arena);
                running = null;
            }

            if (running == null && players >= Math.max(1, section.getInt("min-players", 1))) {
                String name = arena + "-" + System.currentTimeMillis() / 1000;
                try {
                    Handle handle = section.contains("radius")
                        ? this.recordArea(name, new Location(world, section.getDouble("x"), 0, section.getDouble("z")), section.getInt("radius"))
                        : this.recordWorld(name, world);
                    this.autoRecordings.put(arena, handle);
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

    private static void send(CommandSender sender, String text, NamedTextColor colour) {
        sender.sendMessage(Component.text(text, colour));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return false;
        }
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "world" -> {
                    // /fbrec world <name> [world]
                    if (args.length < 2) {
                        send(sender, "/" + label + " world <name> [world]", NamedTextColor.RED);
                        return true;
                    }
                    World world = args.length >= 3 ? Bukkit.getWorld(args[2]) : sender instanceof Player p ? p.getWorld() : Bukkit.getWorlds().getFirst();
                    if (world == null) {
                        send(sender, "Unknown world " + args[2], NamedTextColor.RED);
                        return true;
                    }
                    this.recordWorld(args[1], world);
                    send(sender, "Recording '" + args[1] + "' started (whole world " + world.getName() + ")", NamedTextColor.GREEN);
                }
                case "area" -> {
                    // /fbrec area <name> <radius> [world x z]
                    if (args.length < 3) {
                        send(sender, "/" + label + " area <name> <radius in blocks> [world x z]", NamedTextColor.RED);
                        return true;
                    }
                    Location center;
                    if (args.length >= 6) {
                        World world = Bukkit.getWorld(args[3]);
                        if (world == null) {
                            send(sender, "Unknown world " + args[3], NamedTextColor.RED);
                            return true;
                        }
                        center = new Location(world, Double.parseDouble(args[4]), 0, Double.parseDouble(args[5]));
                    } else if (sender instanceof Player player) {
                        center = player.getLocation();
                    } else {
                        send(sender, "/" + label + " area <name> <radius> <world> <x> <z>", NamedTextColor.RED);
                        return true;
                    }
                    int radius = Integer.parseInt(args[2]);
                    this.recordArea(args[1], center, radius);
                    send(sender, "Recording '" + args[1] + "' started (" + radius + " blocks around " + center.getBlockX() + ", " + center.getBlockZ() + ")", NamedTextColor.GREEN);
                }
                case "camera" -> {
                    // /fbrec camera <name> [radius] [world x y z [yaw pitch]]
                    if (args.length < 2) {
                        send(sender, "/" + label + " camera <name> [radius in blocks, 0 = whole world] [world x y z [yaw pitch]]", NamedTextColor.RED);
                        return true;
                    }
                    int radius = args.length >= 3 ? Integer.parseInt(args[2]) : 0;
                    Location camera;
                    if (args.length >= 7) {
                        World world = Bukkit.getWorld(args[3]);
                        if (world == null) {
                            send(sender, "Unknown world " + args[3], NamedTextColor.RED);
                            return true;
                        }
                        camera = new Location(world, Double.parseDouble(args[4]), Double.parseDouble(args[5]), Double.parseDouble(args[6]),
                            args.length >= 8 ? Float.parseFloat(args[7]) : 0, args.length >= 9 ? Float.parseFloat(args[8]) : 0);
                    } else if (sender instanceof Player player) {
                        camera = player.getEyeLocation();
                    } else {
                        send(sender, "/" + label + " camera <name> <radius> <world> <x> <y> <z> [yaw] [pitch]", NamedTextColor.RED);
                        return true;
                    }
                    this.recordCamera(args[1], camera, radius);
                    send(sender, "Recording '" + args[1] + "' started, camera placed at " + camera.getBlockX() + ", " + camera.getBlockY() + ", "
                        + camera.getBlockZ() + (radius > 0 ? " (" + radius + " blocks around it)" : " (whole world)"), NamedTextColor.GREEN);
                }
                case "player" -> {
                    // /fbrec player <name> <player>
                    if (args.length < 3) {
                        send(sender, "/" + label + " player <name> <player>", NamedTextColor.RED);
                        return true;
                    }
                    Player target = Bukkit.getPlayerExact(args[2]);
                    if (target == null) {
                        send(sender, "Player not found", NamedTextColor.RED);
                        return true;
                    }
                    this.recordPlayer(args[1], target);
                    send(sender, "Recording '" + args[1] + "' started, following " + target.getName(), NamedTextColor.GREEN);
                }
                case "stop" -> {
                    List<Handle> toStop = new ArrayList<>();
                    if (args.length < 2 || args[1].equalsIgnoreCase("all")) {
                        toStop.addAll(this.recordings.values());
                    } else {
                        Handle handle = this.getRecording(args[1]);
                        if (handle == null) {
                            send(sender, "No recording called " + args[1], NamedTextColor.RED);
                            return true;
                        }
                        toStop.add(handle);
                    }
                    if (toStop.isEmpty()) {
                        send(sender, "Nothing is being recorded", NamedTextColor.YELLOW);
                    }
                    for (Handle handle : toStop) {
                        handle.stop().whenComplete((path, error) -> Bukkit.getScheduler().runTask(this, () -> {
                            if (error != null) {
                                send(sender, "Failed to save '" + handle.name() + "': " + error.getMessage(), NamedTextColor.RED);
                            } else {
                                send(sender, "Saved '" + handle.name() + "' to " + path, NamedTextColor.GREEN);
                            }
                        }));
                    }
                }
                case "list" -> {
                    if (this.recordings.isEmpty()) {
                        send(sender, "Nothing is being recorded", NamedTextColor.YELLOW);
                    }
                    for (Handle handle : this.recordings.values()) {
                        send(sender, handle.name() + " (" + handle.recording.level().getWorld().getName() + ", " + handle.recordedTicks() / 20 + "s)", NamedTextColor.AQUA);
                    }
                }
                case "marker" -> {
                    if (args.length < 2) {
                        send(sender, "/" + label + " marker <name> [description]", NamedTextColor.RED);
                        return true;
                    }
                    Handle handle = this.getRecording(args[1]);
                    if (handle == null) {
                        send(sender, "No recording called " + args[1], NamedTextColor.RED);
                        return true;
                    }
                    String description = args.length >= 3 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : null;
                    if (sender instanceof Player player) {
                        handle.marker(description, 0x55FFFF, player.getLocation());
                    } else {
                        handle.marker(description, 0x55FFFF);
                    }
                    send(sender, "Marker added", NamedTextColor.GREEN);
                }
                case "hud" -> {
                    if (args.length < 3) {
                        send(sender, "/" + label + " hud <recording> <player|auto>", NamedTextColor.RED);
                        return true;
                    }
                    Handle handle = this.getRecording(args[1]);
                    if (handle == null) {
                        send(sender, "No recording called " + args[1], NamedTextColor.RED);
                        return true;
                    }
                    Player hud = Bukkit.getPlayerExact(args[2]);
                    handle.setHudPlayer(hud);
                    send(sender, "Scoreboard/tab list/chat now come from " + (hud == null ? "an automatically chosen player" : hud.getName()), NamedTextColor.GREEN);
                }
                case "verify" -> {
                    if (args.length < 2) {
                        send(sender, "/" + label + " verify <replay zip>", NamedTextColor.RED);
                        return true;
                    }
                    Path path = Path.of(args[1]);
                    Path file = path.isAbsolute() ? path : this.outputFolder().resolve(args[1]);
                    var registryAccess = ((CraftServer) Bukkit.getServer()).getServer().registryAccess();
                    Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                        try {
                            var result = ReplayVerifier.verify(file, registryAccess);
                            send(sender, "Ticks: " + result.totalTicks() + ", chunk files: " + result.chunkFiles()
                                + ", actions: " + result.actions() + ", cached chunks: " + result.cachedChunks(), NamedTextColor.AQUA);
                            result.packetCounts().entrySet().stream().sorted(Map.Entry.comparingByKey())
                                .forEach(e -> send(sender, "  " + e.getKey() + " x" + e.getValue(), NamedTextColor.GRAY));
                            if (result.errors().isEmpty()) {
                                send(sender, "Replay is valid", NamedTextColor.GREEN);
                            } else {
                                result.errors().stream().limit(30).forEach(e -> send(sender, e, NamedTextColor.RED));
                            }
                        } catch (Exception e) {
                            send(sender, "Unable to read replay: " + e, NamedTextColor.RED);
                        }
                    });
                }
                default -> {
                    return false;
                }
            }
        } catch (IllegalArgumentException | IOException e) {
            send(sender, String.valueOf(e.getMessage()), NamedTextColor.RED);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("world", "area", "camera", "player", "stop", "list", "marker", "hud", "verify"), args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (sub.equals("stop") || sub.equals("marker") || sub.equals("hud"))) {
            List<String> names = new ArrayList<>(this.recordings.values().stream().map(Handle::name).toList());
            if (sub.equals("stop")) {
                names.add("all");
            }
            return filter(names, args[1]);
        }
        if ((args.length == 3 && sub.equals("world")) || (args.length == 4 && sub.equals("area"))) {
            return filter(Bukkit.getWorlds().stream().map(World::getName).toList(), args[args.length - 1]);
        }
        if (args.length == 3 && sub.equals("player")) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[2]);
        }
        if (args.length == 3 && sub.equals("hud")) {
            List<String> names = new ArrayList<>(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
            names.add("auto");
            return filter(names, args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).toList();
    }

}
