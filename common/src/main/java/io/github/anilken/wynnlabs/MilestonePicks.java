package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.Roadmap.Gear;
import io.github.anilken.wynnlabs.Roadmap.Option;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One milestone's slot-by-slot picks: for each slot type, its candidates filtered by what the
 * milestone allows (level window, tier, off-skill points, budget, quests, skill points, source),
 * ranked in the build, the best few judged again with the skill points spread anew, and the pick
 * with its row and comparison panel put into the run's set. Along the way it keeps what the
 * search works from: each slot type's ranked pool, everything the milestone allows, the set
 * pieces you could get, the best rings and the fresh copies the ranking chose.
 */
final class MilestonePicks {

    private final MilestonePlanner run;
    final int m;
    /** The skill points earned by this milestone's level. */
    final int earned;
    private final int maxOff;
    private final List<String> badTiers;
    private final List<String> badSources;
    /** A Mythic's later version (Masterwork Aftershock, an Ascended one) by the Mythic's name and its level. */
    private final Map<String, Integer> laterVersion;
    /** Today's Trade Market listings only matter for what you would buy soon, not 70 levels on. */
    private final boolean listingsMatter;
    /** The milestone's rows. */
    final List<Gear> gear;
    private final boolean preferMarket;
    /** The run's set's score now, with the respec charge ({@code baseJ}), and its skill points (null: no build plan, or it cannot be worn). */
    double baseObj;
    double baseJ;
    int[] allocM;
    /** The best few per slot type. */
    final Map<Slot, List<ItemData>> pools = new java.util.EnumMap<>(Slot.class);
    /** Name → a fresh copy to buy of a piece you have, where the slot ranking chose one over yours. */
    final Map<String, Damage.Piece> freshCopies = new java.util.concurrent.ConcurrentHashMap<>();
    /** The set and the placed slots as the milestone started. */
    final Map<String, Damage.Piece> startSet;
    final Map<String, String> placedStart;
    /** This milestone's best rings, for the other hand. */
    final List<ItemData> ringPool = new ArrayList<>();
    /** Set pieces you could get here, for whole sets. */
    final List<ItemData> setGear = new ArrayList<>();
    /** Everything the milestone allows per slot type. */
    final Map<Slot, List<ItemData>> wide = new java.util.EnumMap<>(Slot.class);
    /** For the trace's reasons: the most each skill could get from the milestone's gear. */
    private final int[] reach;

    MilestonePicks(MilestonePlanner run, int m, boolean first) {
        this.run = run;
        this.m = m;
        earned = Math.max(0, (Math.min(m, 101) - 1) * 2);
        maxOff = CandidateRules.maxOff(m);
        List<String> tiers = CandidateRules.badTiers(m);
        List<String> sources = CandidateRules.badSources(m);
        badTiers = tiers;
        badSources = sources;
        // when this milestone lets you get it
        laterVersion = CandidateRules.laterVersions(run.state, info -> !tiers.contains(info.tier().name())
                && (run.owned.contains(info.name()) || !Shopping.excluded(info, sources)) && run.affordable.test(info));
        listingsMatter = m - run.level <= (int) Rules.sub("shopping", "aheadLevels", 5);
        gear = new ArrayList<>(first ? run.lockRows : List.of());   // the chosen items: rows of the first milestone
        if (first && run.plan != null) {
            // and the powders your own pieces lack, but for a piece a chosen item replaces
            for (Gear g : RoadmapRows.powderRows(run.plan.pieces(), m, earned)) {
                if (!run.fixed.contains(g.slot().substring("POWDER:".length()))) {
                    gear.add(g);
                }
            }
        }
        preferMarket = Rules.sub("shopping", "preferTradeMarket", 1) > 0;
        // With a build plan, everything is judged by the damage model: the % the build gains
        // with the piece in, starting from the best build you own and adding each pick
        if (run.baseline != null) {
            Build.Eval be = Build.allocate(run.baseline, m, earned);
            if (be != null) {
                baseObj = be.result().objective();
                baseJ = SetJudge.judged(be, run.prevAlloc, m);
                allocM = be.allocation();
            }
        }
        startSet = run.baseline == null ? null : new LinkedHashMap<>(run.baseline);
        placedStart = new LinkedHashMap<>(run.placed);
        reach = Roadmap.trace == null ? null : RoadmapTrace.windowReach(run.cands, m, laterVersion);
    }

