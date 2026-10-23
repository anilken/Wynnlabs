package io.github.anilken.wynnlabs.search;

import io.github.anilken.wynnlabs.quests.Discoveries;
import io.github.anilken.wynnlabs.quests.QuestGuides;
import io.github.anilken.wynnlabs.quests.QuestWiki;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The search's quests, NPCs and secret discoveries, from the mod's shipped guides: every quest
 * of the quest guides with the NPC its first stage names, the NPCs the stages send the player to
 * ("Talk to Aledar in Lutho at [977, 75, -732]") with their places and quests, and every
 * discovery with its level, region and place. What the player has done is looked up when the
 * card is drawn, not here. Pure apart from reading the shipped files.
 */
final class StoryThings {

    /** "Talk to the Almuj Bank Guard", "Speak to Zuett": the capitalised words after it are the NPC. */
    private static final Pattern TALK = Pattern.compile(
            "(?:^|[.!] )(?:Talk|Speak) (?:to|with) (?:the )?([A-Z][\\p{L}'’.-]*(?: (?:of |the |de )?[A-Z][\\p{L}'’.-]*){0,3})");
    /** Words that end a place's name rather than a person's. */
    private static final Pattern PLACE_WORD = Pattern.compile(
            "(?i).*\\b(town|village|city|camp|house|castle|ranch|inn|bank|tower|temple|church|mines?|forest|island)$");
    private static final Pattern COORDS = Pattern.compile("\\[\\s*-?\\d+\\s*,\\s*-?\\d+\\s*,\\s*-?\\d+\\s*]");
    /** Two places of one NPC closer than this are one place. */
    static final int SAME_PLACE = 8;

    private StoryThings() {}

    /** Every shipped quest: type "quest", level 0 (the quest book knows it), its start NPC with its place when given. */
    static List<Entry> quests(Collection<QuestGuides.Guide> guides) {
        List<Entry> out = new ArrayList<>();
        for (QuestGuides.Guide g : sorted(guides)) {
            List<Entry.Origin> links = new ArrayList<>();
            if (!g.english().isEmpty()) {
                QuestWiki.Stage first = g.english().get(0);
                String npc = npc(first.task());
                List<Area> at = first.at() == null ? List.of() : List.of(Area.point(first.at()[0], first.at()[1], first.at()[2]));
                if (npc != null || !at.isEmpty()) {
                    links.add(new Entry.Origin(Entry.NPC, npc, at));
                }
            }
            out.add(Entry.thing(g.id(), Kind.QUEST, "quest", 0, links));
        }
        return out;
    }

    /** The NPCs the guides' stages name with a place: each with every place and every quest it is in. */
    static List<Entry> npcs(Collection<QuestGuides.Guide> guides) {
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, List<Area>> places = new LinkedHashMap<>();
        Map<String, List<String>> quests = new LinkedHashMap<>();
        for (QuestGuides.Guide g : sorted(guides)) {
            for (QuestWiki.Stage s : g.english()) {
                String npc = npc(s.task());
                if (npc == null || s.at() == null || !COORDS.matcher(s.task()).find()) {
                    continue;
                }
                String k = npc.toLowerCase(Locale.ROOT);
                names.putIfAbsent(k, npc);
                List<Area> at = places.computeIfAbsent(k, x -> new ArrayList<>());
                Area a = Area.point(s.at()[0], s.at()[1], s.at()[2]);
                if (at.stream().noneMatch(b -> Math.hypot(b.x() - a.x(), b.z() - a.z()) < SAME_PLACE)) {
                    at.add(a);
                }
                List<String> q = quests.computeIfAbsent(k, x -> new ArrayList<>());
                if (!q.contains(g.id())) {
                    q.add(g.id());
                }
            }
        }
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<String, String> n : names.entrySet()) {
            List<Entry.Origin> links = new ArrayList<>();
            links.add(new Entry.Origin(Entry.AT, null, List.copyOf(places.get(n.getKey()))));
            for (String q : quests.get(n.getKey())) {
                links.add(new Entry.Origin(Entry.QUEST, q, List.of()));
            }
            out.add(Entry.thing(n.getValue(), Kind.NPC, "npc", 0, links));
        }
        return out;
    }

    /** The NPC a task sends the player to ("Talk to the Almuj Bank Guard at …" → "Almuj Bank Guard"), or null. */
    static String npc(String task) {
        if (task == null) {
            return null;
        }
        Matcher m = TALK.matcher(task);
        if (!m.find()) {
            return null;
        }
        String name = m.group(1).replaceAll("[.'’-]+$", "").trim();
        return name.length() < 3 || name.length() > 32 || PLACE_WORD.matcher(name).matches() ? null : name;
    }

    /** Every discovery: its minimum level, its region in words, its place and its id (for its tab). */
    static List<Entry> discoveries(List<Discoveries.Entry> all) {
        List<Entry> out = new ArrayList<>();
        for (Discoveries.Entry d : all) {
            List<Entry.Origin> links = new ArrayList<>();
            if (d.x() != 0 || d.z() != 0) {
                links.add(new Entry.Origin(Entry.AT, d.region(), List.of(Area.point(d.x(), d.y(), d.z()))));
            }
            if (d.region() != null && !d.region().isBlank()) {
                links.add(new Entry.Origin(Entry.WHERE, d.region(), List.of()));
            }
            links.add(new Entry.Origin(Entry.ID, d.id(), List.of()));
            out.add(Entry.thing(d.name(), Kind.DISCOVERY, "discovery", Math.max(0, d.minLevel()), links));
        }
        return out;
    }

    private static List<QuestGuides.Guide> sorted(Collection<QuestGuides.Guide> guides) {
        return guides.stream().sorted(Comparator.comparing(QuestGuides.Guide::id)).toList();
    }
}
