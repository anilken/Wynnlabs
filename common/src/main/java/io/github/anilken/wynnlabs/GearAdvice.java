package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.Requirement;
import io.github.anilken.wynnlabs.model.Roll;
import io.github.anilken.wynnlabs.model.SetData;
import io.github.anilken.wynnlabs.model.SkillKind;
import io.github.anilken.wynnlabs.model.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Verdicts for gear and crafted gear: wear it, keep it, sell it or salvage it, against what
 * you wear in the same slot, the road map's plan, your skill points and set bonuses.
 */
final class GearAdvice {

    private GearAdvice() {}

    static Advice.Entry gear(AutoAdvice.StackFacts facts, ItemView gear, Klass cls, int level) {
        ItemStack stack = facts.stack();
        int tooLow = (int) Rules.num("tooLowLevels", 4);
        int tooHigh = (int) Rules.num("tooHighLevels", 5);
        ItemData info = gear.data();
        Slot type = info.slot();
        Klass need = classOf(gear);
        if (need != null && cls != null && need != cls) {
            String alt = AltCharacters.altOfClass(need, info.level(), info.level());
            if (alt != null) {
                return new Advice.Entry(Verdict.KEEP, L.t("wynnlabs.auto.other_class_alt", AltCharacters.className(need), alt));
            }
            return SellAdvice.sell(gear, L.t("wynnlabs.auto.wrong_class", AltCharacters.className(need), AltCharacters.className(cls)));
        }
        if (type == Slot.MASTERY_TOME || type == Slot.CHARM) {
            return null;
        }
        List<ItemStack> worn = WornGear.worn(type);
        for (ItemStack w : worn) {
            if (w == stack) {
                return null;                 // what you wear: only the assistant judges that
            }
        }
        int itemLevel = info.level();
        // An empty slot: anything you can wear beats nothing, however old it is
        WornGear.Weakest now = WornGear.weakest(worn);
        boolean slotEmpty = now != null && now.score() < 0;
        if (gear.unidentified()) {
            int cost = info.tier().identificationCost(itemLevel);
            if (slotEmpty && itemLevel <= level) {
                return new Advice.Entry(Verdict.IDENTIFY, L.t("wynnlabs.auto.identify_empty_slot", cost));
            }
            if (itemLevel > level + tooHigh) {
                return new Advice.Entry(Verdict.KEEP, L.t("wynnlabs.auto.identify_near_level", itemLevel));
            }
            // Judged by what it could become against what you wear, not by its level alone
            double potential = GearScore.score(info);
            if (now != null && now.name() != null && potential < now.score() - 0.5) {
                return SellAdvice.sell(gear, L.t("wynnlabs.auto.worn_better_even_identified", now.name()));
            }
            if (itemLevel < level - tooLow) {
                // Old for you: when it sells well as it is, sell it unidentified instead of paying to identify it
                Prices.Price pr = GameState.current().price(info.name());
                Double unid = pr == null ? null : pr.unidTypical() != null ? pr.unidTypical() : pr.typical();
                if (unid != null && unid >= Rules.sub("trade", "minTradeMarketPrice", 1920)) {
                    return AutoAdvice.market(new Advice.Entry(Verdict.SELL, L.t("wynnlabs.auto.old_sell_unidentified",
                            itemLevel, Prices.fmt(unid), cost)), SellAdvice.afterFee(unid));
                }
                return new Advice.Entry(Verdict.IDENTIFY, L.t("wynnlabs.auto.old_identify", cost));
            }
            return new Advice.Entry(Verdict.IDENTIFY, L.t("wynnlabs.auto.identify_fits_class", cost));
        }
        boolean junk = Rules.list("trade", "junkTiers").contains(info.tier().name());
        Advice.Entry planned = fromPlan(facts, gear);
        if (planned != null) {
            return planned;
        }
        if (itemLevel > level) {
            // Worth carrying only if it beats what the shopping list already points at
            if (junk) {
                return SellAdvice.sell(gear, L.t("wynnlabs.auto.normal_weak_later", itemLevel));
            }
            Shopping.Pick target = Shopping.best(type);
            if (target != null && weakerThanPick(facts, gear, target)) {
                return SellAdvice.sell(gear, L.t("wynnlabs.auto.list_pick_better", target.info().name(),
                        target.info().requirements().level()));
            }
            return new Advice.Entry(Verdict.KEEP, L.t("wynnlabs.auto.wear_at_level", itemLevel));
        }
        // Compare with the weakest piece you wear in the same slot
        WornGear.Weakest wk = WornGear.weakest(worn);
        if (wk == null) {
            return null;
        }
        double weakestScore = wk.score();
        ItemView weakestGear = wk.gear();
        String weakestName = wk.name();
        double mine = GearScore.score(gear) + setDelta(info, wk.stack());
        boolean empty = weakestScore < 0;
        if (empty || mine > weakestScore + 0.5) {
            String then = empty ? L.t("wynnlabs.auto.then_wear_empty") : L.t("wynnlabs.auto.then_wear_instead", weakestName);
            String rival = betterInBag(stack, gear, type, level, mine);
            if (rival != null) {
                return SellAdvice.sell(gear, L.t("wynnlabs.auto.bag_rival_better", rival));
            }
            Damage.Piece own = Damage.piece(gear, ItemJson.keyOf(gear, facts.name()), stack);
            Damage.Piece off = wk.stack() == null || wk.stack().isEmpty() ? null : Damage.piece(wk.stack());
            Advice.Entry gate = skillGate(info.requirements(), then, own == null ? null : own.stats(),
                    off == null ? null : off.stats());
            if (gate != null) {
                return gate;
            }
            Optional<String> quest = info.requirements().quest();
            if (quest.isPresent() && !gear.unidentified()
                    && !gear.rolled().marks().all()) {
                return new Advice.Entry(Verdict.KEEP, L.t("wynnlabs.auto.quest_first", quest.get(), then));
            }
            // Old but the best you have: wear it, and point at the next upgrade
            String old = itemLevel < level - tooLow ? L.t("wynnlabs.auto.old_suffix", itemLevel, nextUpgrade(type)) : "";
            if (empty) {
                return new Advice.Entry(Verdict.WEAR, L.t("wynnlabs.auto.wear_empty", best(gear), old));
            }
            return new Advice.Entry(Verdict.WEAR, L.t("wynnlabs.auto.wear_instead", weakestName,
                    Math.round((mine - weakestScore) * 10) / 10.0,
                    setNote(info, wk.stack()), diff(gear, weakestGear), old));
        }
        if (mine < weakestScore - 0.5) {
            return SellAdvice.sell(gear, L.t("wynnlabs.auto.worn_better", weakestName));
        }
        if (itemLevel < level - tooLow) {
            return SellAdvice.sell(gear, L.t("wynnlabs.auto.worn_same_older", weakestName));
        }
        if (junk) {
            return SellAdvice.sell(gear, L.t("wynnlabs.auto.normal_not_better"));
        }
        return null;                         // too close to call: ask the assistant
    }