    /** Picks the best for slot type {@code t}, adds its row and puts it into the run's set. */
    void pick(Slot t) {
        new SlotPick(t).pick();
    }

    /**
     * The best ring for the hand the milestone's ring pick did not take: a second copy of a ring
     * in the build, or one of this milestone's best rings. Put into the run's set when it clears
     * the same bar as any pick; null when nothing does.
     */
    Gear otherRing() {
        return otherRing(run.baseline, run.placed, ringPool, m, earned, baseObj, badSources, run.affordable);
    }

    private static Gear otherRing(Map<String, Damage.Piece> baseline, Map<String, String> placed, List<ItemData> pool,
            int m, int earned, double baseObj, List<String> badSources, java.util.function.Predicate<ItemData> affordable) {
        if (baseObj <= 0) {
            return null;
        }
        java.util.LinkedHashSet<ItemData> cands = new java.util.LinkedHashSet<>();
        for (String s : new String[] {"RING1", "RING2"}) {
            Damage.Piece p = baseline.get(s);
            ItemData info = p == null ? null : Roadmap.findGear(p.name());
            if (info != null) {
                cands.add(info);
            }
        }
        cands.addAll(pool);
        String justPicked = placed.get(Slot.RING.name());
        ItemData best = null;
        String bestSlot = null;
        double bestGain = Double.NEGATIVE_INFINITY;
        for (String slot : new String[] {"RING1", "RING2"}) {
            if (slot.equals(justPicked)) {
                continue;                               // the hand this milestone's ring pick took
            }
            Damage.Piece there = baseline.get(slot);
            for (ItemData info : cands) {
                Damage.Piece cand = OwnedGear.pieceOf(info);
                if (info.requirements().level() > m || there != null && there.key().equals(cand.key()) || !affordable.test(info)) {
                    continue;
                }
                if (Shopping.excluded(info, badSources) || Exclusive.clash(baseline, slot, Exclusive.group(info)) != null) {
                    continue;
                }
                double obj = SetJudge.full(baseline, slot, cand, m, earned);
                if (Double.isNaN(obj)) {
                    continue;
                }
                double gain = (obj / baseObj - 1) * 100;
                RoadmapTrace.trace(m, Slot.RING, info, L.t("wynnlabs.road.trace_other_hand", RoadmapTrace.pct(gain), slot));
                if (gain > bestGain) {
                    bestGain = gain;
                    best = info;
                    bestSlot = slot;
                }
            }
        }
        if (best == null || bestGain < Rules.sub("shopping", "minGainPercent", 10)
                && bestGain < Build.gainThreshold(baseline.get(bestSlot), m)) {
            return null;
        }
        Build.Eval before = Build.allocate(baseline, m, earned);
        Option first = before == null ? null : RoadmapRows.option(best, bestSlot, baseline, before, m, earned);
        baseline.put(bestSlot, OwnedGear.pieceOf(best));
        RoadmapRows.place(placed, Roadmap.RING2, bestSlot);
        String src = Shopping.sourceOf(best);
        return new Gear(Roadmap.RING2, best, bestGain, Shopping.where(src), Prices.buyText(best.name()), null, null,
                new ArrayList<>(), List.of(), first == null ? List.of() : List.of(first));
    }

