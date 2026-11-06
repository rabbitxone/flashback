package com.moulberry.flashback.serverrecorder.recording;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Payload of the flashback:action/create_local_player action. */
record LocalPlayerData(UUID uuid, double x, double y, double z, float xRot, float yRot, float yHeadRot,
                       Vec3 velocity, GameProfile profile, GameType gameType) {

    static LocalPlayerData of(ServerPlayer player, GameType gameType) {
        return new LocalPlayerData(player.getUUID(), player.getX(), player.getY(), player.getZ(),
            player.getXRot(), player.getYRot(), player.getYHeadRot(), player.getDeltaMovement(),
            player.getGameProfile(), gameType);
    }

    void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(this.uuid);
        buf.writeDouble(this.x);
        buf.writeDouble(this.y);
        buf.writeDouble(this.z);
        buf.writeFloat(this.xRot);
        buf.writeFloat(this.yRot);
        buf.writeFloat(this.yHeadRot);
        buf.writeDouble(this.velocity.x);
        buf.writeDouble(this.velocity.y);
        buf.writeDouble(this.velocity.z);
        ByteBufCodecs.GAME_PROFILE.encode(buf, this.profile);
        buf.writeVarInt(this.gameType.getId());
    }

}
