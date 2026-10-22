package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.Slot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The road map's trace ({@link Roadmap#trace}, the tests' and the local API's): why each candidate
 * was skipped or how it scored, and what the search did. Nothing is put together while it is off.
 */
final class RoadmapTrace {

    private RoadmapTrace() {}

    /** A line about one candidate: its name and level, then {@code what}. */
    static void trace(int m, Slot t, ItemData info, String what) {
        Roadmap.TriConsumer tc = Roadmap.trace;
        if (tc != null) {
            tc.accept(m, t, info.name() + " Lv" + info.requirements().level() + ": " + what);
        }
    }

    /** A "skip: …" line for the trace, its reason a language key: nothing is put together while it is off. */
    static void skip(int m, Slot t, ItemData info, String key, Object... args) {
        if (Roadmap.trace != null) {
            trace(m, t, info, L.t("wynnlabs.road.trace_skip", L.t(key, args)));
        }
    }

    /** A trace line for the milestone's search. */
    static void note(int m, String line) {
        Roadmap.TriConsumer tc = Roadmap.trace;
        if (tc != null) {
            tc.accept(m, Slot.RING, line);
        }
    }

    /** A % for the trace, two places ("3.14"). */
    static String pct(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    /** A % change for the trace, signed ("+3.14"). */
    static String signedPct(double v) {
        return String.format(java.util.Locale.ROOT, "%+.2f", v);
    }

    /** For the trace: how the build's parts move with {@code cand} in {@code slot}, points spread anew. */
    static String parts(Map<String, Damage.Piece> base, String slot, Damage.Piece cand, int m, int earned) {
        Build.Eval a = Build.allocate(base, m, earned);
        Map<String, Damage.Piece> with = new LinkedHashMap<>(base);
        with.put(slot, cand);
        Build.Eval b = Build.allocate(with, m, earned);
        if (a == null || b == null) {
            String why = Build.whyNot(b == null ? with : base, earned);
            return why != null ? why : L.t("wynnlabs.road.trace_no_alloc");
        }
        Damage.Result x = a.result();
        Damage.Result y = b.result();
        double[] fx = x.factors();
        double[] fy = y.factors();
        StringBuilder f = new StringBuilder();
        String[] names = {"dmg", "life", "mana", "speed", "xp", "feel"};
        for (int i = 0; i < Math.min(fx.length, fy.length) && i < names.length; i++) {
            f.append(String.format(java.util.Locale.ROOT, " %s %.3f→%.3f", names[i], fx[i], fy[i]));
        }
        return String.format(java.util.Locale.ROOT,
                "obj %.0f→%.0f spell %.0f→%.0f main %.0f→%.0f ehp %.0f→%.0f casts %.2f→%.2f speed %.0f→%.0f xp %.0f→%.0f |%s",
                x.objective(), y.objective(), x.spellDps(), y.spellDps(), x.mainDps(), y.mainDps(), x.ehp(), y.ehp(),
                x.casts(), y.casts(), x.speed(), y.speed(), x.xp(), y.xp(), f);
    }

    /**
     * For the trace: why {@code cand} cannot go into {@code slot} of the milestone's set, null when
     * the skill points are not the reason. A piece no set can wear is skipped as impossible
     * ({@link Build#impossible}); one only this set cannot wear says what the set lacks
     * ({@link Build#whyNot}), and a weapon is then no skip at all: the search builds sets around
     * weapons ({@link SetSearch}) with the gear that gives the points.
     */
    static String whyOut(Map<String, Damage.Piece> baseline, String slot, Damage.Piece cand, Slot t, int earned,
            int[] reach) {
        String alone = Build.impossible(cand, earned, reach);
        if (alone != null) {
            return L.t("wynnlabs.road.trace_skip", alone);
        }
        Map<String, Damage.Piece> trial = new LinkedHashMap<>(baseline);
        trial.put(slot, cand);
        String why = Build.whyNot(trial, earned, reach);
        if (why == null) {
            return null;
        }
        return L.t(t.isWeapon() ? "wynnlabs.road.trace_around" : "wynnlabs.road.trace_skip", why);
    }

    /**
     * For the trace's reasons ({@link Build#whyNot}): the most points each skill could get from
     * the gear milestone {@code m} weighs (its window, the Mythics it keeps, never weapons) and tomes.
     */
    static int[] windowReach(Map<Slot, List<ItemData>> cands, int m, Map<String, Integer> laterVersion) {
        int floor = m - (int) Rules.sub("shopping", "milestoneWindow", 20);
        List<Damage.Piece> pieces = new ArrayList<>();
        cands.forEach((t, l) -> {
            if (!t.isWeapon()) {
                l.stream().filter(i -> i.requirements().level() <= m
                        && (i.requirements().level() >= floor || CandidateRules.lastOfItsKind(i, m, laterVersion)))
                        .forEach(i -> pieces.add(Damage.piece(i)));
            }
        });
        return Build.reach(pieces);
    }
}
