package io.github.anilken.wynnlabs.search;

import io.github.anilken.wynnlabs.market.Prices;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Every known way to get an item, as {@link Source} rows ranked easiest first: owned (bag, each
 * bank page), the Trade Market, then what the data names (dropMeta, an ingredient's mobs, the
 * item obtain list), gathering nodes for materials, and for gear that drops normally a
 * "Lv a–b mobs" row (a = level − 4, b = level + 4) with the nearest places of that band plus a
 * loot chest row. No drop chances: the data has none. Built once per selection, not per frame.
 */
public final class Sources {

    /** Levels below and above the item's level that the normal-drop band covers. */
    public static final int BAND = 4;
    /** Places listed on a band or chest row. */
    public static final int NEAR = Integer.MAX_VALUE;          // no cap: every place, nearest first
    /** Gathering spots listed on a material's row, at least {@link #NODE_SPACING} blocks apart. */
    static final int NODES = Integer.MAX_VALUE;
    static final int NODE_SPACING = 64;

    private Sources() {}

    /**
     * What the player has: the bag count and the count on each seen bank page (page label →
     * count, in display order). Filled by the caller from the bag and {@code Vault}.
     */
    public record Owned(int bag, Map<String, Integer> bank) {
        public static final Owned NONE = new Owned(0, Map.of());

        public boolean any() {
            return bag > 0 || bank.values().stream().anyMatch(c -> c > 0);
        }
    }

    /**
     * The ranked sources of {@code e}. {@code price} is the item's Trade Market price or null;
     * {@code pos} the player's {x, z} or null (rows then keep the data's order of places).
     */
    public static List<Source> of(Entry e, Places places, Owned owned, Prices.Price price, double[] pos) {
        List<Source> out = new ArrayList<>();
        Owned have = owned == null ? Owned.NONE : owned;
        if (have.bag() > 0) {
            out.add(row(Source.Type.BAG, null, List.of(), have.bag(), null, 0, 0));
        }
        have.bank().forEach((page, n) -> {
            if (n > 0) {
                out.add(row(Source.Type.BANK, page, List.of(), n, null, 0, 0));
            }
        });
        if (e.tradable() && price != null && (price.typical() != null || price.count() != null && price.count() > 0)) {
            out.add(row(Source.Type.MARKET, null, List.of(), 0, price, 0, 0));
        }
        int farm = out.size();
        boolean band = e.normalDrop();
        boolean chests = band || "lootchest".equals(e.drop());
        Map<String, Source> named = new LinkedHashMap<>();
        for (Entry.Origin o : e.origins()) {
            Source.Type t = Source.Type.of(o.type());
            if (t == Source.Type.MOB_BAND) {
                band = true;
                continue;
            }
            if (t == Source.Type.LOOT_CHEST) {
                chests = true;
                continue;
            }
            List<Area> areas = o.areas();
            if (areas.isEmpty() && places != null) {
                Area at = places.find(o.name());
                if (at != null) {
                    areas = List.of(at);
                }
            }
            String key = t + "|" + Objects.toString(o.name(), "");
            Source before = named.get(key);
            if (before != null) {
                List<Area> more = new ArrayList<>(before.areas());
                areas.stream().filter(a -> !more.contains(a)).forEach(more::add);
                areas = more;
            }
            named.put(key, row(t, o.name(), SourceRank.nearest(areas, pos, areas.size()), 0, null, 0, 0));
        }
        out.addAll(named.values());
        if (e.kind() == Kind.MATERIAL && places != null) {
            String resource = resource(e.name());
            List<Area> spots = spaced(SourceRank.nearest(places.nodes(resource), pos, Integer.MAX_VALUE));
            if (!spots.isEmpty()) {
                out.add(row(Source.Type.GATHERING, cap(resource), spots, 0, null, 0, 0));
            }
        }
        int lo = Math.max(1, e.level() - BAND);
        int hi = e.level() + BAND;
        if (band && e.level() > 0) {
            List<Area> near = places == null ? List.of() : near(places.inBand(lo, hi), pos, e.level(), false);
            out.add(row(Source.Type.MOB_BAND, null, near, 0, null, lo, hi));
        }
        if (chests && e.level() > 0) {
            List<Area> near = places == null ? List.of() : near(places.chestsInBand(lo, hi), pos, e.level(), true);
            out.add(row(Source.Type.LOOT_CHEST, null, near, 0, null, lo, hi));
        }
        boolean market = out.stream().anyMatch(s -> s.type() == Source.Type.MARKET);
        if (out.size() == farm && !market) {
            out.add(row(Source.Type.UNKNOWN, null, List.of(), 0, null, 0, 0));
        }
        return SourceRank.rank(out, pos);
    }

    private static Source row(Source.Type t, String name, List<Area> areas, int count, Prices.Price price, int lo, int hi) {
        return new Source(t, name, areas, SourceRank.effort(t), SourceRank.badge(t), count, price, lo, hi, null);
    }

    /**
     * The {@link #NEAR} places to show: the nearest to the player, or without a position the ones
     * closest in level (chests: the higher tier first), then by position for a stable order.
     */
    static List<Area> near(List<Area> candidates, double[] pos, int level, boolean chest) {
        if (pos != null) {
            return SourceRank.nearest(candidates, pos, NEAR);
        }
        Comparator<Area> order = chest
                ? Comparator.comparingInt((Area a) -> -a.stars()).thenComparingInt(a -> Math.abs(a.level() - level))
                : Comparator.comparingInt((Area a) -> Math.abs(a.level() - level));
        return candidates.stream()
                .sorted(order.thenComparingInt(Area::x).thenComparingInt(Area::z))
                .limit(NEAR)
                .toList();
    }

    /** Up to {@link #NODES} areas of the list, each at least {@link #NODE_SPACING} blocks from those kept before it. */
    static List<Area> spaced(List<Area> nodes) {
        List<Area> out = new ArrayList<>();
        for (Area n : nodes) {
            boolean far = out.stream().allMatch(k -> Math.hypot(k.x() - n.x(), k.z() - n.z()) >= NODE_SPACING);
            if (far) {
                out.add(n);
                if (out.size() == NODES) {
                    break;
                }
            }
        }
        return out;
    }

    /** The gathering resource of a material ("Copper Ingot" → "COPPER"). */
    static String resource(String material) {
        int sp = material.indexOf(' ');
        return (sp < 0 ? material : material.substring(0, sp)).toUpperCase(Locale.ROOT);
    }

    private static String cap(String upper) {
        return upper.isEmpty() ? upper : upper.charAt(0) + upper.substring(1).toLowerCase(Locale.ROOT);
    }
}
