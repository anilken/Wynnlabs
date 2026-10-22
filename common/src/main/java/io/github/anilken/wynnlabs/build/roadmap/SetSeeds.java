package io.github.anilken.wynnlabs.build.roadmap;

import io.github.anilken.wynnlabs.build.Build;
import io.github.anilken.wynnlabs.build.Damage;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap.Seed;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.SetData;
import io.github.anilken.wynnlabs.model.Slot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Whole item sets as starts of the search: the set pieces a milestone can put together, and a
 * build with a set put on (whole or in part) or taken off. A set bonus only shows with its
 * pieces together, so a search that changes one piece at a time never finds it.
 */
final class SetSeeds {

    private SetSeeds() {}

    /**
     * The set pieces a milestone can put together, by set: what you have (worn, carried or
     * banked, whatever its level or where it once dropped) and every piece you could get there
     * ({@code found}: the milestone's level, tier, source and budget, not only each slot's best
     * forty, as a set piece alone rarely ranks high). Set weapons too, of your class's kind,
     * unless a chosen item holds the weapon.
     */
    static Map<String, List<ItemData>> setPieces(Map<Slot, List<ItemData>> cands, List<ItemData> found,
            java.util.Set<String> owned, int m, java.util.Set<String> fixed) {
        Map<String, List<ItemData>> out = new LinkedHashMap<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        boolean weapon = !fixed.contains("WEAPON");
        java.util.function.Consumer<ItemData> add = info -> {
            if (info.set().isPresent() && info.slot() != null && (weapon || !info.slot().isWeapon())
                    && info.requirements().level() <= m && seen.add(info.name())) {
                out.computeIfAbsent(info.set().get().name(), k -> new ArrayList<>()).add(info);
            }
        };
        cands.values().forEach(l -> l.stream().filter(i -> owned.contains(i.name())).forEach(add));
        found.forEach(add);
        return out;
    }

