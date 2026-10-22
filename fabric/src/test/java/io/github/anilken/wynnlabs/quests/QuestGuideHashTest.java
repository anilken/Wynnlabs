package io.github.anilken.wynnlabs.quests;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** The shipped guide's hash is the one the mod works out from the same page at run time. */
class QuestGuideHashTest {

    @Test
    void shippedHashMatchesTheLiveParse() throws Exception {
        String page = Files.readString(Path.of(getClass().getResource("/quests/Jungle Fever.txt").toURI()),
                StandardCharsets.UTF_8);
        String live = QuestWiki.stageHash(QuestWiki.parse(page).stages());
        String shipped = null;
        try (var in = getClass().getResourceAsStream("/assets/wynnlabs/quests/en.json")) {
            assertNotNull(in);
            for (JsonElement e : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray()) {
                if (e.getAsJsonObject().get("id").getAsString().equals("Jungle Fever")) {
                    shipped = e.getAsJsonObject().get("hash").getAsString();
                }
            }
        }
        assertEquals(shipped, live);
        // and an edit on the wiki changes it
        assertNotEquals(live, QuestWiki.stageHash(QuestWiki.parse(page.replace("Worid", "Woird")).stages()));
    }
}
