package io.github.anilken.wynnlabs.build.roadmap;

import io.github.anilken.wynnlabs.advice.AutoAdvice;
import io.github.anilken.wynnlabs.build.Build;
import io.github.anilken.wynnlabs.build.Damage;
import io.github.anilken.wynnlabs.build.Locks;
import io.github.anilken.wynnlabs.build.TreeModel;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap.Candidate;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap.Computed;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap.Ctx;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap.Gear;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap.Option;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap.Step;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap.Versus;
import io.github.anilken.wynnlabs.coach.Danger;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Perf;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.game.ItemJson;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.market.Shopping;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.Slot;
import io.github.anilken.wynnlabs.quests.Grind;
import io.github.anilken.wynnlabs.quests.QuestAdvisor;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One road map computation, milestone by milestone: what you have and may get, the items you
 * chose held in place, then at each milestone the slot-by-slot picks ({@link MilestonePicks}),
 * the search over whole sets ({@link SetSearch}), the other hand's ring, the community targets,
 * the budget, each row's step and gain over what you wear, the abilities and places, and the
 * skill points the next milestone is judged against. Runs with the run's tuning, tree and memo
 * already in force ({@link Roadmap#compute}).
 */
final class MilestonePlanner {

    /** Milestones after "now": every multiple of 10 up to the level cap (Lv 50, 60 … 120). */
    private static final int STEP = 10;

    private final Roadmap.Inputs in;
    private final Roadmap.Job job;
    final GameState state;
    private final String forId;                 // a switch mid-build must not publish this map
    private final List<ItemData> lockedAll;
    private final Locks.Problem problem0;
    /** The level the map starts at: yours, or the highest chosen item's when they fit together there. */
    final int level;
    final long budget;
    /** What you have (worn, carried, banked) by name and count: it costs nothing under a budget. */
    final Map<String, Integer> ownedCount;
    final java.util.Set<String> owned;
    final java.util.function.Predicate<ItemData> affordable;
    final java.util.function.Predicate<ItemData> overBudget;
    /** The slot types in the order each milestone picks them: the weapon first. */
    private final List<Slot> slots;
    /** Per slot type, what you wear scored like the shopping list (the picks without a build plan). */
    final Map<Slot, Double> best;
    final List<String> preferred;
    private final List<Integer> levels;
    /** Candidates per slot type, once for the run. */
    final Map<Slot, List<ItemData>> cands;
    private final Map<String, Integer> abilities;
    private final TreeModel.Run treeRun;
    private final java.util.Set<String> placesShown = new java.util.HashSet<>();
    private final List<Step> steps = new ArrayList<>();
    /** The last milestone's level (before the first: one below yours). */
    private int prev;
    final Build.Plan plan;
    /** The set the milestones build on, pick by pick (null without a build plan). */
    final Map<String, Damage.Piece> baseline;
    /** What you wear now, for the "worn → pick" gain of each milestone (Step.vsWorn). */
    private final Map<String, Damage.Piece> wornNow;
    /** Slot type name → build slot of its latest pick. */
    final Map<String, String> placed = new LinkedHashMap<>();
    /** The last milestone's set's skill points ({@link SetJudge#carry}); before the first, the ones you placed. */
    int[] prevAlloc;
    // piece key → its row number and reasons from the milestone that first picked it
    private final Map<String, Double> firstGain = new HashMap<>();
    private final Map<String, List<String>> firstWhy = new HashMap<>();
    private List<ItemData> locked;
    private Locks.Problem problem;
    /** Build slots the chosen items hold. */
    final java.util.Set<String> fixed = new java.util.LinkedHashSet<>();
    /** Slot kinds with nothing left to pick. */
    private final java.util.Set<Slot> lockedKinds = java.util.EnumSet.noneOf(Slot.class);
    /** The chosen items' rows: the first milestone's. */
    List<Gear> lockRows;
    private boolean ringFixed;
    /** The milestones where the budget left a pick out. */
    final java.util.Set<Integer> budgetCut = new java.util.TreeSet<>();
    private List<Candidate> ranked0 = List.of();

    /** The road map for {@code in}; null outside the game. */
    static Computed compute(Roadmap.Inputs in, Roadmap.Job job) {
        GameState state = GameState.current();
        if (!state.inGame()) {
            return null;
        }
        return new MilestonePlanner(in, job, state).run();
    }

    private MilestonePlanner(Roadmap.Inputs in, Roadmap.Job job, GameState state) {
        this.in = in;
        this.job = job;
        this.state = state;
        forId = state.characterId();
        // The items you chose (Locks), at any level: a set around a piece above your level is
        // planned for the level it needs (the highest chosen item's), which then sets the skill
        // points and the other slots' candidates. Pieces that cannot be worn together there:
        // the map as without them, and why (problem)
        lockedAll = Locks.resolve(state, in.around());
        int planLevel = Math.max(state.level(), lockedAll.stream().mapToInt(ItemData::level).max().orElse(0));
        problem0 = Locks.problem(lockedAll, planLevel);
        int level = problem0 == null ? planLevel : state.level();
        this.level = level;
        long budget = in.tuning().budget();
        this.budget = budget;
        // what you have (worn, carried, banked): it costs nothing under a budget
        ownedCount = OwnedGear.ownedCounts(state);
        java.util.Set<String> owned = ownedCount.keySet();
        this.owned = owned;
        // and the copies you have with their real rolls: a 0 % copy is judged as one, not as a middle roll
        OwnedGear.readOwned(state);
        // (a ring by name is no proof: the second hand's copy is a buy). A price that is only
        // history over the budget is no skip: it costs the piece a little in the ranking
        // (overBudget) instead, since the market may well have it cheaper now
        affordable = info -> budget <= 0
                || info.slot() != Slot.RING && owned.contains(info.name())
                || CandidateRules.cost(state, info) >= 0 && (CandidateRules.cost(state, info) <= budget
                        || CandidateRules.uncertainPrice(state, info));
        overBudget = info -> budget > 0
                && !(info.slot() != Slot.RING && owned.contains(info.name())) && CandidateRules.cost(state, info) > budget;
        Klass cls = state.classType();
        Slot weapon = Slot.weaponOf(cls);
        slots = new ArrayList<>(List.of(Slot.HELMET, Slot.CHESTPLATE, Slot.LEGGINGS, Slot.BOOTS, Slot.RING,
                Slot.BRACELET, Slot.NECKLACE));
        if (weapon != null) {
            slots.add(0, weapon);           // first: it sets the element, and without one nothing scores
        }

        // What you wear now, scored like the shopping list
        Map<Slot, Double> best = new EnumMap<>(Slot.class);
        for (Slot t : slots) {
            double weakest = Double.MAX_VALUE;
            for (ItemStack w : AutoAdvice.worn(t)) {
                double s = AutoAdvice.scoreAny(w);
                weakest = Math.min(weakest, Double.isNaN(s) ? -1 : s);
            }
            best.put(t, weakest == Double.MAX_VALUE ? -1 : weakest);
        }
        this.best = best;

        preferred = Rules.list("shopping", "preferredSkills");
        levels = in.nowOnly() ? List.of(level) : milestones(level);
        int last = levels.get(levels.size() - 1);
        // tiers and sources go by each milestone's level (badTiers, badSources): the pool holds
        // what the last milestone allows, each milestone filters again. The off-skill limit is
        // only checked there, against that milestone's skill points: a piece asking for 60
        // Dexterity costs nothing off-skill where the spread holds 80 of it anyway
        List<String> badTiersLast = CandidateRules.badTiers(last);

        // Candidates per slot, once
        Map<Slot, List<ItemData>> cands = new EnumMap<>(Slot.class);
        state.allGear().forEach(info -> {
            Slot t = info.slot();
            int lv = info.requirements().level();
            // what you have is weighed below the level window too: the bag or bank may hold a set
            // that only pays off whole (the Wynnterfest ornaments at Lv 50 on a Lv 72 build)
            if (!best.containsKey(t) || lv > last || lv < level - 8 && !owned.contains(info.name())
                    || badTiersLast.contains(info.tier().name())
                    || Shopping.walkPenalty(info) > Rules.sub("shopping", "maxWalkSpeedPenalty", 999)
                    || info.base().attackSpeed().isPresent()
                            && Rules.list("shopping", "excludeAttackSpeeds").contains(info.base().attackSpeed().get())) {
                return;
            }
            Klass need = info.requirements().klass().orElse(t.klass());
            if (need != null && need != Klass.NONE && need != cls) {
                return;
            }
            cands.computeIfAbsent(t, k -> new ArrayList<>()).add(info);
        });
        this.cands = cands;

        abilities = state.abilityPlan();
        // the tree read once for the run; each milestone scores with the nodes it will have,
        // the chosen items below with the ones you have now
        treeRun = TreeModel.run();
        TreeModel.use(treeRun == null ? null : treeRun.at(state.level()));
        prev = level - 1;
        plan = Build.plan();
        // what you have gets its empty powder slots filled as a piece you would buy has them, or
        // every buy gained a powdered piece's worth over it (powderRows says what powdering adds)
        baseline = plan == null ? null : OwnedGear.powdered(plan.pieces());
        wornNow = plan == null ? null
                : OwnedGear.powdered(plan.worn() != null ? plan.worn() : plan.pieces());
        // before the first milestone, the skill points you placed (when the Character Info menu
        // showed them), so "now" does not quietly take your Defence away
        prevAlloc = OwnedGear.assignedPoints(state);
    }

    /** The levels the road map looks at: yours, then every 10 levels up to 120. */
    private static List<Integer> milestones(int level) {
        List<Integer> out = new ArrayList<>(List.of(level));
        for (int m = (level / STEP + 1) * STEP; m <= 120; m += STEP) {
            out.add(m);
        }
        return out;
    }

    private Computed run() {
        // The items you chose to build around (Locks): held in their slots on every milestone, the
        // rest fitted to them. When they cannot be worn together the map is built as without them
        // and says why (problem)
        Roadmap.phase(0);
        locked = lockedAll;
        problem = problem0;
        if (problem != null) {
            locked = List.of();
        }
        long lockedRings = locked.stream().filter(i -> Locks.kind(i) == Slot.RING).count();
        for (ItemData info : locked) {
            Slot k = Locks.kind(info);
            if (k != Slot.RING || lockedRings >= Locks.RINGS) {
                lockedKinds.add(k);
            }
        }
        if (!locked.isEmpty()) {
            Roadmap.phase(3);
        }
        lockRows = locked.isEmpty() ? List.of() : lockIn(locked, baseline, placed, fixed, level);
        ringFixed = fixed.contains("RING1") || fixed.contains("RING2");
        if (job == null) {
            Roadmap.progressTotal = levels.size();
            Roadmap.progressDone = 0;
        } else {
            job.total = levels.size() + (in.builds() > 0 ? 1 : 0);
            job.done = 0;
        }
        for (int m : levels) {
            if (m <= prev) {
                continue;
            }
            milestone(m);
        }
        return new Computed(steps, ranked0, locked.stream().map(ItemData::name).toList(), problem, budgetCut, forId, level);
    }

    /** One milestone: its picks, the search, the rows' numbers, and its step. */
    private void milestone(int m) {
        Roadmap.phase(1);
        // the ability tree you will have by then: your nodes now, the plan's up to this level
        TreeModel.use(treeRun == null ? null : treeRun.at(m));
        long perfPools = Perf.start();
        Build.Memo memo = Build.memo();
        if (memo != null) {
            memo.clear();                       // the spreads of the last milestone's level are never asked again
        }
        MilestonePicks ms = new MilestonePicks(this, m, steps.isEmpty());
        int earned = ms.earned;
        for (Slot t : slots) {
            if (lockedKinds.contains(t.isWeapon() ? Slot.WEAPON : t)) {
                continue;                               // a chosen item holds it
            }
            ms.pick(t);
        }
        Perf.end("roadmap.pools", perfPools);
        List<Gear> gear = ms.gear;
        // Slot by slot, an early pick never sees the later ones: whole sets are searched
        // (search), from the beam's and from many other starts, until no swap helps
        if (ms.allocM != null && baseline != null) {
            Roadmap.phase(2);
            long perfSearch = Perf.start();
            java.util.Set<String> buyable = new java.util.HashSet<>();
            ms.setGear.forEach(i -> buyable.add(i.name()));
            SetSearch.search(new Ctx(ms.startSet, ms.pools, ms.wide, SetSeeds.setPieces(cands, ms.setGear, owned, m, fixed),
                    ownedCount, buyable, wornNow, fixed, m, earned, prevAlloc, ms.freshCopies),
                    baseline, placed, gear);
            Perf.end("roadmap.search", perfSearch);
            Build.Eval be = Build.allocate(baseline, m, earned);
            if (be != null) {
                ms.baseObj = be.result().objective();
                ms.baseJ = SetJudge.judged(be, prevAlloc, m);
                ms.allocM = be.allocation();
            }
        }
        long perfRows = Perf.start();
        // The other hand: Wynncraft takes the same ring twice, and one pick per slot type left
        // the second ring at what you wear now all the way to Lv 120
        if (ms.allocM != null && baseline != null && !ringFixed) {         // a chosen ring holds the other hand
            Gear second = ms.otherRing();
            if (second != null) {
                gear.add(second);
                Build.Eval be = Build.allocate(baseline, m, earned);
                if (be != null) {
                    ms.baseObj = be.result().objective();
                    ms.allocM = be.allocation();
                }
            }
        }
        addTargets(m, gear);
        // The budget: the buys this milestone's set holds (what you own and the chosen items
        // cost nothing) stay within it, else its own picks go back to what it started with
        if (budget > 0 && baseline != null && ms.startSet != null) {
            Roadmap.phase(4);
            if (CandidateRules.spend(state, baseline, fixed, owned) > budget) {
                baseline.clear();
                baseline.putAll(ms.startSet);
                gear.removeIf(g -> !lockRows.contains(g) && !RoadmapRows.isPowder(g));
                placed.clear();
                placed.putAll(ms.placedStart);
                budgetCut.add(m);
            }
        }
        if (baseline != null && ms.startSet != null) {
            RoadmapGains.stepsInSet(gear, baseline, ms.startSet, placed, m, earned, prevAlloc);
        }
        if (m == level && in.builds() > 0 && baseline != null && ms.startSet != null) {
            Roadmap.phase(5);
            ranked0 = BuildRanking.rank(ms.startSet, baseline, ms.pools, fixed, level, earned, in.builds(), budget, state,
                    owned);
        }
        List<String> abil = abilities(m);
        List<String> places = places(m);
        int sp = Math.max(0, (Math.min(m, 101) - Math.min(Math.max(prev, level), 101)) * 2);
        Versus rows = vsWorn(m, earned);
        // the spread shown and carried on: the greedy one polished by moving points between
        // skills (once per milestone; the candidates above are ranked with the quick one)
        Build.Eval set = baseline == null ? null : Build.allocateBest(baseline, m, earned);
        if (set != null) {
            prevAlloc = SetJudge.carry(set, baseline, m);
        }
        steps.add(new Step(m, gear, abil, places, m == level ? 0 : sp, rows.gain(), rows.why(),
                baseline == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(baseline)),
                set));
        Perf.end("roadmap.rows", perfRows);
        prev = m;
        if (job == null) {
            Roadmap.progressDone++;
        } else {
            job.done++;
            if (set != null && Double.isNaN(job.best)) {
                job.best = SetJudge.judged(set, null, m);
            }
        }
    }

    /** Community targets (rules.roadmap.targets): e.g. the Adventurer's set at 26-29. */
    private void addTargets(int m, List<Gear> gear) {
        for (String name : Rules.list("roadmap", "targets")) {
            ItemData info = Roadmap.findGear(name);
            if (info == null) {
                continue;
            }
            int lv = info.requirements().level();
            if (lv <= prev || lv > m || lockedKinds.contains(Locks.kind(info)) || fixed.contains(Build.slotOf(info.slot(), 0))
                    || !affordable.test(info)) {
                continue;                           // a chosen item holds the slot, or over the budget
            }
            String slotName = info.slot().name();
            gear.removeIf(x -> x.slot().equals(slotName));
            String src = Shopping.sourceOf(info);
            // the old default note in rules.json ("topluluk önerisi") means: the localized default
            String note = Rules.subText("roadmap", "targetNote", null);
            if (note == null || note.isBlank() || note.equals("topluluk önerisi")) {
                note = L.t("wynnlabs.road.target_note");
            }
            gear.add(new Gear(slotName, info, Double.NaN, src == null ? "?" : Shopping.where(src) + " · "
                    + note, Prices.buyText(info.name()),
                    null, null, List.of(), List.of(), List.of()));
            if (baseline != null) {
                String slot = Build.slotOf(info.slot(), 0);
                if (slot != null) {
                    baseline.put(slot, OwnedGear.pieceOf(info));
                    RoadmapRows.place(placed, slotName, slot);
                }
            }
        }
    }

    /** The ability plan's nodes from the last milestone up to {@code m}. */
    private List<String> abilities(int m) {
        List<String> abil = new ArrayList<>();
        for (var e : abilities.entrySet()) {
            if (e.getValue() > prev && e.getValue() <= m) {
                abil.add(e.getKey());
            }
        }
        return abil;
    }

    /** Up to two places to level near {@code m} not shown at an earlier milestone, as "risk|text". */
    private List<String> places(int m) {
        List<String> places = new ArrayList<>();
        for (Grind.Spot s : Grind.near(m, 4)) {
            if (places.size() < 2 && placesShown.add(s.name())) {
                // "risk|text": 0 safe, 1 careful, 2 risky for you now (Danger.risk)
                double xph = state.xpRate(s.name());
                // " (Lv. ...)" and the "risk|" prefix are read back by RoadmapScreen: same in every language
                places.add(Danger.risk(s, m) + "|" + s.name() + " " + Grind.kindName(s) + " (Lv. "
                        + (s.estimated() ? "~" : "") + s.level()
                        + (xph > 0 ? ", " + L.t("wynnlabs.road.xp_per_hour", QuestAdvisor.fmt(Math.round(xph))) : "") + ")");
            }
        }
        return places;
    }

    /**
     * Each changed slot's gain over what you wear and its reasons ({@link RoadmapGains#versus}); a
     * piece keeps the number and reasons of the milestone that first picked it.
     */
    private Versus vsWorn(int m, int earned) {
        Versus sinceNow = baseline == null ? new Versus(Map.of(), Map.of())
                : RoadmapGains.versus(baseline, wornNow, placed, m, earned);
        Map<String, Double> rowGain = new LinkedHashMap<>(sinceNow.gain());
        Map<String, List<String>> rowWhy = new LinkedHashMap<>(sinceNow.why());
        for (String slotName : sinceNow.gain().keySet()) {
            Damage.Piece pick = baseline.get(placed.get(slotName));
            if (pick == null) {
                continue;
            }
            Double first = firstGain.putIfAbsent(pick.key(), rowGain.get(slotName));
            if (first != null) {
                rowGain.put(slotName, first);
                rowWhy.put(slotName, firstWhy.getOrDefault(pick.key(), List.of()));
            } else {
                firstWhy.put(pick.key(), rowWhy.getOrDefault(slotName, List.of()));
            }
        }
        return new Versus(rowGain, rowWhy);
    }

    /**
     * Puts the items you chose (Locks) into the set before the first milestone: each into its
     * slot (a ring into the hand that holds it already, else a free one), recorded in
     * {@code fixed} so nothing later swaps it out. When the set's skill points no longer add up,
     * the other pieces make room ({@link Build#fitAround}) and the milestones fill those slots
     * again. A row for each chosen piece you do not wear yet: its gain is NaN (chosen, not
     * picked), its comparison the piece against the set before. {@code baseline} null (no build
     * plan): only the rows.
     */
    private static List<Gear> lockIn(List<ItemData> locked, Map<String, Damage.Piece> baseline, Map<String, String> placed,
            java.util.Set<String> fixed, int level) {
        int earned = Math.max(0, (Math.min(level, 101) - 1) * 2);
        long rings = locked.stream().filter(i -> Locks.kind(i) == Slot.RING).count();
        Map<ItemData, String> slotOf = new LinkedHashMap<>();
        // the rings first into the hand that holds them already
        for (ItemData info : locked) {
            if (Locks.kind(info) == Slot.RING && baseline != null) {
                for (String s : new String[] {"RING1", "RING2"}) {
                    Damage.Piece p = baseline.get(s);
                    if (!slotOf.containsValue(s) && p != null && p.name().equals(info.name())) {
                        slotOf.put(info, s);
                        break;
                    }
                }
            }
        }
        for (ItemData info : locked) {
            if (slotOf.containsKey(info)) {
                continue;
            }
            Slot k = Locks.kind(info);
            String s = k == Slot.WEAPON ? "WEAPON" : k == Slot.RING ? (slotOf.containsValue("RING1") ? "RING2" : "RING1")
                    : Build.slotOf(k, 0);
            if (s != null) {
                slotOf.put(info, s);
            }
        }
        Map<String, Damage.Piece> start = baseline == null ? null : new LinkedHashMap<>(baseline);
        Build.Eval before = start == null ? null : Build.allocate(start, level, earned);
        List<Object[]> made = new ArrayList<>();        // {info, build slot, row name}
        int ringRow = 0;
        for (var e : slotOf.entrySet()) {
            ItemData info = e.getKey();
            String slot = e.getValue();
            fixed.add(slot);
            Slot k = Locks.kind(info);
            // the second hand's row name when one ring is chosen: the milestones' ring pick is "RING"
            String row = k == Slot.RING ? (rings == 1 || ringRow++ > 0 ? Roadmap.RING2 : Slot.RING.name()) : info.slot().name();
            if (baseline != null) {
                baseline.put(slot, OwnedGear.pieceOf(info));
            }
            boolean worn = false;
            for (ItemStack w : AutoAdvice.worn(info.slot())) {
                worn |= !w.isEmpty() && ItemJson.clean(w.getHoverName().getString()).trim().equalsIgnoreCase(info.name());
            }
            if (!worn) {
                made.add(new Object[] {info, slot, row});
            }
        }
        if (baseline != null) {
            List<String> out = Build.fitAround(baseline, fixed, earned);
            Roadmap.TriConsumer tc = Roadmap.trace;
            if (tc != null && !out.isEmpty()) {
                tc.accept(level, Slot.UNKNOWN, L.t("wynnlabs.road.trace_locks_out", out));
            }
        }
        Build.Eval after = baseline == null ? null : Build.allocate(baseline, level, earned);
        List<Gear> rows = new ArrayList<>();
        for (Object[] r : made) {
            ItemData info = (ItemData) r[0];
            String slot = (String) r[1];
            String row = (String) r[2];
            List<String> detail = new ArrayList<>();
            if (before != null && after != null) {
                detail.addAll(RoadmapGains.explain(before, after));
            }
            Option first = before == null ? null : RoadmapRows.option(info, slot, start, before, level, earned);
            String src = Shopping.sourceOf(info);
            rows.add(new Gear(row, info, Double.NaN, src == null ? "?" : Shopping.where(src), Prices.buyText(info.name()),
                    null, null, detail, List.of(), first == null ? List.of() : List.of(first)));
            RoadmapRows.place(placed, row, slot);
        }
        return rows;
    }
}