    /**
     * What the build plan says about a piece you carry: GİY when it is part of the best build
     * (with how much the whole build gains and the skill points it needs), SAT when the plan
     * fills its slot with something better. Null without a plan or for pieces it cannot read.
     */
    private static Advice.Entry fromPlan(AutoAdvice.StackFacts stack, ItemView gear) {
        Build.Plan plan = Build.plan();
        if (plan == null || gear.unidentified() || gear.data().level() > GameState.current().level()) {
            return null;
        }
        String slot = null;
        String key = ItemJson.keyOf(gear, stack.name());
        for (var e : plan.pieces().entrySet()) {
            if (e.getValue() != null && e.getValue().key().equals(key)) {
                slot = e.getKey();
            }
        }
        if (slot != null) {
            Damage.Piece w = plan.worn().get(slot);
            String gain = plan.gainPercent() > 0.5 ? L.t("wynnlabs.auto.build_gain", Math.round(plan.gainPercent())) : "";
            return new Advice.Entry(Verdict.WEAR, w == null
                    ? L.t("wynnlabs.auto.wear_empty", gain, skillChange(plan))
                    : L.t("wynnlabs.auto.plan_instead", w.name(), gain, skillChange(plan)));
        }
        Slot type = gear.data().slot();
        String s1 = Build.slotOf(type, 0);
        if (s1 == null) {
            return null;
        }
        Damage.Piece p1 = plan.pieces().get(s1);
        Damage.Piece p2 = type == Slot.RING ? plan.pieces().get("RING2") : null;
        Damage.Piece better = p1 != null ? p1 : p2;
        if (better == null) {
            return null;
        }
        String names = better.name() + (p2 != null && p1 != null ? ", " + p2.name() : "");
        return SellAdvice.sell(gear, L.t("wynnlabs.auto.plan_has_better", names));
    }

