package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.AspectEntry;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.PoolAspect;
import io.github.anilken.wynnlabs.model.PoolItem;
import io.github.anilken.wynnlabs.model.PoolRegion;
import io.github.anilken.wynnlabs.model.Tier;
import io.github.anilken.wynnlabs.model.WeeklyPool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * The weekly pools as the Havuzlar view lists them (raid-lootrun-tracking, design decision 11):
 * region rows, groups and folded item rows. Pure: no Minecraft or Wynntils type.
 *
 * <p>Raids are grouped by kind (Aspects, Tomes, Gear, Misc; Shiny first when a raid has one),
 * camps by rarity (Shiny, Mythic, Fabled, Legendary, Rare, Unique, then Other). Inside a group,
 * rows follow rarity, then Wynnventory's order; in Aspects the class in play comes first.
 * Empty groups are left out.
 *
 * <p>API contract of group 8 (design.md): the signatures below stay as they are.
 */
final class PoolView {

    /**
     * One item row.
     *
     * @param item      the item (folded: the first entry)
     * @param count     the folded amount
     * @param ownClass  an aspect of the class in play
     * @param mark      as decision 10; NONE otherwise
     * @param tagTier   as decision 10; 0 otherwise
     * @param ownedTier the owned tier; -1 when unknown or not an own aspect
     * @param maxTier   the aspect's tier count; 0 when unknown
     */
    record Row(PoolItem item, int count, boolean ownClass, RaidPool.Mark mark, int tagTier, int ownedTier, int maxTier) {

        /** Whether an own aspect is owned at its top tier. */
        boolean maxed() {
            return maxTier > 0 && ownedTier >= maxTier;
        }
    }

    /**
     * One group under a header.
     *
     * @param kind the group
     * @param rows its rows, in drawing order
     */
    record Group(Kind kind, List<Row> rows) {

        /** SHINY, ASPECTS, TOMES, GEAR, MISC for raids (SHINY only if a raid has one); SHINY…UNIQUE and OTHER for camps. */
        enum Kind { SHINY, ASPECTS, TOMES, GEAR, MISC, MYTHIC, FABLED, LEGENDARY, RARE, UNIQUE, OTHER }
    }

    /**
     * One region row: a raid or a camp.
     *
     * @param name    the full name; "" for the pool seen in game
     * @param raid    a raid (else a camp)
     * @param seenAt  when the pool was seen, epoch milliseconds; -1 when unknown
     * @param marked  the class's NEW + TIER_UP aspects (raids)
     * @param mythics distinct non-shiny mythic gear (camps)
     * @param shinies shiny entries
     * @param items   folded rows
     */
    record Region(String name, boolean raid, long seenAt, int marked, int mythics, int shinies, int items) {}

    private static final String ASPECT_TYPE = "AspectItem";
    private static final Set<String> TOME_TYPES = Set.of("TomeItem");
    private static final Set<String> GEAR_TYPES = Set.of("GearItem");

    private PoolView() {}

    /** The raid regions, in {@code RaidPool.poolRaids()} order; empty without data. */
    static List<Region> raidRegions() {
        RaidPool.Source source = RaidPool.source();
        if (source == RaidPool.Source.GAME) {
            List<PoolAspect> game = RaidPool.aspects();
            return game.isEmpty() ? List.of()
                    : List.of(new Region("", true, RaidPool.seenAt(), RaidPool.markedCount(), 0, 0, game.size()));
        }
        if (source != RaidPool.Source.WYNNVENTORY) {
            return List.of();
        }
        WeeklyPool api = WynnventoryPools.raids();
        List<Region> out = new ArrayList<>();
        for (String raid : RaidPool.poolRaids()) {
            PoolRegion r = region(api, raid);
            if (r == null) {
                continue;
            }
            int marked = 0;
            for (PoolAspect a : RaidPool.aspectsIn(raid)) {
                if (RaidPool.markOf(a.name()) != RaidPool.Mark.NONE) {
                    marked++;
                }
            }
            List<PoolItem> folded = fold(r.items());
            out.add(new Region(raid, true, r.seenAt(), marked, mythics(folded), shinies(folded), folded.size()));
        }
        return List.copyOf(out);
    }

