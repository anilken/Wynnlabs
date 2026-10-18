package io.github.anilken.wynnlabs;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Made-up personal values go in; none comes out, and the fake ids agree across files. */
class SanitiserTest {

    private static final String ACTIVE = "5ab3c9d1";
    private static final String OTHER = "7e4f20aa-1b2c-4d5e-8f90-123456789abc";
    private static final String PLAYER = "Zorbleflex";
    private static final String PET = "Snuffle Wump";

    @Test
    void noPersonalValueSurvives(@TempDir Path tmp) throws Exception {
        TestGame.boot();
        Fixture.Data data = SyntheticData.data(ACTIVE, OTHER, PLAYER, PET);
        Sanitiser clean = new Sanitiser(List.of(ACTIVE, OTHER), Map.of(PLAYER, "Player", PET, "Pet"));
        Fixture.write(data, tmp, clean);

        List<Path> files;
        try (Stream<Path> all = Files.walk(tmp)) {
            files = all.filter(Files::isRegularFile).toList();
        }
        for (Path f : files) {
            String text = (tmp.relativize(f) + "\n" + Files.readString(f, StandardCharsets.UTF_8)).toLowerCase(Locale.ROOT);
            for (String v : List.of(ACTIVE, OTHER, OTHER.substring(0, 8), PLAYER, PET)) {
                assertFalse(text.contains(v.toLowerCase(Locale.ROOT)), tmp.relativize(f) + " still holds a personal value");
            }
        }
        assertEquals(2, clean.idCount());
        assertTrue(clean.nameCount() > 0);

        // the active character comes first in state.json: char0001; the other is char0002 everywhere
        JsonObject state = JsonParser.parseString(Files.readString(tmp.resolve("state.json"))).getAsJsonObject();
        assertEquals("char0001", state.get("characterId").getAsString());
        String other = "char0002-0000-0000-0000-000000000000";
        assertEquals(other, state.getAsJsonArray("characterIds").get(0).getAsString());
        assertTrue(state.getAsJsonObject("today").has(other));
        assertTrue(Files.exists(tmp.resolve("rules").resolve("char0001.json")));
        assertTrue(Files.exists(tmp.resolve("rules").resolve(other + ".json")));
        String items = Files.readString(tmp.resolve("items.json"));
        assertTrue(items.contains("\"char:" + other + ":Character Bank p. 2\""), "bank key uses the same fake id");
        assertTrue(items.contains("\"Pet Fur\""));
    }

    @Test
    void textRules() {
        Sanitiser s = new Sanitiser(List.of("abcd1234"), Map.of("Fox", "Pet"));
        assertEquals("char:char0001:Bank p. 1", s.text("char:ABCD1234:Bank p. 1"));
        assertEquals("char0002-0000-0000-0000-000000000000", s.text("0f0f0f0f-1111-2222-3333-444444444444"));
        // a name only as a whole word
        assertEquals("Pet's Foxglove Hood", s.text("Fox's Foxglove Hood"));
    }
}
