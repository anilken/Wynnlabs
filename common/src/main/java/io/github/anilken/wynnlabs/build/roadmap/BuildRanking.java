package io.github.anilken.wynnlabs.build.roadmap;

import io.github.anilken.wynnlabs.build.Build;
import io.github.anilken.wynnlabs.build.Damage;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap.Candidate;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.Slot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Whole builds at your level for the build window's result list: the milestone's set and the
 * sets a swap or two from it, within the budget, kept apart from each other and labelled
 * ("best", "cheap", "tanky", "fast").
 */
final class BuildRanking {

    private BuildRanking() {}

    /** How many slots outside {@code fixed} two sets hold different pieces in, the two rings as a pair either way round. */
    static int distance(Map<String, Damage.Piece> a, Map<String, Damage.Piece> b, java.util.Set<String> fixed) {
        int d = 0;
        for (String slot : Roadmap.BEAM_ORDER) {
            if (slot.startsWith("RING") || fixed.contains(slot)) {
                continue;
            }
            if (!nameOf(a.get(slot)).equals(nameOf(b.get(slot)))) {
                d++;
            }
        }
        List<String> ra = new ArrayList<>();
        List<String> rb = new ArrayList<>();
        for (String slot : new String[] {"RING1", "RING2"}) {
            if (!fixed.contains(slot)) {
                ra.add(nameOf(a.get(slot)));
                rb.add(nameOf(b.get(slot)));
            }
        }
        for (String r : ra) {
            if (!rb.remove(r)) {
                d++;
            }
        }
        return d;
    }

    private static String nameOf(Damage.Piece p) {
        return p == null ? "-" : p.name();
    }

