package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.StatRange;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The downsides of a piece, said before you buy it: slow attack speed, and every stat it takes
 * away (walk speed, skill points, defences, regen; spell cost going up). Shown as a red
 * "Dikkat:" line on the road map and on shop tooltips.
 */
final class Cautions {

    /** Stat API name → the language key of its short name. */
    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("walkSpeed", "wynnlabs.caution.walk_speed"),
            Map.entry("rawStrength", "wynnlabs.caution.strength"),
            Map.entry("rawDexterity", "wynnlabs.caution.dexterity"),
            Map.entry("rawIntelligence", "wynnlabs.caution.intelligence"),
            Map.entry("rawDefence", "wynnlabs.caution.defence"),
            Map.entry("rawAgility", "wynnlabs.caution.agility"),
            Map.entry("earthDefence", "wynnlabs.caution.earth_defence"),
            Map.entry("thunderDefence", "wynnlabs.caution.thunder_defence"),
            Map.entry("waterDefence", "wynnlabs.caution.water_defence"),
            Map.entry("fireDefence", "wynnlabs.caution.fire_defence"),
            Map.entry("airDefence", "wynnlabs.caution.air_defence"),
            Map.entry("healthRegen", "wynnlabs.caution.health_regen"),
            Map.entry("healthRegenRaw", "wynnlabs.caution.health_regen"),
            Map.entry("manaRegen", "wynnlabs.caution.mana_regen"),
            Map.entry("rawHealth", "wynnlabs.caution.health"),
            Map.entry("lifeSteal", "wynnlabs.caution.life_steal"),
            Map.entry("manaSteal", "wynnlabs.caution.mana_steal"),
            Map.entry("spellDamage", "wynnlabs.caution.spell_damage"),
            Map.entry("rawSpellDamage", "wynnlabs.caution.spell_damage"),
            Map.entry("damage", "wynnlabs.caution.damage"),
            Map.entry("combatExperience", "wynnlabs.caution.combat_xp"));

    private Cautions() {}

    static List<String> of(ItemData info) {
        List<String> out = new ArrayList<>();
        if (info == null) {
            return out;
        }
        // a quest it asks for that the quest book says is not done (Royal amulets: WynnExcavation Site D)
        QuestBook.Entry quest = Shopping.questPending(GameState.current(), info);
        if (quest != null) {
            out.add(L.t("wynnlabs.caution.quest_first", quest.name()));
        }
        info.base().attackSpeed().ifPresent(a -> {
            switch (a) {
                case "SUPER_SLOW" -> out.add(L.t("wynnlabs.caution.attack_super_slow"));
                case "VERY_SLOW" -> out.add(L.t("wynnlabs.caution.attack_very_slow"));
                case "SLOW" -> out.add(L.t("wynnlabs.caution.attack_slow"));
                default -> {
                }
            }
        });
        for (StatRange v : info.stats()) {
            double mid = (v.min() + v.max()) / 2.0;
            // spell costs: lower is better, so −20 % is a plus, not a caution
            boolean inverted = v.inverted()
                    || v.key().toLowerCase(Locale.ROOT).contains("spellcost");
            if (mid == 0 || (mid > 0) != inverted) {
                continue;
            }
            String api = v.key();
            String nameKey = NAMES.get(api);
            String name = nameKey == null ? v.name() : L.t(nameKey);
            boolean percent = v.unit().equals("PERCENT");
            long n = Math.round(mid);
            String sign = n > 0 ? "+" : "−";
            out.add(percent ? L.t("wynnlabs.caution.stat_percent", name, sign, Math.abs(n))
                    : name + " " + sign + Math.abs(n));
        }
        for (var d : info.base().defences()) {
            if (d.value() < 0) {
                String el = d.element().toLowerCase(Locale.ROOT);
                String nameKey = NAMES.get(el + "Defence");
                out.add((nameKey == null ? d.element() : L.t(nameKey)) + " " + d.value());
            }
        }
        return out;
    }

    /** "Dikkat: Çok yavaş saldırı · Yürüme −%16 · Çeviklik −10", or null if nothing. */
    static String line(ItemData info) {
        List<String> c = of(info);
        return c.isEmpty() ? null : L.t("wynnlabs.caution.line", String.join(" · ", c));
    }
}
