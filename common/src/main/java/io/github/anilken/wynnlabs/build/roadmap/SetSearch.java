package io.github.anilken.wynnlabs.build.roadmap;

import io.github.anilken.wynnlabs.build.Build;
import io.github.anilken.wynnlabs.build.Damage;
import io.github.anilken.wynnlabs.build.Exclusive;
import io.github.anilken.wynnlabs.build.TreeModel;
import io.github.anilken.wynnlabs.build.Tuning;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap.Ctx;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap.Gear;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap.Scored;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap.Seed;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Perf;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.Slot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One milestone's search over whole sets, after the slot-by-slot picks: the beam
 * ({@link BeamSearch}), then a local search ({@link LocalSearch}) from many starts: the beam's
 * best few, whole sets put on or taken off ({@link SetSeeds}), the last milestone's set, what
 * you wear, each slot's best, and a set built around each strong weapon. Kept on disk by its
 * inputs ({@link BeamCache}), and the starts run on low-priority threads in a fixed order.
 */
final class SetSearch {

    private SetSearch() {}

    /**
     * The milestone's search: the best set it ends on replaces the picks, a row for each slot it
     * changes, when it beats them by more than 0.2 %. Kept on disk by its inputs
     * ({@link BeamCache}): a map that nothing changed for skips all of it.
     */
    static void search(Ctx c, Map<String, Damage.Piece> build, Map<String, String> placed, List<Gear> gear) {
        Build.Eval s0 = Build.allocate(c.start(), c.m(), c.earned());
        Build.Eval cur = Build.allocate(build, c.m(), c.earned());
        if (s0 == null || cur == null) {
            return;
        }
        // the same inputs give the same set: kept on disk per character, so a map that
        // nothing changed for comes back in seconds after a restart
        String ck = searchKey(c, build);
        Map<String, Damage.Piece> chosen = null;
        boolean hit = false;
        if (BeamCache.has(ck)) {
            List<Map<String, Damage.Piece>> from = new ArrayList<>(List.of(c.start(), build));
            if (c.worn() != null) {
                from.add(c.worn());
            }
            chosen = BeamCache.get(ck, from);
            hit = chosen != null || BeamCache.isNone(ck);
        }
        Roadmap.TriConsumer tc = Roadmap.trace;
        if (hit) {
            if (tc != null) {
                tc.accept(c.m(), Slot.RING, L.t("wynnlabs.road.trace_cache_hit",
                        chosen == null ? L.t("wynnlabs.road.trace_cache_none") : SetJudge.setKey(chosen)));
            }
        } else {
            Scored best = searchBest(c, build, cur, s0);
            double from = SetJudge.judged(cur, c.prev(), c.m());
            if (best.j() > from * 1.002 && !SetJudge.setKey(best.set()).equals(SetJudge.setKey(build))) {
                chosen = best.set();
                RoadmapTrace.note(c.m(), L.t("wynnlabs.road.trace_search_set",
                        RoadmapTrace.signedPct((best.j() / from - 1) * 100)));
            }
            BeamCache.put(ck, chosen);
        }
        if (chosen != null) {
            RoadmapRows.applySet(chosen, c.start(), build, placed, c.pools(), gear, c.m(), c.earned(), c.prev());
        }
    }