    /** ". Beceri: Dex 4→17, Def 6→0" when the plan wants different skill points. */
    private static String skillChange(Build.Plan plan) {
        List<String> parts = new ArrayList<>();
        for (SkillKind s : SkillKind.values()) {
            int now = plan.wornAllocation()[s.ordinal()];
            int target = plan.allocation()[s.ordinal()];
            if (now != target) {
                parts.add(s.displayName().substring(0, 3) + " " + now + "→" + target);
            }
        }
        if (parts.isEmpty()) {
            return "";
        }
        String text = L.t("wynnlabs.auto.skill_change", String.join(", ", parts));
        return GameState.current().skillsKnown() ? text : " ≈" + text;
    }

    /** "listede Presto var (Lv. 16, ~2 EB)" or a plain hint when the list has nothing. */
    private static String nextUpgrade(Slot type) {
        Shopping.Pick next = Shopping.best(type);
        if (next == null) {
            return L.t("wynnlabs.auto.next_upgrade_none");
        }
        Prices.Price price = GameState.current().price(next.info().name());
        String cost = price != null && price.typical() != null ? ", ~" + Prices.fmt(price.typical()) : "";
        return L.t("wynnlabs.auto.next_upgrade", next.info().name(), next.info().requirements().level(), cost);
    }

    /**
     * When better gear asks for skill points you do not have: which and how many. Your points
     * are what the Character Info menu showed last plus what gear, tomes and sets give.
     * Null when it fits.
     */
    /**
     * Whether a higher-level piece in your bag loses to the shopping list's pick for its slot,
     * both measured the same way: % the build gains (the plan), else the stat score.
     */
    private static boolean weakerThanPick(AutoAdvice.StackFacts stack, ItemView gear, Shopping.Pick target) {
        Build.Plan plan = Build.plan();
        Damage.Piece mine = Damage.piece(gear, ItemJson.keyOf(gear, stack.name()), stack.stack());
        if (plan == null || mine == null || plan.result().objective() <= 0) {
            return GearScore.score(gear) < target.score();
        }
        int lv = gear.data().level();
        int earned = Math.max(plan.earned(), (Math.min(lv, 101) - 1) * 2);
        double best = Double.NaN;
        Slot t = gear.data().slot();
        for (String slot : t == Slot.RING ? new String[] {"RING1", "RING2"} : new String[] {Build.slotOf(t, 0)}) {
            if (slot == null) {
                continue;
            }
            double v = Build.trySlot(plan.pieces(), plan.allocation(), slot, mine, lv, earned);
            if (!Double.isNaN(v) && (Double.isNaN(best) || v > best)) {
                best = v;
            }
        }
        if (Double.isNaN(best)) {
            return true;                        // cannot be worn even then
        }
        double base = plan.objectiveAt(lv);
        double gain = base <= 0 ? -100 : (best / base - 1) * 100;
        return gain < target.gain();
    }

    private static Advice.Entry skillGate(Requirement reqs, String then,
            java.util.Map<String, Double> ownStats) {
        return skillGate(reqs, then, ownStats, null);
    }

    /**
     * Skill points missing to wear a piece. {@code replaced} is the piece it would take off:
     * its skill bonus goes away with it, so it does not count toward the new one.
     */
    private static Advice.Entry skillGate(Requirement reqs, String then,
            java.util.Map<String, Double> ownStats, java.util.Map<String, Double> replaced) {
        GameState state = GameState.current();
        List<String> missing = new ArrayList<>();
        int needed = 0;
        for (var req : reqs.skills()) {
            SkillKind skill = req.skill();
            int have = state.assignedSeen(skill);
            have += state.gearPoints(skill) + state.tomePoints(skill)
                    + state.setBonusPoints(skill);
            String api = "raw" + skill.name().charAt(0) + skill.name().substring(1).toLowerCase(java.util.Locale.ROOT);
            if (ownStats != null) {             // the piece's own penalty counts against itself
                have += (int) Math.min(0, ownStats.getOrDefault(api, 0.0));
            }
            if (replaced != null) {             // the piece coming off takes its bonus with it
                have -= (int) Math.round(replaced.getOrDefault(api, 0.0));
            }
            int shortBy = req.points() - have;
            if (shortBy > 0) {
                missing.add(skill.displayName() + " +" + shortBy);
                needed += shortBy;
            }
        }
        if (missing.isEmpty()) {
            return null;
        }
        String what = String.join(", ", missing);
        int free = state.unspent();
        if (free >= needed) {
            return new Advice.Entry(Verdict.SPEND, L.t("wynnlabs.auto.skill_gate_spend", what, free, then));
        }
        return new Advice.Entry(Verdict.KEEP, L.t("wynnlabs.auto.skill_gate_missing", what, then));
    }

