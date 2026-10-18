package io.github.anilken.wynnlabs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.model.SkillKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Damage tab and GET /damage: what the model says the worn build deals against what the
 * damage meter measured, where the DPS comes from, and what one more step of each stat would
 * add. The model part is worked out once per build plan (and rules change), not every frame.
 */
final class DamageReport {

    /** One "what if": the change in English (for the API), its language key, the DPS it gives. */
    record WhatIf(String change, String key, double dps, double gain, double percent, double casts) {}

    record Report(Damage.Detail detail, List<WhatIf> whatIf, int freePoints) {}

    /** The skills' what-if labels, by SkillKind.ordinal(). */
    private static final String[] SKILL_KEYS = {"wynnlabs.damage.if_strength", "wynnlabs.damage.if_dexterity",
        "wynnlabs.damage.if_intelligence", "wynnlabs.damage.if_defence", "wynnlabs.damage.if_agility"};
    /** The elements' what-if labels, in Damage.ELEMENTS order. */
    private static final String[] ELEMENT_IF_KEYS = {"wynnlabs.damage.if_neutral", "wynnlabs.damage.if_earth",
        "wynnlabs.damage.if_thunder", "wynnlabs.damage.if_water", "wynnlabs.damage.if_fire", "wynnlabs.damage.if_air"};
    /** The elements' names, in Damage.ELEMENTS order. */
    static final String[] ELEMENT_KEYS = {"wynnlabs.damage.el_neutral", "wynnlabs.damage.el_earth",
        "wynnlabs.damage.el_thunder", "wynnlabs.damage.el_water", "wynnlabs.damage.el_fire", "wynnlabs.damage.el_air"};

    /** Below this many measured casts the average is rough and Coach does not calibrate yet. */
    static final int ENOUGH_CASTS = 30;

    private static Build.Plan cachedFor;
    private static int cachedVersion = -1;
    private static Report cached;

    private DamageReport() {}

    /** The report for the plan in use, or null before there is one. */
    static synchronized Report get() {
        Build.Plan p = Build.plan();
        if (p == null || p.current() == null) {
            return null;
        }
        if (p != cachedFor || Rules.version != cachedVersion) {
            cached = compute(p);
            cachedFor = p;
            cachedVersion = Rules.version;
        }
        return cached;
    }

    static Report compute(Build.Plan p) {
        Damage.Detail base = p.currentDetail(null, null);
        double now = base.breakdown().total();
        List<WhatIf> out = new ArrayList<>();
        // +10 points in a skill: the model's marginal value, whether or not points are free
        for (SkillKind sk : SkillKind.values()) {
            int[] a = p.wornAllocation().clone();
            int i = sk.ordinal();
            if (a[i] >= 100) {
                continue;                       // the game stops assigning at 100
            }
            a[i] = Math.min(100, a[i] + 10);
            out.add(whatIf("+10 " + sk.displayName(), SKILL_KEYS[i], p.currentDetail(a, null), now));
        }
        out.add(whatIf("+10% spell damage", "wynnlabs.damage.if_spell",
                p.currentDetail(null, extra(Map.of("spellDamage", 10.0))), now));
        out.add(whatIf("+10% main attack damage", "wynnlabs.damage.if_main",
                p.currentDetail(null, extra(Map.of("mainAttackDamage", 10.0))), now));
        for (int i = 0; i < Damage.ELEMENTS.length; i++) {
            String el = Damage.ELEMENTS[i];
            out.add(whatIf("+10% " + el + " damage", ELEMENT_IF_KEYS[i],
                    p.currentDetail(null, extra(Map.of(el + "Damage", 10.0))), now));
        }
        out.add(whatIf("+1 mana regen", "wynnlabs.damage.if_mana",
                p.currentDetail(null, extra(Map.of("manaRegen", 1.0))), now));
        // −10 % on every spell's cost, as the game's "% Nth Spell Cost" IDs do it
        Map<String, Double> cost = new HashMap<>();
        for (String nth : new String[] {"1st", "2nd", "3rd", "4th"}) {
            cost.put(nth + "SpellCost", -10.0);
        }
        out.add(whatIf("-10% spell cost", "wynnlabs.damage.if_cost", p.currentDetail(null, extra(cost)), now));
        out.sort(Comparator.comparingDouble(WhatIf::gain).reversed());
        int used = 0;
        for (int v : p.wornAllocation()) {
            used += v;
        }
        return new Report(base, List.copyOf(out), Math.max(0, p.earned() - used));
    }