    /** The search itself: the best set it finds ({@code build} itself when nothing beats it). */
    static Scored searchBest(Ctx c, Map<String, Damage.Piece> build, Build.Eval cur, Build.Eval s0) {
        int m = c.m();
        double from = SetJudge.judged(cur, c.prev(), m);
        long t = Perf.start();
        List<Scored> beam = BeamSearch.beamBest(c, build, s0);
        Perf.end("roadmap.beam", t);
        Scored inc = new Scored(new LinkedHashMap<>(build), cur, from);
        if (!beam.isEmpty() && beam.get(0).j() > from * 1.002) {
            inc = beam.get(0);
            RoadmapTrace.note(m, L.t("wynnlabs.road.trace_beam_set", RoadmapTrace.signedPct((inc.j() / from - 1) * 100)));
        }
        if (c.fixed().isEmpty()) {
            inc = BeamSearch.exactSearch(c, inc);              // its ring pairs know no chosen hand
        }
        t = Perf.start();
        Map<String, List<Damage.Piece>> main = LocalSearch.slotPools(c);
        Scored first = LocalSearch.localSearch(c, inc.set(), main);
        if (first != null && SetJudge.better(first, inc)) {
            RoadmapTrace.note(m, L.t("wynnlabs.road.trace_local_search",
                    RoadmapTrace.signedPct((first.j() / inc.j() - 1) * 100)));
            inc = first;
        }
        Perf.end("roadmap.local", t);
        // the other starts, each searched to its own end; the best end wins
        t = Perf.start();
        java.util.Set<String> seen = new java.util.HashSet<>();
        seen.add(SetJudge.setKey(build));
        seen.add(SetJudge.setKey(inc.set()));
        inc = runStarts(c, starts(c, inc, beam, build, main, seen), inc, main);
        Perf.end("roadmap.starts", t);
        // whole sets again from where that ended (an armour set, then one of accessories)
        t = Perf.start();
        int rounds = (int) Rules.sub("roadmap", "setRounds", 3);
        for (int round = 1; round < rounds; round++) {
            List<Start> more = new ArrayList<>();
            for (Seed s : SetSeeds.setSeeds(inc.set(), inc.eval(), c.pools(), c.sets(), c.owned(), c.buyable(), c.fixed(), m,
                    c.earned(), c.prev())) {
                addStart(more, seen, c, build, L.t("wynnlabs.road.trace_start_set_round", round + 1), s.build());
            }
            if (more.isEmpty()) {
                break;
            }
            Scored was = inc;
            inc = runStarts(c, more, inc, main);
            if (was == inc) {
                break;
            }
        }
        Perf.end("roadmap.sets", t);
        // and every single swap of the slot pools judged in full: what the quick scores missed
        t = Perf.start();
        inc = LocalSearch.oneOpt(c, inc, main);
        Perf.end("roadmap.local", t);
        Roadmap.Searched hook = Roadmap.searched;
        if (hook != null) {
            hook.accept(m, c.earned(), c.prev(), main, inc);
        }
        return inc;
    }

    /**
     * Where a local search starts: a set as it is, or (with {@code weapon}) a set built around
     * that weapon ({@link #aroundWeapon}), and why, for the trace.
     */
    private record Start(String why, Map<String, Damage.Piece> set, ItemData weapon) {}

    /** Adds a start unless it is one already tried; the chosen items stay in their slots. */
    private static void addStart(List<Start> out, java.util.Set<String> seen, Ctx c, Map<String, Damage.Piece> build,
            String why, Map<String, Damage.Piece> set) {
        if (set == null) {
            return;
        }
        Map<String, Damage.Piece> s = new LinkedHashMap<>(set);
        for (String f : c.fixed()) {
            s.put(f, build.get(f));
        }
        if (!SetJudge.clashes(s) && seen.add(SetJudge.setKey(s))) {
            out.add(new Start(why, s, null));
        }
    }

    /**
     * The starts besides the beam's best, in a fixed order: the beam's next best
     * (rules.roadmap.beamStarts, 4), every whole set put on or taken off ({@link SetSeeds#setSeeds}), the
     * last milestone's set, what you wear, each slot's best piece, and a set around each of the
     * strongest weapons ({@link #weaponStarts}).
     */
    private static List<Start> starts(Ctx c, Scored inc, List<Scored> beam, Map<String, Damage.Piece> build,
            Map<String, List<Damage.Piece>> main, java.util.Set<String> seen) {
        List<Start> out = new ArrayList<>();
        int more = (int) Rules.sub("roadmap", "beamStarts", 4);
        for (int i = 0, n = 0; i < beam.size() && n < more; i++) {
            int had = out.size();
            addStart(out, seen, c, build, L.t("wynnlabs.road.trace_start_beam", i + 1), beam.get(i).set());
            n += out.size() - had;
        }
        for (Seed s : SetSeeds.setSeeds(inc.set(), inc.eval(), c.pools(), c.sets(), c.owned(), c.buyable(), c.fixed(), c.m(),
                c.earned(), c.prev())) {
            addStart(out, seen, c, build, L.t("wynnlabs.road.trace_start_set"), s.build());
        }
        addStart(out, seen, c, build, L.t("wynnlabs.road.trace_start_last"), c.start());
        addStart(out, seen, c, build, L.t("wynnlabs.road.trace_start_worn"), c.worn());
        Map<String, Damage.Piece> fresh = new LinkedHashMap<>(inc.set());
        main.forEach((slot, pool) -> fresh.put(slot, pool.get(0)));
        addStart(out, seen, c, build, L.t("wynnlabs.road.trace_start_each"), fresh);
        for (ItemData w : weaponStarts(c, inc)) {
            out.add(new Start(L.t("wynnlabs.road.trace_start_weapon", w.name()), inc.set(), w));
        }
        return out;
    }