    /**
     * A milestone's quick score of {@code cand} (the piece {@code info} is judged as) in the best
     * build slot of its type for it: {score, slot}, the score NaN when it fits none. A slot a
     * chosen item holds, or one that holds this very piece already, is not tried.
     */
    private static Object[] bestSlot(Map<String, Damage.Piece> baseline, int[] allocM, java.util.Set<String> fixed, Slot t,
            ItemData info, Damage.Piece cand, int m, int earned) {
        double sc = Double.NaN;
        String used = null;
        for (String slot : t == Slot.RING ? new String[] {"RING1", "RING2"} : new String[] {Build.slotOf(t, 0)}) {
            // the piece an earlier milestone already put in this slot is no new pick:
            // its only "gain" would be the community nudge
            Damage.Piece there = slot == null ? null : baseline.get(slot);
            if (slot == null || fixed.contains(slot) || there != null && there.key().equals(cand.key())) {
                continue;
            }
            // Wynncraft refuses a second piece of an exclusive group (Ornate Shadow, the Hives …)
            String clash = Exclusive.clash(baseline, slot, Exclusive.group(info));
            if (clash != null) {
                RoadmapTrace.trace(m, t, info, L.t("wynnlabs.road.trace_clash", slot, Exclusive.group(info), clash));
                continue;
            }
            // a weapon sets the element, so the points must be spread anew for it:
            // with the old spread every weapon unlike the one in the build ranked low
            Roadmap.tick();
            double v = t.isWeapon() ? SetJudge.full(baseline, slot, cand, m, earned)
                    : Build.trySlot(baseline, allocM, slot, cand, m, earned);
            if (!Double.isNaN(v) && (Double.isNaN(sc) || v > sc)) {
                sc = v;
                used = slot;
            }
        }
        return new Object[] {sc, used};
    }

    /** One slot type's pick at this milestone: the candidates ranked, the best judged, its row. */
    private final class SlotPick {
        private final Slot t;
        private ItemData top;
        private boolean emptyBuild;                 // the pick fills an empty hand (no score to compare)
        private double topScore;
        private double topReal = Double.NaN;        // the pick's own gain (topScore may be lowered for availability)
        private String topSlot;
        private ItemData alt;
        private double altScore;
        private double altReal = Double.NaN;        // the alternative's own gain
        private String altSlot;
        private final List<Object[]> pool = new ArrayList<>();      // {info, % gain, slot} for the alternatives
        private final List<Object[]> ranked = new ArrayList<>();    // {info, % gain, gain for availability, slot, its factor}
        // what you already wear is no upgrade, unless you have a better roll of it, or the
        // worn one rolled below rules.rerollBelowPercent (a fresh copy is then a buy)
        private final java.util.Set<String> wornNames = new java.util.HashSet<>();
        private final java.util.Set<String> wornKeys = new java.util.HashSet<>();
        private final Map<String, Float> wornRoll = new HashMap<>();     // lower-case name → its weakest worn overall roll
        // name → the piece each candidate was judged as (the copy you have, or a fresh one to buy)
        private final Map<String, Damage.Piece> judgedAs = new HashMap<>();

        SlotPick(Slot t) {
            this.t = t;
            topScore = allocM != null ? 0 : run.best.getOrDefault(t, 0.0) + 0.5;
            altScore = topScore;
        }

        void pick() {
            int peek = (int) Rules.sub("shopping", "aheadLevels", 5);
            readWorn();
            // a later milestone only weighs gear made for around that level: a piece 20 levels
            // below it is not the pick there, and trying every piece up to Lv 120 took seconds
            int floor = m == run.level ? Integer.MIN_VALUE : m - (int) Rules.sub("shopping", "milestoneWindow", 20);
            // what this milestone's set already holds per skill (its points and the gear's): a
            // requirement within it asks for nothing more
            double[] cover = allocM == null ? null : Build.cover(run.baseline, allocM);
            for (ItemData info : run.cands.getOrDefault(t, List.of())) {
                rank(info, peek, floor, cover);
            }
            if (allocM != null) {
                judgeBest();
            }
            if (alt != null && top != null && altScore <= topScore) {
                alt = null;                 // the market pick is as good: no alternative needed
            }
            if (top == null && alt != null) {
                top = alt;                  // nothing on the market beats what you wear
                topScore = altScore;
                topReal = altReal;          // its own gain, not the one it was chosen by
                alt = null;
            }
            if (top == null) {
                return;
            }
            addRow();
        }

