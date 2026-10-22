package io.github.anilken.wynnlabs.build.roadmap;

import io.github.anilken.wynnlabs.build.Build;
import io.github.anilken.wynnlabs.build.Damage;
import io.github.anilken.wynnlabs.build.Exclusive;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.SkillKind;
import io.github.anilken.wynnlabs.model.Slot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How the road map scores and compares whole sets: the judged score (respec charge and keep
 * bonus), what the next milestone is judged against, a set's key, and the small set helpers the
 * search, the rows and the ranking share.
 */
final class SetJudge {

    private SetJudge() {}

    /**
     * A set's score with the respec charge and the keep bonus. The charge: the skill points it
     * takes away from the last milestone's set need a reset in town, and a road map that turns
     * Strength into Agility into Intelligence every ten levels is no plan. rules.roadmap.respecWeight
     * per 100 points taken back (0.2) for every 10 levels between the two milestones (a level apart
     * charges a tenth of it), half that once no new points come (past Lv 101) or against the points
     * you placed yourself ("now"), and never more than rules.roadmap.respecFloor leaves (0.85): a
     * set that is clearly better may change course. Points added are free: you earn them.
     * The bonus: rules.roadmap.keepBonusPercent (1 %) for each piece the set keeps from the last
     * milestone's, so of two sets about as good the one you mostly have already wins.
     * {@code prev}: as {@link #carry} makes it, only skill points (the ones you placed), or null.
     */
    static double judged(Build.Eval e, int[] prev, int m) {
        return judged(e, prev, m, e.result().pieces(), e.result().weapon());
    }

    /** {@link #judged} with {@code build}'s pieces, for a result that does not carry them (read back from disk). */
    static double judged(Map<String, Damage.Piece> build, Build.Eval e, int[] prev, int m) {
        List<Damage.Piece> pieces = new ArrayList<>();
        build.forEach((slot, p) -> {
            if (p != null && !slot.equals("WEAPON")) {
                pieces.add(p);
            }
        });
        return judged(e, prev, m, pieces, build.get("WEAPON"));
    }

    private static double judged(Build.Eval e, int[] prev, int m, List<Damage.Piece> pieces, Damage.Piece weapon) {
        // the effective health floor (what build-solver asks as min EHP) is part of the score
        // itself now (Damage.ehpFloor), so the skill points are spread with it in mind too
        double o = e.result().objective();
        if (prev == null) {
            return o;
        }
        int n = SkillKind.values().length;
        int back = 0;
        for (int i = 0; i < n && i < prev.length && i < e.allocation().length; i++) {
            back += Math.max(0, prev[i] - e.allocation()[i]);
        }
        int from = prev.length > n ? prev[n] : 0;           // 0: the points you placed yourself
        double scale = from <= 0 ? 0.5 : Math.max(0, Math.min(1, (m - from) / 10.0));
        if (from > 0 && Math.min(m, 101) - Math.min(from, 101) <= 1) {
            scale *= 0.5;                                   // no new points: new gear can only move the old ones
        }
        double charge = Math.max(Rules.sub("roadmap", "respecFloor", 0.85),
                1 - Rules.sub("roadmap", "respecWeight", 0.2) * scale * back / 100.0);
        int kept = prev.length > n + 1 ? kept(prev, n + 1, pieces, weapon) : 0;
        return o * charge * (1 + Rules.sub("roadmap", "keepBonusPercent", 1) / 100.0 * kept);
    }

