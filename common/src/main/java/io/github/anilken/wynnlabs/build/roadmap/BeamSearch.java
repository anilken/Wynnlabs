package io.github.anilken.wynnlabs.build.roadmap;

import io.github.anilken.wynnlabs.build.Build;
import io.github.anilken.wynnlabs.build.Damage;
import io.github.anilken.wynnlabs.build.Exclusive;
import io.github.anilken.wynnlabs.build.TreeModel;
import io.github.anilken.wynnlabs.build.Tuning;
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
 * The search's wide passes over whole sets: the beam that fills a set slot by slot from the
 * milestone's start, and the exact search over every combination around the beam's set (off
 * unless rules.roadmap.exactPool asks for it).
 */
final class BeamSearch {

    private BeamSearch() {}

    private record BeamState(Map<String, Damage.Piece> build, int[] alloc, double score) {}

    /**
     * A wide search over whole sets, after build-solver's beam (RawFish69/build-solver, written
     * anew): from the milestone's starting set, slot by slot in BEAM_ORDER, every kept partial
     * set tries each of the slot's best rules.roadmap.jointPool (40) or keeps what it has; the
     * rules.roadmap.beamWidth (200) best by a quick score go on (the weapon spreads the points
     * anew, the rest move only what the piece needs), no weapon holding more than a quarter of
     * them, so a weapon of another element keeps its sets to the end. The best few at the end
     * (rules.roadmap.beamFinal, 40) are judged with the points spread anew: those, best first.
     */
    static List<Scored> beamBest(Ctx c, Map<String, Damage.Piece> build, Build.Eval s0) {
        int m = c.m();
        int earned = c.earned();
        int[] prev = c.prev();
        int width = (int) Rules.sub("roadmap", "beamWidth", 200);
        int perWeapon = Math.max(1, width / 4);
        List<BeamState> beam = new ArrayList<>(List.of(new BeamState(new LinkedHashMap<>(c.start()), s0.allocation(),
                SetJudge.judged(s0, prev, m))));
        for (String slot : Roadmap.BEAM_ORDER) {
            Slot t = slot.startsWith("RING") ? Slot.RING : slot.equals("WEAPON") ? null : Slot.valueOf(slot);
            List<ItemData> pool = t == null ? SetJudge.weaponPool(c.pools()) : c.pools().get(t);
            if (pool == null || pool.isEmpty() || c.fixed().contains(slot)) {
                continue;                                           // a chosen item stays
            }
            List<BeamState> next = new ArrayList<>(beam);          // keeping the slot as it is
            for (BeamState st : beam) {
                Damage.Piece there = st.build().get(slot);
                for (ItemData info : pool) {
                    if (info.requirements().level() > m) {
                        continue;
                    }
                    Damage.Piece cand = c.piece(info);
                    if (there != null && there.key().equals(cand.key())
                            || Exclusive.clash(st.build(), slot, Exclusive.group(info)) != null) {
                        continue;
                    }
                    Roadmap.tick();
                    Build.Eval ev = t == null ? SetJudge.fullEval(st.build(), slot, cand, m, earned)
                            : Build.tryEval(st.build(), st.alloc(), slot, cand, m, earned);
                    if (ev == null) {
                        continue;
                    }
                    Map<String, Damage.Piece> b = new LinkedHashMap<>(st.build());
                    b.put(slot, cand);
                    next.add(new BeamState(b, ev.allocation(), SetJudge.judged(ev, prev, m)));
                }
            }
            // the quick score moves only the points a piece needs: the best few by it are scored
            // again with the points spread anew, so a set that needs a new spread is not cut early
            next.sort((x, y) -> Double.compare(y.score(), x.score()));
            int exact = (int) Rules.sub("roadmap", "beamExact", 20);
            for (int i = 0; i < next.size() && i < exact; i++) {
                BeamState st = next.get(i);
                Build.Eval ev = Build.allocate(st.build(), m, earned);
                if (ev != null) {
                    next.set(i, new BeamState(st.build(), ev.allocation(), SetJudge.judged(ev, prev, m)));
                }
            }
            // the same set reached two ways counts once (the two rings in either hand too); a
            // weapon fills at most a quarter, the rest of the width after every weapon had its turn
            Map<String, BeamState> seen = new LinkedHashMap<>();
            Map<String, Integer> byWeapon = new HashMap<>();
            next.sort((x, y) -> Double.compare(y.score(), x.score()));
            List<BeamState> over = new ArrayList<>();
            for (BeamState st : next) {
                if (seen.size() >= width) {
                    break;
                }
                String key = SetJudge.setKey(st.build());
                if (seen.containsKey(key)) {
                    continue;
                }
                Damage.Piece w = st.build().get("WEAPON");
                String wk = w == null ? "-" : w.key();
                if (byWeapon.merge(wk, 1, Integer::sum) > perWeapon) {
                    over.add(st);
                    continue;
                }
                seen.put(key, st);
            }
            for (BeamState st : over) {
                if (seen.size() >= width) {
                    break;
                }
                seen.putIfAbsent(SetJudge.setKey(st.build()), st);
            }
            beam = new ArrayList<>(seen.values());
            beam.sort((x, y) -> Double.compare(y.score(), x.score()));
        }
        // judge the best few with the points spread anew
        List<Scored> out = new ArrayList<>();
        int fin = (int) Rules.sub("roadmap", "beamFinal", 40);
        for (int i = 0; i < beam.size() && i < fin; i++) {
            Build.Eval ev = Build.allocate(beam.get(i).build(), m, earned);
            if (ev != null) {
                out.add(new Scored(beam.get(i).build(), ev, SetJudge.judged(ev, prev, m)));
            }
        }
        out.sort((x, y) -> SetJudge.better(x, y) ? -1 : SetJudge.better(y, x) ? 1 : 0);
        return out;
    }

