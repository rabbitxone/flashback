package com.moulberry.flashback.serverrecorder.recording;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * Decides which packets seen by players are copied into a world recording.
 * <p>
 * Only "world" packets are copied: things that every nearby player receives in the same way (block
 * changes, sounds, particles, entity metadata...). Entity spawning/removal, positions, the player list,
 * time and weather are generated from the server state instead, so they're never copied.
 */
public final class PacketFilter {

    private static final Set<Class<?>> WORLD_PACKETS = Set.of(
        // Blocks & chunks
        ClientboundBlockUpdatePacket.class,
        ClientboundSectionBlocksUpdatePacket.class,
        ClientboundBlockEventPacket.class,
        ClientboundBlockEntityDataPacket.class,
        ClientboundBlockDestructionPacket.class,
        ClientboundLevelChunkWithLightPacket.class,
        ClientboundLightUpdatePacket.class,
        ClientboundChunksBiomesPacket.class,
        // Effects
        ClientboundLevelEventPacket.class,
        ClientboundLevelParticlesPacket.class,
        ClientboundExplodePacket.class,
        ClientboundSoundPacket.class,
        ClientboundSoundEntityPacket.class,
        // Entities
        ClientboundSetEntityDataPacket.class,
        ClientboundSetEquipmentPacket.class,
        ClientboundUpdateAttributesPacket.class,
        ClientboundAnimatePacket.class,
        ClientboundSwingAnimationPacket.class,
        ClientboundHurtAnimationPacket.class,
        ClientboundDamageEventPacket.class,
        ClientboundEntityEventPacket.class,
        ClientboundSetPassengersPacket.class,
        ClientboundSetEntityLinkPacket.class,
        ClientboundUpdateMobEffectPacket.class,
        ClientboundRemoveMobEffectPacket.class,
        ClientboundTakeItemEntityPacket.class,
        ClientboundSetEntityMotionPacket.class,
        ClientboundProjectilePowerPacket.class,
        // World border
        ClientboundSetBorderCenterPacket.class,
        ClientboundSetBorderLerpSizePacket.class,
        ClientboundSetBorderSizePacket.class,
        ClientboundSetBorderWarningDelayPacket.class,
        ClientboundSetBorderWarningDistancePacket.class
    );

    /** HUD packets, copied only from the recording's "HUD source" player. */
    private static final Set<Class<?>> HUD_PACKETS = Set.of(
        ClientboundSetPlayerTeamPacket.class,
        ClientboundSetObjectivePacket.class,
        ClientboundSetDisplayObjectivePacket.class,
        ClientboundSetScorePacket.class,
        ClientboundResetScorePacket.class,
        ClientboundSystemChatPacket.class,
        ClientboundPlayerChatPacket.class,
        ClientboundDisguisedChatPacket.class,
        ClientboundTabListPacket.class,
        ClientboundBossEventPacket.class,
        ClientboundSetTitleTextPacket.class,
        ClientboundSetSubtitleTextPacket.class,
        ClientboundSetTitlesAnimationPacket.class,
        ClientboundClearTitlesPacket.class,
        ClientboundSetActionBarTextPacket.class
    );

    public static boolean isWorldPacket(Packet<?> packet) {
        return WORLD_PACKETS.contains(packet.getClass());
    }

    public static boolean isHudPacket(Packet<?> packet) {
        return HUD_PACKETS.contains(packet.getClass());
    }

    /**
     * Signed player chat can't be replayed, so it becomes system chat (same as the Flashback mod does).
     *
     * @return the packet to record, or null to drop it
     */
    public static @Nullable Packet<?> transform(Packet<?> packet) {
        if (packet instanceof ClientboundPlayerChatPacket chat) {
            try {
                Component content = chat.unsignedContent().orElse(Component.literal(chat.body().content()));
                return new ClientboundSystemChatPacket(chat.chatType().decorate(content), false);
            } catch (Exception e) {
                return null;
            }
        }
        if (packet instanceof ClientboundDisguisedChatPacket chat) {
            try {
                return new ClientboundSystemChatPacket(chat.chatType().decorate(chat.message()), false);
            } catch (Exception e) {
                return null;
            }
        }
        return packet;
    }

    private PacketFilter() {
    }

}
