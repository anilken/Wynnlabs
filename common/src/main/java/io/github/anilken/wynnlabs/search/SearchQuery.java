package io.github.anilken.wynnlabs.search;

import io.github.anilken.wynnlabs.model.Klass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * What the search box and its chips ask for: the text, the sort, and the filters (kinds, level
 * range, the player's class, owned, on the Trade Market). {@link #run} turns it into a ranked list
 * plus per-kind counts under the other filters. Pure: what the player owns and what things cost
 * come in through {@link Facts}. Runs off the game thread through {@link SearchWorker}.
 *
 * @param kinds    kinds to list; empty means all
 * @param minLevel lowest level listed (0 for no limit)
 * @param maxLevel highest level listed ({@link Integer#MAX_VALUE} for no limit)
 */
public record SearchQuery(String text, Sort sort, Set<Kind> kinds, int minLevel, int maxLevel,
        boolean myClass, boolean owned, boolean onMarket) {

    public enum Sort { EASIEST, LEVEL, NAME, PRICE }

    public SearchQuery {
        kinds = kinds == null ? Set.of() : Set.copyOf(kinds);
        sort = sort == null ? Sort.EASIEST : sort;
    }

    /** Everything, easiest first, for this text. */
    public static SearchQuery of(String text) {
        return new SearchQuery(text, Sort.EASIEST, Set.of(), 0, Integer.MAX_VALUE, false, false, false);
    }

    /**
     * What the query needs to know about the player: their class ({@link Klass#NONE} when not in
     * the world), whether they own an item (bag or a seen bank page), and an item's typical Trade
     * Market price (null when unknown). The functions are called from the search thread, so they
     * must read snapshots, not the game.
     */
    public record Facts(Klass klass, Predicate<String> owned, Function<String, Double> price) {
        public static final Facts NONE = new Facts(Klass.NONE, n -> false, n -> null);
    }

    /** The ranked hits, how many items of each kind pass the other filters, and the query it answers. */
    public record Result(SearchQuery query, List<Hit> hits, Map<Kind, Integer> counts) {
        public static final Result EMPTY = new Result(of(""), List.of(), Map.of());
    }

    public Result run(ItemIndex index, Facts facts) {
        Facts f = facts == null ? Facts.NONE : facts;
        String t = text == null ? "" : text.trim();
        List<Hit> found;
        if (t.isEmpty()) {
            found = new ArrayList<>(index.entries().size());
            for (Entry e : index.entries()) {
                found.add(new Hit(e, 0, Hit.Match.ALL, null));
            }
        } else {
            found = ItemMatch.match(index, t);
        }
        Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
        List<Hit> kept = new ArrayList<>();
        Map<String, Double> prices = new java.util.HashMap<>();
        Map<String, Boolean> owns = new java.util.HashMap<>();
        for (Hit h : found) {
            Entry e = h.entry();
            if (e.level() < minLevel || e.level() > maxLevel) {
                continue;
            }
            if (myClass && e.klass() != Klass.NONE && e.klass() != f.klass()) {
                continue;
            }
            if (owned && !owns.computeIfAbsent(e.name(), f.owned()::test)) {
                continue;
            }
            if (onMarket && !(e.tradable() && price(e, f, prices) != null)) {
                continue;
            }
            counts.merge(e.kind(), 1, Integer::sum);
            if (kinds.isEmpty() || kinds.contains(e.kind())) {
                kept.add(h);
            }
        }
        kept.sort(order(f, prices, owns));
        return new Result(this, Collections.unmodifiableList(kept), Collections.unmodifiableMap(counts));
    }

    private static Double price(Entry e, Facts f, Map<String, Double> cache) {
        if (cache.containsKey(e.name())) {
            return cache.get(e.name());
        }
        Double p = e.tradable() ? f.price().apply(e.name()) : null;
        cache.put(e.name(), p);
        return p;
    }

    private Comparator<Hit> order(Facts f, Map<String, Double> prices, Map<String, Boolean> owns) {
        Comparator<Hit> byScore = Comparator.comparingInt((Hit h) -> -h.score());
        Comparator<Hit> byName = Comparator.comparing((Hit h) -> h.entry().name(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(h -> h.entry().name());
        return switch (sort) {
            case LEVEL -> Comparator.comparingInt((Hit h) -> h.entry().level()).thenComparing(byScore).thenComparing(byName);
            case NAME -> byName;
            case PRICE -> Comparator.comparingDouble((Hit h) -> {
                Double p = price(h.entry(), f, prices);
                return p == null ? Double.MAX_VALUE : p;
            }).thenComparing(byScore).thenComparing(byName);
            case EASIEST -> {
                Map<Entry, Integer> ease = new java.util.IdentityHashMap<>();
                yield Comparator.comparingInt((Hit h) -> -(h.score() / 100))
                        .thenComparingInt(h -> owns.computeIfAbsent(h.entry().name(), f.owned()::test) ? 0 : 1)
                        .thenComparingInt(h -> ease.computeIfAbsent(h.entry(), e ->
                                SourceRank.effort(SourceRank.easiest(e, price(e, f, prices) != null))))
                        .thenComparing(byScore)
                        .thenComparing(Comparator.comparingInt((Hit h) -> h.entry().name().length()))
                        .thenComparing(byName);
            }
        };
    }
}