    /**
     * What changes against the worn piece, weighted for the build: ". Kazanç: Spell Damage +12,
     * XP +6. Kayıp: Health -20". Stats the build does not care about are left out.
     */
    private static String diff(ItemView mine, ItemView worn) {
        java.util.Map<String, double[]> d = new java.util.LinkedHashMap<>();   // api -> {delta, weighted}
        java.util.Map<String, String> names = new java.util.HashMap<>();
        java.util.Map<String, String> units = new java.util.HashMap<>();
        for (int side = 0; side < 2; side++) {
            ItemView g = side == 0 ? mine : worn;
            if (g == null || g.rolled() == null) {
                continue;
            }
            for (Roll v : g.rolled().rolls()) {
                String api = v.key();
                names.put(api, v.name());
                units.put(api, v.unit().equals("PERCENT") ? "%" : "");
                double[] e = d.computeIfAbsent(api, k -> new double[2]);
                e[0] += side == 0 ? v.value() : -v.value();
            }
        }
        for (var e : d.entrySet()) {
            e.getValue()[1] = Rules.statWeight(e.getKey()) * e.getValue()[0];
        }
        List<String> gain = new ArrayList<>();
        List<String> loss = new ArrayList<>();
        d.entrySet().stream()
                .filter(e -> e.getValue()[0] != 0 && e.getValue()[1] != 0)
                .sorted((a, b) -> Double.compare(Math.abs(b.getValue()[1]), Math.abs(a.getValue()[1])))
                .forEach(e -> {
                    int delta = (int) e.getValue()[0];
                    String t = names.get(e.getKey()) + " " + (delta > 0 ? "+" : "") + delta + units.get(e.getKey());
                    List<String> to = e.getValue()[1] > 0 ? gain : loss;
                    if (to.size() < 2) {
                        to.add(t);
                    }
                });
        String out = gain.isEmpty() ? "" : L.t("wynnlabs.auto.diff_gain", String.join(", ", gain));
        return loss.isEmpty() ? out : out + L.t("wynnlabs.auto.diff_loss", String.join(", ", loss));
    }

    /** ", öne çıkan: Spell Damage +17, XP +6": the two stats that weigh most for the build. */
    private static String best(ItemView gear) {
        ItemView.Rolled gi = gear.rolled();
        if (gi == null) {
            return "";
        }
        List<Roll> vals = new ArrayList<>(gi.rolls());
        vals.removeIf(v -> Rules.statWeight(v.key()) * v.value() <= 0);
        vals.sort((a, b) -> Double.compare(
                Math.abs(Rules.statWeight(b.key()) * b.value()),
                Math.abs(Rules.statWeight(a.key()) * a.value())));
        if (vals.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Roll v : vals.subList(0, Math.min(2, vals.size()))) {
            String unit = v.unit().equals("PERCENT") ? "%" : "";
            parts.add(v.name() + " " + (v.value() > 0 ? "+" : "") + v.value() + unit);
        }
        return L.t("wynnlabs.auto.best_stats", String.join(", ", parts));
    }

    /**
     * Crafted gear: same checks as dropped gear (class, level, what you wear, skill points),
     * plus a warning when its durability is almost used up.
     */
    static Advice.Entry crafted(ItemStack stack, ItemView.Crafted c, Klass cls, int level) {
        Klass need = c.requiredClass();
        if (need == null || need == Klass.NONE) {
            need = c.slot().klass();
        }
        if (need != null && need != Klass.NONE && cls != null && need != cls) {
            return new Advice.Entry(Verdict.SELL, L.t("wynnlabs.auto.crafted_wrong_class", AltCharacters.className(need), AltCharacters.className(cls)));
        }
        List<ItemStack> worn = WornGear.worn(c.slot());
        for (ItemStack w : worn) {
            if (w == stack) {
                return null;
            }
        }
        String wear = "";
        var dur = c.durability();
        if (dur != null && dur.max() > 0 && dur.current() * 10 < dur.max()) {
            wear = L.t("wynnlabs.auto.crafted_durability", dur.current(), dur.max());
        }
        if (c.level() > level) {
            return new Advice.Entry(Verdict.KEEP, L.t("wynnlabs.auto.wear_at_level", c.level()) + wear);
        }
        WornGear.Weakest wk = WornGear.weakest(worn);
        if (wk == null) {
            return null;
        }
        double mine = GearScore.score(c);
        if (wk.score() < 0 || mine > wk.score() + 0.5) {
            String then = wk.score() < 0 ? L.t("wynnlabs.auto.then_wear_empty") : L.t("wynnlabs.auto.then_wear_instead", wk.name());
            Advice.Entry gate = skillGate(c.requirements(), then, null);
            if (gate != null) {
                return gate;
            }
            return new Advice.Entry(Verdict.WEAR, wk.score() < 0
                    ? L.t("wynnlabs.auto.wear_empty", wear, "")
                    : L.t("wynnlabs.auto.wear_instead", wk.name(), Math.round((mine - wk.score()) * 10) / 10.0, "", "", wear));
        }
        if (mine < wk.score() - 0.5 || c.level() < level - (int) Rules.num("tooLowLevels", 4)) {
            return new Advice.Entry(Verdict.SELL, L.t("wynnlabs.auto.crafted_worn_better", wk.name(), wear));
        }
        return null;
    }

