package io.github.anilken.wynnlabs.quests;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Stage task lines from real wiki pages, as they appear on pins and in the quest panel. */
class QuestWikiCleanTest {

    @Test
    void linkAroundAColouredItem() {
        assertEquals("» Bring [1 Bob's Tear] from Ragni",
                QuestWiki.clean("» Bring [[Bob's Tear|<span style=\"color:#009999\">[1 Bob's Tear]</span>]] from [[Ragni]]"));
    }

    @Test
    void linkAroundSeveralItems() {
        assertEquals("» Bring [1 Depressing Stick], [1 Depressing Bow] to the Blacksmith.",
                QuestWiki.clean("» Bring [[Depressing Items#Depressing Weapons|<span style=\"color:#009999\">[1 Depressing Stick]</span>, "
                        + "<span style=\"color:#009999\">[1 Depressing Bow]</span>]] to the [[The Blacksmith|Blacksmith]]."));
    }
}
