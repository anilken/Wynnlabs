package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.Requirement;
import io.github.anilken.wynnlabs.model.Roll;
import io.github.anilken.wynnlabs.model.SetData;
import io.github.anilken.wynnlabs.model.SkillKind;
import io.github.anilken.wynnlabs.model.Slot;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Instant verdicts from {@link Rules}, so not every item needs a question to the assistant.
 *
 * <p>Fixed rules first (names.&lt;item&gt;, kinds.&lt;kind&gt;), then plain checks: wrong class,
 * far below your level, not yet wearable, skill points missing, or better or worse than what
 * you wear in the same slot, scored with the rule weights. Advice the assistant wrote for one
 * item always wins. Items the rules cannot judge (equipped gear, close calls) get no line.
 */
final class AutoAdvice {

    private AutoAdvice() {}

    /** {@code gain}: what a Trade Market verdict expects to bring in ({@link #market}), NaN otherwise. */
    private record Cached(long until, int stamp, Advice.Entry entry, double gain) {}

    private static final java.util.Map<ItemStack, Cached> CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * {@link #decide}, remembered until something it depends on changes ({@link Stamp}: rules,
     * settings, prices, advice, what you carry and wear, your level), and at most about half a
     * minute for what the stamp does not cover, staggered per stack so a full menu never
     * expires in one frame.
     */
    static Advice.Entry cached(ItemStack stack) {
        return MarketSlots.apply(stack, raw(stack).entry());
    }

    /** {@link #cached} before the Trade Market's order limit: what {@link MarketSlots} ranks. */
    static Advice.Entry uncapped(ItemStack stack) {
        return raw(stack).entry();
    }

    /** Emeralds the stack's Trade Market verdict expects after the fee (and a reveal), NaN when unknown. */
    static double gain(ItemStack stack) {
        return raw(stack).gain();
    }

    private static Cached raw(ItemStack stack) {
        long now = System.currentTimeMillis();
        int stamp = Stamp.now();
        Cached c = CACHE.get(stack);
        if (c != null && now < c.until() && c.stamp() == stamp) {
            return c;
        }
        long t = Perf.start();                  // a miss: /perf counts them as advice.decide
        double[] noted = GAIN.get();
        double outer = noted[0];                // a verdict that asks for another stack's keeps its own
        noted[0] = Double.NaN;
        Advice.Entry e = decide(stack);
        double gain = noted[0];
        noted[0] = outer;
        Perf.end("advice.decide", t);
        c = new Cached(now + 30_000 + (System.identityHashCode(stack) & 4095), stamp, e, gain);
        CACHE.put(stack, c);
        return c;
    }

    /** The gain the verdict being decided on this thread noted with {@link #market}. */
    private static final ThreadLocal<double[]> GAIN = ThreadLocal.withInitial(() -> new double[] {Double.NaN});

    /**
     * A verdict that sends the item to the Trade Market, with what it should bring in after the
     * fee: {@link MarketSlots} ranks these when there are more of them than market orders.
     */
    private static Advice.Entry market(Advice.Entry e, double gain) {
        GAIN.get()[0] = gain;
        return e;
    }

    /** Rolled at least rules.trade.keepRollPercent overall (0 turns it off): never for the Blacksmith. */
    static boolean highRoll(ItemView gear) {
        double min = Rules.sub("trade", "keepRollPercent", 80);
        ItemView.Rolled r = gear.rolled();
        return min > 0 && r != null && r.hasOverall() && r.overallPercent() >= min;
    }

    /** {@code price} less the market fee. */
    private static double afterFee(double price) {
        return price * (1 - marketFee() / 100.0);
    }

    /** True when this stack is what you wear (armour, accessory or your weapon). */
    static boolean isWorn(ItemStack stack) {
        GameState state = GameState.current();
        if (!state.inGame() || stack == null || stack.isEmpty()) {
            return false;
        }
        Optional<ItemView> p = state.view(stack);
        Slot type = p.isPresent() && p.get().isGear() ? p.get().data().slot()
                : p.isPresent() && p.get().isCrafted() ? p.get().crafted().slot() : null;
        if (type == null) {
            return false;
        }
        for (ItemStack w : worn(type)) {
            if (w == stack) {
                return true;
            }
        }
        return false;
    }

    /**
     * What a verdict needs from the stack besides the mod's reading of it: its shown name
     * (cleaned) and count, and the stack itself, to tell what you wear and carry apart from a
     * copy elsewhere. {@code stack} may be null for an item that is in neither (a bank page).
     */
    record StackFacts(ItemStack stack, String name, int count) {

        static StackFacts of(ItemStack stack) {
            return new StackFacts(stack, ItemJson.clean(stack.getHoverName().getString()), stack.getCount());
        }
    }

    static Advice.Entry decide(ItemStack stack) {
        GameState state = GameState.current();
        if (!state.inGame() || stack == null || stack.isEmpty()) {
            return null;
        }
        try {
            Optional<ItemView> parsed = state.view(stack);
            if (parsed.isEmpty()) {
                return null;
            }
            return decide(parsed.get(), StackFacts.of(stack));
        } catch (Throwable ignored) {
            // no automatic advice rather than a broken tooltip
        }
        return null;
    }

    /** The verdict for an item the mod has read; null when the rules cannot judge it. */
    static Advice.Entry decide(ItemView item, StackFacts stack) {
        try {
            Advice.Entry fixed = Rules.fixed("names", nameOf(item, stack));
            if (fixed == null) {
                fixed = Rules.fixed("kinds", kindOf(item));
            }
            if (fixed != null) {
                return fixed;
            }
            GameState state = GameState.current();
            Klass cls = state.classType();
            int level = state.level();
            switch (item.kind()) {
                case GEAR -> {
                    return gear(stack, item, cls, level);
                }
                case BOX -> {
                    return box(item.box(), cls, level);
                }
                case CRAFTED -> {
                    return crafted(stack.stack(), item.crafted(), cls, level);
                }
                case SKILL_POINT -> {
                    return skillButton(item.skillPoint().skill());
                }
                case POWDER -> {
                    return powder(item.powder());
                }
                case DUNGEON_KEY -> {
                    return dungeonKey(item.dungeonKey(), level, stack);
                }
                case INGREDIENT, MATERIAL -> {
                    return craftingItem(item, stack);
                }
                default -> {
                }
            }
        } catch (Throwable ignored) {
            // no automatic advice rather than a broken tooltip
        }
        return null;
    }