    /** How many pieces of this set you wear, leaving out one stack (the one being replaced). */
    private static int setCount(String set, ItemStack except) {
        int n = 0;
        for (ItemStack w : WornGear.allWorn()) {
            if (w == except || w.isEmpty()) {
                continue;
            }
            Optional<ItemView> p = GameState.current().view(w);
            if (p.isPresent() && p.get().isGear() && p.get().data().set().isPresent()
                    && p.get().data().set().get().name().equals(set)) {
                n++;
            }
        }
        return n;
    }

    /** The set bonus for n pieces, scored with the rule weights. */
    private static double setScore(SetData set, int n) {
        if (n < 1) {
            return 0;
        }
        double s = 0;
        var bonus = set.bonusFor(n);
        if (bonus == null) {
            return 0;
        }
        for (var e : bonus.minor()) {
            double w = Rules.statWeight(e.key());
            int v = e.value();
            if (w != 0) {
                s += w * Math.signum(v) * Math.min(3.0, 1.0 + Math.abs(v) / 10.0);
            }
        }
        return s + bonus.major().size() * 2.0;
    }

    /**
     * What wearing this piece instead of {@code replaced} does to set bonuses: the bonus it
     * adds to its own set, minus what the replaced piece's set loses.
     */
    private static double setDelta(ItemData info, ItemStack replaced) {
        double d = 0;
        if (info.set().isPresent()) {
            var set = info.set().get();
            int others = setCount(set.name(), replaced);
            d += setScore(set, others + 1) - setScore(set, others);
        }
        Optional<ItemView> rp = replaced == null || replaced.isEmpty() ? Optional.empty()
                : GameState.current().view(replaced);
        if (rp.isPresent() && rp.get().isGear() && rp.get().data().set().isPresent()) {
            var set = rp.get().data().set().get();
            boolean same = info.set().isPresent() && info.set().get().name().equals(set.name());
            if (!same) {
                int withIt = setCount(set.name(), null);
                d -= setScore(set, withIt) - setScore(set, withIt - 1);
            }
        }
        return d;
    }

    /** ", set: Morph 3/8" when the piece belongs to a set. */
    private static String setNote(ItemData info, ItemStack replaced) {
        if (info.set().isEmpty()) {
            return "";
        }
        var set = info.set().get();
        int n = setCount(set.name(), replaced) + 1;
        return ", set: " + set.cleanName() + " " + n + "/" + set.full();
    }

    /**
     * Another piece in your bag that fills the same slot better (two for rings), or null.
     * Keeps two upgrades for one slot from both saying GİY.
     */
    private static String betterInBag(ItemStack self, ItemView gear, Slot type, int level, double mine) {
        GameState state = GameState.current();
        int slots = type == Slot.RING ? 2 : 1;
        int better = 0;
        String best = null;
        double bestScore = mine;
        List<ItemStack> inv = state.inventory();
        for (int i = 0; i < 36 && i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (s == self || s.isEmpty()) {
                continue;
            }
            Optional<ItemView> p = state.view(s);
            if (p.isEmpty() || !p.get().isGear() || p.get().data().slot() != type
                    || p.get().unidentified() || p.get().data().level() > level
                    || AutoAdvice.isWorn(s)) {
                continue;
            }
            ItemView g = p.get();
            Klass need = classOf(g);
            if (need != null && need != state.classType()) {
                continue;
            }
            double sc = GearScore.score(g);
            if (sc > mine || (sc == mine && i < slotOf(self, inv))) {
                better++;
                if (sc > bestScore || best == null) {
                    best = g.data().name();
                    bestScore = sc;
                }
            }
        }
        return better >= slots ? best : null;
    }

    private static int slotOf(ItemStack stack, List<ItemStack> inv) {
        for (int i = 0; i < 36 && i < inv.size(); i++) {
            if (inv.get(i) == stack) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static Klass classOf(ItemView gear) {
        Klass c = gear.data().slot().klass();
        if (c == null || c == Klass.NONE) {
            c = gear.data().requirements().klass().orElse(null);
        }
        return c == Klass.NONE ? null : c;
    }
}