        /** What you wear in this slot type: names, keys and the weakest overall roll per name. */
        private void readWorn() {
            for (ItemStack w : AutoAdvice.worn(t)) {
                if (!w.isEmpty()) {
                    String wn = ItemJson.clean(w.getHoverName().getString()).trim().toLowerCase(java.util.Locale.ROOT);
                    wornNames.add(wn);
                    wornKeys.add(ItemJson.keyOf(w));
                    var wv = run.state.view(w);
                    float q = wv.isPresent() && wv.get().isGear() && wv.get().rolled() != null
                            && wv.get().rolled().hasOverall() ? wv.get().rolled().overallPercent() : Float.NaN;
                    wornRoll.merge(wn, q, (a, b) -> Float.isNaN(a) ? b : Float.isNaN(b) ? a : Math.min(a, b));
                }
            }
        }

        /** Filters one candidate, scores it in the build and ranks it (or, with no build plan, keeps the best by its score). */
        private void rank(ItemData info, int peek, int floor, double[] cover) {
            // a Mythic with no later version by then is still the best of its kind there
            // (Freedom, Ignis at Lv 120): the window does not drop it
            String lname = info.name().toLowerCase(java.util.Locale.ROOT);
            Damage.Piece judge = OwnedGear.judgeAs(info, wornNames.contains(lname) ? wornRoll.get(lname) : null, wornKeys,
                    run.budget, run.state);
            if (judge == null || allocM == null && wornNames.contains(lname)
                    || info.requirements().level() < floor && !CandidateRules.lastOfItsKind(info, m, laterVersion)
                    || badTiers.contains(info.tier().name())) {
                RoadmapTrace.skip(m, t, info, "wynnlabs.road.trace_window");
                return;
            }
            // the weapon decides the element, so it is never thrown out for its skills
            int off = t.isWeapon() ? 0 : CandidateRules.offPoints(info, run.preferred, cover);
            if (off > maxOff) {
                RoadmapTrace.skip(m, t, info, "wynnlabs.road.trace_off_skill", off, maxOff);
                return;
            }
            if (!run.affordable.test(info)) {
                // over the budget, or a tradable piece with no known price: never suggested under one
                RoadmapTrace.skip(m, t, info, "wynnlabs.road.trace_budget");
                run.budgetCut.add(m);
                return;
            }
            // a few levels ahead only for the comparison panel's alternatives
            boolean future = info.requirements().level() > m;
            if (info.requirements().level() > m + peek || future && allocM == null) {
                RoadmapTrace.skip(m, t, info, "wynnlabs.road.trace_above");
                return;
            }
            // a quest the piece asks for that you have not done: not before its level
            // (Sodeta Boots want Lost Soles); from there on it stays, with a caution
            QuestBook.Entry quest = Shopping.questPending(run.state, info);
            if (quest != null && quest.level() > m) {
                RoadmapTrace.skip(m, t, info, "wynnlabs.road.trace_quest", quest.name(), quest.level());
                return;
            }
            int total = info.requirements().skills().stream().mapToInt(r -> r.points()).sum();
            if (total > earned) {
                RoadmapTrace.skip(m, t, info, "wynnlabs.road.trace_points", total, earned);
                return;
            }
            if (!future && info.set().isPresent()) {
                // a set piece alone may rank below the best forty and still pay with its set
                // by where it comes from in the world (a listed piece's source reads TRADE_MARKET)
                if (!Shopping.excluded(info, badSources)) {
                    setGear.add(info);
                }
            }
            if (!future && (!Shopping.excluded(info, badSources) || run.owned.contains(info.name()))) {
                // for the search's sets around another weapon: ranked anew there, not by the score below
                wide.computeIfAbsent(t, k -> new ArrayList<>()).add(info);
            }
            double sc;
            String slotUsed = null;
            if (allocM != null) {
                Damage.Piece cand = judge;
                Object[] at = bestSlot(run.baseline, allocM, run.fixed, t, info, cand, m, earned);
                // a copy you have that rolled below the middle: a fresh one may be worth
                // its price, when it beats yours by rules.shopping.minGainPercent
                float roll = OwnedGear.ownedPercent(info.name());
                if (cand.stack() != null && !Float.isNaN(roll) && roll < 50) {
                    Damage.Piece buy = OwnedGear.fresh(info, run.budget, run.state);
                    Object[] other = buy == null ? null : bestSlot(run.baseline, allocM, run.fixed, t, info, buy, m, earned);
                    if (other != null && !Double.isNaN((double) other[0]) && (Double.isNaN((double) at[0])
                            || (double) other[0] > (double) at[0] * (1 + Rules.sub("shopping", "minGainPercent", 10) / 100))) {
                        cand = buy;
                        at = other;
                    }
                }
                judgedAs.put(info.name(), cand);
                if (OwnedGear.buysOwned(cand)) {
                    freshCopies.put(info.name(), cand);       // the search judges it as the same fresh copy
                }
                sc = (double) at[0];
                slotUsed = (String) at[1];
                if (Double.isNaN(sc) || baseObj <= 0 && !t.isWeapon()) {
                    if (Roadmap.trace != null) {
                        traceUnfit(info, cand);
                    }
                    return;
                }
                // with no weapon yet (a new character) the build scores nothing: weapons
                // are ranked by their raw score, the rest once one is in
                sc = baseObj <= 0 ? sc : (sc / baseObj - 1) * 100;
                // a nudge toward what players actually pick (rules.community)
                sc += Rules.communityBonus(info.name());
                // and away from what needs a skill reset in town
                if (slotUsed != null && Build.needsRespec(run.baseline, run.prevAlloc, slotUsed, cand, earned)) {
                    sc -= Rules.sub("shopping", "respecPenaltyPercent", 3);
                }
                if (Shopping.excluded(info, badSources) && (!run.owned.contains(info.name()) || OwnedGear.buysOwned(cand))) {
                    RoadmapTrace.skip(m, t, info, "wynnlabs.road.trace_source", Shopping.origin(info));
                    return;               // never offer what cannot be obtained
                }
                RoadmapTrace.trace(m, t, info, L.t("wynnlabs.road.trace_ranked", RoadmapTrace.pct(sc), slotUsed)
                        + (OwnedGear.buysOwned(cand) ? L.t("wynnlabs.road.trace_fresh") : "")
                        + (Roadmap.trace != null && slotUsed != null
                                ? " | " + RoadmapTrace.parts(run.baseline, slotUsed, cand, m, earned) : ""));
                pool.add(new Object[] {info, sc, slotUsed});
                if (future) {
                    return;                   // shown as an alternative, not picked yet
                }
                // the bar (Build.gainThreshold) is checked once the best few are judged again
            } else {
                sc = AutoAdvice.score(info);
            }
            // what you can actually get now counts: a market item with no listing at the moment
            // (Wynnventory's live listings) only wins when it is clearly better than one that has
            double factor = 1;
            if (allocM != null && listingsMatter && Shopping.tradable(info)) {
                String src = Shopping.origin(info);
                boolean merchant = src.startsWith("MERCHANT") || src.startsWith("DUNGEON_MERCHANT");
                if (!merchant) {
                    factor = CandidateRules.availability(run.state.price(info.name()));
                }
            }
            if (allocM != null && run.overBudget.test(info)) {
                factor *= Rules.sub("shopping", "overBudgetFactor", 0.8);   // its old price is over the budget
            }
            double eff = CandidateRules.scaled(sc, factor);
            if (allocM != null) {
                ranked.add(new Object[] {info, sc, eff, slotUsed, factor});
            } else if (preferMarket && !Shopping.tradable(info)) {
                if (eff > altScore) {
                    altScore = eff;
                    alt = info;
                }
            } else if (eff > topScore) {
                topScore = eff;
                topReal = sc;
                top = info;
            }
        }