    /** The camp regions, by mythics then name; empty without data. */
    static List<Region> campRegions() {
        WeeklyPool loot = WynnventoryPools.loot();
        Map<String, PoolRegion> byName = new LinkedHashMap<>();
        for (PoolRegion r : loot.regions()) {
            byName.putIfAbsent(r.name(), region(loot, r.name()));
        }
        List<Region> out = new ArrayList<>();
        for (PoolRegion r : byName.values()) {
            List<PoolItem> folded = fold(r.items());
            out.add(new Region(r.name(), false, r.seenAt(), 0, mythics(folded), shinies(folded), folded.size()));
        }
        out.sort(Comparator.comparingInt(Region::mythics).reversed().thenComparing(Region::name));
        return List.copyOf(out);
    }

    /** The groups of a raid for the class in play ("" = the pool seen in game). */
    static List<Group> raid(String region, boolean classOnly) {
        PoolRegion r;
        if (region == null || region.isEmpty()) {
            if (RaidPool.source() != RaidPool.Source.GAME) {
                return List.of();
            }
            List<PoolItem> items = new ArrayList<>();
            for (PoolAspect a : RaidPool.aspects()) {
                Klass k = a.klass() == null ? Klass.NONE : a.klass();
                String subtype = k == Klass.NONE ? "" : capital(k.name()) + "Aspect";
                items.add(new PoolItem(a.name(), ASPECT_TYPE, a.rarity(), subtype, 1, "", "", false, "", Math.max(0, a.tier())));
            }
            r = new PoolRegion("", "", RaidPool.seenAt(), items);
        } else {
            r = region(WynnventoryPools.raids(), region);
            if (r == null) {
                return List.of();
            }
        }
        Map<String, Integer> max = maxTiers(AspectBook.classAspects());
        return group(r, true, AspectBook.klass(), AspectBook::ownedTier,
                n -> max.getOrDefault(n.toLowerCase(Locale.ROOT), 0), classOnly);
    }

    /** The rarity groups of a camp. */
    static List<Group> camp(String region) {
        PoolRegion r = region == null ? null : region(WynnventoryPools.loot(), region);
        if (r == null) {
            return List.of();
        }
        return group(r, false, Klass.NONE, n -> -1, n -> 0, false);
    }

    /**
     * The pure core of {@link #raid} and {@link #camp}, for tests. For the region seen in game
     * (named "") an aspect's {@code tier} is the tier the menu showed; Wynnventory's aspects have
     * none (decision 10's marks).
     */
    static List<Group> group(PoolRegion r, boolean raid, Klass k, ToIntFunction<String> owned,
                             ToIntFunction<String> maxTier, boolean classOnly) {
        if (r == null) {
            return List.of();
        }
        Klass klass = k == null ? Klass.NONE : k;
        boolean game = r.name().isEmpty();
        Map<Group.Kind, List<Row>> by = new EnumMap<>(Group.Kind.class);
        List<Row> others = new ArrayList<>();
        for (PoolItem i : fold(r.items())) {
            Row row = row(i, klass, owned, maxTier, game);
            Group.Kind kind = raid ? raidKind(i) : campKind(i);
            if (kind == Group.Kind.ASPECTS && !row.ownClass() && klass != Klass.NONE) {
                if (!classOnly) {
                    others.add(row);
                }
                continue;
            }
            by.computeIfAbsent(kind, x -> new ArrayList<>()).add(row);
        }
        List<Group> out = new ArrayList<>();
        for (Group.Kind kind : Group.Kind.values()) {
            List<Row> rows = by.getOrDefault(kind, new ArrayList<>());
            sortByRarity(rows);
            if (kind == Group.Kind.ASPECTS) {
                sortByRarity(others);
                rows = new ArrayList<>(rows);
                rows.addAll(others);            // the class in play first, then the other classes
            }
            if (!rows.isEmpty()) {
                out.add(new Group(kind, List.copyOf(rows)));
            }
        }
        return List.copyOf(out);
    }

    /** Folds repeated entries by name, item type and shiny flag, amounts summed, first order kept. */
    static List<PoolItem> fold(List<PoolItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        Map<String, PoolItem> by = new LinkedHashMap<>();
        for (PoolItem i : items) {
            if (i == null) {
                continue;
            }
            String key = i.name().toLowerCase(Locale.ROOT) + "\u0000" + i.itemType() + "\u0000" + i.shiny();
            PoolItem had = by.get(key);
            by.put(key, had == null ? i : withAmount(had, had.amount() + i.amount()));
        }
        return List.copyOf(by.values());
    }

    // ---- inside ----

