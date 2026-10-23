package io.github.anilken.wynnlabs.search;

import io.github.anilken.wynnlabs.build.BuildSources;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.market.Shopping;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Orders an item's sources from easiest to hardest: owned in the bag, owned in the bank, the
 * Trade Market, then the rest by effort and, at equal effort, the nearer first. Effort numbers
 * come from the rules ({@code "search": {"merchant": 1, ...}}, keys are the {@link Source.Type}
 * names in lower case) with the built-in numbers below as defaults.
 */
public final class SourceRank {

    private SourceRank() {}

    /** The built-in effort of a source type (lower is easier). */
    static int defaultEffort(Source.Type t) {
        return switch (t) {
            case BAG, BANK, MARKET -> 0;
            case MERCHANT, MOB, GATHERING -> 1;
            case TINKERING, MINIBOSS, ALTAR, CAVE -> 2;
            case DUNGEON, DUNGEON_MERCHANT -> 3;
            case QUEST, CHALLENGE, LOOTRUN, EVENT, WORLD_EVENT, GUILD, FORGERY_CHEST -> 4;
            case RAID -> 5;
            case MOB_BAND -> 6;
            case LOOT_CHEST -> 7;
            case OTHER -> 8;
            case UNAVAILABLE, UNKNOWN -> 9;
        };
    }

    /** The effort of a source type, from the rules ({@code search.<type>}) or the default. */
    public static int effort(Source.Type t) {
        return (int) Rules.sub("search", t.name().toLowerCase(Locale.ROOT), defaultEffort(t));
    }

    /**
     * The badge of a source type: the home page's badge for the same kind of source
     * ({@link BuildSources#kindIcon}) when it has one, else the search tab's own.
     */
    public static String badge(Source.Type t) {
        switch (t) {
            case BAG:
                return "package";
            case BANK:
                return "building-bank";
            case MARKET:
                return "coin";
            default:
                break;
        }
        if (t.obtain != null) {
            String home = BuildSources.kindIcon(Shopping.kind(t.obtain));
            if (!home.equals("question-mark")) {
                return home;
            }
        }
        return switch (t) {
            case TINKERING -> "shopping-cart";
            case ALTAR, WORLD_EVENT, MOB_BAND -> "sword";
            case GATHERING -> "pick";
            case FORGERY_CHEST -> "box";
            case RAID, CHALLENGE, LOOTRUN, GUILD, EVENT -> "trophy";
            default -> "question-mark";
        };
    }

    /**
     * The easiest way to get an item without looking at places, for the results table's "easiest"
     * column and the easiest-first sort: the Trade Market when it is tradable and {@code onMarket}
     * (it has a price or listings), else the lowest-effort source the data names, the normal-drop
     * band, loot chests or gathering; UNKNOWN when nothing is known. Ownership is shown apart.
     */
    public static Source.Type easiest(Entry e, boolean onMarket) {
        if (onMarket && e.tradable()) {
            return Source.Type.MARKET;
        }
        Source.Type best = Source.Type.UNKNOWN;
        int bestEffort = Integer.MAX_VALUE;
        for (Entry.Origin o : e.origins()) {
            Source.Type t = Source.Type.of(o.type());
            int ef = effort(t);
            if (ef < bestEffort) {
                best = t;
                bestEffort = ef;
            }
        }
        Source.Type extra = e.kind() == Kind.MATERIAL ? Source.Type.GATHERING
                : e.normalDrop() ? Source.Type.MOB_BAND
                : "lootchest".equals(e.drop()) ? Source.Type.LOOT_CHEST : null;
        if (extra != null && effort(extra) < bestEffort) {
            best = extra;
        }
        return best;
    }

    /** Where a row sits before effort: bag 0, bank 1, Trade Market 2, the rest 3. */
    private static int group(Source s) {
        return switch (s.type()) {
            case BAG -> 0;
            case BANK -> 1;
            case MARKET -> 2;
            default -> 3;
        };
    }

    /**
     * The sources easiest first, each with its areas nearest first (by the data's order when the
     * position is unknown). Stable: equal rows keep their order.
     */
    public static List<Source> rank(List<Source> sources, double[] pos) {
        List<Source> out = new ArrayList<>();
        for (Source s : sources) {
            out.add(pos == null || s.areas().size() < 2 ? s : new Source(s.type(), s.name(), nearest(s.areas(), pos, s.areas().size()),
                    s.effort(), s.badge(), s.count(), s.price(), s.minLevel(), s.maxLevel(), s.cost()));
        }
        Comparator<Source> order = Comparator.comparingInt(SourceRank::group)
                .thenComparingInt(Source::effort)
                .thenComparingDouble(s -> {
                    double d = Compass.distance(s.main(), pos);
                    return Double.isNaN(d) ? Double.MAX_VALUE : d;
                });
        out.sort(order);
        return out;
    }

    /** The {@code max} areas nearest to {@code pos} (all of them, in order, when it is null). */
    static List<Area> nearest(List<Area> areas, double[] pos, int max) {
        if (pos == null) {
            return areas.size() <= max ? areas : List.copyOf(areas.subList(0, max));
        }
        return areas.stream()
                .sorted(Comparator.comparingDouble(a -> Compass.distance(a, pos)))
                .limit(max)
                .toList();
    }
}