        /** For the trace: the allocator's reason, for the first slot it could take. */
        private void traceUnfit(ItemData info, Damage.Piece cand) {
            String why = null;
            boolean held = false;           // an earlier milestone's pick, in the set already
            for (String slot : t == Slot.RING ? new String[] {"RING1", "RING2"} : new String[] {Build.slotOf(t, 0)}) {
                if (slot != null) {
                    held |= run.baseline.get(slot) != null && run.baseline.get(slot).key().equals(cand.key());
                    why = RoadmapTrace.whyOut(run.baseline, slot, cand, t, earned, reach);
                    if (why == null) {
                        break;
                    }
                }
            }
            RoadmapTrace.trace(m, t, info, why != null ? why : L.t("wynnlabs.road.trace_skip", L.t(held
                    ? "wynnlabs.road.trace_held" : baseObj <= 0
                    ? "wynnlabs.road.trace_no_weapon" : "wynnlabs.road.trace_no_fit")));
        }

        /**
         * The ranked candidates' best few judged again: trySlot keeps the skill points and only
         * moves what the piece needs, so it ranks roughly; the best few are judged again with the
         * points spread anew (as the comparison panel does), or the pick could show below its own
         * alternatives. Keeps the slot's pool for the search, and picks the best that clears the bar.
         */
        private void judgeBest() {
            ranked.sort((p1, p2) -> Double.compare((double) p2[2], (double) p1[2]));
            int keep = (int) Rules.sub("roadmap", "rescore", 6);
            if (t == Slot.RING) {
                ranked.stream().limit(keep).forEach(r -> ringPool.add((ItemData) r[0]));
            }
            pools.put(t, ranked.stream().limit((long) Rules.sub("roadmap", "jointPool", 40))
                    .map(r -> (ItemData) r[0]).toList());
            int market = 0;
            int other = 0;
            List<Object[]> cleared = new ArrayList<>();     // {info, eff, gain, slot}: market picks over the bar
            for (Object[] r : ranked) {
                ItemData info = (ItemData) r[0];
                boolean side = preferMarket && !Shopping.tradable(info);
                if (side ? other++ >= keep : market++ >= keep) {
                    RoadmapTrace.skip(m, t, info, "wynnlabs.road.trace_not_best", keep);
                    continue;
                }
                String slotUsed = (String) r[3];
                double sc = (double) r[1];
                double eff = (double) r[2];
                Damage.Piece cand = judgedAs.getOrDefault(info.name(), OwnedGear.pieceOf(info));
                boolean buy = OwnedGear.buysOwned(cand);
                if (slotUsed != null) {
                    Build.Eval fe = SetJudge.fullEval(run.baseline, slotUsed, cand, m, earned);
                    if (fe == null || baseJ <= 0 && !t.isWeapon()) {
                        if (Roadmap.trace != null) {
                            String why = RoadmapTrace.whyOut(run.baseline, slotUsed, cand, t, earned, reach);
                            RoadmapTrace.trace(m, t, info, why != null ? why : L.t("wynnlabs.road.trace_skip", L.t(fe == null
                                    ? "wynnlabs.road.trace_no_alloc" : "wynnlabs.road.trace_no_weapon")));
                        }
                        continue;
                    }
                    if (baseJ <= 0) {
                        // the first weapon of an empty build: by its raw score, no bar to clear
                        eff = SetJudge.judged(fe, run.prevAlloc, m) * (double) r[4];
                        sc = eff;
                        emptyBuild = true;
                        if (side ? eff > altScore : eff > topScore) {
                            if (side) {
                                altScore = eff;
                                alt = info;
                                altSlot = slotUsed;
                            } else {
                                topScore = eff;
                                topReal = sc;
                                top = info;
                                topSlot = slotUsed;
                            }
                        }
                        continue;
                    }
                    // judged: a pick that undoes the last milestone's skill points pays for it
                    double full = (SetJudge.judged(fe, run.prevAlloc, m) / baseJ - 1) * 100 + Rules.communityBonus(info.name());
                    if (Build.needsRespec(run.baseline, run.prevAlloc, slotUsed, cand, earned)) {
                        full -= Rules.sub("shopping", "respecPenaltyPercent", 3);
                    }
                    // for the choice only (the row shows the gain itself): an untradable
                    // piece from a dungeon, its merchant, a raid or a quest takes a run of
                    // its own, so it must clear the bar by this much more (Admiral wants
                    // Redbeard's Hair); what you have needs no buy and counts a little extra
                    double pickBy = full;
                    if (run.owned.contains(info.name()) && !buy) {
                        pickBy += Rules.sub("shopping", "ownedBonusPercent", 2);
                    } else if (Shopping.effort(info)) {
                        pickBy -= Rules.sub("shopping", "untradableEffortPercent", 5);
                    }
                    // a fresh copy of the one you wear must beat it by the full bar, never the
                    // lower one old gear gets
                    boolean reroll = buy && wornNames.contains(info.name().toLowerCase(java.util.Locale.ROOT));
                    if (pickBy < Rules.sub("shopping", "minGainPercent", 10)
                            && (reroll || pickBy < Build.gainThreshold(run.baseline.get(slotUsed), m))) {
                        RoadmapTrace.skip(m, t, info, "wynnlabs.road.trace_under_bar",
                                RoadmapTrace.pct(full), RoadmapTrace.pct(pickBy),
                                RoadmapTrace.pct(Build.gainThreshold(run.baseline.get(slotUsed), m)), slotUsed,
                                run.baseline.get(slotUsed) == null ? -1 : run.baseline.get(slotUsed).level());
                        continue;
                    }
                    RoadmapTrace.trace(m, t, info, L.t("wynnlabs.road.trace_judged", RoadmapTrace.pct(full), slotUsed));
                    eff = CandidateRules.scaled(pickBy, (double) r[4]);     // keeps the "no listings" factor
                    sc = full;
                }
                if (!side) {
                    cleared.add(new Object[] {info, eff, sc, slotUsed});
                }
                if (side) {
                    if (eff > altScore) {
                        altScore = eff;
                        altReal = sc;
                        alt = info;
                        altSlot = slotUsed;
                    }
                } else if (eff > topScore) {
                    topScore = eff;
                    topReal = sc;
                    top = info;
                    topSlot = slotUsed;
                }
            }
            // close picks (rules.shopping.costTieBreakPercent of the best) are as good a
            // buy: what you have first, then the cheaper while you would buy it soon
            if (top != null && !emptyBuild) {
                java.util.Set<String> have = new java.util.HashSet<>(run.owned);     // a fresh copy is no copy you have
                judgedAs.forEach((n, p) -> {
                    if (OwnedGear.buysOwned(p)) {
                        have.remove(n);
                    }
                });
                Object[] pick = CandidateRules.closest(cleared, topScore, have, listingsMatter);
                if (pick != null && pick[0] != top) {
                    RoadmapTrace.trace(m, t, (ItemData) pick[0], L.t("wynnlabs.road.trace_tie", top.name()));
                    top = (ItemData) pick[0];   // topScore stays the best's: as good a buy
                    topReal = (double) pick[2];
                    topSlot = (String) pick[3];
                }
            }
            if (top == null && alt != null) {
                topSlot = altSlot;
            }
        }

