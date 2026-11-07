package com.moulberry.flashback.action;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.playback.ReplayServer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;

/**
 * State of a player that normally only that player's own client knows (hotbar, selected slot, food, experience).
 * <p>
 * Written by server-side recorders, which know this for every player, so that spectating any player in the
 * replay shows their real hotbar. The name ends with "optional", so versions of Flashback that don't know
 * this action simply skip it.
 * <p>
 * Payload: varint entityId, byte flags, then depending on the flags:
 * <ul>
 *     <li>{@link #HAS_SELECTED_SLOT}: varint selectedSlot</li>
 *     <li>{@link #HAS_FOOD}: varint foodLevel, float saturation</li>
 *     <li>{@link #HAS_EXPERIENCE}: float progress, varint total, varint level</li>
 *     <li>{@link #HAS_SLOTS}: varint count, then count times (varint inventorySlot, optional ItemStack)</li>
 * </ul>
 */
public class ActionPlayerState implements Action {

    public static final int HAS_SELECTED_SLOT = 1;
    public static final int HAS_FOOD = 2;
    public static final int HAS_EXPERIENCE = 4;
    public static final int HAS_SLOTS = 8;

    private static final Identifier NAME = Flashback.createIdentifier("action/player_state_optional");
    public static final ActionPlayerState INSTANCE = new ActionPlayerState();
    private ActionPlayerState() {
    }

    @Override
    public Identifier name() {
        return NAME;
    }

    @Override
    public void handle(ReplayServer replayServer, RegistryFriendlyByteBuf friendlyByteBuf) {
        replayServer.handlePlayerState(friendlyByteBuf);
    }

}