    /**
     * Every combination around the set the beam found: per slot what it holds and the best
     * rules.roadmap.exactPool - 1 (2) others by a single swap into it, the two rings as an
     * unordered pair (the same ring twice too). The beam scores half-built sets quickly and can
     * miss a set where several pieces change at once (on the recorded Shaman this finds 2 to 5 %
     * at Lv 44, 50 and 90; 2 to 7 s a milestone, but a wide beam alone does as well in 20 s, so it
     * is off unless rules.roadmap.exactPool is 2 or more). Judged in full on rules.roadmap.exactThreads low-priority
     * threads (at most 8, a third of the cores), so the game keeps its share. The best set, or
     * {@code inc} when none beats it by 0.2 %.
     */
    static Scored exactSearch(Ctx cx, Scored inc) {
        int k = (int) Rules.sub("roadmap", "exactPool", 0);
        if (k < 2) {
            return inc;
        }
        Map<String, Damage.Piece> build = inc.set();
        Map<Slot, List<ItemData>> pools = cx.pools();
        int m = cx.m();
        int earned = cx.earned();
        int[] prev = cx.prev();
        double curJ = inc.j();
        String[] slots = {"WEAPON", "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS", "RING1", "BRACELET", "NECKLACE"};
        Map<String, List<Damage.Piece>> cands = new LinkedHashMap<>();
        for (String slot : slots) {
            Slot t = slot.startsWith("RING") ? Slot.RING : slot.equals("WEAPON") ? null : Slot.valueOf(slot);
            List<ItemData> pool = t == null ? SetJudge.weaponPool(pools) : pools.get(t);
            List<Damage.Piece> top = new ArrayList<>();
            for (String held : slot.equals("RING1") ? new String[] {"RING1", "RING2"} : new String[] {slot}) {
                Damage.Piece h = build.get(held);
                if (h != null && top.stream().noneMatch(q -> q.key().equals(h.key()))) {
                    top.add(h);
                }
            }
            if (pool != null) {
                List<Object[]> ranked = new ArrayList<>();
                for (ItemData info : pool) {
                    Damage.Piece cand = cx.piece(info);
                    if (info.requirements().level() > m || top.stream().anyMatch(q -> q.key().equals(cand.key()))
                            || Exclusive.clash(build, slot, Exclusive.group(info)) != null) {
                        continue;
                    }
                    Build.Eval ev = SetJudge.fullEval(build, slot, cand, m, earned);
                    if (ev != null) {
                        ranked.add(new Object[] {cand, SetJudge.judged(ev, prev, m)});
                    }
                }
                ranked.sort((x, y) -> Double.compare((double) y[1], (double) x[1]));
                int room = k - 1 + (slot.equals("RING1") ? 1 : 0);
                for (int i = 0; i < ranked.size() && i < room; i++) {
                    top.add((Damage.Piece) ranked.get(i)[0]);
                }
            }
            if (!top.isEmpty()) {
                cands.put(slot, top);
            }
        }
        // the sets without their rings, then each with every ring pair
        List<Map<String, Damage.Piece>> sets = new ArrayList<>(List.of(new LinkedHashMap<>(build)));
        for (var e : cands.entrySet()) {
            if (e.getKey().equals("RING1")) {
                continue;
            }
            List<Map<String, Damage.Piece>> next = new ArrayList<>();
            for (Map<String, Damage.Piece> b : sets) {
                for (Damage.Piece p : e.getValue()) {
                    Map<String, Damage.Piece> c = new LinkedHashMap<>(b);
                    c.put(e.getKey(), p);
                    next.add(c);
                }
            }
            sets = next;
        }
        List<Damage.Piece[]> rings = new ArrayList<>();
        List<Damage.Piece> rs = cands.getOrDefault("RING1", List.of());
        for (int i = 0; i < rs.size(); i++) {
            for (int j = i; j < rs.size(); j++) {
                rings.add(new Damage.Piece[] {rs.get(i), rs.get(j)});
            }
        }
        if (rings.isEmpty()) {
            rings.add(new Damage.Piece[] {build.get("RING1"), build.get("RING2")});
        }
        Roadmap.TriConsumer tr = Roadmap.trace;
        if (tr != null) {
            StringBuilder c = new StringBuilder();
            cands.forEach((sl, ps) -> c.append(sl).append(ps.stream().map(Damage.Piece::name).toList()).append(' '));
            tr.accept(m, Slot.RING, L.t("wynnlabs.road.trace_exact_over", sets.size() * (long) rings.size(), c));
        }
        long started = System.nanoTime();
        double[] best = {curJ * 1.002};
        Object[] bestSet = {null};
        int threads = (int) Rules.sub("roadmap", "exactThreads",
                Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 3)));
        java.util.concurrent.ForkJoinPool fj = SetSearch.lowPriority(threads, "wynnlabs-exact");
        List<Map<String, Damage.Piece>> all = sets;
        try {
            Tuning.Choice tuned = Tuning.active();             // the workers score with the same tuning
            TreeModel.Overlay tree = TreeModel.active();       // and the milestone's tree
            fj.submit(() -> all.parallelStream().forEach(b -> Tuning.with(tuned, () -> TreeModel.with(tree, () -> {
                for (Damage.Piece[] rp : rings) {
                    Map<String, Damage.Piece> c = new LinkedHashMap<>(b);
                    c.put("RING1", rp[0]);
                    c.put("RING2", rp[1]);
                    if (SetJudge.clashes(c)) {
                        continue;
                    }
                    Build.Eval ev = Build.allocate(c, m, earned);
                    if (ev == null) {
                        continue;
                    }
                    double j = SetJudge.judged(ev, prev, m);
                    synchronized (best) {
                        if (j > best[0]) {
                            best[0] = j;
                            bestSet[0] = c;
                        }
                    }
                }
                return null;
            })))).get();
        } catch (Exception e) {
            if (tr != null) {
                tr.accept(m, Slot.RING, L.t("wynnlabs.road.trace_exact_failed", e));
            }
            return inc;                             // the beam's set stands
        } finally {
            fj.shutdown();
        }
        if (tr != null) {
            tr.accept(m, Slot.RING, L.t("wynnlabs.road.trace_exact_took", (System.nanoTime() - started) / 1_000_000,
                    Math.round(best[0]), Math.round(curJ)));
        }
        if (bestSet[0] == null) {
            return inc;
        }
        @SuppressWarnings("unchecked")
        Map<String, Damage.Piece> win = (Map<String, Damage.Piece>) bestSet[0];
        // the ring that is already in a hand stays in that hand
        Damage.Piece r1 = win.get("RING1");
        Damage.Piece r2 = win.get("RING2");
        if (r1 != null && r2 != null && build.get("RING2") != null && build.get("RING2").key().equals(r1.key())
                && (build.get("RING1") == null || !build.get("RING1").key().equals(r2.key()))) {
            win.put("RING1", r2);
            win.put("RING2", r1);
        }
        Build.Eval ev = Build.allocate(win, m, earned);
        if (ev == null) {
            return inc;
        }
        RoadmapTrace.note(m, L.t("wynnlabs.road.trace_exact_set", RoadmapTrace.signedPct((best[0] / curJ - 1) * 100)));
        return new Scored(win, ev, SetJudge.judged(ev, prev, m));
    }
}