    /** "gear", "gearbox", else the item's kind from its reader's name ("ingredient", "emeraldpouch" …). */
    static String kindOf(ItemView item) {
        if (item.isGear()) {
            return "gear";
        }
        if (item.isBox()) {
            return "gearbox";
        }
        return item.type().replace("Item", "").toLowerCase(Locale.ROOT);
    }

    private static String nameOf(ItemView item, StackFacts stack) {
        if (item.isGear()) {
            return item.data().name();
        }
        return stack.name();
    }

    private static Advice.Entry gear(StackFacts facts, ItemView gear, Klass cls, int level) {
        ItemStack stack = facts.stack();
        int tooLow = (int) Rules.num("tooLowLevels", 4);
        int tooHigh = (int) Rules.num("tooHighLevels", 5);
        ItemData info = gear.data();
        Slot type = info.slot();
        Klass need = classOf(gear);
        if (need != null && cls != null && need != cls) {
            String alt = altOfClass(need, info.level(), info.level());
            if (alt != null) {
                return new Advice.Entry(Verdict.KEEP, L.t("wynnlabs.auto.other_class_alt", className(need), alt));
            }
            return sell(gear, L.t("wynnlabs.auto.wrong_class", className(need), className(cls)));
        }
        if (type == Slot.MASTERY_TOME || type == Slot.CHARM) {
            return null;
        }
        List<ItemStack> worn = worn(type);
        for (ItemStack w : worn) {
            if (w == stack) {
                return null;                 // what you wear: only the assistant judges that
            }
        }
        int itemLevel = info.level();
        // An empty slot: anything you can wear beats nothing, however old it is
        Weakest now = weakest(worn);
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
            double potential = score(info);
            if (now != null && now.name() != null && potential < now.score() - 0.5) {
                return sell(gear, L.t("wynnlabs.auto.worn_better_even_identified", now.name()));
            }
            if (itemLevel < level - tooLow) {
                // Old for you: when it sells well as it is, sell it unidentified instead of paying to identify it
                Prices.Price pr = GameState.current().price(info.name());
                Double unid = pr == null ? null : pr.unidTypical() != null ? pr.unidTypical() : pr.typical();
                if (unid != null && unid >= Rules.sub("trade", "minTradeMarketPrice", 1920)) {
                    return market(new Advice.Entry(Verdict.SELL, L.t("wynnlabs.auto.old_sell_unidentified",
                            itemLevel, Prices.fmt(unid), cost)), afterFee(unid));
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
                return sell(gear, L.t("wynnlabs.auto.normal_weak_later", itemLevel));
            }
            Shopping.Pick target = Shopping.best(type);
            if (target != null && weakerThanPick(facts, gear, target)) {
                return sell(gear, L.t("wynnlabs.auto.list_pick_better", target.info().name(),
                        target.info().requirements().level()));
            }
            return new Advice.Entry(Verdict.KEEP, L.t("wynnlabs.auto.wear_at_level", itemLevel));
        }
        // Compare with the weakest piece you wear in the same slot
        Weakest wk = weakest(worn);
        if (wk == null) {
            return null;
        }
        double weakestScore = wk.score();
        ItemView weakestGear = wk.gear();
        String weakestName = wk.name();
        double mine = score(gear) + setDelta(info, wk.stack());
        boolean empty = weakestScore < 0;
        if (empty || mine > weakestScore + 0.5) {
            String then = empty ? L.t("wynnlabs.auto.then_wear_empty") : L.t("wynnlabs.auto.then_wear_instead", weakestName);
            String rival = betterInBag(stack, gear, type, level, mine);
            if (rival != null) {
                return sell(gear, L.t("wynnlabs.auto.bag_rival_better", rival));
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
            return sell(gear, L.t("wynnlabs.auto.worn_better", weakestName));
        }
        if (itemLevel < level - tooLow) {
            return sell(gear, L.t("wynnlabs.auto.worn_same_older", weakestName));
        }
        if (junk) {
            return sell(gear, L.t("wynnlabs.auto.normal_not_better"));
        }
        return null;                         // too close to call: ask the assistant
    }

    /**
     * What the build plan says about a piece you carry: GİY when it is part of the best build
     * (with how much the whole build gains and the skill points it needs), SAT when the plan
     * fills its slot with something better. Null without a plan or for pieces it cannot read.
     */
    private static Advice.Entry fromPlan(StackFacts stack, ItemView gear) {
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
        return sell(gear, L.t("wynnlabs.auto.plan_has_better", names));
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

    /** The Character Info buttons: how many points the best build wants in this skill. */
    private static Advice.Entry skillButton(SkillKind skill) {
        Build.Plan plan = Build.plan();
        if (plan == null) {
            return null;
        }
        // For what you wear now; the gear swap notes say how the points change with a swap
        int target = plan.wornTarget()[skill.ordinal()];
        int now = GameState.current().assigned(skill);
        if (target > now) {
            return new Advice.Entry(Verdict.SPEND, L.t("wynnlabs.auto.skill_target_up", target, target - now));
        }
        if (target < now) {
            return new Advice.Entry(Verdict.WAIT, L.t("wynnlabs.auto.skill_target_down", target, now - target));
        }
        return new Advice.Entry(Verdict.WAIT, L.t("wynnlabs.auto.skill_on_target", target));
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
    private static boolean weakerThanPick(StackFacts stack, ItemView gear, Shopping.Pick target) {
        Build.Plan plan = Build.plan();
        Damage.Piece mine = Damage.piece(gear, ItemJson.keyOf(gear, stack.name()), stack.stack());
        if (plan == null || mine == null || plan.result().objective() <= 0) {
            return score(gear) < target.score();
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
        double gain = (best / plan.result().objective() - 1) * 100;
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
     * SAT, and where: the Trade Market when something makes the item worth more than the
     * Blacksmith pays (shiny, perfect, a valuable tier, a high roll), else the Blacksmith.
     */
    private static Advice.Entry sell(ItemView gear, String why) {
        String restriction = gear.data().restriction();
        if (restriction.equals("QUEST_ITEM")) {
            return new Advice.Entry(Verdict.KEEP, L.t("wynnlabs.auto.quest_item"));
        }
        // One of your other characters would wear it: keep it for them (bank is shared), when
        // the player asks for that ("Keep items for your other characters")
        String alt = Settings.on("altAdvice") ? Today.forAlt(gear.data()) : null;
        if (alt != null && !restriction.equals("SOULBOUND")) {
            return new Advice.Entry(Verdict.KEEP, L.t("wynnlabs.auto.keep_for_alt", why, alt));
        }
        if (restriction.equals("UNTRADABLE") || restriction.equals("SOULBOUND")) {
            return new Advice.Entry(Verdict.SELL, L.t("wynnlabs.auto.sell_untradable", why));
        }
        // A known market price beats every guess below; a week-old one is no longer known
        GameState state = GameState.current();
        boolean high = highRoll(gear);
        Prices.Price price = state.price(gear.data().name());
        if (price == null || state.now() - price.seen() > 7L * 24 * 3600 * 1000) {
            state.requestPrices(List.of(gear.data().name()));
            price = price != null && state.now() - price.seen() > 7L * 24 * 3600 * 1000 ? null : price;
        }
        if (price != null && gear.unidentified()) {
            Advice.Entry revealed = sellRevealed(gear, why, price);
            if (revealed != null) {
                return revealed;
            }
        }
        if (price != null) {
            Double typical = price.typical();
            if (typical != null) {
                if (typical >= Rules.sub("trade", "minTradeMarketPrice", 1920)) {
                    return market(new Advice.Entry(Verdict.SELL, L.t("wynnlabs.auto.sell_market_price", why,
                            Prices.fmt(typical))), afterFee(typical));
                }
                if (high) {
                    // the price is the cheapest listing, any roll: a high roll sells for more than that
                    return market(new Advice.Entry(Verdict.SELL, L.t("wynnlabs.auto.sell_market_high_roll", why,
                            Math.round(gear.rolled().overallPercent()), Prices.fmt(typical))), afterFee(typical));
                }
                return new Advice.Entry(Verdict.SELL, L.t("wynnlabs.auto.sell_blacksmith_market_price", why, Prices.fmt(typical)));
            }
        }
        String where;
        ItemView.Rolled gi = gear.rolled();
        String tier = gear.data().tier().name();
        float pct = gi != null && gi.hasOverall() ? gi.overallPercent() : -1;
        if (gi != null && gi.shiny()) {
            where = L.t("wynnlabs.auto.sell_shiny");
        } else if (gi != null && gi.perfect()) {
            where = L.t("wynnlabs.auto.sell_perfect");
        } else if (Rules.list("trade", "valuableTiers").contains(tier)) {
            where = L.t("wynnlabs.auto.sell_valuable_tier", nice(tier));
        } else if (high) {
            where = L.t("wynnlabs.auto.sell_market_high_roll_no_price", Math.round(pct));
        } else if (tier.equals("LEGENDARY") && pct >= Rules.sub("trade", "legendaryMinPercent", 80)
                || pct >= Rules.sub("trade", "anyMinPercent", 95)) {
            // without a price the 30 EB rule cannot be checked: say so instead of guessing
            where = L.t("wynnlabs.auto.sell_blacksmith_high_roll", Math.round(pct),
                    Prices.fmt(Rules.sub("trade", "minTradeMarketPrice", 1920)));
        } else {
            where = L.t("wynnlabs.auto.sell_blacksmith_no_price");
        }
        return new Advice.Entry(Verdict.SELL, why + ". " + where);
    }

    /**
     * A revealed piece you will not wear (named, stats "?"): it lists on the Trade Market as it
     * is, but buyers cannot see its rolls. Identify it first only when identified copies sell
     * for clearly more (at least rules.trade.revealMinProfit over the fee). Null without a price.
     */
    private static Advice.Entry sellRevealed(ItemView gear, String why, Prices.Price price) {
        Double unid = price.unidTypical();
        Double identified = price.typical();
        int cost = gear.data().tier().identificationCost(gear.data().level());
        if (unid != null && identified != null
                && (identified - unid) * (1 - marketFee() / 100.0) - cost >= revealMinProfit()) {
            return new Advice.Entry(Verdict.IDENTIFY, L.t("wynnlabs.auto.unid_identify_then_sell", cost,
                    Prices.fmt(identified), Prices.fmt(unid), why));
        }
        Double asIs = unid != null ? unid : identified;
        if (asIs == null) {
            return null;
        }
        if (asIs >= Rules.sub("trade", "minTradeMarketPrice", 1920)) {
            return market(new Advice.Entry(Verdict.SELL, L.t("wynnlabs.auto.sell_market_unidentified", why,
                    Prices.fmt(asIs))), afterFee(asIs));
        }
        return new Advice.Entry(Verdict.SELL, L.t("wynnlabs.auto.sell_blacksmith_market_price", why, Prices.fmt(asIs)));
    }

    private static Advice.Entry sellBox(ItemView.Box box, String why) {
        // Boss altar and similar drops cannot be traded: if nothing in the box can, only the
        // Blacksmith takes it (The Scarecrow's Vest from Bovine Barn)
        String tier = box.tier().name();
        boolean valuable = Rules.list("trade", "valuableTiers").contains(tier) || tier.equals("LEGENDARY");
        BoxRoutes.Choice c = BoxRoutes.choose(outcomes(box, null), marketFee(), revealMinProfit(), valuable);
        return boxRoute(box, c, why, 0, 0);
    }

    /** The market fee the advice takes off a sale, in % (rules.trade.marketFeePercent). */
    static double marketFee() {
        return Rules.sub("trade", "marketFeePercent", 5);
    }

    /** Emeralds a reveal and listing must leave over its fee (rules.trade.revealMinProfit). */
    static double revealMinProfit() {
        return Rules.sub("trade", "revealMinProfit", 640);
    }

    /**
     * Everything the box can hold, with its fee, its price listed unidentified (else identified)
     * and whether it is {@code wear}, the one you would wear. Asks for the prices it lacks.
     */
    private static List<BoxRoutes.Outcome> outcomes(ItemView.Box box, String wear) {
        GameState state = GameState.current();
        List<String> names = new ArrayList<>();
        List<BoxRoutes.Outcome> out = new ArrayList<>();
        for (ItemData info : state.possibleGears(box)) {
            names.add(info.name());
            Prices.Price p = state.price(info.name());
            Double v = p == null ? null : p.unidTypical() != null ? p.unidTypical() : p.typical();
            out.add(new BoxRoutes.Outcome(info.name(), box.tier().identificationCost(info.level()), v,
                    Shopping.tradable(info), info.name().equals(wear)));
        }
        if (!names.isEmpty()) {
            state.requestPrices(names);
        }
        return out;
    }

    /** The verdict and note for the way {@link BoxRoutes} picked: the way first, then why. */
    private static Advice.Entry boxRoute(ItemView.Box box, BoxRoutes.Choice c, String why, int fits, int all) {
        BoxRoutes.Value v = c.value();
        String cost = v.outcomes() == 0 ? box.tier().identificationCost(box.high()) + " E" : BoxRoutes.cost(v);
        String tail = why == null || why.isEmpty() ? "" : ". " + why;
        return switch (c.route()) {
            case IDENTIFIER -> new Advice.Entry(Verdict.IDENTIFY,
                    L.t("wynnlabs.auto.box_identify_wear", cost, c.wearable(), fits, all));
            case UNTRADABLE -> new Advice.Entry(Verdict.SELL, L.t("wynnlabs.auto.sell_untradable", why));
            case REVEAL_MARKET -> market(new Advice.Entry(Verdict.SELL, v.expected() == null
                    ? L.t("wynnlabs.auto.box_reveal_no_price", cost) + tail
                    : L.t("wynnlabs.auto.box_reveal_market", cost, Prices.fmt(v.expected())) + unpriced(v) + tail),
                    v.net() == null ? Double.NaN : v.net());
            case BLACKSMITH -> new Advice.Entry(Verdict.SELL, v.expected() == null
                    ? L.t("wynnlabs.auto.box_blacksmith_no_price") + tail
                    : v.net() < 0
                    ? L.t("wynnlabs.auto.box_blacksmith_fee", Prices.fmt(v.expected()), cost) + unpriced(v) + tail
                    : L.t("wynnlabs.auto.box_blacksmith_small", Prices.fmt(v.net()), Prices.fmt(revealMinProfit()))
                            + unpriced(v) + tail);
        };
    }

    /** " (Tarnkappe: no price)" or " (3/5 have a price)" when some outcomes have none; else "". */
    private static String unpriced(BoxRoutes.Value v) {
        List<String> missing = v.unpriced();
        if (missing.isEmpty()) {
            return "";
        }
        return " " + (missing.size() == 1 ? L.t("wynnlabs.auto.box_unpriced_one", missing.get(0))
                : L.t("wynnlabs.auto.box_unpriced_some", v.outcomes() - missing.size(), v.outcomes()));
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
     * Powders of the build's elements go into gear. The others are never sold: another
     * character may use them, and a Powder Master turns four into one of the next tier
     * (the Powder Exchange changes the element).
     */
    private static Advice.Entry powder(ItemView.Powder powder) {
        String el = powder.element();
        if (Rules.list("powders", "weapon").contains(el)) {
            return new Advice.Entry(Verdict.USE, L.t("wynnlabs.auto.powder_weapon", nice(el)));
        }
        if (Rules.list("powders", "armour").contains(el)) {
            return new Advice.Entry(Verdict.USE, L.t("wynnlabs.auto.powder_armour"));
        }
        String alt = powderUser(el);
        if (alt != null) {
            return new Advice.Entry(Verdict.KEEP, L.t("wynnlabs.auto.powder_alt", alt));
        }
        return new Advice.Entry(Verdict.KEEP, L.t("wynnlabs.auto.powder_unused", nice(el)));
    }

    /**
     * Another character of this class, as the notes name it: Turkish possessive ("Mage'in",
     * "Archer'ın"), English "your Mage". Whole words per class, since the suffix is Turkish only.
     */
    private static String genitive(String cls) {
        return switch (cls.toUpperCase(Locale.ROOT)) {
            case "MAGE" -> L.t("wynnlabs.auto.class_mage_gen");
            case "ARCHER" -> L.t("wynnlabs.auto.class_archer_gen");
            case "WARRIOR" -> L.t("wynnlabs.auto.class_warrior_gen");
            case "ASSASSIN" -> L.t("wynnlabs.auto.class_assassin_gen");
            case "SHAMAN" -> L.t("wynnlabs.auto.class_shaman_gen");
            default -> L.t("wynnlabs.auto.class_other_gen", nice(cls));
        };
    }

    /** A class name shown in a note ("Mage"), from the language file. */
    private static String className(Klass c) {
        if (c == null) {
            return "?";
        }
        return switch (c.name()) {
            case "MAGE" -> L.t("wynnlabs.auto.class_mage");
            case "ARCHER" -> L.t("wynnlabs.auto.class_archer");
            case "WARRIOR" -> L.t("wynnlabs.auto.class_warrior");
            case "ASSASSIN" -> L.t("wynnlabs.auto.class_assassin");
            case "SHAMAN" -> L.t("wynnlabs.auto.class_shaman");
            default -> nice(c);
        };
    }

    /** Another of your characters whose rules put this element's powder in gear ("Mage"), or null. */
    private static String powderUser(String element) {
        GameState state = GameState.current();
        try {
            for (String id : state.characterIds()) {
                var r = id.equals(state.characterId()) ? null : state.characterFile(id, "rules.json");
                if (r == null) {
                    continue;
                }
                if (!r.has("powders")) {
                    continue;
                }
                String text = r.get("powders").toString();
                if (text.contains("\"" + element + "\"")) {
                    String cls = L.t("wynnlabs.auto.other_character_gen");
                    var today = state.characterFile(id, "today.json");
                    if (today != null) {
                        cls = genitive(today.get("class").getAsString());
                    } else if (r.has("goal")) {
                        String goal = r.get("goal").getAsString();
                        for (String c : List.of("MAGE", "ARCHER", "WARRIOR", "ASSASSIN", "SHAMAN")) {
                            if (goal.toUpperCase(Locale.ROOT).contains(c)) {
                                cls = genitive(c);
                            }
                        }
                    }
                    return cls;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Another of your characters of this class for whom an item of these levels still matters
     * (not more than 15 levels below them, nor more than 15 above: a Lv 2 alt will not wear a
     * Lv 61 box for a long time): "Archer'ın (Lv. 70)", or null. An alt whose advice is off
     * (rules.advice.enabled in its own folder) is never kept for.
     */
    static String altOfClass(Klass need, int low, int high) {
        if (!Settings.on("altAdvice")) {
            return null;                        // the player wants no "keep for an alt" advice
        }
        try {
            for (var e : GameState.current().characters().getAsJsonArray("characters")) {
                var o = e.getAsJsonObject();
                if (o.get("active").getAsBoolean() || !need.name().equals(o.get("class").getAsString()) || !altAdvised(o)) {
                    continue;
                }
                int lv = o.get("level").getAsInt();
                if (high >= lv - 15 && low <= lv + 15) {
                    return L.t("wynnlabs.auto.alt_with_level", genitive(need.name()), lv);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Whether the advice is on for that character (its own rules file); unknown counts as on. */
    private static boolean altAdvised(com.google.gson.JsonObject character) {
        try {
            String id = character.has("id") ? character.get("id").getAsString() : "";
            if (id.isEmpty()) {
                return true;
            }
            var f = Dirs.config().resolve("characters").resolve(id).resolve("rules.json");
            if (!java.nio.file.Files.exists(f)) {
                return true;
            }
            var r = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(f, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            var a = r.has("advice") && r.get("advice").isJsonObject() ? r.getAsJsonObject("advice") : null;
            return a == null || !a.has("enabled") || a.get("enabled").getAsBoolean();
        } catch (Exception e) {
            return true;
        }
    }

    /** Another character (of this class, or any for armour) these levels suit: within 15 below to 5 above. */
    static String altOfLevel(Klass need, int low, int high) {
        if (!Settings.on("altAdvice")) {
            return null;
        }
        try {
            for (var e : GameState.current().characters().getAsJsonArray("characters")) {
                var o = e.getAsJsonObject();
                if (o.get("active").getAsBoolean() || !altAdvised(o)) {
                    continue;
                }
                if (need != null && need != Klass.NONE && !need.name().equals(o.get("class").getAsString())) {
                    continue;
                }
                int lv = o.get("level").getAsInt();
                if (high >= lv - 15 && low <= lv + 5) {
                    return L.t("wynnlabs.auto.alt_with_level", genitive(o.get("class").getAsString()), lv);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Ingredients and materials: kept when an open quest asks for them or one of your
     * professions can use them, sold when the stack is worth the 30 EB Trade Market minimum,
     * and never "throw away" while the price is unknown (FİYATLA: hover it once at the market).
     */
    private static Advice.Entry craftingItem(ItemView item, StackFacts stack) {
        GameState state = GameState.current();
        String name = item.name() != null ? item.name() : stack.name();
        int level = item.level() != null ? item.level() : 0;
        String low = name.toLowerCase(Locale.ROOT);
        for (QuestBook.Entry q : state.quests()) {
            if (!"COMPLETED".equals(q.status()) && q.task() != null && q.task().toLowerCase(Locale.ROOT).contains(low)) {
                return new Advice.Entry(Verdict.KEEP, L.t("wynnlabs.auto.quest_wants", QuestGuides.title(q.name())));
            }
        }
        List<String> usable = new ArrayList<>();
        if (item.professions() != null) {
            for (String prof : item.professions()) {
                int lv = state.professionLevel(prof);
                if (lv >= level && lv >= 10) {
                    usable.add(nice(prof) + " Lv. " + lv);
                }
            }
        }
        Prices.Price p = state.price(name);
        Double each = p == null ? null : p.typical();
        double min = Rules.sub("trade", "minTradeMarketPrice", 1920);
        if (each != null && each * stack.count() >= min) {
            return market(new Advice.Entry(Verdict.SELL, L.t("wynnlabs.auto.craft_sell", Prices.fmt(each * stack.count()),
                    stack.count() > 1 ? L.t("wynnlabs.auto.craft_sell_all") : "",
                    usable.isEmpty() ? "" : L.t("wynnlabs.auto.craft_or_use", usable.get(0)))), afterFee(each * stack.count()));
        }
        if (!usable.isEmpty()) {
            return new Advice.Entry(Verdict.KEEP, L.t("wynnlabs.auto.craft_usable", String.join(", ", usable)));
        }
        if (each == null && !state.pricesInstalled()) {
            return new Advice.Entry(Verdict.KEEP, L.t("wynnlabs.auto.craft_no_prices"));
        }
        if (each == null) {
            return new Advice.Entry(Verdict.PRICE, L.t("wynnlabs.auto.craft_price_unknown"));
        }
        return new Advice.Entry(Verdict.KEEP, L.t("wynnlabs.auto.craft_low_value", Prices.fmt(each * stack.count())));
    }

    /**
     * Dungeon keys: use them while the dungeon suits your level (good XP, and you own the key
     * already), keep them for a character they suit, otherwise sell them if worth it.
     */
    private static Advice.Entry dungeonKey(ItemView.DungeonKey key, int level, StackFacts stack) {
        if (key.combatLevel() == null) {
            return null;
        }
        int lv = key.combatLevel();
        String where = L.t("wynnlabs.auto.dungeon_entrance", key.x(), key.z());
        String what = (key.corrupted() ? "Corrupted " : "") + key.dungeon();
        if (key.broken()) {
            return new Advice.Entry(Verdict.KEEP, L.t("wynnlabs.auto.dungeon_broken_key", what, lv));
        }
        if (level >= lv - 2 && level <= lv + 8) {
            return new Advice.Entry(Verdict.USE, L.t("wynnlabs.auto.dungeon_at_level", what, lv, where));
        }
        if (level < lv - 2) {
            return new Advice.Entry(Verdict.KEEP, L.t("wynnlabs.auto.dungeon_later", lv, what));
        }
        String alt = altOfLevel(null, lv, lv);
        if (alt != null) {
            return new Advice.Entry(Verdict.KEEP, L.t("wynnlabs.auto.dungeon_for_alt", what, lv, alt));
        }
        Prices.Price p = GameState.current().price(stack.name());
        if (p != null && p.typical() != null && p.typical() * stack.count() >= Rules.sub("trade", "minTradeMarketPrice", 1920)) {
            return market(new Advice.Entry(Verdict.SELL, L.t("wynnlabs.auto.dungeon_outlevelled_sell",
                    Prices.fmt(p.typical() * stack.count()))), afterFee(p.typical() * stack.count()));
        }
        return new Advice.Entry(Verdict.KEEP, L.t("wynnlabs.auto.dungeon_outlevelled_low", what, lv));
    }

    /** "◆ Powder" hint for your own gear with empty powder slots, or null. */
    static String powderHint(ItemView gear) {
        if (gear.rolled() == null) {
            return null;
        }
        int free = gear.data().powderSlots() - gear.rolled().powders().size();
        if (free <= 0) {
            return null;
        }
        boolean weapon = gear.data().slot().klass() != null;
        List<String> order = Rules.list("powders", weapon ? "weapon" : "armour");
        if (order.isEmpty()) {
            return null;
        }
        List<String> pick = new ArrayList<>();
        for (int i = 0; i < free; i++) {
            pick.add(nice(order.get(i % order.size())));
        }
        return L.n("wynnlabs.auto.powder_hint", free, free, String.join(", ", pick));
    }

    static String nice(String upper) {
        String n = upper.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private static Advice.Entry box(ItemView.Box box, Klass cls, int level) {
        int tooLow = (int) Rules.num("tooLowLevels", 4);
        int tooHigh = (int) Rules.num("tooHighLevels", 5);
        int low = box.low();
        int high = box.high();
        if (high < level - tooLow) {
            // too old for you, maybe right for another of your characters (account bank is shared)
            for (ItemData info : GameState.current().possibleGears(box)) {
                Klass c = info.requirements().klass().orElse(info.slot().klass());
                String alt = altOfLevel(c, low, high);
                if (alt != null) {
                    return new Advice.Entry(Verdict.KEEP, L.t("wynnlabs.auto.box_for_alt", low, high, alt));
                }
            }
            return sellBox(box, L.t("wynnlabs.auto.box_too_low", low, high));
        }
        int fits = 0;
        int all = 0;
        for (ItemData info : GameState.current().possibleGears(box)) {
            all++;
            Klass c = info.requirements().klass().orElse(info.slot().klass());
            if (c == null || c == Klass.NONE || c == cls) {
                fits++;
            }
        }
        if (all > 0 && fits == 0) {
            // not for you, but maybe for another of your characters (the account bank is shared)
            for (ItemData info : GameState.current().possibleGears(box)) {
                Klass c = info.requirements().klass().orElse(info.slot().klass());
                String alt = c == null ? null : altOfClass(c, low, high);
                if (alt != null) {
                    return new Advice.Entry(Verdict.KEEP, L.t("wynnlabs.auto.box_other_class_alt", className(c), alt));
                }
            }
            return sellBox(box, L.t("wynnlabs.auto.box_none_for_class", className(cls)));
        }
        // If even the best outcome is weaker than what you wear, the box is for selling
        // the build model: does any outcome you could wear raise the build at its level? A box
        // whose outcomes all leave the build where it is gets sold, not identified
        Build.Plan plan = Build.plan();
        String wearName = null;
        if (all > 0 && plan != null && plan.result() != null && plan.result().objective() > 0) {
            double bestGain = Double.NEGATIVE_INFINITY;
            String bestName = null;
            for (ItemData info : GameState.current().possibleGears(box)) {
                Klass c = info.requirements().klass().orElse(info.slot().klass());
                if (c != null && c != Klass.NONE && c != cls) {
                    continue;
                }
                double g = buildGain(plan, info, level);
                if (!Double.isNaN(g) && g > bestGain) {
                    bestGain = g;
                    bestName = info.name();
                }
            }
            if (bestName != null && bestGain < Rules.num("boxMinGainPercent", 3)) {
                return sellBox(box, L.t("wynnlabs.auto.box_no_build_gain", bestName,
                        (bestGain >= 0 ? "+" : "") + Math.round(bestGain * 10) / 10.0));
            }
            wearName = bestName;
        }
        // too far ahead to identify now: kept for later only when an outcome will help (above)
        if (low > level + tooHigh) {
            return new Advice.Entry(Verdict.KEEP, L.t("wynnlabs.auto.identify_near_level", low));
        }
        Weakest wb = !GameState.current().inGame() ? null : weakest(worn(box.slot()));
        if (wb != null && wb.name() != null && all > 0) {
            double bestOutcome = Double.NEGATIVE_INFINITY;
            for (ItemData info : GameState.current().possibleGears(box)) {
                Klass c = info.requirements().klass().orElse(info.slot().klass());
                if (c == null || c == Klass.NONE || c == cls) {
                    bestOutcome = Math.max(bestOutcome, score(info));
                }
            }
            if (bestOutcome < wb.score() - 0.5) {
                return sellBox(box, L.t("wynnlabs.auto.box_worn_better", wb.name()));
            }
        }
        if (all == 0) {
            return new Advice.Entry(Verdict.IDENTIFY, L.t("wynnlabs.auto.box_identify", box.tier().identificationCost(high)));
        }
        // the one you would wear: the build's best pick, else the best-scoring outcome for you
        String wear = wearName;
        if (wear == null) {
            double best = Double.NEGATIVE_INFINITY;
            for (ItemData info : GameState.current().possibleGears(box)) {
                Klass c = info.requirements().klass().orElse(info.slot().klass());
                if ((c == null || c == Klass.NONE || c == cls) && info.level() <= level + tooHigh && score(info) > best) {
                    best = score(info);
                    wear = info.name();
                }
            }
        }
        if (wear == null) {
            return new Advice.Entry(Verdict.IDENTIFY, L.t("wynnlabs.auto.box_identify", box.tier().identificationCost(high)));
        }
        String tier = box.tier().name();
        boolean valuable = Rules.list("trade", "valuableTiers").contains(tier) || tier.equals("LEGENDARY");
        return boxRoute(box, BoxRoutes.choose(outcomes(box, wear), marketFee(), revealMinProfit(), valuable),
                null, fits, all);
    }

    /**
     * What a piece adds to the build, in %, worn in its best slot at its own level (or yours,
     * whichever is higher) with the points that level brings; NaN when it does not fit.
     */
    static double buildGain(Build.Plan plan, ItemData info, int level) {
        Slot t = info.slot();
        int lv = Math.max(level, info.requirements().level());
        int earned = Math.max(plan.earned(), (Math.min(lv, 101) - 1) * 2);
        Damage.Piece cand = Damage.piece(info);
        double best = Double.NaN;
        for (String slot : t == Slot.RING ? new String[] {"RING1", "RING2"} : new String[] {Build.slotOf(t, 0)}) {
            if (slot == null) {
                continue;
            }
            double v = Build.trySlot(plan.pieces(), plan.allocation(), slot, cand, lv, earned);
            if (!Double.isNaN(v) && (Double.isNaN(best) || v > best)) {
                best = v;
            }
        }
        return Double.isNaN(best) ? Double.NaN : (best / plan.result().objective() - 1) * 100;
    }

    /** The weakest worn piece: its score, name, the gear (null if crafted) and the stack. */
    record Weakest(double score, String name, ItemView gear, ItemStack stack) {}

    /** The weakest of what you wear there (score -1, name null for an empty slot); null if none. */
    static Weakest weakest(List<ItemStack> worn) {
        if (worn.isEmpty()) {
            return null;
        }
        Weakest out = null;
        for (ItemStack w : worn) {
            Optional<ItemView> p = w.isEmpty() ? Optional.empty() : GameState.current().view(w);
            Weakest here;
            if (p.isPresent() && p.get().isGear()) {
                ItemView wg = p.get();
                here = new Weakest(score(wg), wg.data().name(), wg, w);
            } else if (p.isPresent() && p.get().isCrafted()) {
                ItemView.Crafted wc = p.get().crafted();
                here = new Weakest(score(wc), wc.name(), null, w);
            } else {
                return new Weakest(-1, null, null, ItemStack.EMPTY);
            }
            if (out == null || here.score() < out.score()) {
                out = here;
            }
        }
        return out;
    }

    /** Score of any gear you can wear, crafted or not; NaN for anything else. */
    static double scoreAny(ItemStack stack) {
        Optional<ItemView> p = stack.isEmpty() ? Optional.empty() : GameState.current().view(stack);
        if (p.isPresent() && p.get().isGear()) {
            return score(p.get());
        }
        if (p.isPresent() && p.get().isCrafted()) {
            return score(p.get().crafted());
        }
        return Double.NaN;
    }

    /**
     * Crafted gear: same checks as dropped gear (class, level, what you wear, skill points),
     * plus a warning when its durability is almost used up.
     */
    private static Advice.Entry crafted(ItemStack stack, ItemView.Crafted c, Klass cls, int level) {
        Klass need = c.requiredClass();
        if (need == null || need == Klass.NONE) {
            need = c.slot().klass();
        }
        if (need != null && need != Klass.NONE && cls != null && need != cls) {
            return new Advice.Entry(Verdict.SELL, L.t("wynnlabs.auto.crafted_wrong_class", className(need), className(cls)));
        }
        List<ItemStack> worn = worn(c.slot());
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
        Weakest wk = weakest(worn);
        if (wk == null) {
            return null;
        }
        double mine = score(c);
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

    /** Crafted gear scored like the rest: level, DPS or health, then its rolled stats. */
    static double score(ItemView.Crafted c) {
        double s = c.level() * Rules.num("levelWeight", 1) + Rules.tierBonus("CRAFTED");
        if (c.slot().klass() != null) {
            s += c.dps() * Rules.num("weaponDpsWeight", 0.2);
        } else {
            s += c.health() * Rules.num("armourHealthWeight", 0.04);
        }
        for (Roll v : c.rolls()) {
            s += contribution(v.key(), v.value(), weaponDps(c.slot(), c.dps()));
        }
        return s;
    }

    // ---- set bonuses ----------------------------------------------------------------------

    /** Every piece you wear (armour and accessories), for counting set pieces. */
    private static List<ItemStack> allWorn() {
        List<ItemStack> out = new ArrayList<>();
        for (Slot t : List.of(Slot.HELMET, Slot.CHESTPLATE, Slot.LEGGINGS, Slot.BOOTS,
                Slot.RING, Slot.BRACELET, Slot.NECKLACE)) {
            for (ItemStack w : worn(t)) {
                if (!out.contains(w)) {
                    out.add(w);
                }
            }
        }
        return out;
    }

    /** How many pieces of this set you wear, leaving out one stack (the one being replaced). */
    private static int setCount(String set, ItemStack except) {
        int n = 0;
        for (ItemStack w : allWorn()) {
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
        if (n < 2) {
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
        return ", set: " + set.cleanName() + " " + n + "/" + set.pieces();
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
                    || isWorn(s)) {
                continue;
            }
            ItemView g = p.get();
            Klass need = classOf(g);
            if (need != null && need != state.classType()) {
                continue;
            }
            double sc = score(g);
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

    /** What you wear in the slot this gear goes to (both rings for a ring). */
    private static final java.util.Map<EquipmentSlot, ItemStack> LAST_ARMOUR = new java.util.EnumMap<>(EquipmentSlot.class);

    /**
     * An armour slot. On a mount Wynncraft takes the armour off the player model, so the slots
     * read empty: then the armour seen last on foot stands in, and nothing looks "missing".
     */
    static ItemStack armour(net.minecraft.world.entity.player.Player player, EquipmentSlot slot) {
        ItemStack now = player.getItemBySlot(slot);
        // all four empty at once is the mount hiding them too, even without a vehicle entity
        boolean hidden = player.isPassenger()
                || player.getItemBySlot(EquipmentSlot.HEAD).isEmpty() && player.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
                && player.getItemBySlot(EquipmentSlot.LEGS).isEmpty() && player.getItemBySlot(EquipmentSlot.FEET).isEmpty();
        synchronized (LAST_ARMOUR) {
            ItemStack last = LAST_ARMOUR.get(slot);
            if (last != null && !last.isEmpty() && !WynntilsItems.known(last)) {
                // a kept copy Wynntils has not read: let it read it, so its stats count
                Wyn.annotate(last);
            }
            if (hidden) {
                return now.isEmpty() && last != null ? last : now;
            }
            // A piece that left its slot without turning up in the bag was hidden by the game (mount
            // menus hide some slots, not always all four), not taken off by you: keep the last one.
            if (now.isEmpty() && last != null && !last.isEmpty()) {
                boolean inBag = false;
                var inv = player.getInventory();
                for (int i = 0; i < 36 && !inBag; i++) {
                    inBag = ItemStack.isSameItemSameComponents(inv.getItem(i), last);
                }
                var carried = player.containerMenu == null ? ItemStack.EMPTY : player.containerMenu.getCarried();
                if (!inBag && !ItemStack.isSameItemSameComponents(carried, last)) {
                    return last;
                }
            }
            LAST_ARMOUR.put(slot, now);         // the stack itself: a copy loses Wynntils' reading of it
        }
        return now;
    }

    /** Every second: note the armour while it shows, so a mount taken later has it to stand in. */
    static void snapshotArmour(net.minecraft.client.Minecraft mc) {
        if (mc.player == null || mc.player.tickCount % 20 != 0) {
            return;
        }
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET}) {
            armour(mc.player, slot);
        }
    }

    /** What you wear in the slot this gear goes to (both rings for a ring); nothing when not in game. */
    static List<ItemStack> worn(Slot type) {
        GameState state = GameState.current();
        List<ItemStack> out = new ArrayList<>();
        if (!state.inGame()) {
            return out;
        }
        switch (type) {
            case HELMET -> out.add(state.armour(EquipmentSlot.HEAD));
            case CHESTPLATE -> out.add(state.armour(EquipmentSlot.CHEST));
            case LEGGINGS -> out.add(state.armour(EquipmentSlot.LEGS));
            case BOOTS -> out.add(state.armour(EquipmentSlot.FEET));
            case RING, BRACELET, NECKLACE -> {
                List<ItemStack> acc = state.accessories();
                // order in Wynncraft: ring, ring, bracelet, necklace
                int[] idx = switch (type) {
                    case RING -> new int[] {0, 1};
                    case BRACELET -> new int[] {2};
                    default -> new int[] {3};
                };
                for (int i : idx) {
                    out.add(i < acc.size() ? acc.get(i) : ItemStack.EMPTY);
                }
            }
            default -> {
                if (type.klass() != null) {
                    out.add(bestWeapon());
                }
            }
        }
        return out;
    }

    /** The weapon you fight with: the one in hand, else the strongest in the hotbar. */
    static ItemStack bestWeapon() {
        GameState state = GameState.current();
        ItemStack hand = state.mainHand();
        if (isOwnWeapon(hand)) {
            return hand;
        }
        ItemStack best = ItemStack.EMPTY;
        double bestScore = -1;
        List<ItemStack> inv = state.inventory();
        for (int i = 0; i < 9; i++) {
            ItemStack s = i < inv.size() ? inv.get(i) : ItemStack.EMPTY;
            if (isOwnWeapon(s)) {
                double sc = score(state.view(s).get());
                if (sc > bestScore) {
                    best = s;
                    bestScore = sc;
                }
            }
        }
        return best;
    }

    private static boolean isOwnWeapon(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        Optional<ItemView> p = GameState.current().view(s);
        if (p.isEmpty() || !p.get().isGear() || p.get().data().slot().klass() == null) {
            return false;
        }
        return p.get().data().slot().klass() == GameState.current().classType();
    }

    /**
     * A rough strength number from the rule weights: level and rarity, the weapon's average DPS
     * or the armour's health, then each rolled stat (capped, so one big roll cannot win alone).
     */
    static double score(ItemView g) {
        ItemData info = g.data();
        double s = info.level() * Rules.num("levelWeight", 1) + Rules.tierBonus(info.tier().name());
        if (info.slot().klass() != null) {
            s += info.base().averageDps() * Rules.num("weaponDpsWeight", 0.2);
        } else {
            s += info.base().health() * Rules.num("armourHealthWeight", 0.04);
        }
        ItemView.Rolled gi = g.rolled();
        if (gi != null) {
            for (Roll v : gi.rolls()) {
                s += contribution(v.key(), v.value(),
                        weaponDps(info.slot(), info.base().averageDps()));
            }
        }
        return s;
    }

    /** The same number for gear you do not own yet, with every stat at the middle of its range. */
    static double score(ItemData info) {
        double s = info.requirements().level() * Rules.num("levelWeight", 1) + Rules.tierBonus(info.tier().name());
        if (info.slot().klass() != null) {
            s += info.base().averageDps() * Rules.num("weaponDpsWeight", 0.2);
        } else {
            s += info.base().health() * Rules.num("armourHealthWeight", 0.04);
        }
        for (var v : info.stats()) {
            double mid = (v.min() + v.max()) / 2.0;
            s += contribution(v.key(), mid,
                    weaponDps(info.slot(), info.base().averageDps()));
        }
        return s;
    }

    /**
     * What one rolled stat is worth. Raw spell damage is turned into the spell damage % it equals
     * first (raw / weapon base DPS, as Wynncraft adds raw after the spell multiplier), so +17 raw
     * on a 50 DPS relik counts like +34 %. Capped so one stat cannot win alone.
     */
    static double contribution(String api, double value, double dps) {
        double w = Rules.statWeight(api);
        if (w == 0) {
            return 0;
        }
        String lower = api.toLowerCase(Locale.ROOT);
        if (lower.startsWith("raw") && lower.contains("spelldamage") && dps > 0) {
            value = value * 100.0 / dps;
        }
        return w * Math.signum(value) * Math.min(3.0, 1.0 + Math.abs(value) / 10.0);
    }

    /** Base DPS spells scale from: the item's own if it is a weapon, else the one you fight with. */
    static double weaponDps(Slot type, double own) {
        if (type.klass() != null && own > 0) {
            return own;
        }
        if (GameState.current().inGame() && !FINDING_WEAPON.get()) {
            FINDING_WEAPON.set(true);           // bestWeapon scores weapons, which ask again
            try {
                double d = scoreDps(bestWeapon());
                if (d > 0) {
                    return d;
                }
            } finally {
                FINDING_WEAPON.set(false);
            }
        }
        return 50;
    }

    private static final ThreadLocal<Boolean> FINDING_WEAPON = ThreadLocal.withInitial(() -> false);

    private static double scoreDps(ItemStack weapon) {
        Optional<ItemView> p = weapon == null || weapon.isEmpty() ? Optional.empty() : GameState.current().view(weapon);
        if (p.isPresent() && p.get().isGear()) {
            return p.get().data().base().averageDps();
        }
        if (p.isPresent() && p.get().isCrafted()) {
            return p.get().crafted().dps();
        }
        return 0;
    }

    private static Klass classOf(ItemView gear) {
        Klass c = gear.data().slot().klass();
        if (c == null || c == Klass.NONE) {
            c = gear.data().requirements().klass().orElse(null);
        }
        return c == Klass.NONE ? null : c;
    }

    static String nice(Klass c) {
        if (c == null) {
            return "?";
        }
        String n = c.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
