package io.github.anilken.wynnlabs.search;

import io.github.anilken.wynnlabs.quests.QuestWiki;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What an item's wiki page (wynncraft.wiki.gg) adds to Wynntils' data: how it is obtained, the
 * merchants that sell it and for what, the identify cost, the mob level band of a normal drop and
 * the festival it belongs to. Read through {@link QuestWiki#page} (same switch, week-long disk
 * cache and User-Agent), in the background, once per item a session; offline, missing or off
 * gives {@link Info#EMPTY}, never an error.
 */
public final class ItemWiki {

    /** One way to buy the item: "Fallen Factory Dungeon Merchant" for "4 Antikythera Supercomputer's Chips". */
    public record Cost(String merchant, String price) {}

    /**
     * An item page's facts. {@code obtain}: the infobox's obtain line, or the first line of the
     * "Obtaining" section; {@code identifyCost}: emeralds, 0 when unknown or pre-identified;
     * {@code mobMin}/{@code mobMax}: the level band of mobs that drop it, 0 when none is given;
     * {@code event}: the festival ("Festival of the Heroes") or ""; {@code worldEvents}: world
     * events that reward it.
     */
    public record Info(String obtain, List<Cost> costs, int identifyCost, boolean preIdentified,
            int mobMin, int mobMax, String event, List<String> worldEvents) {

        public static final Info EMPTY = new Info("", List.of(), 0, false, 0, 0, "", List.of());

        public boolean empty() {
            return obtain.isEmpty() && costs.isEmpty() && identifyCost == 0 && !preIdentified && mobMin == 0
                    && event.isEmpty() && worldEvents.isEmpty();
        }
    }

    private static final Map<String, CompletableFuture<Info>> CACHE = new ConcurrentHashMap<>();
    private static final CompletableFuture<Info> NONE = CompletableFuture.completedFuture(Info.EMPTY);

    private static final Pattern OBTAIN = Pattern.compile("(?m)^\\|\\s*obtain\\s*=\\s*(.*)$");
    private static final Pattern SOLD = Pattern.compile(
            "(?:purchased|bought) from (?:the )?([^.]+?)(?: year-round)? for (\\d.*?)(?:, and is pre-identified\\.?|\\.(?:\\s|$)|$)");
    private static final Pattern ID_COST = Pattern.compile("It costs ([\\d,]+) Emeralds to identify");
    private static final Pattern MOBS = Pattern.compile("(?i)mobs of level (\\d+) ?(?:to|-|–) ?(\\d+)");
    private static final Pattern FESTIVAL_CATEGORY = Pattern.compile("\\[\\[Category:([^\\]]+?) \\d{4} Items]]");
    private static final Pattern FESTIVAL_LINK = Pattern.compile("\\[\\[(Festival of the [^\\]|]+)");
    private static final Pattern LIST_LINK = Pattern.compile("<li>\\s*\\[\\[(?:[^|\\]]*\\|)?([^\\]]+)]]");

    private ItemWiki() {}

    /**
     * The item's page facts, loaded once per session in the background: an already finished
     * future when the wiki switch is off or the name is blank. Never completes exceptionally.
     */
    public static CompletableFuture<Info> load(String item) {
        return load(item, QuestWiki.on(), ItemWiki::read);
    }

    /** The facts when they are already loaded, else null (and nothing is started). Cheap enough per frame. */
    public static Info peek(String item) {
        if (item == null || !QuestWiki.on()) {
            return Info.EMPTY;
        }
        CompletableFuture<Info> f = CACHE.get(item);
        return f == null ? null : f.getNow(null);
    }

    /** The item's page in the browser. */
    public static String url(String item) {
        return "https://wynncraft.wiki.gg/wiki/" + URLEncoder.encode(item.replace(' ', '_'), StandardCharsets.UTF_8);
    }

    static CompletableFuture<Info> load(String item, boolean on, Function<String, String> pages) {
        if (!on || item == null || item.isBlank()) {
            return NONE;
        }
        // a failure stays EMPTY for the session: the card never waits on the wiki twice
        return CACHE.computeIfAbsent(item, k -> CompletableFuture.supplyAsync(() -> {
            try {
                String text = pages.apply(k);
                return text == null ? Info.EMPTY : parse(k, text);
            } catch (RuntimeException e) {
                return Info.EMPTY;
            }
        }));
    }

    private static String read(String item) {
        try {
            return QuestWiki.page(item);
        } catch (Exception e) {
            return null;                                // offline, timeout, odd reply
        }
    }

    /** An item page's wikitext → its facts; anything the page does not say stays empty. */
    public static Info parse(String item, String wikitext) {
        String text = wikitext.replace("{{PAGENAME}}", item);
        String obtain = "";
        Matcher o = OBTAIN.matcher(text);
        if (o.find()) {
            obtain = QuestWiki.clean(o.group(1));
        }
        Set<Cost> costs = new LinkedHashSet<>();
        int idCost = 0;
        boolean preset = false;
        int mobMin = 0;
        int mobMax = 0;
        boolean obtaining = false;
        String obtainingLine = "";
        for (String raw : text.split("\n")) {
            if (raw.matches("\\s*==+.*==+\\s*")) {
                obtaining = raw.toLowerCase().contains("obtain");
                continue;
            }
            String line = QuestWiki.clean(idCost(item, raw));
            if (line.isEmpty() || raw.trim().startsWith("|")) {
                continue;
            }
            if (obtaining && obtainingLine.isEmpty()) {
                obtainingLine = line;
            }
            Matcher s = SOLD.matcher(line);
            while (s.find()) {
                costs.add(new Cost(s.group(1).trim(), s.group(2).trim()));
            }
            Matcher c = ID_COST.matcher(line);
            if (c.find()) {
                idCost = Integer.parseInt(c.group(1).replace(",", ""));
            }
            if (line.contains("is pre-identified") || line.contains("is identified when bought")) {
                preset = true;
            }
            Matcher m = MOBS.matcher(line);
            if (m.find() && mobMin == 0) {
                mobMin = Integer.parseInt(m.group(1));
                mobMax = Integer.parseInt(m.group(2));
            }
        }
        if (obtain.isEmpty()) {
            obtain = obtainingLine;
        }
        return new Info(obtain, List.copyOf(costs), idCost, preset, mobMin, mobMax, festival(text), worldEvents(text));
    }

    private static String festival(String text) {
        Matcher m = FESTIVAL_CATEGORY.matcher(text);
        while (m.find()) {
            if (m.group(1).startsWith("Festival")) {
                return m.group(1);
            }
        }
        Matcher l = FESTIVAL_LINK.matcher(text);
        return l.find() ? l.group(1).trim() : "";
    }

    /** The bullet links after a "World Event" line ("<li>[[Encroaching Conflagration]]</li>"). */
    private static List<String> worldEvents(String text) {
        int at = text.indexOf("World Event");
        if (at < 0) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        int end = text.indexOf("</ul>", at);
        Matcher m = LIST_LINK.matcher(end < 0 ? text.substring(at) : text.substring(at, end));
        while (m.find()) {
            out.add(m.group(1).trim());
        }
        return List.copyOf(out);
    }

    /**
     * A line with the wiki's {@code {{IDcost|…}}} template replaced by the sentence it draws
     * ("It costs 101 Emeralds to identify Papyrus.", "… can be bought from X for Y, and is
     * pre-identified."), following Template:IDcost's rarity formulas.
     */
    static String idCost(String item, String line) {
        int at = line.indexOf("{{IDcost|");
        if (at < 0) {
            return line;
        }
        int end = closing(line, at);
        if (end < 0) {
            return line;
        }
        List<String> a = args(line.substring(at + 2, end - 2));
        String kind = a.size() > 1 ? a.get(1).trim().toLowerCase() : "";
        String two = a.size() > 2 ? a.get(2).trim() : "";
        int level = two.matches("\\d+") ? Integer.parseInt(two) - 1 : -1;
        String sentence = switch (kind) {
            case "u", "unique" -> cost(4 + level / 2, level, item);
            case "r", "rare", "s", "set" -> cost(10 + 12 * level / 10, level, item);
            case "l", "legendary" -> cost(17 + 42 * level / 10, level, item);
            case "f", "fabled" -> cost(24 + 8 * level, level, item);
            case "m", "mythic" -> cost(108 + 18 * level, level, item);
            case "c", "cost" -> two.matches("[\\d,]+") ? "It costs " + two + " Emeralds to identify " + item + "." : "";
            case "p", "preset" -> item + " can be bought from the " + two + " for " + (a.size() > 3 ? a.get(3).trim() : "")
                    + ", and is pre-identified.";
            default -> item + " is identified when bought.";
        };
        return line.substring(0, at) + sentence + line.substring(end);
    }

    private static String cost(int emeralds, int level, String item) {
        return level < 0 ? "" : "It costs " + emeralds + " Emeralds to identify " + item + ".";
    }

    /** The index just past the {@code }}} that closes the template opening at {@code at}, or -1. */
    private static int closing(String s, int at) {
        int depth = 0;
        for (int i = at; i < s.length() - 1; i++) {
            if (s.startsWith("{{", i)) {
                depth++;
                i++;
            } else if (s.startsWith("}}", i)) {
                depth--;
                i++;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }
        return -1;
    }

    /** A template's body split on the {@code |} that are not inside a nested link or template. */
    private static List<String> args(String body) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int from = 0;
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == '{' || ch == '[') {
                depth++;
            } else if (ch == '}' || ch == ']') {
                depth--;
            } else if (ch == '|' && depth == 0) {
                out.add(body.substring(from, i));
                from = i + 1;
            }
        }
        out.add(body.substring(from));
        return out;
    }
}