    /**
     * Whole builds at your level for the window, best first: the set the milestone found, the
     * sets one or two swaps from it (each slot's best three from the milestone's pools), each
     * worn whole (skill points spread anew) and within the budget, the chosen slots never
     * touched. Diversity: going down by score, a build is kept only when it differs from every
     * build kept before in at least {@link Roadmap#MIN_DISTANCE} slots outside the chosen ones (the two
     * rings as a pair), so the list never holds near-copies that swap one ring. At most
     * {@code n}; labelled by {@link #label}.
     */
    static List<Candidate> rank(Map<String, Damage.Piece> start, Map<String, Damage.Piece> best,
            Map<Slot, List<ItemData>> pools, java.util.Set<String> fixed, int level, int earned, int n, long budget,
            GameState state, java.util.Set<String> owned) {
        Map<String, List<Damage.Piece>> options = new LinkedHashMap<>();
        for (String slot : Roadmap.BEAM_ORDER) {
            if (fixed.contains(slot)) {
                continue;
            }
            Slot t = slot.startsWith("RING") ? Slot.RING : slot.equals("WEAPON") ? null : Slot.valueOf(slot);
            List<ItemData> pool = t == null ? SetJudge.weaponPool(pools) : pools.get(t);
            List<Damage.Piece> top = new ArrayList<>();
            if (start.get(slot) != null && !nameOf(start.get(slot)).equals(nameOf(best.get(slot)))) {
                top.add(start.get(slot));                       // what the slot held before: often a cheaper way
            }
            for (ItemData info : pool == null ? List.<ItemData>of() : pool) {
                if (top.size() >= 3) {
                    break;
                }
                if (info.requirements().level() <= level && !info.name().equals(nameOf(best.get(slot)))) {
                    top.add(OwnedGear.pieceOf(info));
                }
            }
            options.put(slot, top);
        }
        List<Map<String, Damage.Piece>> sets = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.function.Consumer<Map<String, Damage.Piece>> add = s -> {
            if (seen.add(SetJudge.setKey(s)) && !SetJudge.clashes(s)) {
                sets.add(s);
            }
        };
        add.accept(new LinkedHashMap<>(best));
        List<String> slots = new ArrayList<>(options.keySet());
        for (int i = 0; i < slots.size(); i++) {
            for (Damage.Piece a : options.get(slots.get(i))) {
                Map<String, Damage.Piece> one = new LinkedHashMap<>(best);
                one.put(slots.get(i), a);
                add.accept(one);
                for (int j = i + 1; j < slots.size(); j++) {
                    for (Damage.Piece b : options.get(slots.get(j))) {
                        Map<String, Damage.Piece> two = new LinkedHashMap<>(one);
                        two.put(slots.get(j), b);
                        add.accept(two);
                    }
                }
            }
        }
        List<Candidate> all = new ArrayList<>();
        for (Map<String, Damage.Piece> s : sets) {
            Roadmap.tick();
            double price = CandidateRules.spend(state, s, fixed, owned);
            if (budget > 0 && price > budget || Double.isInfinite(price) && budget > 0) {
                continue;
            }
            Build.Eval e = Build.allocate(s, level, earned);
            if (e == null) {
                continue;
            }
            all.add(new Candidate(s, e, SetJudge.judged(e, null, level), Double.isInfinite(price) ? Double.NaN : price,
                    List.of(), fixed));
        }
        all.sort((x, y) -> Double.compare(y.score(), x.score()));
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : all) {
            if (out.size() >= n) {
                break;
            }
            boolean apart = true;
            for (Candidate k : out) {
                apart &= distance(c.set(), k.set(), fixed) >= Roadmap.MIN_DISTANCE;
            }
            if (apart) {
                out.add(c);
                Roadmap.Job j = Roadmap.JOB.get();
                if (j != null && (Double.isNaN(j.best) || c.score() > j.best)) {
                    j.best = c.score();
                }
            }
        }
        return label(out);
    }

    /**
     * The labels, each on one build only and only where it sets that build apart: "best" on the
     * first; "cheap" on the build with the strictly lowest price among those within
     * {@link Roadmap#CHEAP_WITHIN} of the best score; "tanky" on the strictly highest effective health;
     * "fast" on the strictly highest walk speed (above 0). A build keeps one label, the first
     * of that order it earns; a tie gives none.
     */
    static List<Candidate> label(List<Candidate> builds) {
        if (builds.isEmpty()) {
            return builds;
        }
        String[] labels = new String[builds.size()];
        labels[0] = "best";
        double top = builds.get(0).score();
        int cheap = unique(builds, c -> c.score() >= top * (1 - Roadmap.CHEAP_WITHIN) && !Double.isNaN(c.price())
                ? -c.price() : Double.NaN);
        if (cheap > 0 && labels[cheap] == null && builds.get(cheap).price() < builds.get(0).price()) {
            labels[cheap] = "cheap";
        }
        int tanky = unique(builds, c -> c.eval().result().ehp());
        if (tanky > 0 && labels[tanky] == null) {
            labels[tanky] = "tanky";
        }
        int fast = unique(builds, c -> c.eval().result().speed() > 0 ? c.eval().result().speed() : Double.NaN);
        if (fast > 0 && labels[fast] == null) {
            labels[fast] = "fast";
        }
        List<Candidate> out = new ArrayList<>();
        for (int i = 0; i < builds.size(); i++) {
            Candidate c = builds.get(i);
            out.add(new Candidate(c.set(), c.eval(), c.score(), c.price(), labels[i] == null ? List.of() : List.of(labels[i]),
                    c.fixed()));
        }
        return out;
    }

    /** The one build with the highest value (NaN: left out); -1 when none or two share it. */
    private static int unique(List<Candidate> builds, java.util.function.ToDoubleFunction<Candidate> value) {
        int at = -1;
        double best = Double.NEGATIVE_INFINITY;
        boolean tie = false;
        for (int i = 0; i < builds.size(); i++) {
            double v = value.applyAsDouble(builds.get(i));
            if (Double.isNaN(v)) {
                continue;
            }
            if (v > best + 1e-9) {
                best = v;
                at = i;
                tie = false;
            } else if (Math.abs(v - best) <= 1e-9) {
                tie = true;
            }
        }
        return tie ? -1 : at;
    }
}