    private static Row row(PoolItem i, Klass k, ToIntFunction<String> owned, ToIntFunction<String> maxTier, boolean game) {
        boolean own = i.aspect() && k != Klass.NONE && i.aspectClass() == k;
        if (!own) {
            return new Row(i, i.amount(), false, RaidPool.Mark.NONE, 0, -1, 0);
        }
        int have = owned.applyAsInt(i.name());
        int max = Math.max(0, maxTier.applyAsInt(i.name()));
        int shown = game ? i.tier() : 0;
        RaidPool.Mark mark = RaidPool.mark(new PoolAspect(i.name(), k, i.rarity(), shown), have, max);
        int tag = mark == RaidPool.Mark.TIER_UP ? (shown >= 1 ? shown : have + 1) : 0;
        return new Row(i, i.amount(), true, mark, tag, have < 0 ? -1 : have, max);
    }

    private static Group.Kind raidKind(PoolItem i) {
        if (i.shiny()) {
            return Group.Kind.SHINY;
        }
        if (i.aspect()) {
            return Group.Kind.ASPECTS;
        }
        if (TOME_TYPES.contains(i.itemType())) {
            return Group.Kind.TOMES;
        }
        if (GEAR_TYPES.contains(i.itemType())) {
            return Group.Kind.GEAR;
        }
        return Group.Kind.MISC;
    }

    private static Group.Kind campKind(PoolItem i) {
        if (i.shiny()) {
            return Group.Kind.SHINY;
        }
        return switch (i.rarity()) {
            case MYTHIC -> Group.Kind.MYTHIC;
            case FABLED -> Group.Kind.FABLED;
            case LEGENDARY -> Group.Kind.LEGENDARY;
            case RARE -> Group.Kind.RARE;
            case UNIQUE -> Group.Kind.UNIQUE;
            default -> Group.Kind.OTHER;
        };
    }

    /** Mythic, fabled, legendary, rare, set, unique, normal, crafted; a stable sort keeps Wynnventory's order. */
    private static void sortByRarity(List<Row> rows) {
        rows.sort(Comparator.comparingInt(r -> rank(r.item().rarity())));
    }

    private static int rank(Tier t) {
        return switch (t) {
            case MYTHIC -> 0;
            case FABLED -> 1;
            case LEGENDARY -> 2;
            case RARE -> 3;
            case SET -> 4;
            case UNIQUE -> 5;
            case NORMAL -> 6;
            case CRAFTED -> 7;
        };
    }

    /** Distinct non-shiny mythic gear names. */
    private static int mythics(List<PoolItem> items) {
        List<String> names = new ArrayList<>();
        for (PoolItem i : items) {
            String n = i.name().toLowerCase(Locale.ROOT);
            if (i.mythicGear() && !i.shiny() && !names.contains(n)) {
                names.add(n);
            }
        }
        return names.size();
    }

    private static int shinies(List<PoolItem> items) {
        int n = 0;
        for (PoolItem i : items) {
            if (i.shiny()) {
                n++;
            }
        }
        return n;
    }

    /** The region with this name (regions repeated under one name are joined); null when none. */
    private static PoolRegion region(WeeklyPool p, String name) {
        if (p == null || name == null) {
            return null;
        }
        PoolRegion first = null;
        List<PoolItem> joined = null;
        long seen = -1;
        for (PoolRegion r : p.regions()) {
            if (!r.name().equals(name)) {
                continue;
            }
            if (first == null) {
                first = r;
            } else {
                if (joined == null) {
                    joined = new ArrayList<>(first.items());
                }
                joined.addAll(r.items());
            }
            seen = Math.max(seen, r.seenAt());
        }
        if (first == null || joined == null) {
            return first;
        }
        return new PoolRegion(first.name(), first.type(), seen, joined);
    }

    private static Map<String, Integer> maxTiers(List<AspectEntry> aspects) {
        Map<String, Integer> m = new HashMap<>();
        for (AspectEntry e : aspects) {
            m.put(e.name().toLowerCase(Locale.ROOT), e.tiers());
        }
        return m;
    }

    private static PoolItem withAmount(PoolItem i, int amount) {
        return new PoolItem(i.name(), i.itemType(), i.rarity(), i.subtype(), amount, i.iconFormat(), i.iconValue(),
                i.shiny(), i.shinyStat(), i.tier());
    }

    private static String capital(String upper) {
        String l = upper.toLowerCase(Locale.ROOT);
        return l.isEmpty() ? l : Character.toUpperCase(l.charAt(0)) + l.substring(1);
    }
}
