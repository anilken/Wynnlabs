package io.github.anilken.wynnlabs.build.roadmap;

import io.github.anilken.wynnlabs.build.Build;
import io.github.anilken.wynnlabs.build.Damage;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap.Ctx;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap.Scored;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.Slot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The search's local moves: from a set, the best single swap or pair of swaps out of each slot's
 * first few, until none helps by more than 0.2 %. Deterministic.
 */
final class LocalSearch {

    private LocalSearch() {}

    /**
     * Each free slot's first rules.roadmap.localPool (12) of its pool as pieces, the level's
     * only, in BEAM_ORDER: the slots the local search tries (the two rings share one).
     */
    static Map<String, List<Damage.Piece>> slotPools(Ctx c) {
        int n = (int) Rules.sub("roadmap", "localPool", 12);
        Map<String, List<Damage.Piece>> out = new LinkedHashMap<>();
        for (String slot : Roadmap.BEAM_ORDER) {
            if (c.fixed().contains(slot)) {
                continue;                                   // a chosen item stays
            }
            Slot t = slot.startsWith("RING") ? Slot.RING : slot.equals("WEAPON") ? null : Slot.valueOf(slot);
            List<ItemData> pool = t == null ? SetJudge.weaponPool(c.pools()) : c.pools().get(t);
            List<Damage.Piece> top = new ArrayList<>();
            for (ItemData info : pool == null ? List.<ItemData>of() : pool) {
                if (top.size() >= n) {
                    break;
                }
                if (info.requirements().level() <= c.m()) {
                    top.add(c.piece(info));
                }
            }
            if (!top.isEmpty()) {
                out.put(slot, top);
            }
        }
        return out;
    }

    /** A move the local search weighs: the set after it, its quick score, its spread, and whether that spread is the full one. */
    private record Move(Map<String, Damage.Piece> set, double quick, Build.Eval eval, boolean exact) {}

    /**
     * From {@code start}, the best move until none helps by more than 0.2 %: any one slot to one
     * of its first rules.roadmap.localPool (12), and once no single swap helps, any two slots at
     * once to their first rules.roadmap.localPairPool (8) each (a weapon of another element with
     * the armour it needs, or two pieces that only meet their skill points together). Moves are
     * scored quickly (only the points a piece needs move, from the spread of the swap before it),
     * the best rules.roadmap.localJudge (50) and every weapon change judged with the points
     * spread anew. Null when the start cannot be worn. Deterministic.
     */
    static Scored localSearch(Ctx c, Map<String, Damage.Piece> start, Map<String, List<Damage.Piece>> pools) {
        Map<String, Damage.Piece> b = new LinkedHashMap<>(start);
        Build.Eval ev = Build.allocate(b, c.m(), c.earned());
        if (ev == null) {
            return null;
        }
        Scored cur = new Scored(b, ev, SetJudge.judged(ev, c.prev(), c.m()));
        int one = (int) Rules.sub("roadmap", "localPool", 12);
        int two = (int) Rules.sub("roadmap", "localPairPool", 8);
        int rounds = (int) Rules.sub("roadmap", "localRounds", 40);
        for (int round = 0; round < rounds; round++) {
            Scored next = bestMove(c, cur, pools, one, 0);
            if (next == null && two > 0) {
                next = bestMove(c, cur, pools, one, two);
            }
            if (next == null) {
                break;
            }
            cur = next;
        }
        return cur;
    }