    /**
     * The weapons a set is built around: the first rules.roadmap.weaponStarts of the weapon pool
     * (6 from the endgame level on, 3 before), then every Mythic and every weapon you have that
     * the milestone allows, at most rules.roadmap.weaponStartsMax (16). A weapon judged as a
     * swap into armour made for another element mostly loses, Mythics most of all.
     */
    private static List<ItemData> weaponStarts(Ctx c, Scored inc) {
        if (c.fixed().contains("WEAPON")) {
            return List.of();
        }
        int k = (int) (CandidateRules.endgame(c.m()) ? Rules.sub("roadmap", "weaponStarts", 6)
                : Rules.sub("roadmap", "weaponStartsEarly", 3));
        int max = (int) Rules.sub("roadmap", "weaponStartsMax", 16);
        Damage.Piece held = inc.set().get("WEAPON");
        java.util.Set<String> names = new java.util.HashSet<>();
        if (held != null) {
            names.add(held.name().toLowerCase(java.util.Locale.ROOT));
        }
        List<ItemData> out = new ArrayList<>();
        List<ItemData> ranked = SetJudge.weaponPool(c.pools());
        for (ItemData info : ranked == null ? List.<ItemData>of() : ranked) {
            if (out.size() >= k) {
                break;
            }
            if (info.requirements().level() <= c.m() && names.add(info.name().toLowerCase(java.util.Locale.ROOT))) {
                out.add(info);
            }
        }
        List<ItemData> all = new ArrayList<>();
        c.wide().forEach((t, l) -> {
            if (t.isWeapon()) {
                all.addAll(l);
            }
        });
        for (ItemData info : all) {
            if (out.size() >= max) {
                break;
            }
            boolean mythic = info.tier().name().equals("MYTHIC");
            if ((mythic || c.owned().containsKey(info.name())) && info.requirements().level() <= c.m()
                    && names.add(info.name().toLowerCase(java.util.Locale.ROOT))) {
                out.add(info);
            }
        }
        return out;
    }

    /**
     * A set built around {@code weapon}: it goes into {@code base}, the pieces it cannot be worn
     * with come off ({@link Build#fitAround}), then slot by slot in BEAM_ORDER every piece the
     * milestone allows there ({@code wide}) is ranked with that weapon in and the best goes in.
     * Each slot's ranking (its first rules.roadmap.localPool) is the pool the local search tries
     * from there, so armour made for this weapon is not cut by a ranking made for another.
     * Null when it cannot be worn.
     */
    private static Scored aroundWeapon(Ctx c, Map<String, Damage.Piece> base, ItemData weapon,
            Map<String, List<Damage.Piece>> main, Map<String, List<Damage.Piece>> pools) {
        int m = c.m();
        int earned = c.earned();
        Map<String, Damage.Piece> b = new LinkedHashMap<>(base);
        b.put("WEAPON", c.piece(weapon));
        java.util.Set<String> keep = new java.util.HashSet<>(c.fixed());
        keep.add("WEAPON");
        Build.fitAround(b, keep, earned);
        // a weapon that asks for more than 100 in a skill (a Mythic's 110 Strength) is worn only
        // with gear that gives it: the piece that brings the set closest to wearable goes in
        // until it is (the first such in the milestone's order on a tie)
        for (int k = 0; k < Roadmap.BEAM_ORDER.length && Build.shortfall(b, earned) > 0; k++) {
            int now = Build.shortfall(b, earned);
            String bestSlot = null;
            Damage.Piece bestPiece = null;
            for (String slot : Roadmap.BEAM_ORDER) {
                if (keep.contains(slot)) {
                    continue;
                }
                Slot t = slot.startsWith("RING") ? Slot.RING : Slot.valueOf(slot);
                for (ItemData info : c.wide().getOrDefault(t, List.of())) {
                    if (info.requirements().level() > m || Exclusive.clash(b, slot, Exclusive.group(info)) != null) {
                        continue;
                    }
                    int s = Build.shortfall(SetJudge.with(b, slot, c.piece(info)), earned);
                    if (s < now) {
                        now = s;
                        bestSlot = slot;
                        bestPiece = c.piece(info);
                    }
                }
            }
            if (bestSlot == null) {
                return null;
            }
            b.put(bestSlot, bestPiece);
        }
        Build.Eval ev = Build.allocate(b, m, earned);
        if (ev == null) {
            return null;
        }
        int n = (int) Rules.sub("roadmap", "localPool", 12);
        if (main.containsKey("WEAPON")) {
            pools.put("WEAPON", main.get("WEAPON"));
        }
        for (String slot : Roadmap.BEAM_ORDER) {
            if (slot.equals("WEAPON") || c.fixed().contains(slot)) {
                continue;
            }
            Slot t = slot.startsWith("RING") ? Slot.RING : Slot.valueOf(slot);
            double hereJ = b.get(slot) == null ? Double.NEGATIVE_INFINITY : SetJudge.judged(ev, c.prev(), m);
            List<Object[]> ranked = new ArrayList<>();       // {piece, judged quickly}
            for (ItemData info : c.wide().getOrDefault(t, List.of())) {
                if (info.requirements().level() > m) {
                    continue;
                }
                Damage.Piece p = c.piece(info);
                if (SetJudge.same(b.get(slot), p)) {
                    ranked.add(new Object[] {p, hereJ});
                    continue;
                }
                if (Exclusive.clash(b, slot, Exclusive.group(info)) != null) {
                    continue;
                }
                Roadmap.tick();
                Build.Eval e = Build.tryEval(b, ev.allocation(), slot, p, m, earned);
                if (e != null) {
                    ranked.add(new Object[] {p, SetJudge.judged(e, c.prev(), m)});
                }
            }
            ranked.sort((x, y) -> {
                int by = Double.compare((double) y[1], (double) x[1]);
                return by != 0 ? by : ((Damage.Piece) x[0]).key().compareTo(((Damage.Piece) y[0]).key());
            });
            List<Damage.Piece> top = new ArrayList<>();
            for (int i = 0; i < ranked.size() && i < n; i++) {
                top.add((Damage.Piece) ranked.get(i)[0]);
            }
            if (!top.isEmpty()) {
                pools.put(slot, top);
            }
            if (!ranked.isEmpty() && (double) ranked.get(0)[1] > hereJ && !SetJudge.same(b.get(slot), top.get(0))) {
                Map<String, Damage.Piece> s = SetJudge.with(b, slot, top.get(0));
                Build.Eval e = Build.allocate(s, m, earned);
                if (e != null) {
                    b = s;
                    ev = e;
                }
            }
        }
        return new Scored(b, ev, SetJudge.judged(ev, c.prev(), m));
    }

