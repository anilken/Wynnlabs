package io.github.anilken.wynnlabs.quests;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.anilken.wynnlabs.core.L;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The quest guides shipped with the mod (assets/wynnlabs/quests): every quest's stages in English
 * (en.json, cut by {@link QuestWiki#parse} from the wiki) and in Turkish (tr.json, the same stages
 * translated). Read once, when first needed; the Turkish file only while the mod speaks Turkish.
 */
public final class QuestGuides {

    /** A shipped guide: the stage hash of the page it was cut from, the English stages, and the translated ones (null: none). */
    public record Guide(String id, String hash, List<QuestWiki.Stage> english, List<QuestWiki.Stage> translated, String title) {}

    private static volatile Map<String, Guide> english;
    private static volatile Map<String, Guide> turkish;

    private QuestGuides() {}

    /** The shipped guide of a quest in the mod's language (translated stages only in Turkish), or null. */
    public static Guide find(String quest) {
        if (quest == null) {
            return null;
        }
        boolean tr = L.code().startsWith("tr");
        Map<String, Guide> m = tr ? turkish() : english();
        return m.get(quest);
    }

    /**
     * A quest's name as the player reads it: in Turkish the name the game shows with the
     * WynnChaYuan translation (tr.json's "name"), else the English one. Lookups stay English.
     */
    public static String title(String quest) {
        if (quest == null) {
            return "";
        }
        Guide g = L.code().startsWith("tr") ? find(quest) : null;
        return g != null && g.title() != null && !g.title().isBlank() ? g.title() : quest;
    }

    private static Map<String, Guide> english() {
        Map<String, Guide> m = english;
        if (m == null) {
            m = load(false);
            english = m;
        }
        return m;
    }

    private static Map<String, Guide> turkish() {
        Map<String, Guide> m = turkish;
        if (m == null) {
            m = load(true);
            turkish = m;
        }
        return m;
    }

    private static JsonArray read(String file) {
        try (InputStream in = QuestGuides.class.getResourceAsStream("/assets/wynnlabs/quests/" + file)) {
            if (in == null) {
                return null;
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
        } catch (Exception e) {
            System.out.println("[WynnLabs] could not read the shipped quest guides (" + file + "): " + e);
            return null;
        }
    }

    private static Map<String, Guide> load(boolean withTurkish) {
        Map<String, Guide> out = new HashMap<>();
        JsonArray en = read("en.json");
        if (en == null) {
            return out;
        }
        Map<String, JsonObject> tr = new HashMap<>();
        if (withTurkish) {
            JsonArray t = read("tr.json");
            if (t != null) {
                for (JsonElement e : t) {
                    tr.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
                }
            }
        }
        for (JsonElement el : en) {
            JsonObject e = el.getAsJsonObject();
            String id = e.get("id").getAsString();
            List<QuestWiki.Stage> stages = new ArrayList<>();
            for (JsonElement s : e.getAsJsonArray("stages")) {
                stages.add(stage(s.getAsJsonObject()));
            }
            List<QuestWiki.Stage> local = null;
            JsonObject t = tr.get(id);
            String title = t != null && t.has("name") ? t.get("name").getAsString() : null;
            if (t != null && t.getAsJsonArray("stages").size() == stages.size()) {
                local = new ArrayList<>();
                JsonArray ts = t.getAsJsonArray("stages");
                for (int i = 0; i < stages.size(); i++) {
                    local.add(translated(stages.get(i), ts.get(i).getAsJsonObject()));
                }
            }
            out.put(id, new Guide(id, e.get("hash").getAsString(), List.copyOf(stages), local == null ? null : List.copyOf(local), title));
        }
        return out;
    }

    static QuestWiki.Stage stage(JsonObject s) {
        List<String> hints = new ArrayList<>();
        // a wiki template's own lines ("{{RenderLocation", "}}") left in a guide are not hints
        s.getAsJsonArray("hints").forEach(h -> {
            String l = h.getAsString().trim();
            if (!l.startsWith("{{") && !l.equals("}}")) {
                hints.add(h.getAsString());
            }
        });
        List<int[]> points = new ArrayList<>();
        s.getAsJsonArray("points").forEach(p -> points.add(xyz(p.getAsJsonArray())));
        List<String> names = new ArrayList<>();
        s.getAsJsonArray("names").forEach(n -> names.add(n.getAsString()));
        JsonElement at = s.get("at");
        return new QuestWiki.Stage(s.get("title").getAsString(), s.get("task").getAsString(), List.copyOf(hints),
                at == null || at.isJsonNull() ? null : xyz(at.getAsJsonArray()), List.copyOf(points), List.copyOf(names));
    }

    /**
     * An English stage with the translated title, task and hints; its places stay. Each place of a
     * stage with several gets the translated task's start as its label, numbered, since the
     * English labels are cut from the English sentence.
     */
    private static QuestWiki.Stage translated(QuestWiki.Stage en, JsonObject t) {
        List<String> hints = new ArrayList<>();
        // the same template lines left out as in the English, so the lines still pair up
        List<String> th = new ArrayList<>();
        t.getAsJsonArray("hints").forEach(h -> {
            String l = h.getAsString().trim();
            if (!l.startsWith("{{") && !l.equals("}}")) {
                th.add(h.getAsString());
            }
        });
        for (int i = 0; i < en.hints().size(); i++) {
            hints.add(i < th.size() ? th.get(i) : en.hints().get(i));
        }
        String task = t.has("task") ? t.get("task").getAsString() : en.task();
        String title = t.has("title") ? t.get("title").getAsString() : en.title();
        // a place named after a line of the stage takes that line in Turkish; a place the wiki names
        // itself (a map template: "Moonsalt", "Voidroot") keeps its name, as names stay English
        List<String> enLines = new ArrayList<>();
        enLines.add(en.task());
        enLines.addAll(en.hints());
        List<String> trLines = new ArrayList<>();
        trLines.add(task);
        trLines.addAll(hints);
        List<String> names = new ArrayList<>();
        for (String name : en.names()) {
            String label = name;
            String key = name.replaceFirst("^»\\s*", "");     // a task line's place is cut from "» Talk to …"
            for (int j = 0; j < enLines.size(); j++) {
                if (!key.isBlank() && enLines.get(j).startsWith(key)) {
                    label = QuestWiki.placeText(trLines.get(j));
                    break;
                }
            }
            names.add(label);
        }
        return new QuestWiki.Stage(title, task, List.copyOf(hints), en.at(), en.points(), List.copyOf(names));
    }

    private static int[] xyz(JsonArray a) {
        return new int[] {a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()};
    }

    /** For tests: forget what was read. */
    static void reset() {
        english = null;
        turkish = null;
    }
}