    /**
     * The searched set with whole sets changed at once, as starts of the search ({@link SetSearch}): a set bonus only
     * shows with its pieces together, so a search that changes one piece at a time never puts a
     * set on (nothing worn and the Wynnterfest ornaments in the bank: Glimmer, Growth … one by
     * one) nor takes one off for pieces that beat it only together. For every set
     * ({@link #setPieces}): all of it on, and for each smaller count it has a bonus at the pieces
     * that do most alone (Goblin's hood and cloak without the boots); for every set the build
     * wears: that set off, each of its slots holding the pool's best. A ring you can have twice
     * fills both hands and counts twice (Black Catalyst, Constellation).
     */
    static List<Seed> setSeeds(Map<String, Damage.Piece> build, Build.Eval cur, Map<Slot, List<ItemData>> pools,
            Map<String, List<ItemData>> sets, Map<String, Integer> owned, java.util.Set<String> buyable,
            java.util.Set<String> fixed, int m, int earned, int[] prev) {
        Map<String, Seed> out = new LinkedHashMap<>();
        double curJ = SetJudge.judged(cur, prev, m);
        String now = SetJudge.setKey(build);
        java.util.function.Consumer<Seed> keep = seed -> {
            String k = SetJudge.setKey(seed.build());
            if (!k.equals(now) && !SetJudge.clashes(seed.build())) {
                out.putIfAbsent(k, seed);
            }
        };
        List<String> hands = new ArrayList<>();
        for (String h : new String[] {"RING1", "RING2"}) {
            if (!fixed.contains(h)) {
                hands.add(h);
            }
        }
        // a set put on, whole or in part
        for (List<ItemData> pieces : sets.values()) {
            SetData set = pieces.get(0).set().get();
            // where its pieces go: a slot each (of two for one slot, the one that does more
            // alone), a ring in each free hand it can fill: twice when you have two or can buy it
            java.util.Set<String> bySlot = new java.util.HashSet<>();
            List<ItemData> rings = new ArrayList<>();
            for (ItemData info : pieces) {
                if (info.slot() == Slot.RING) {
                    int copies = buyable.contains(info.name()) ? 2 : Math.min(2, owned.getOrDefault(info.name(), 1));
                    for (int c = 0; c < copies; c++) {
                        rings.add(info);
                    }
                    continue;
                }
                String slot = Build.slotOf(info.slot(), 0);
                if (slot != null && !fixed.contains(slot)) {
                    bySlot.add(slot);
                }
            }
            int n = bySlot.size() + Math.min(rings.size(), hands.size());
            int lowest = Integer.MAX_VALUE;
            for (int k : set.bonuses().keySet()) {
                SetData.Bonus b = set.bonuses().get(k);
                if (k >= 2 && k <= n && (!b.minor().isEmpty() || !b.major().isEmpty())) {
                    lowest = Math.min(lowest, k);
                }
            }
            if (lowest == Integer.MAX_VALUE) {
                continue;                               // no bonus it can reach here
            }
            // each piece judged alone in the build, best first: {slot, piece, judged alone}; a
            // ring once per copy, the first in the hand where it does more, the second in the other
            List<Object[]> spots = new ArrayList<>();
            Map<String, Object[]> best = new LinkedHashMap<>();
            for (ItemData info : pieces) {
                String slot = info.slot() == Slot.RING ? null : Build.slotOf(info.slot(), 0);
                if (slot == null || fixed.contains(slot)) {
                    continue;
                }
                double j = aloneJ(build, cur.allocation(), curJ, slot, info, m, earned, prev);
                Object[] had = best.get(slot);
                if (had == null || j > (double) had[2]) {
                    best.put(slot, new Object[] {slot, info, j});
                }
            }
            spots.addAll(best.values());
            java.util.Set<String> ringNames = new java.util.HashSet<>();
            for (ItemData info : rings) {
                if (!ringNames.add(info.name())) {
                    continue;
                }
                List<Object[]> byHand = new ArrayList<>();
                for (String h : hands) {
                    byHand.add(new Object[] {h, info, aloneJ(build, cur.allocation(), curJ, h, info, m, earned, prev)});
                }
                byHand.sort((x, y) -> Double.compare((double) y[2], (double) x[2]));
                long copies = rings.stream().filter(r -> r == info).count();
                for (int c = 0; c < copies && c < byHand.size(); c++) {
                    spots.add(byHand.get(c));
                }
            }
            spots.sort((x, y) -> Double.compare((double) y[2], (double) x[2]));
            int[] handsLeft = {hands.size()};             // the best rings, as many as there are hands
            spots.removeIf(sp -> ((String) sp[0]).startsWith("RING") && handsLeft[0]-- <= 0);
            java.util.Set<Integer> counts = new java.util.TreeSet<>();
            for (int k : set.bonuses().keySet()) {
                if (k >= lowest && k < spots.size()) {
                    counts.add(k);
                }
            }
            counts.add(spots.size());
            for (int k : counts) {
                Map<String, Damage.Piece> seed = new LinkedHashMap<>(build);
                java.util.Set<String> held = new java.util.HashSet<>();
                List<Object[]> ringsOn = new ArrayList<>();
                for (int i = 0; i < k; i++) {
                    Object[] sp = spots.get(i);
                    ItemData info = (ItemData) sp[1];
                    String slot = (String) sp[0];
                    if (slot.startsWith("RING")) {
                        ringsOn.add(sp);
                        continue;
                    }
                    if (!sameName(seed.get(slot), info)) {      // the one you wear keeps its rolls
                        seed.put(slot, OwnedGear.pieceOf(info));
                    }
                    held.add(slot);
                }
                putRings(seed, ringsOn, hands, held);
                keep.accept(new Seed(seed, held));
            }
        }
        // a set taken off whole
        java.util.Set<String> worn = new java.util.LinkedHashSet<>();
        build.values().forEach(p -> {
            if (p != null) {
                p.stats().keySet().stream().filter(k -> k.startsWith(Damage.SET)).forEach(worn::add);
            }
        });
        for (String set : worn) {
            Map<String, Damage.Piece> seed = new LinkedHashMap<>(build);
            java.util.Set<String> held = new java.util.HashSet<>();
            int ring = 0;
            for (String slot : Roadmap.BEAM_ORDER) {
                Damage.Piece p = seed.get(slot);
                if (p == null || !p.stats().containsKey(set) || fixed.contains(slot) || slot.equals("WEAPON")) {
                    continue;
                }
                Slot t = slot.startsWith("RING") ? Slot.RING : Slot.valueOf(slot);
                List<ItemData> other = pools.getOrDefault(t, List.of()).stream()
                        .filter(i -> i.set().map(si -> !set.equals(Damage.SET + si.name())).orElse(true)).toList();
                if (other.isEmpty()) {
                    continue;
                }
                // the two hands: the pool's best two, or its best twice
                ItemData pick = other.get(t == Slot.RING ? Math.min(ring++, other.size() - 1) : 0);
                seed.put(slot, OwnedGear.pieceOf(pick));
                held.add(slot);
            }
            keep.accept(new Seed(seed, held));
        }
        return new ArrayList<>(out.values());
    }

    /**
     * A set piece judged alone in the build, in {@code slot}. Quick ({@link Build#tryEval}: only
     * the points it needs move), as it only orders them.
     */
    private static double aloneJ(Map<String, Damage.Piece> build, int[] alloc, double curJ, String slot, ItemData info,
            int m, int earned, int[] prev) {
        if (sameName(build.get(slot), info)) {
            return curJ;                                // worn there already
        }
        Roadmap.tick();
        Build.Eval ev = Build.tryEval(build, alloc, slot, OwnedGear.pieceOf(info), m, earned);
        return ev == null ? Double.NEGATIVE_INFINITY : SetJudge.judged(ev, prev, m);
    }

    /**
     * The set's rings ({hand, piece, ...}, best first) into the free hands: a ring a hand already
     * holds stays there with its rolls, the others go to the hand they were judged in, or the
     * one left.
     */
    private static void putRings(Map<String, Damage.Piece> seed, List<Object[]> rings, List<String> hands,
            java.util.Set<String> held) {
        List<String> free = new ArrayList<>(hands);
        List<Object[]> left = new ArrayList<>();
        for (Object[] r : rings) {
            ItemData info = (ItemData) r[1];
            String h = free.stream().filter(x -> sameName(seed.get(x), info)).findFirst().orElse(null);
            if (h == null) {
                left.add(r);
            } else {
                free.remove(h);
                held.add(h);
            }
        }
        for (Object[] r : left) {
            if (free.isEmpty()) {
                break;
            }
            String h = free.contains((String) r[0]) ? (String) r[0] : free.get(0);
            free.remove(h);
            seed.put(h, OwnedGear.pieceOf((ItemData) r[1]));
            held.add(h);
        }
    }

    private static boolean sameName(Damage.Piece p, ItemData info) {
        return p != null && p.name().equalsIgnoreCase(info.name());
    }
}
