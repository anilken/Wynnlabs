package io.github.anilken.wynnlabs.search;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import io.github.anilken.wynnlabs.quests.QuestWiki;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every mob of the wiki's "Lists of mobs/…" pages (wynncraft.wiki.gg): name, level, the list it is
 * on (a region, a dungeon, a cave), where the table says it lives and what it drops. Read through
 * {@link QuestWiki#api} (same switch and User-Agent): the list pages fifty at a time, then the mob
 * pages they pull rows from ({@code {{:Reggie}}}), and kept as one file in the wiki folder for a
 * week. Offline or off, the kept file (any age) or nothing.
 */
public final class MobWiki {

    /** A mob the wiki lists; {@code level} 0 when the table gives none; {@code drops} item names. */
    public record Mob(String name, int level, String region, String where, List<String> drops) {}

    static final String FILE = "Lists of mobs.json";
    static final long WEEK = 7L * 86_400_000L;
    private static final String PREFIX = "Lists of mobs/";
    private static final Pattern TRANSCLUDE = Pattern.compile("\\{\\{:([^}|]+)}}");
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern CELL_ATTR = Pattern.compile("^[a-z-]+\\s*=\\s*\"[^\"]*\"\\s*\\|(?!\\|)\\s*(.*)$");
    /** A name's trailing note: "(Lv. 120)", "(Melee)", "(Phase 1)". */
    private static final Pattern TAIL = Pattern.compile("\\s*\\([^()]*\\)\\s*$");
    private static final Pattern FILE_LINK = Pattern.compile("\\[\\[(?:File|Image):[^\\]]*]]");
    /** A link's page: "[[Gunpowder]]", "[[Bandit's Set|the set]]" → the page's name. */
    private static final Pattern LINK = Pattern.compile("\\[\\[([^\\]|]+)(?:\\|[^\\]]*)?]]");
    private static final Pattern AMOUNT = Pattern.compile("^[\\d,\\-–x× ]+\\s+");
    private static final Gson GSON = new Gson();

    private MobWiki() {}

    // ---- the kept file ----

    /** The kept list (any age), or empty when there is none or it is unreadable. */
    static List<Mob> cached(Path dir) {
        Path f = dir.resolve(FILE);
        if (!Files.isRegularFile(f)) {
            return List.of();
        }
        try {
            List<Mob> out = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), new TypeToken<List<Mob>>() {}.getType());
            return out == null ? List.of() : List.copyOf(out);
        } catch (Exception e) {
            System.out.println("[WynnLabs] " + FILE + " unreadable: " + e);
            return List.of();
        }
    }

    /** Whether the kept list is under a week old. */
    static boolean fresh(Path dir) {
        try {
            Path f = dir.resolve(FILE);
            return Files.isRegularFile(f) && System.currentTimeMillis() - Files.getLastModifiedTime(f).toMillis() < WEEK;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The list for the index: the kept file when it is fresh, else (wiki switch on) a new read,
     * saved; when that fails, the kept file whatever its age. Blocking: the index thread calls it.
     */
    public static List<Mob> load() {
        Path dir = QuestWiki.cacheDir();
        if (fresh(dir) || !QuestWiki.on()) {
            return cached(dir);
        }
        try {
            List<Mob> mobs = fetch(q -> {
                try {
                    return QuestWiki.api(q);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            if (!mobs.isEmpty()) {
                Files.createDirectories(dir);
                Files.writeString(dir.resolve(FILE), GSON.toJson(mobs), StandardCharsets.UTF_8);
                return mobs;
            }
        } catch (Exception e) {
            System.out.println("[WynnLabs] wiki mob list not read: " + e);
        }
        return cached(dir);
    }

    // ---- reading the wiki ----

    /** Every list page, then the mob pages they pull rows from; {@code api} answers an api.php query. */
    static List<Mob> fetch(Function<String, String> api) {
        Map<String, String> lists = pages(api, "action=query&generator=allpages&gapprefix=" + enc(PREFIX)
                + "&gaplimit=50&prop=revisions&rvprop=content&rvslots=main&format=json");
        List<Mob> out = new ArrayList<>();
        Map<String, String> pulled = new LinkedHashMap<>();         // mob page → the list it is on
        for (Map.Entry<String, String> p : lists.entrySet()) {
            String region = p.getKey().startsWith(PREFIX) ? p.getKey().substring(PREFIX.length()) : p.getKey();
            out.addAll(parse(region, p.getValue()));
            for (String t : transclusions(p.getValue())) {
                pulled.putIfAbsent(t, region);
            }
        }
        List<String> titles = new ArrayList<>(pulled.keySet());
        for (int i = 0; i < titles.size(); i += 50) {
            List<String> batch = titles.subList(i, Math.min(titles.size(), i + 50));
            Map<String, String> got = pages(api, "action=query&prop=revisions&rvprop=content&rvslots=main&format=json&titles="
                    + enc(String.join("|", batch)));
            for (Map.Entry<String, String> g : got.entrySet()) {
                String region = pulled.getOrDefault(g.getKey(), "");
                out.addAll(parse(region, onlyInclude(g.getValue())));
            }
        }
        return merge(out);
    }

    /** The pages of one query, following its "continue": title → wikitext. */
    private static Map<String, String> pages(Function<String, String> api, String query) {
        Map<String, String> out = new LinkedHashMap<>();
        String more = "";
        for (int guard = 0; guard < 40; guard++) {
            JsonObject r = JsonParser.parseString(api.apply(query + more)).getAsJsonObject();
            if (r.has("query") && r.getAsJsonObject("query").has("pages")) {
                for (Map.Entry<String, JsonElement> e : r.getAsJsonObject("query").getAsJsonObject("pages").entrySet()) {
                    JsonObject p = e.getValue().getAsJsonObject();
                    if (p.has("revisions")) {
                        JsonObject main = p.getAsJsonArray("revisions").get(0).getAsJsonObject().getAsJsonObject("slots")
                                .getAsJsonObject("main");
                        out.put(p.get("title").getAsString(), main.get("*").getAsString());
                    }
                }
            }
            if (!r.has("continue")) {
                break;
            }
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, JsonElement> c : r.getAsJsonObject("continue").entrySet()) {
                b.append('&').append(c.getKey()).append('=').append(enc(c.getValue().getAsString()));
            }
            more = b.toString();
        }
        return out;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** The mob pages a list page pulls rows from ("{{:Reggie}}" → "Reggie"). */
    static List<String> transclusions(String wikitext) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = TRANSCLUDE.matcher(wikitext);
        while (m.find()) {
            out.add(m.group(1).trim());
        }
        return List.copyOf(out);
    }

    /** A mob page's table row: what lies between {@code <onlyinclude>} and its end, or the whole text. */
    static String onlyInclude(String wikitext) {
        int a = wikitext.indexOf("<onlyinclude>");
        int b = wikitext.indexOf("</onlyinclude>");
        return a >= 0 && b > a ? wikitext.substring(a + "<onlyinclude>".length(), b) : wikitext;
    }

    /**
     * The mob rows of a list table: nine cells each (picture, name, level, health, AI, abilities,
     * elements, drops, location); a line holding two rows is read as two.
     */
    public static List<Mob> parse(String region, String wikitext) {
        List<Mob> out = new ArrayList<>();
        for (String chunk : wikitext.split("\n\\|-")) {
            List<String> cells = new ArrayList<>();
            for (String line : chunk.split("\n")) {
                if (line.startsWith("|") && !line.startsWith("|-") && !line.startsWith("|}")) {
                    String c = line.substring(1).trim();
                    Matcher attr = CELL_ATTR.matcher(c);
                    cells.add(attr.matches() ? attr.group(1) : c);
                }
            }
            for (int at = 0; at + 9 <= cells.size(); at += 9) {
                Mob m = row(region, cells.subList(at, at + 9));
                if (m != null) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    private static Mob row(String region, List<String> c) {
        // the whole cell at once: a link's label may hold the <br> ("[[Silencer (Lv. 120)|Silencer <br> (Lv. 120)]]")
        String name = QuestWiki.clean(FILE_LINK.matcher(c.get(1)).replaceAll("").replaceAll("<br\\s*/?>", " "));
        name = TAIL.matcher(name).replaceAll("").trim();
        if (name.isEmpty() || name.equals("-") || name.equals("?")) {
            return null;
        }
        Matcher lv = NUMBER.matcher(c.get(2));
        int level = lv.find() ? Integer.parseInt(lv.group()) : 0;
        return new Mob(name, level, region, where(c.get(8)), drops(c.get(7)));
    }

    /**
     * A drops cell's items: every link's page ("[[Gunpowder]]") and every unlinked line
     * ("64-128 Emeralds" and pictures left out); a mob it turns into ("(Phase 2)") is no item.
     */
    static List<String> drops(String cell) {
        List<String> out = new ArrayList<>();
        String text = FILE_LINK.matcher(cell).replaceAll("");
        Matcher link = LINK.matcher(text);
        while (link.find()) {
            add(out, link.group(1));
        }
        for (String part : LINK.matcher(text).replaceAll("").split("<br\\s*/?>")) {
            add(out, QuestWiki.clean(part));
        }
        return List.copyOf(out);
    }

    private static void add(List<String> drops, String raw) {
        String d = AMOUNT.matcher(raw.trim()).replaceFirst("").trim();
        String low = d.toLowerCase(Locale.ROOT);
        if (d.chars().anyMatch(Character::isLetter) && !d.contains("(") && !d.contains("#") && !low.startsWith("emerald") && !drops.contains(d)) {
            drops.add(d);
        }
    }

    private static String where(String cell) {
        String w = QuestWiki.clean(FILE_LINK.matcher(cell).replaceAll("").replaceAll("<br\\s*/?>", " ")).replaceAll("\\s+,", ",").trim();
        return w.equals("-") || w.equals("?") ? "" : w;
    }

    /** One entry per name (any case): the first level found, every place and drop kept. */
    static List<Mob> merge(List<Mob> mobs) {
        Map<String, Mob> out = new LinkedHashMap<>();
        for (Mob m : mobs) {
            String k = m.name().toLowerCase(Locale.ROOT);
            Mob had = out.get(k);
            if (had == null) {
                out.put(k, m);
                continue;
            }
            List<String> drops = new ArrayList<>(had.drops());
            m.drops().stream().filter(d -> !drops.contains(d)).forEach(drops::add);
            String where = had.where().isEmpty() ? m.where()
                    : m.where().isEmpty() || had.where().contains(m.where()) ? had.where() : had.where() + "; " + m.where();
            out.put(k, new Mob(had.name(), had.level() > 0 ? had.level() : m.level(), had.region(), where, List.copyOf(drops)));
        }
        return List.copyOf(out.values());
    }
}