        /** The pick's row with its alternatives and comparison panel, and the pick put into the run's set. */
        private void addRow() {
            String source = Shopping.sourceOf(top);
            Damage.Piece topPiece = judgedAs.getOrDefault(top.name(), OwnedGear.pieceOf(top));
            if (source == null || Shopping.excluded(top, badSources)
                    && (!run.owned.contains(top.name()) || OwnedGear.buysOwned(topPiece))) {
                return;                       // what cannot be had, unless you have it already
            }
            String price = Prices.buyText(top.name());
            String altSource = alt == null ? null : Shopping.sourceOf(alt);
            double gain = allocM != null ? (Double.isNaN(topReal) ? topScore : topReal) : topScore - run.best.getOrDefault(t, 0.0);
            if (emptyBuild) {
                gain = Double.NaN;              // against an empty hand a % means nothing
            }
            List<String> detail = new ArrayList<>();
            Build.Eval before = allocM != null ? Build.allocate(run.baseline, m, earned) : null;
            List<String> others = alternatives();
            List<Option> options = panel(before, topPiece);
            gear.add(new Gear(t.name(), top, gain, Shopping.where(source), price, alt,
                    altSource == null ? null : Shopping.where(altSource), detail, others, options));
            if (allocM != null) {
                run.baseline.put(topSlot != null ? topSlot : Build.slotOf(t, 0), topPiece);
                RoadmapRows.place(run.placed, t.name(), topSlot != null ? topSlot : Build.slotOf(t, 0));
                Build.Eval be = Build.allocate(run.baseline, m, earned);
                if (before != null && be != null) {
                    detail.addAll(RoadmapGains.explain(before, be));
                }
                if (be != null) {
                    baseObj = be.result().objective();
                    baseJ = SetJudge.judged(be, run.prevAlloc, m);
                    allocM = be.allocation();
                }
            } else {
                run.best.put(t, topScore);
            }
        }

