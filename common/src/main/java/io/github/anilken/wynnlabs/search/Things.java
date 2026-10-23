package io.github.anilken.wynnlabs.search;

import io.github.anilken.wynnlabs.quests.Discoveries;
import io.github.anilken.wynnlabs.quests.QuestGuides;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The world's things the search lists beside the items: mobs, places, content (dungeons, raids,
 * caves, ...), services, gathering resources, quests, NPCs and secret discoveries, read by
 * {@link MobThings}, {@link WorldThings} and {@link StoryThings}. Here also what the tab needs to
 * show one: how easy it is (the easiest-first sort), its source type and badge, the rows of its
 * card, and the thing an item's source row names (the card's cross-links). Pure.
 */
public final class Things {

    /** Places of a service the card lists, nearest first. */
    public static final int SERVICE_ROWS = 8;

    private Things() {}

    /** Every thing, from the cache folder, the items already read, the {@link Places} and the wiki's mobs (may be empty). */
    static List<Entry> read(Path cacheDir, List<Entry> items, Places places, List<MobWiki.Mob> wiki) {
        List<Entry> out = new ArrayList<>(MobThings.of(items, wiki));
        List<Entry> towns = WorldThings.places(cacheDir);
        out.addAll(towns);
        out.addAll(WorldThings.content(cacheDir, items, wiki));
        out.addAll(WorldThings.services(cacheDir, towns));
        out.addAll(WorldThings.resources(places, items));
        var guides = QuestGuides.allEnglish();
        out.addAll(StoryThings.quests(guides));
        // "Talk to Cinfras' guards" names a town, not a person
        java.util.Set<String> placeNames = new java.util.HashSet<>();
        out.stream().filter(e -> e.kind() == Kind.PLACE || e.kind() == Kind.CONTENT)
                .forEach(e -> placeNames.add(e.name().toLowerCase(java.util.Locale.ROOT)));
        StoryThings.npcs(guides).stream().filter(n -> !placeNames.contains(n.name().toLowerCase(java.util.Locale.ROOT))).forEach(out::add);
        out.addAll(StoryThings.discoveries(Discoveries.english()));
        return out;
    }

    /**
     * How easy a thing is, for the easiest-first sort (lower first, like {@link SourceRank#effort}):
     * a mob the item data knows comes before one only the wiki lists.
     */
    public static int ease(Entry e) {
        return switch (e.kind()) {
            case MOB -> MobThings.WIKI.equals(e.type()) ? 4 : 1;
            case PLACE, SERVICE -> 1;
            case CONTENT, RESOURCE, QUEST -> 2;
            default -> 3;
        };
    }

    /** The source type a thing is shown as (its badge, its card row). */
    public static Source.Type type(Entry e) {
        return switch (e.kind()) {
            case MOB -> MobThings.MINIBOSS.equals(e.type()) ? Source.Type.MINIBOSS : Source.Type.MOB;
            case CONTENT -> contentType(e.type());
            case SERVICE -> Source.Type.MERCHANT;
            case RESOURCE -> Source.Type.GATHERING;
            case QUEST -> Source.Type.QUEST;
            default -> Source.Type.OTHER;
        };
    }

    /** "dungeon" → DUNGEON, "boss-altar" → ALTAR, ...: a content type as a source type. */
    static Source.Type contentType(String type) {
        if (type == null) {
            return Source.Type.OTHER;
        }
        return switch (type) {
            case "dungeon", "corrupted-dungeon" -> Source.Type.DUNGEON;
            case "raid" -> Source.Type.RAID;
            case "cave" -> Source.Type.CAVE;
            case "boss-altar" -> Source.Type.ALTAR;
            case "grind-spot" -> Source.Type.MOB_BAND;
            case "lootrun-camp" -> Source.Type.LOOTRUN;
            case "time-trial", "shrine" -> Source.Type.CHALLENGE;
            default -> Source.Type.OTHER;
        };
    }

    /** A thing's badge: its source type's, or a place's house, an NPC's figure, a discovery's compass. */
    public static String badge(Entry e) {
        return switch (e.kind()) {
            case PLACE -> "home";
            case NPC -> "user";
            case DISCOVERY -> "compass";
            default -> SourceRank.badge(type(e));
        };
    }

    /**
     * The rows of a thing's card, each a {@link Source} the item card's rows are drawn from: a
     * mob's spawn areas in one row (a row without a place for a wiki mob, so it can still be
     * tracked), a service's {@link #SERVICE_ROWS} nearest places one per row, a resource's nodes
     * spaced out, else the thing's own place. {@code pos} is the player's {x, z} or null.
     */
    public static List<Source> rows(Entry e, double[] pos) {
        Source.Type t = type(e);
        String badge = badge(e);
        List<Area> places = e.places();
        List<Source> out = new ArrayList<>();
        switch (e.kind()) {
            case SERVICE -> {
                for (Area a : SourceRank.nearest(places, pos, SERVICE_ROWS)) {
                    out.add(row(t, a.name() == null ? e.name() : a.name(), List.of(a), badge));
                }
            }
            case RESOURCE -> out.add(row(t, e.name(), Sources.spaced(SourceRank.nearest(places, pos, Integer.MAX_VALUE)), badge));
            case QUEST -> {
                for (Entry.Origin o : e.links(Entry.NPC)) {
                    out.add(row(t, o.name() == null ? e.name() : o.name(), o.areas(), badge));
                }
            }
            default -> {
                if (!places.isEmpty() || e.kind() == Kind.MOB) {
                    out.add(row(t, e.name(), SourceRank.nearest(places, pos, places.size()), badge));
                }
            }
        }
        return out;
    }

    private static Source row(Source.Type t, String name, List<Area> areas, String badge) {
        return new Source(t, name, areas, SourceRank.effort(t), badge, 0, null, 0, 0, null);
    }

    /**
     * The thing an item's source row names, for a link to its card: the mob of a mob or miniboss
     * row, the town of a merchant, the dungeon, raid, cave or altar of those rows, the quest of a
     * quest row, the resource of a gathering row; null when the index has none by that name.
     */
    public static Entry of(ItemIndex index, Source.Type type, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Kind[] order = switch (type) {
            case MOB, MINIBOSS -> new Kind[] {Kind.MOB};
            case MERCHANT -> new Kind[] {Kind.PLACE, Kind.CONTENT, Kind.NPC};
            case DUNGEON, DUNGEON_MERCHANT, RAID, CAVE, ALTAR, LOOTRUN, CHALLENGE, WORLD_EVENT -> new Kind[] {Kind.CONTENT, Kind.PLACE};
            case QUEST -> new Kind[] {Kind.QUEST};
            case GATHERING -> new Kind[] {Kind.RESOURCE};
            case BAG, BANK, MARKET, MOB_BAND, LOOT_CHEST, UNKNOWN, UNAVAILABLE -> new Kind[0];
            default -> new Kind[] {Kind.CONTENT, Kind.PLACE, Kind.NPC};
        };
        for (Kind k : order) {
            Entry e = index.byKey(k.name().toLowerCase(java.util.Locale.ROOT) + ":" + MobThings.clean(name));
            if (e != null) {
                return e;
            }
        }
        return null;
    }
}
