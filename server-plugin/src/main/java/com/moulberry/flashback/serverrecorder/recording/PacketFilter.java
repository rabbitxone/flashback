package com.moulberry.flashback.serverrecorder.recording;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.*;
import net.minecraft.network.protocol.configuration.ClientboundCodeOfConductPacket;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * Decides which clientbound packets end up in the replay.
 * <p>
 * Based on {@code com.moulberry.flashback.record.IgnoredPacketSet}. The main difference is that the mod
 * captures sounds/level events client-side (so it ignores them on the network), while on the server the
 * network packets are the only source of those, so we keep them.
 * Entity movement packets are dropped because positions are written every tick with the
 * {@code move_entities} action using exact server-side positions.
 */
public final class PacketFilter {

    private static final Set<Class<?>> IGNORED = Set.of(
        // Common
        ClientboundStoreCookiePacket.class,
        ClientboundCustomReportDetailsPacket.class,
        ClientboundServerLinksPacket.class,
        ClientboundCookieRequestPacket.class,
        ClientboundDisconnectPacket.class,
        ClientboundPingPacket.class,
        ClientboundKeepAlivePacket.class,
        ClientboundTransferPacket.class,
        ClientboundClearDialogPacket.class,
        ClientboundShowDialogPacket.class,
        ClientboundCustomPayloadPacket.class,
        ClientboundResourcePackPushPacket.class,
        ClientboundResourcePackPopPacket.class,
        ClientboundUpdateTagsPacket.class,

        // Configuration
        ClientboundFinishConfigurationPacket.class,
        ClientboundCodeOfConductPacket.class,

        // Game
        ClientboundAwardStatsPacket.class,
        ClientboundRecipeBookAddPacket.class,
        ClientboundRecipeBookRemovePacket.class,
        ClientboundRecipeBookSettingsPacket.class,
        ClientboundOpenSignEditorPacket.class,
        ClientboundRotateHeadPacket.class,
        ClientboundMoveEntityPacket.Pos.class,
        ClientboundMoveEntityPacket.Rot.class,
        ClientboundMoveEntityPacket.PosRot.class,
        ClientboundPlayerPositionPacket.class,
        ClientboundPlayerRotationPacket.class,
        ClientboundDeleteChatPacket.class,
        ClientboundMoveMinecartPacket.class,
        ClientboundContainerClosePacket.class,
        ClientboundContainerSetContentPacket.class,
        ClientboundMountScreenOpenPacket.class,
        ClientboundContainerSetDataPacket.class,
        ClientboundContainerSetSlotPacket.class,
        ClientboundForgetLevelChunkPacket.class,
        ClientboundPlayerAbilitiesPacket.class,
        ClientboundSetCursorItemPacket.class,
        ClientboundSetExperiencePacket.class,
        ClientboundSetHealthPacket.class,
        ClientboundSetHeldSlotPacket.class,
        ClientboundSetPlayerInventoryPacket.class,
        ClientboundTickingStatePacket.class,
        ClientboundTickingStepPacket.class,
        ClientboundPlayerCombatEndPacket.class,
        ClientboundPlayerCombatEnterPacket.class,
        ClientboundPlayerCombatKillPacket.class,
        ClientboundSetCameraPacket.class,
        ClientboundCooldownPacket.class,
        ClientboundUpdateAdvancementsPacket.class,
        ClientboundSelectAdvancementsTabPacket.class,
        ClientboundPlaceGhostRecipePacket.class,
        ClientboundCommandsPacket.class,
        ClientboundCommandSuggestionsPacket.class,
        ClientboundUpdateRecipesPacket.class,
        ClientboundTagQueryPacket.class,
        ClientboundOpenBookPacket.class,
        ClientboundOpenScreenPacket.class,
        ClientboundMerchantOffersPacket.class,
        ClientboundSetChunkCacheRadiusPacket.class,
        ClientboundSetSimulationDistancePacket.class,
        ClientboundSetChunkCacheCenterPacket.class,
        ClientboundBlockChangedAckPacket.class,
        ClientboundCustomChatCompletionsPacket.class,
        ClientboundChunkBatchStartPacket.class,
        ClientboundChunkBatchFinishedPacket.class,
        ClientboundDebugSamplePacket.class,
        ClientboundPongResponsePacket.class,
        ClientboundTestInstanceBlockStatus.class,
        ClientboundTrackedWaypointPacket.class,
        ClientboundDebugChunkValuePacket.class,
        ClientboundDebugBlockValuePacket.class,
        ClientboundDebugEntityValuePacket.class,
        ClientboundDebugEventPacket.class,
        ClientboundGameRuleValuesPacket.class,
        ClientboundLowDiskSpaceWarningPacket.class,
        ClientboundServerDataPacket.class
    );

    /** HUD-like packets that can be copied from a "HUD source" player into a camera recording. */
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

    public static boolean isIgnored(Packet<?> packet) {
        return IGNORED.contains(packet.getClass());
    }

    public static boolean isHudPacket(Packet<?> packet) {
        return HUD_PACKETS.contains(packet.getClass());
    }

    /**
     * Applies the same conversions the Flashback mod does when recording.
     * Signed player chat can't be replayed (signatures/session would be invalid), so it becomes system chat.
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