    /**
     * Searches each start to its end on the low-priority threads and folds the ends in the
     * starts' order, so the result never depends on which thread was quicker. A start whose own
     * score, raised by rules.roadmap.startReach (10 %), still cannot reach the best so far is
     * not searched (the bar is the best before this round, the same however the work is split).
     * A set built around a weapon gets rules.roadmap.weaponStartReach (25 %): it is put together
     * slot by slot in one pass, and the search often lifts it by more than 10 % (a Mythic that
     * wants its own skill, Transfiguration at Lv 120 started 10.2 % behind and was never searched).
     */
    private static Scored runStarts(Ctx c, List<Start> starts, Scored inc, Map<String, List<Damage.Piece>> main) {
        if (starts.isEmpty()) {
            return inc;
        }
        double bar = inc.j();
        double reach = Rules.sub("roadmap", "startReach", 0.10);
        double weaponReach = Rules.sub("roadmap", "weaponStartReach", 0.25);
        List<java.util.function.Supplier<Scored>> work = new ArrayList<>();
        for (Start s : starts) {
            work.add(() -> {
                Map<String, List<Damage.Piece>> pools = main;
                Scored at;
                if (s.weapon() != null) {
                    pools = new LinkedHashMap<>();
                    at = aroundWeapon(c, s.set(), s.weapon(), main, pools);
                } else {
                    Build.Eval e = Build.allocate(s.set(), c.m(), c.earned());
                    at = e == null ? null : new Scored(s.set(), e, SetJudge.judged(e, c.prev(), c.m()));
                }
                if (at == null) {
                    RoadmapTrace.note(c.m(), L.t("wynnlabs.road.trace_start_unworn", s.why()));
                    return null;
                }
                if (at.j() * (1 + (s.weapon() != null ? weaponReach : reach)) < bar) {
                    RoadmapTrace.note(c.m(), L.t("wynnlabs.road.trace_start_behind", s.why(),
                            RoadmapTrace.signedPct((at.j() / bar - 1) * 100)));
                    return at;
                }
                Scored end = LocalSearch.localSearch(c, at.set(), pools);
                RoadmapTrace.note(c.m(), L.t("wynnlabs.road.trace_start_searched", s.why(),
                        RoadmapTrace.signedPct((at.j() / bar - 1) * 100),
                        RoadmapTrace.signedPct(end == null ? Double.NaN : (end.j() / bar - 1) * 100)));
                return end == null ? at : end;
            });
        }
        List<Scored> ends = inParallel(work);
        Scored best = inc;
        for (int i = 0; i < ends.size(); i++) {
            Scored e = ends.get(i);
            if (e != null && e.j() > best.j() * 1.002 && SetJudge.better(e, best)) {
                RoadmapTrace.note(c.m(), L.t("wynnlabs.road.trace_start_wins", starts.get(i).why(),
                        RoadmapTrace.signedPct((e.j() / best.j() - 1) * 100)));
                best = e;
            }
        }
        return best;
    }

