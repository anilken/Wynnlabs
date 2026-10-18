package io.github.anilken.wynnlabs.model;

/**
 * The kinds of entries in the content book (quests, discoveries, dungeons …), by the names the
 * mod saves in quests.json and passes back to start tracking one.
 */
public enum QuestKind {
    RECOMMENDED, QUEST, STORYLINE_QUEST, MINI_QUEST, WORLD_EVENT, SECRET_DISCOVERY, WORLD_DISCOVERY,
    TERRITORIAL_DISCOVERY, CAVE, DUNGEON, RAID, BOSS_ALTAR, LOOTRUN_CAMP, MOUNT_ENCLOSURE
}