        /** At least three other options for the slot, best first, with what each costs you. */
        private List<String> alternatives() {
            List<String> others = new ArrayList<>();
            ItemData chosen = top;
            pool.stream()
                    .filter(o -> o[0] != chosen)
                    .sorted((p1, p2) -> Double.compare((double) p2[1], (double) p1[1]))
                    .limit((long) Rules.sub("roadmap", "alternatives", 4))
                    .forEach(o -> {
                        ItemData gi = (ItemData) o[0];
                        run.state.requestPrices(List.of(gi.name()));
                        String pr = Prices.buyText(gi.name());
                        String pShort = pr == null ? (Shopping.tradable(gi) ? "" : " · " + L.t("wynnlabs.road.untradable"))
                                : " · " + (pr.contains(" (") ? pr.substring(0, pr.indexOf(" (")) : pr);
                        List<String> c = Cautions.of(gi);
                        others.add(L.t("wynnlabs.road.alternative", gi.name(), gi.requirements().level(), pShort,
                                Math.round((double) o[1])) + (c.isEmpty() ? "" : " · " + String.join(", ", c)));
                    });
            return others;
        }

        /**
         * The comparison panel: the pick first, then the best others (up to five in all), weaker
         * ones too: a cheaper piece a few % behind is still a choice. Empty without a build plan.
         */
        private List<Option> panel(Build.Eval before, Damage.Piece topPiece) {
            List<Option> options = new ArrayList<>();
            if (before == null) {
                return options;
            }
            ItemData chosen = top;
            String topS = topSlot != null ? topSlot : Build.slotOf(t, 0);
            Option first = topS == null ? null : RoadmapRows.option(top, topPiece, topS, run.baseline, before, m, earned);
            if (first != null) {
                options.add(first);
            }
            pool.stream()
                    .filter(o -> o[0] != chosen && o[2] != null && CandidateRules.panelAllows((ItemData) o[0], m))
                    .sorted((p1, p2) -> Double.compare((double) p2[1], (double) p1[1]))
                    .limit((long) Rules.sub("roadmap", "alternatives", 6))
                    .forEach(o -> {
                        Option op = RoadmapRows.option((ItemData) o[0], (String) o[2], run.baseline, before, m, earned);
                        if (op != null) {
                            options.add(op);
                        }
                    });
            // the best few may all sit above your level: keep some you can wear today in
            // the panel too, even when they lose a little (the panel says so in red)
            int nowMin = (int) Rules.sub("roadmap", "alternativesNow", 3);
            long nowHave = options.stream().filter(op -> op.info().requirements().level() <= run.level).count();
            if (nowHave < nowMin) {
                java.util.Set<String> shown = new java.util.HashSet<>();
                options.forEach(op -> shown.add(op.info().name()));
                pool.stream()
                        .filter(o -> o[0] != chosen && o[2] != null && CandidateRules.panelAllows((ItemData) o[0], m)
                                && ((ItemData) o[0]).requirements().level() <= run.level
                                && !shown.contains(((ItemData) o[0]).name()))
                        .sorted((p1, p2) -> Double.compare((double) p2[1], (double) p1[1]))
                        .limit(nowMin - nowHave)
                        .forEach(o -> {
                            Option op = RoadmapRows.option((ItemData) o[0], (String) o[2], run.baseline, before, m, earned);
                            if (op != null) {
                                options.add(op);
                            }
                        });
            }
            return options;
        }
    }
}