    /** How many of the pieces are among the names hashed in {@code prev} from {@code at} on (each name once). */
    private static int kept(int[] prev, int at, List<Damage.Piece> pieces, Damage.Piece weapon) {
        int used = 0;                                       // bit i: prev[at + i] matched already
        int n = 0;
        int count = pieces == null ? 0 : pieces.size();
        for (int k = 0; k <= count; k++) {
            Damage.Piece p = k < count ? pieces.get(k) : weapon;
            if (p == null) {
                continue;
            }
            int h = p.name().hashCode();
            for (int i = at; i < prev.length && i - at < 31; i++) {
                if (prev[i] == h && (used & 1 << (i - at)) == 0) {
                    used |= 1 << (i - at);
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    /**
     * What the next milestone's sets are judged against ({@link #judged}): this set's skill points
     * by skill, then its milestone's level, then the hash of each piece's name (for the keep bonus).
     */
    static int[] carry(Build.Eval set, Map<String, Damage.Piece> build, int m) {
        int n = SkillKind.values().length;
        List<Integer> names = new ArrayList<>();
        build.values().forEach(p -> {
            if (p != null) {
                names.add(p.name().hashCode());
            }
        });
        int[] out = new int[n + 1 + names.size()];
        System.arraycopy(set.allocation(), 0, out, 0, Math.min(n, set.allocation().length));
        out[n] = m;
        for (int i = 0; i < names.size(); i++) {
            out[n + 1 + i] = names.get(i);
        }
        return out;
    }

    /** {@code a} beats {@code b}: a higher score, or the same and the first set key (so any order of work ends the same). */
    static boolean better(Roadmap.Scored a, Roadmap.Scored b) {
        return a.j() > b.j() || a.j() == b.j() && setKey(a.set()).compareTo(setKey(b.set())) < 0;
    }

    /** {@code there} is the very piece {@code p} (same rolls and powders). */
    static boolean same(Damage.Piece there, Damage.Piece p) {
        return there != null && there.key().equals(p.key());
    }

    /** A copy of {@code b} with {@code p} in {@code slot}. */
    static Map<String, Damage.Piece> with(Map<String, Damage.Piece> b, String slot, Damage.Piece p) {
        Map<String, Damage.Piece> s = new LinkedHashMap<>(b);
        s.put(slot, p);
        return s;
    }

    /** Two pieces of one exclusive group (Ornate Shadow, the Hives …) in a set. */
    static boolean clashes(Map<String, Damage.Piece> set) {
        java.util.Set<String> groups = new java.util.HashSet<>();
        for (Damage.Piece p : set.values()) {
            String g = Exclusive.group(p);         // by name, or a set marked illegal
            if (g != null && !groups.add(g)) {
                return true;
            }
        }
        return false;
    }

    /** The weapon pool: the class's weapon type is the one slot type in pools that is a weapon. */
    static List<ItemData> weaponPool(Map<Slot, List<ItemData>> pools) {
        for (var e : pools.entrySet()) {
            if (e.getKey().isWeapon()) {
                return e.getValue();
            }
        }
        return null;
    }

    /** A set as its pieces, the two rings in either order the same. */
    static String setKey(Map<String, Damage.Piece> b) {
        StringBuilder k = new StringBuilder();
        for (String slot : Roadmap.BEAM_ORDER) {
            if (slot.equals("RING2")) {
                continue;
            }
            if (slot.equals("RING1")) {
                String r1 = b.get("RING1") == null ? "-" : b.get("RING1").key();
                String r2 = b.get("RING2") == null ? "-" : b.get("RING2").key();
                k.append(r1.compareTo(r2) <= 0 ? r1 + "|" + r2 : r2 + "|" + r1).append(';');
                continue;
            }
            k.append(b.get(slot) == null ? "-" : b.get(slot).key()).append(';');
        }
        return k.toString();
    }

    /** {@code build} with {@code cand} in {@code slot}, the skill points spread anew (a set tried); null if it cannot be worn. */
    static Build.Eval fullEval(Map<String, Damage.Piece> build, String slot, Damage.Piece cand, int m, int earned) {
        Roadmap.tick();
        Map<String, Damage.Piece> trial = new LinkedHashMap<>(build);
        trial.put(slot, cand);
        return Build.allocate(trial, m, earned);
    }

    /** The build's score with {@code cand} in {@code slot} and the skill points spread anew; NaN if it cannot be worn. */
    static double full(Map<String, Damage.Piece> build, String slot, Damage.Piece cand, int m, int earned) {
        Map<String, Damage.Piece> trial = new LinkedHashMap<>(build);
        trial.put(slot, cand);
        Build.Eval e = Build.allocate(trial, m, earned);
        return e == null ? Double.NaN : e.result().objective();
    }
}