    private static WhatIf whatIf(String change, String key, Damage.Detail d, double now) {
        double dps = d.breakdown().total();
        return new WhatIf(change, key, dps, dps - now, now > 0 ? (dps / now - 1) * 100 : 0, d.result().casts());
    }

    /** A make-believe piece that only carries these stats, added to what you wear. */
    private static Damage.Piece extra(Map<String, Double> stats) {
        return new Damage.Piece(null, "what-if", "what-if", null, 0, new HashMap<>(stats), Map.of(), 0,
                Map.of("neutral", 1.0), 0);
    }

    // ---- computed vs measured ----

    /** The model's damage per cast for what you wear, with the calibration applied. */
    static double modelPerCast() {
        Build.Plan p = Build.plan();
        return p == null || p.current() == null ? 0 : p.current().dmgPerCast();
    }

    static double calibration() {
        return Rules.sub("model", "spellCalibration", 1.0);
    }

    static int measuredCasts() {
        JsonObject m = Coach.measured();
        return m.has("casts") ? m.get("casts").getAsInt() : 0;
    }

    static double measuredPerCast() {
        JsonObject m = Coach.measured();
        return m.has("avgPerCast") ? m.get("avgPerCast").getAsDouble() : 0;
    }

    // ---- API ----

    private static double r1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    static JsonObject json() {
        JsonObject o = new JsonObject();
        Report r = get();
        JsonObject cmp = new JsonObject();
        double model = modelPerCast();
        int casts = measuredCasts();
        double measured = measuredPerCast();
        cmp.addProperty("modelPerCast", Math.round(model));
        cmp.addProperty("spellCalibration", calibration());
        cmp.addProperty("modelPerCastUncalibrated", calibration() > 0 ? Math.round(model / calibration()) : 0);
        cmp.addProperty("measuredCasts", casts);
        cmp.addProperty("measuredPerCast", Math.round(measured));
        if (model > 0 && casts > 0) {
            cmp.addProperty("measuredToModel", Math.round(measured / model * 100) / 100.0);
        }
        cmp.addProperty("enoughCasts", casts >= ENOUGH_CASTS);
        cmp.addProperty("note", "measured = damage numbers within 1.5 s after each cast (running mean of the last ~200);"
                + " after " + ENOUGH_CASTS + " casts the model's spellCalibration follows it, kept within 0.7-1.4");
        o.add("computedVsMeasured", cmp);
        if (r == null) {
            o.addProperty("note", "no build plan yet (needs a character in game)");
        } else {
            Damage.Breakdown b = r.detail().breakdown();
            JsonObject bd = new JsonObject();
            bd.addProperty("spellDps", r1(b.spellTotal()));
            bd.addProperty("mainAttackDps", r1(b.mainTotal()));
            bd.addProperty("summonDps", r1(b.summonTotal()));
            bd.addProperty("totalDps", r1(b.total()));
            bd.addProperty("mainAttackWeight", Rules.sub("model", "mainAttackWeight", 0.5));
            JsonObject els = new JsonObject();
            for (int i = 0; i < Damage.ELEMENTS.length; i++) {
                JsonObject e = new JsonObject();
                e.addProperty("spell", r1(b.spell()[i]));
                e.addProperty("mainAttack", r1(b.main()[i]));
                e.addProperty("summons", r1(b.summon()[i]));
                e.addProperty("total", r1(b.element(i)));
                els.add(Damage.ELEMENTS[i], e);
            }
            bd.add("byElement", els);
            bd.addProperty("note", "DPS of what you wear by the model: spells + main attack + summons."
                    + " The build score counts the main attack times mainAttackWeight.");
            o.add("breakdown", bd);
            JsonArray wi = new JsonArray();
            for (WhatIf w : r.whatIf()) {
                JsonObject j = new JsonObject();
                j.addProperty("change", w.change());
                j.addProperty("dps", r1(w.dps()));
                j.addProperty("gain", r1(w.gain()));
                j.addProperty("gainPercent", r1(w.percent()));
                j.addProperty("castsPer5s", Math.round(w.casts() * 100) / 100.0);
                wi.add(j);
            }
            o.add("whatIf", wi);
            o.addProperty("freeSkillPoints", r.freePoints());
        }
        o.add("live", DpsMeter.json(DpsMeter.LIVE.state()));
        return o;
    }
}
