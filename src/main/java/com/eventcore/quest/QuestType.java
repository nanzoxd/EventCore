package com.eventcore.quest;

import java.util.Locale;

/** What a quest counts. Every type is fed by one listener in {@link QuestListener}. */
public enum QuestType {
    KILL_PLAYER, KILL_ENTITY, DAMAGE_PLAYER, WIN_PVP, MINE_BLOCK, SMELT, COLLECT, CRAFT, TRAVEL,
    EXPLODE_TNT, OPEN_CHEST, BREED, FISH, TRADE, PLACE_BLOCK, BREAK_BLOCK;

    public static QuestType of(String s) {
        try { return valueOf(s.trim().toUpperCase(Locale.ROOT)); } catch (Exception e) { return null; }
    }
}
