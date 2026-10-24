package io.github.anilken.wynnlabs.ui;

import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The archetype list: a line for every archetype in both languages, and which one it marks Suggested. */
class ArchetypePickerTest {

    private static final List<String> ALL = List.of("Fallen", "Battle Monk", "Paladin", "Riftwalker", "Light Bender",
            "Arcanist", "Boltslinger", "Sharpshooter", "Trapper", "Shadestepper", "Trickster", "Acrobat",
            "Summoner", "Ritualist", "Acolyte");

    @AfterEach
    void reset() {
        Settings.use(new JsonObject());
    }

    @Test
    void everyArchetypeHasALineInBothLanguages() {
        for (int pick : new int[] {1, 2}) {
            JsonObject s = new JsonObject();
            s.addProperty("langOverride", pick);
            Settings.use(s);
            for (String a : ALL) {
                String key = ArchetypePicker.descKey(a);
                assertNotEquals(key, L.t(key), a + " in language " + pick);
            }
        }
        assertEquals("wynnlabs.arch.desc.battlemonk", ArchetypePicker.descKey("Battle Monk"));
        assertEquals("wynnlabs.arch.desc.lightbender", ArchetypePicker.descKey("Lightbender"));
    }

    @Test
    void suggestsTheArchetypeYouHaveTheMostNodesIn() {
        List<String> mage = List.of("Riftwalker", "Light Bender", "Arcanist");
        assertEquals("Arcanist", ArchetypePicker.suggested(mage, "Arcanist", 7));
        // the tree's first with no node of it is no lean: Automatic instead
        assertEquals("", ArchetypePicker.suggested(mage, "Riftwalker", 0));
        assertEquals("", ArchetypePicker.suggested(mage, "", 0));
        assertNull(ArchetypePicker.suggested(List.of(), "", 0), "tree not read: nothing to suggest");
    }
}
