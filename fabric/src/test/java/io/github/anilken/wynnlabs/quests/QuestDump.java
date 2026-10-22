package io.github.anilken.wynnlabs.quests;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Writes the shipped quest guides (assets/wynnlabs/quests/en.json) from the wiki pages that
 * tools/quests/fetch.py kept in build/quests-wiki/, cut into stages by the mod's own parser, so
 * the shipped stages and the ones read live follow one set of rules. Runs only when asked:
 *
 * <pre>gradle test --tests '*QuestDump*' -PdumpQuests</pre>
 *
 * Fixes by quest id from tools/quests/fixes.json are applied after the stages are hashed ("stages"
 * replaces the list, other fields replace theirs). Pages with no stages are listed at the end.
 */
class QuestDump {

    private static final Pattern QUEST_SUFFIX = Pattern.compile(" \\(Quest\\)$");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    @Test
    void dump() throws Exception {
        assumeTrue(Boolean.getBoolean("wynnlabs.dumpQuests"), "quest dump off");
        Path root = Path.of(System.getProperty("wynnlabs.projectDir"));
        Path cache = root.resolve("build/quests-wiki");
        JsonObject kinds = JsonParser.parseString(Files.readString(cache.resolve("_titles.json"))).getAsJsonObject();
        Path fixesFile = root.resolve("tools/quests/fixes.json");
        JsonObject fixes = Files.exists(fixesFile)
                ? JsonParser.parseString(Files.readString(fixesFile, StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
        JsonArray out = new JsonArray();
        List<String> empty = new ArrayList<>();
        for (String kind : List.of("quest", "mini")) {
            for (JsonElement t : kinds.getAsJsonArray(kind)) {
                String title = t.getAsString();
                Path page = cache.resolve(title.replaceAll("[^A-Za-z0-9 ._'-]", "_") + ".txt");
                if (!Files.exists(page)) {
                    empty.add(title + " (no page)");
                    continue;
                }
                QuestWiki.Info info = QuestWiki.parse(Files.readString(page, StandardCharsets.UTF_8));
                String id = QUEST_SUFFIX.matcher(title).replaceFirst("");
                JsonObject e = new JsonObject();
                e.addProperty("id", id);
                e.addProperty("wiki", "https://wynncraft.wiki.gg/wiki/" + title.replace(' ', '_'));
                e.addProperty("kind", kind);
                e.addProperty("hash", QuestWiki.stageHash(info.stages()));
                JsonArray stages = new JsonArray();
                for (QuestWiki.Stage st : info.stages()) {
                    stages.add(stage(st));
                }
                e.add("stages", stages);
                if (fixes.has(id)) {
                    for (var f : fixes.getAsJsonObject(id).entrySet()) {
                        e.add(f.getKey(), f.getValue());
                    }
                }
                if (e.getAsJsonArray("stages").isEmpty()) {
                    empty.add(id);
                    continue;
                }
                out.add(e);
            }
        }
        Path file = root.resolve("common/src/main/resources/assets/wynnlabs/quests/en.json");
        Files.createDirectories(file.getParent());
        // one quest per line: small, and a re-fetch shows up as a diff of the quests that changed
        List<String> lines = new ArrayList<>();
        out.forEach(e -> lines.add(GSON.toJson(e)));
        Files.writeString(file, "[\n" + String.join(",\n", lines) + "\n]\n", StandardCharsets.UTF_8);
        System.out.println("[fixtures] quests: " + out.size() + " guides written; no stages: " + empty);
    }

    static JsonObject stage(QuestWiki.Stage st) {
        JsonObject o = new JsonObject();
        o.addProperty("title", st.title());
        o.addProperty("task", st.task());
        JsonArray hints = new JsonArray();
        st.hints().forEach(hints::add);
        o.add("hints", hints);
        o.add("at", st.at() == null ? null : xyz(st.at()));
        JsonArray points = new JsonArray();
        st.points().forEach(p -> points.add(xyz(p)));
        o.add("points", points);
        JsonArray names = new JsonArray();
        st.names().forEach(names::add);
        o.add("names", names);
        return o;
    }

    private static JsonArray xyz(int[] p) {
        JsonArray a = new JsonArray();
        for (int v : p) {
            a.add(v);
        }
        return a;
    }
}
