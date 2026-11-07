package com.moulberry.flashback.serverrecorder.format;

import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Constants describing the Flashback replay format.
 * <p>
 * These must stay in sync with the Flashback mod ({@code com.moulberry.flashback.Flashback#MAGIC}
 * and the actions registered in {@code Flashback#onInitializeClient}).
 */
public final class FlashbackFormat {

    public static final int MAGIC = 0xD780E884;

    /** Size of a single level_chunk_caches/N file, must match ReplayChunkCache.CHUNK_CACHE_SIZE. */
    public static final int CHUNK_CACHE_SIZE = 10000;

    public static final Identifier ACTION_NEXT_TICK = action("next_tick");
    public static final Identifier ACTION_GAME_PACKET = action("game_packet");
    public static final Identifier ACTION_CONFIGURATION_PACKET = action("configuration_packet");
    public static final Identifier ACTION_CREATE_LOCAL_PLAYER = action("create_local_player");
    public static final Identifier ACTION_MOVE_ENTITIES = action("move_entities");
    public static final Identifier ACTION_LEVEL_CHUNK_CACHED = action("level_chunk_cached");
    public static final Identifier ACTION_REAL_TIME_CLOCK = action("real_time_clock_optional");
    /** Hotbar/food/xp of any player. Added in this fork of Flashback, older versions skip it ("optional"). */
    public static final Identifier ACTION_PLAYER_STATE = action("player_state_optional");

    public static final int PLAYER_STATE_HAS_SELECTED_SLOT = 1;
    public static final int PLAYER_STATE_HAS_FOOD = 2;
    public static final int PLAYER_STATE_HAS_EXPERIENCE = 4;
    public static final int PLAYER_STATE_HAS_SLOTS = 8;

    /** The action table written at the start of every replay chunk file (order defines the action ids). */
    public static final List<Identifier> ACTIONS = List.of(
        ACTION_NEXT_TICK,
        ACTION_GAME_PACKET,
        ACTION_CONFIGURATION_PACKET,
        ACTION_CREATE_LOCAL_PLAYER,
        ACTION_MOVE_ENTITIES,
        ACTION_LEVEL_CHUNK_CACHED,
        ACTION_REAL_TIME_CLOCK,
        ACTION_PLAYER_STATE
    );

    private static Identifier action(String name) {
        return Identifier.fromNamespaceAndPath("flashback", "action/" + name);
    }

    private FlashbackFormat() {
    }

}