    /** The best move from {@code cur} that helps by more than 0.2 %, or null: single swaps, and pairs when {@code two} > 0. */
    private static Scored bestMove(Ctx c, Scored cur, Map<String, List<Damage.Piece>> pools, int one, int two) {
        int m = c.m();
        int earned = c.earned();
        Map<String, Damage.Piece> b = cur.set();
        List<String> free = new ArrayList<>(pools.keySet());
        java.util.Set<String> seen = new java.util.HashSet<>();
        seen.add(SetJudge.setKey(b));
        List<Move> moves = new ArrayList<>();
        // slot + piece key → the single swap, whose spread the pairs start from
        Map<String, Move> singles = new HashMap<>();
        int reach = Math.max(one, two);
        for (String slot : free) {
            List<Damage.Piece> pool = pools.get(slot);
            for (int i = 0; i < pool.size() && i < reach; i++) {
                Damage.Piece p = pool.get(i);
                if (SetJudge.same(b.get(slot), p)) {
                    continue;
                }
                Map<String, Damage.Piece> s = SetJudge.with(b, slot, p);
                if (SetJudge.clashes(s)) {
                    continue;
                }
                Roadmap.tick();
                // a weapon sets the element: its points are spread anew, the rest move what they need
                boolean weapon = slot.equals("WEAPON");
                Build.Eval e = weapon ? Build.allocate(s, m, earned) : Build.tryEval(b, cur.eval().allocation(), slot, p, m, earned);
                if (e == null) {
                    continue;
                }
                Move mv = new Move(s, SetJudge.judged(e, c.prev(), m), e, weapon);
                singles.put(slot + "\n" + p.key(), mv);
                if (i < one && seen.add(SetJudge.setKey(s))) {
                    moves.add(mv);
                }
            }
        }
        if (two > 0) {
            for (int x = 0; x < free.size(); x++) {
                String sa = free.get(x);
                List<Damage.Piece> pa = pools.get(sa);
                for (int i = 0; i < pa.size() && i < two; i++) {
                    Damage.Piece a = pa.get(i);
                    if (SetJudge.same(b.get(sa), a)) {
                        continue;
                    }
                    // one that cannot be worn alone may fit with the other (it gives the points)
                    Move single = singles.get(sa + "\n" + a.key());
                    Map<String, Damage.Piece> base = SetJudge.with(b, sa, a);
                    int[] alloc = single != null ? single.eval().allocation() : cur.eval().allocation();
                    for (int y = x + 1; y < free.size(); y++) {
                        String sb = free.get(y);
                        List<Damage.Piece> pb = pools.get(sb);
                        for (int k = 0; k < pb.size() && k < two; k++) {
                            Damage.Piece q = pb.get(k);
                            if (SetJudge.same(base.get(sb), q)) {
                                continue;
                            }
                            Map<String, Damage.Piece> s = SetJudge.with(base, sb, q);
                            if (!seen.add(SetJudge.setKey(s)) || SetJudge.clashes(s)) {
                                continue;
                            }
                            Roadmap.tick();
                            Build.Eval e = Build.tryEval(base, alloc, sb, q, m, earned);
                            if (e != null) {
                                moves.add(new Move(s, SetJudge.judged(e, c.prev(), m), e, false));
                            }
                        }
                    }
                }
            }
        }
        // the best few by the quick score, and every weapon change, judged with the points spread anew
        moves.sort((p, q) -> {
            int by = Double.compare(q.quick(), p.quick());
            return by != 0 ? by : SetJudge.setKey(p.set()).compareTo(SetJudge.setKey(q.set()));
        });
        int judge = (int) Rules.sub("roadmap", "localJudge", 50);
        Scored best = null;
        int judgedN = 0;
        for (Move mv : moves) {
            if (!mv.exact() && judgedN++ >= judge) {
                continue;
            }
            Roadmap.tick();
            Build.Eval e = mv.exact() ? mv.eval() : Build.allocate(mv.set(), m, earned);
            if (e == null) {
                continue;
            }
            Scored sc = new Scored(mv.set(), e, SetJudge.judged(e, c.prev(), m));
            if (sc.j() > cur.j() * 1.002 && (best == null || SetJudge.better(sc, best))) {
                best = sc;
            }
        }
        return best;
    }

    /**
     * Every single swap of the slot pools judged in full until none helps by more than 0.2 %,
     * each one found searched on ({@link #localSearch}): the quick scores rank a piece by the
     * points it needs, and a piece that pays only with the points spread anew can sit below the
     * ones judged.
     */
    static Scored oneOpt(Ctx c, Scored inc, Map<String, List<Damage.Piece>> pools) {
        int one = (int) Rules.sub("roadmap", "localPool", 12);
        for (int pass = 0; pass < 8; pass++) {
            Scored best = null;
            for (var e : pools.entrySet()) {
                String slot = e.getKey();
                List<Damage.Piece> pool = e.getValue();
                for (int i = 0; i < pool.size() && i < one; i++) {
                    Damage.Piece p = pool.get(i);
                    if (SetJudge.same(inc.set().get(slot), p)) {
                        continue;
                    }
                    Map<String, Damage.Piece> s = SetJudge.with(inc.set(), slot, p);
                    if (SetJudge.clashes(s)) {
                        continue;
                    }
                    Roadmap.tick();
                    Build.Eval ev = Build.allocate(s, c.m(), c.earned());
                    if (ev == null) {
                        continue;
                    }
                    Scored sc = new Scored(s, ev, SetJudge.judged(ev, c.prev(), c.m()));
                    if (sc.j() > inc.j() * 1.002 && (best == null || SetJudge.better(sc, best))) {
                        best = sc;
                    }
                }
            }
            if (best == null) {
                return inc;
            }
            RoadmapTrace.note(c.m(), L.t("wynnlabs.road.trace_local_swap",
                    RoadmapTrace.signedPct((best.j() / inc.j() - 1) * 100)));
            Scored on = localSearch(c, best.set(), pools);
            inc = on != null && SetJudge.better(on, best) ? on : best;
        }
        return inc;
    }
}
