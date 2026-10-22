package io.github.anilken.wynnlabs.quests;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Java step rules give the same steps as tools/discoveries/fetch.py, so the hashes match. */
class DiscoveryWikiTest {

    @Test
    void sameHashAsTheToolOnEveryFetchedPage() throws Exception {
        // the pages fetch.py read last (build/discoveries-wiki/); skipped when it has not run here
        Path cache = Path.of("..", "discoveries-wiki");
        if (!Files.isDirectory(cache)) {
            System.out.println("[fixtures] no build/discoveries-wiki: run tools/discoveries/fetch.py to compare every page");
            return;
        }
        List<String> differ = new ArrayList<>();
        int n = 0;
        for (JsonElement e : shipped()) {
            String id = e.getAsJsonObject().get("id").getAsString();
            Path f = cache.resolve(id.replaceAll("[^A-Za-z0-9 ._'-]", "_") + ".txt");
            if (!Files.exists(f)) {
                continue;
            }
            n++;
            String hash = DiscoveryWiki.hash(DiscoveryWiki.steps(Files.readString(f, StandardCharsets.UTF_8)));
            if (!hash.equals(e.getAsJsonObject().get("hash").getAsString())) {
                differ.add(id);
            }
        }
        assertTrue(n > 0, "no cached page matched an entry");
        assertEquals(List.of(), differ, "pages whose Java steps differ from fetch.py's");
    }

    @Test
    void cleanKeepsLinkLabelsAndPlaces() {
        assertEquals("At -2206, 11, -1435 is a door with a handle shaped like a frog.",
                DiscoveryWiki.clean("At {{MapLink|x=-2206|y=11|z=-1435}} is a door with a handle shaped like a [[Frog|frog]]."));
        assertEquals("Left-click the bushes", DiscoveryWiki.clean("{{Input|lmb}} the bushes"));
    }

    @Test
    void dialogueBecomesAChoice() {
        String page = """
                == Access ==
                Speak with the NPC at {{MapLink|x=-2092|y=20|z=-1408}}.
                {{Collapse top|Click to reveal dialogue}}
                * '''Fia:''' Welcome!
                ** {{c|var(--color-wc-purple)|Know of any "good green liquid"?}}
                ** {{c|var(--color-wc-purple)|I lost the shovel...}}<ref group="note">This dialogue option appears when the shovel is lost.</ref>
                {{Collapse bottom}}
                """;
        List<DiscoveryWiki.Step> steps = DiscoveryWiki.steps(page);
        assertEquals(2, steps.size());
        assertEquals(-2092, steps.get(0).at()[0]);
        assertEquals("Choose \"Know of any \"good green liquid\"?\".", steps.get(1).text());
    }

    static com.google.gson.JsonArray shipped() throws Exception {
        try (var in = Objects.requireNonNull(DiscoveryWikiTest.class.getResourceAsStream("/assets/wynnlabs/discoveries/en.json"))) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
        }
    }
}
