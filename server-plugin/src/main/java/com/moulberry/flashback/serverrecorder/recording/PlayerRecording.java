package com.moulberry.flashback.serverrecorder.recording;

import com.mojang.datafixers.util.Pair;
import com.moulberry.flashback.serverrecorder.net.PacketCapture;
import io.netty.channel.Channel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Records exactly what one real player sees (their "point of view"), like the Flashback mod would on
 * their client. Packets are captured from the player's netty pipeline. Things the server never sends to
 * the player about themselves (hotbar, swinging, equipment...) are generated every tick, mirroring
 * {@code Recorder#writeLocalData} in the mod.
 */
public final class PlayerRecording extends Recording {

    private final ServerPlayer player;
    private final Channel channel;
    private final Consumer<Packet<?>> listener = this::onPacket;

    // Local player state, main thread only
    private final ItemStack[] lastHotbar = new ItemStack[9];
    private final Map<EquipmentSlot, ItemStack> lastEquipment = new EnumMap<>(EquipmentSlot.class);
    private int lastSelectedSlot = -1;
    private float lastHealth = -1;
    private int lastFood = -1;
    private float lastSaturation = -1;
    private float lastXpProgress = -1;
    private int lastXpTotal = -1;
    private int lastXpLevel = -1;
    private boolean wasSwinging = false;
    private float lastSwingProgress = 0;

    public PlayerRecording(Logger logger, String name, Path tempFolder, Path outputZip, int segmentLengthTicks, ServerPlayer player) throws IOException {
        super(logger, name, tempFolder, outputZip, segmentLengthTicks, player.level());
        this.player = player;
        Channel channel = PacketCapture.channel(player);
        if (channel == null || !PacketCapture.addListener(logger, player, this.listener)) {
            throw new IOException("Player " + player.getPlainTextName() + " has no open connection");
        }
        this.channel = channel;
    }

    @Override
    public ServerPlayer viewer() {
        return this.player;
    }

    @Override
    protected void runSequenced(Runnable runnable) {
        if (this.channel.eventLoop().inEventLoop()) {
            runnable.run();
        } else {
            this.channel.eventLoop().execute(runnable);
        }
    }

    @Override
    protected GameType localGameType() {
        return this.player.gameMode();
    }

    @Override
    public @Nullable ServerPlayer hudSource() {
        return this.player;
    }

    @Override
    protected boolean isCamera() {
        return false;
    }

    @Override
    protected void onConfigurationPhase() {
        super.onConfigurationPhase();
        this.logger.warn("Player {} was moved into the configuration phase, stopping recording '{}'", this.player.getPlainTextName(), this.name);
        org.bukkit.Bukkit.getScheduler().runTask(org.bukkit.Bukkit.getPluginManager().getPlugin("FlashbackServerRecorder"), () -> this.stop());
    }

    @Override
    protected void cleanup() {
        PacketCapture.removeListener(this.player, this.listener);
    }

    @Override
    protected void collectTickPackets(ServerPlayer player, List<Packet<? super ClientGamePacketListener>> packets) {
        // Health & food
        FoodData food = player.getFoodData();
        if (player.getHealth() != this.lastHealth || food.getFoodLevel() != this.lastFood || food.getSaturationLevel() != this.lastSaturation) {
            this.lastHealth = player.getHealth();
            this.lastFood = food.getFoodLevel();
            this.lastSaturation = food.getSaturationLevel();
            packets.add(new ClientboundSetHealthPacket(player.getHealth(), food.getFoodLevel(), food.getSaturationLevel()));
        }

        // Experience
        if (player.experienceProgress != this.lastXpProgress || player.totalExperience != this.lastXpTotal || player.experienceLevel != this.lastXpLevel) {
            this.lastXpProgress = player.experienceProgress;
            this.lastXpTotal = player.totalExperience;
            this.lastXpLevel = player.experienceLevel;
            packets.add(new ClientboundSetExperiencePacket(player.experienceProgress, player.totalExperience, player.experienceLevel));
        }

        // Selected hotbar slot
        int selectedSlot = player.getInventory().getSelectedSlot();
        if (selectedSlot != this.lastSelectedSlot) {
            this.lastSelectedSlot = selectedSlot;
            packets.add(new ClientboundSetHeldSlotPacket(selectedSlot));
        }

        // Hotbar contents (container 0, slot = inventory index, same as the mod)
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (this.lastHotbar[i] == null || !ItemStack.matches(this.lastHotbar[i], stack)) {
                ItemStack copy = stack.copy();
                this.lastHotbar[i] = copy;
                packets.add(new ClientboundContainerSetSlotPacket(0, 0, i, copy));
            }
        }

        // Equipment: the server only sends equipment changes to *other* players
        List<Pair<EquipmentSlot, ItemStack>> changedSlots = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            ItemStack stack = player.getItemBySlot(slot);
            ItemStack last = this.lastEquipment.get(slot);
            if (last == null || !ItemStack.matches(last, stack)) {
                ItemStack copy = stack.copy();
                this.lastEquipment.put(slot, copy);
                changedSlots.add(Pair.of(slot, copy));
            }
        }
        if (!changedSlots.isEmpty()) {
            packets.add(new ClientboundSetEquipmentPacket(player.getId(), changedSlots));
        }

        // Arm swinging: also only sent to other players
        var currentSwing = player.getCurrentSwing();
        float swingProgress = player.getSwingAnimation(1.0f);
        if (currentSwing != null && (!this.wasSwinging || swingProgress < this.lastSwingProgress)) {
            packets.add(new ClientboundSwingAnimationPacket(player, currentSwing.hand(), currentSwing.animation()));
        }
        this.wasSwinging = currentSwing != null;
        this.lastSwingProgress = swingProgress;
    }

}
