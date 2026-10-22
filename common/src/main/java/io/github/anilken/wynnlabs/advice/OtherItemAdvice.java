package io.github.anilken.wynnlabs.advice;

import io.github.anilken.wynnlabs.build.Build;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.SkillKind;
import io.github.anilken.wynnlabs.quests.QuestBook;
import io.github.anilken.wynnlabs.quests.QuestGuides;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Verdicts for the small non-gear kinds: skill point buttons, powders, dungeon keys, and
 * ingredients and materials.
 */
final class OtherItemAdvice {

    private OtherItemAdvice() {}

    /** The Character Info buttons: how many points the best build wants in this skill. */
    static Advice.Entry skillButton(SkillKind skill) {
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

    /**
     * Powders of the build's elements go into gear. The others are never sold: another
     * character may use them, and a Powder Master turns four into one of the next tier
     * (the Powder Exchange changes the element).
     */
    static Advice.Entry powder(ItemView.Powder powder) {
        String el = powder.element();
        if (Rules.list("powders", "weapon").contains(el)) {
            return new Advice.Entry(Verdict.USE, L.t("wynnlabs.auto.powder_weapon", AutoAdvice.nice(el)));
        }
        if (Rules.list("powders", "armour").contains(el)) {
            return new Advice.Entry(Verdict.USE, L.t("wynnlabs.auto.powder_armour"));
        }
        String alt = powderUser(el);
        if (alt != null) {
            return new Advice.Entry(Verdict.KEEP, L.t("wynnlabs.auto.powder_alt", alt));
        }
        return new Advice.Entry(Verdict.KEEP, L.t("wynnlabs.auto.powder_unused", AutoAdvice.nice(el)));
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
                        cls = AltCharacters.genitive(today.get("class").getAsString());
                    } else if (r.has("goal")) {
                        String goal = r.get("goal").getAsString();
                        for (String c : List.of("MAGE", "ARCHER", "WARRIOR", "ASSASSIN", "SHAMAN")) {
                            if (goal.toUpperCase(Locale.ROOT).contains(c)) {
                                cls = AltCharacters.genitive(c);
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
     * Ingredients and materials: kept when an open quest asks for them or one of your
     * professions can use them, sold when the stack is worth the 30 EB Trade Market minimum,
     * and never "throw away" while the price is unknown (FİYATLA: hover it once at the market).
     */
    static Advice.Entry craftingItem(ItemView item, AutoAdvice.StackFacts stack) {
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
                    usable.add(AutoAdvice.nice(prof) + " Lv. " + lv);
                }
            }
        }
        Prices.Price p = state.price(name);
        Double each = p == null ? null : p.typical();
        double min = Rules.sub("trade", "minTradeMarketPrice", 1920);
        if (each != null && each * stack.count() >= min) {
            return AutoAdvice.market(new Advice.Entry(Verdict.SELL, L.t("wynnlabs.auto.craft_sell", Prices.fmt(each * stack.count()),
                    stack.count() > 1 ? L.t("wynnlabs.auto.craft_sell_all") : "",
                    usable.isEmpty() ? "" : L.t("wynnlabs.auto.craft_or_use", usable.get(0)))), SellAdvice.afterFee(each * stack.count()));
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
    static Advice.Entry dungeonKey(ItemView.DungeonKey key, int level, AutoAdvice.StackFacts stack) {
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
        String alt = AltCharacters.altOfLevel(null, lv, lv);
        if (alt != null) {
            return new Advice.Entry(Verdict.KEEP, L.t("wynnlabs.auto.dungeon_for_alt", what, lv, alt));
        }
        Prices.Price p = GameState.current().price(stack.name());
        if (p != null && p.typical() != null && p.typical() * stack.count() >= Rules.sub("trade", "minTradeMarketPrice", 1920)) {
            return AutoAdvice.market(new Advice.Entry(Verdict.SELL, L.t("wynnlabs.auto.dungeon_outlevelled_sell",
                    Prices.fmt(p.typical() * stack.count()))), SellAdvice.afterFee(p.typical() * stack.count()));
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
        List<String> pick = powderPicks(gear.data().slot().klass() != null, free);
        if (pick.isEmpty()) {
            return null;
        }
        return L.n("wynnlabs.auto.powder_hint", free, free, String.join(", ", pick));
    }

    /** The powders for {@code free} empty slots by rules.powders ("Water", "Thunder" …); empty when the rules name none. */
    static List<String> powderPicks(boolean weapon, int free) {
        List<String> order = Rules.list("powders", weapon ? "weapon" : "armour");
        List<String> pick = new ArrayList<>();
        for (int i = 0; i < free && !order.isEmpty(); i++) {
            pick.add(AutoAdvice.nice(order.get(i % order.size())));
        }
        return pick;
    }
}