    /**
     * Runs the work on rules.roadmap.searchThreads low-priority threads (a third of the cores, at
     * most 8) with this run's job, memo, tuning and the milestone's tree, and gives the results
     * in the work's order.
     */
    private static <T> List<T> inParallel(List<java.util.function.Supplier<T>> work) {
        int threads = (int) Rules.sub("roadmap", "searchThreads",
                Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 3)));
        List<T> out = new ArrayList<>();
        if (threads <= 1 || work.size() <= 1) {
            work.forEach(w -> out.add(w.get()));
            return out;
        }
        Roadmap.Job job = Roadmap.JOB.get();
        Build.Memo memo = Build.memo();
        Tuning.Choice tuned = Tuning.active();
        TreeModel.Overlay tree = TreeModel.active();
        java.util.concurrent.ForkJoinPool fj = lowPriority(threads, "wynnlabs-search");
        try {
            List<java.util.concurrent.Future<T>> running = new ArrayList<>();
            for (var w : work) {
                running.add(fj.submit(() -> {
                    Roadmap.JOB.set(job);
                    try {
                        return Tuning.with(tuned, () -> TreeModel.with(tree, () -> Build.withMemo(memo, w)));
                    } finally {
                        Roadmap.JOB.remove();
                    }
                }));
            }
            for (var f : running) {
                out.add(f.get());
            }
            return out;
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof RuntimeException r) {
                throw r;                                    // Cancelled among them
            }
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Roadmap.Cancelled();
        } finally {
            fj.shutdownNow();
        }
    }

    /** A pool of daemon threads at the lowest priority, so the game keeps its share. */
    static java.util.concurrent.ForkJoinPool lowPriority(int threads, String name) {
        return new java.util.concurrent.ForkJoinPool(Math.max(1, threads), fp -> {
            var th = java.util.concurrent.ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(fp);
            th.setPriority(Thread.MIN_PRIORITY);
            th.setDaemon(true);
            th.setName(name);
            return th;
        }, null, false);
    }

    /**
     * What a milestone's search depends on: the sets it starts from, the pools and everything the
     * milestone allows, the sets it can put on with what you have of them, what you wear, the
     * points before and the rules.
     */
    private static String searchKey(Ctx c, Map<String, Damage.Piece> build) {
        StringBuilder k = new StringBuilder();
        k.append(c.m()).append('|').append(c.earned()).append('|').append(java.util.Arrays.toString(c.prev())).append('|');
        k.append(SetJudge.setKey(c.start())).append('|').append(SetJudge.setKey(build)).append('|');
        // each item as the piece the search judges it as: a copy you have with its own rolls and
        // filled powder slots, or a fresh one to buy (the name alone kept a set chosen for another roll)
        c.pools().forEach((t, l) -> {
            k.append(t.name()).append(':');
            l.forEach(i -> k.append(i.name()).append('#').append(c.piece(i).key().hashCode()).append(','));
        });
        k.append("|wide:");
        c.wide().forEach((t, l) -> {
            k.append(t.name()).append(':');
            l.forEach(i -> k.append(i.name()).append('#').append(c.piece(i).key().hashCode()).append(','));
        });
        k.append("|sets:");
        c.sets().forEach((n, l) -> {
            k.append(n).append('{');
            l.forEach(i -> k.append(i.name()).append('#').append(c.piece(i).key().hashCode())
                    .append(c.buyable().contains(i.name()) ? "$" : "")
                    .append(c.owned().getOrDefault(i.name(), 0)).append(','));
            k.append('}');
        });
        k.append("|worn:").append(c.worn() == null ? "-" : SetJudge.setKey(c.worn()));
        k.append('|').append(Rules.hash());
        // the scoring model: results an older one chose are not reused
        k.append("|model:").append(BeamCache.modelVersion());
        if (!c.fixed().isEmpty()) {
            k.append("|fixed:").append(c.fixed());      // the chosen items' slots
        }
        Tuning.Choice tuned = Tuning.active();
        if (tuned != null) {
            k.append("|tuned:").append(tuned.key());   // the strip's weights score the sets otherwise
        }
        String tree = TreeModel.key();
        if (!tree.isEmpty()) {
            k.append("|tree:").append(tree);            // the nodes the milestone has: spells, costs, summons
        }
        return Integer.toHexString(k.toString().hashCode()) + Integer.toHexString(k.reverse().toString().hashCode());
    }
}
