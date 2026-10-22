package io.github.anilken.wynnlabs.build.roadmap;

import io.github.anilken.wynnlabs.build.Build;
import io.github.anilken.wynnlabs.build.Damage;
import io.github.anilken.wynnlabs.build.Exclusive;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap.Gear;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap.Step;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap.Versus;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.model.SkillKind;
import io.github.anilken.wynnlabs.model.Slot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The numbers and reasons a road map row shows: what a pick adds to its milestone's set, what it
 * gains over what you wear, which of the two a row's pill shows, and the reason lines.
 */
final class RoadmapGains {

    private RoadmapGains() {}

    /**
     * For each slot changed by this milestone ({@code placed}: slot type name → the build slot
     * of its latest pick), the % your build gains with the pick instead of what you wear now,
     * at the milestone's level and skill points. Two ways, the larger one shown:
     * <ul>
     * <li>that piece alone in what you wear now: a better piece always shows a bigger number,
     *     also across milestones (inside the milestone's full build the other upgrades shrink
     *     one slot's share: a Lv 93 Mythic at Lv 120 showed less than a Lv 88 Rare at Lv 100);</li>
     * <li>with the milestone's other upgrades: some picks only pay off together (another pick
     *     carries their skill points), alone they can even lose. The reasons then start with a
     *     line that says so.</li>
     * </ul>
     * With the reason lines of each slot ({@link #why}).
     */
    static Versus versus(Map<String, Damage.Piece> build, Map<String, Damage.Piece> worn,
            Map<String, String> placed, int m, int earned) {
        Map<String, Double> out = new LinkedHashMap<>();
        Map<String, List<String>> why = new LinkedHashMap<>();
        Build.Eval now = Build.allocate(worn, m, earned);
        Build.Eval full = Build.allocate(build, m, earned);
        for (var e : placed.entrySet()) {
            String slot = e.getValue();
            Damage.Piece was = worn.get(slot);
            Damage.Piece pick = build.get(slot);
            if (pick == null || was != null && was.key().equals(pick.key())) {
                continue;
            }
            double alone = Double.NaN;
            Build.Eval with = null;
            if (now != null && now.result().objective() > 0) {
                Map<String, Damage.Piece> one = new LinkedHashMap<>(worn);
                one.put(slot, pick);
                with = Build.allocate(one, m, earned);
                if (with != null && with.result().objective() > 0) {
                    alone = (with.result().objective() / now.result().objective() - 1) * 100;
                }
            }
            double together = Double.NaN;
            Build.Eval back = null;
            if (full != null && full.result().objective() > 0) {
                Map<String, Damage.Piece> rest = new LinkedHashMap<>(build);
                rest.put(slot, was);
                back = Build.allocate(rest, m, earned);
                if (back == null && was != null) {
                    // what you wear there no longer fits the set (its skill points, two Black
                    // Catalysts at Lv 90): the set without anything there, or alone the pick
                    // looked like a loss
                    rest.remove(slot);
                    back = Build.allocate(rest, m, earned);
                }
                if (back != null && back.result().objective() > 0) {
                    together = (full.result().objective() / back.result().objective() - 1) * 100;
                }
            }
            if (!Double.isNaN(alone) && (Double.isNaN(together) || alone >= together)) {
                out.put(e.getKey(), alone);
                why.put(e.getKey(), why(now, with, m));
            } else if (!Double.isNaN(together)) {
                out.put(e.getKey(), together);
                List<String> lines = new ArrayList<>();
                lines.add(L.t("wynnlabs.road.why_together", m));
                lines.addAll(why(back, full, m));
                why.put(e.getKey(), new ArrayList<>(lines.subList(0, Math.min(lines.size(), Roadmap.WHY_LINES))));
            }
        }
        return new Versus(out, why);
    }

    /**
     * Each row's step as what its pick adds to the milestone's finished {@code set}: the set
     * judged with it and with the slot as the milestone started ({@code start}: the last
     * milestone's piece, or empty), not as the set stood when the pick went in. Picks that pay off
     * together (a set's pieces, one carrying another's skill points) showed −17 % one after
     * another while the whole set was well ahead; a pick that really costs the set something still
     * shows it. A row whose slot cannot go back alone (the set no longer fits, empty or not) goes
     * back with one other change of the milestone ({@link #withPartner}); one that cannot even so
     * keeps its number, a target's (NaN) too. Runs on the finished set, after the search's.
     */
    static void stepsInSet(List<Gear> gear, Map<String, Damage.Piece> set, Map<String, Damage.Piece> start,
            Map<String, String> placed, int m, int earned, int[] prev) {
        Build.Eval full = Build.allocate(set, m, earned);
        if (full == null) {
            return;
        }
        double fullJ = SetJudge.judged(full, prev, m);
        for (int i = 0; i < gear.size(); i++) {
            Gear g = gear.get(i);
            String slot = placed.get(g.slot());
            Damage.Piece pick = slot == null ? null : set.get(slot);
            if (Double.isNaN(g.gain()) || pick == null || !pick.name().equalsIgnoreCase(g.info().name())) {
                continue;
            }
            Damage.Piece was = start.get(slot);
            if (was != null && was.key().equals(pick.key())) {
                continue;
            }
            double without = withSlot(set, slot, was, m, earned, prev);
            if (Double.isNaN(without) && was != null) {
                without = withSlot(set, slot, null, m, earned, prev);
            }
            if (Double.isNaN(without)) {
                // the pick carries what another of the milestone's changes needs (a new weapon's
                // skill points): it goes back together with that one, and the row shows what the
                // two add. Left at the slot-by-slot number, The Modulator read −21 % at Lv 90
                without = withPartner(set, start, slot, was, m, earned, prev);
            }
            if (Double.isNaN(without) || without <= 0) {
                continue;
            }
            gear.set(i, new Gear(g.slot(), g.info(), (fullJ / without - 1) * 100, g.source(), g.price(), g.alt(),
                    g.altSource(), g.detail(), g.alternatives(), g.options()));
        }
    }

    /** {@code set} judged with {@code p} in {@code slot} (null: empty); NaN when that cannot be worn. */
    private static double withSlot(Map<String, Damage.Piece> set, String slot, Damage.Piece p, int m, int earned,
            int[] prev) {
        Map<String, Damage.Piece> trial = new LinkedHashMap<>(set);
        trial.remove(slot);
        if (p != null) {
            if (Exclusive.clash(trial, slot, Exclusive.group(p)) != null) {
                return Double.NaN;
            }
            trial.put(slot, p);
        }
        Build.Eval ev = Build.allocate(trial, m, earned);
        return ev == null ? Double.NaN : SetJudge.judged(ev, prev, m);
    }

    /**
     * {@code set} with {@code slot} and one other slot the milestone changed both as they were
     * when it started ({@code start}; empty where nothing was): the best such that can be worn, so
     * the row claims the least it can. Never a slot left empty that held something: a set without
     * its leggings says nothing about the leggings (Omniverse read +617 % that way). NaN when none
     * can be worn.
     */
    private static double withPartner(Map<String, Damage.Piece> set, Map<String, Damage.Piece> start, String slot,
            Damage.Piece was, int m, int earned, int[] prev) {
        double best = Double.NaN;
        for (String other : set.keySet()) {
            Damage.Piece now = set.get(other);
            Damage.Piece then = start.get(other);
            if (other.equals(slot) || now == null || then != null && then.key().equals(now.key())) {
                continue;
            }
            Map<String, Damage.Piece> trial = new LinkedHashMap<>(set);
            trial.remove(slot);
            trial.remove(other);
            if (was != null) {
                if (Exclusive.clash(trial, slot, Exclusive.group(was)) != null) {
                    continue;
                }
                trial.put(slot, was);
            }
            if (then != null) {
                if (Exclusive.clash(trial, other, Exclusive.group(then)) != null) {
                    continue;
                }
                trial.put(other, then);
            }
            Build.Eval ev = Build.allocate(trial, m, earned);
            if (ev != null) {
                double j = SetJudge.judged(ev, prev, m);
                if (Double.isNaN(best) || j > best) {
                    best = j;
                }
            }
        }
        return best;
    }

    /**
     * The number a row's pill shows on milestone {@code selected}: the gain over what you wear in
     * that slot ({@link Step#vsWorn}), unless {@link #showsStep}: then the step over the milestone
     * before ({@link Gear#gain}). A piece from the bag shows its own number. NaN: nothing to
     * compare with (a new slot, a target).
     */
    static double shownGain(List<Step> steps, int selected, Gear u) {
        if (u == null || Double.isNaN(u.gain()) || steps.isEmpty() || RoadmapRows.fromBag(u)
                || showsStep(steps, selected, u)) {
            return u == null ? Double.NaN : u.gain();
        }
        Step s = steps.get(Math.max(0, Math.min(selected, steps.size() - 1)));
        return s.vsWorn().getOrDefault(u.slot(), u.gain());
    }

    /**
     * Whether a row shows its step rather than its gain over what you wear: nothing is worn in
     * that slot, or what is worn there is far below the milestone that picked the piece
     * (rules.shopping.oldGearLevels + 4 levels or more). "+64092 %" over a Lv 1 wand says nothing
     * about a Lv 120 relik; what it adds over the last milestone's pick does.
     */
    static boolean showsStep(List<Step> steps, int selected, Gear u) {
        if (u == null || steps.isEmpty() || RoadmapRows.fromBag(u)) {
            return false;
        }
        Step s = steps.get(Math.max(0, Math.min(selected, steps.size() - 1)));
        int at = s.level();
        for (Step x : steps) {
            if (x.gear().stream().anyMatch(g -> g == u)) {
                at = x.level();                         // the milestone that picked it
                break;
            }
        }
        Build.Plan plan = Build.plan();
        if (plan == null) {
            return false;
        }
        Map<String, Damage.Piece> worn = plan.worn() != null ? plan.worn() : plan.pieces();
        // the build slot the piece is in (a ring pick may hold the other hand)
        String slot = u.slot().equals(Roadmap.RING2) ? "RING2" : u.info().slot() == Slot.RING ? "RING1"
                : u.info().slot().isWeapon() ? "WEAPON" : u.slot();
        Damage.Piece here = s.build().get(slot);
        if (here == null || !here.name().equalsIgnoreCase(u.info().name())) {
            for (var e : s.build().entrySet()) {
                if (e.getValue() != null && e.getValue().name().equalsIgnoreCase(u.info().name())) {
                    slot = e.getKey();
                    break;
                }
            }
        }
        Damage.Piece was = worn.get(slot);
        return was == null || was.level() <= at - (int) Rules.sub("shopping", "oldGearLevels", 8) - 4;
    }

    /** A community target (rules.roadmap.targets): its row has no gain, it is there for the set. */
    static boolean isTarget(Gear g) {
        return g != null && Rules.list("roadmap", "targets").stream().anyMatch(n -> n.equalsIgnoreCase(g.info().name()));
    }

    /**
     * A row's pill: "+12%", "−3%", past 999 "&gt;999%" (a number that large only says "much"), with
     * no number "New" (nothing to compare with), or "Set" for a community target.
     */
    static String pill(double gain, Gear g) {
        if (Double.isNaN(gain)) {
            return L.t(isTarget(g) ? "wynnlabs.screen.pill_set" : "wynnlabs.screen.pill_new");
        }
        if (gain > 999) {
            return ">" + L.t("wynnlabs.screen.pct", 999);
        }
        return (gain < -0.5 ? "−" : "+") + L.t("wynnlabs.screen.pct", Math.round(Math.abs(gain)));
    }

    /**
     * Why build {@code b} beats {@code a}, biggest change first, at most {@link Roadmap#WHY_LINES}
     * lines in the game's language (the {@link #explain} lines plus attack speed): spell damage,
     * main attack, effective health, casts, walk speed, XP, attack speed; a change of skill
     * points (or a respec) always gets the last line when there is one. No XP line at the level
     * cap, where the model gives XP no weight.
     */
    static List<String> why(Build.Eval a, Build.Eval b, int level) {
        Damage.Result x = a.result();
        Damage.Result y = b.result();
        List<Object[]> lines = new ArrayList<>();      // {weight, text}
        rel(lines, L.t("wynnlabs.road.spell_damage"), x.spellDps(), y.spellDps());
        rel(lines, L.t("wynnlabs.road.main_attack"), x.mainDps(), y.mainDps());
        rel(lines, L.t("wynnlabs.road.effective_health"), x.ehp(), y.ehp());
        if (Math.abs(y.casts() - x.casts()) >= 0.05 && x.casts() > 0) {
            lines.add(new Object[] {Math.abs(y.casts() / x.casts() - 1), L.t("wynnlabs.road.casts",
                    String.format(java.util.Locale.ROOT, "%.1f", x.casts()),
                    String.format(java.util.Locale.ROOT, "%.1f", y.casts()))});
        }
        if (Math.round(y.speed()) != Math.round(x.speed())) {
            lines.add(new Object[] {Math.abs(y.speed() - x.speed()) / 100.0 * Rules.sub("model", "speedWeight", 0.3),
                    L.t("wynnlabs.road.walk_speed", signed(x.speed()), signed(y.speed()))});
        }
        if (Math.round(y.xp()) != Math.round(x.xp()) && level < Rules.sub("model", "maxLevel", 120)) {
            lines.add(new Object[] {Math.abs(y.xp() - x.xp()) / 100.0 * Rules.sub("model", "xpWeight", 1.0),
                    L.t("wynnlabs.road.xp_bonus", Math.round(x.xp()), Math.round(y.xp()))});
        }
        if (x.factors().length > 5 && y.factors().length > 5) {
            rel(lines, L.t("wynnlabs.damage.factor_attack_speed"), x.factors()[5] * 100, y.factors()[5] * 100);
        }
        lines.sort((p, q) -> Double.compare((double) q[0], (double) p[0]));
        List<String> skills = new ArrayList<>();
        boolean down = false;
        for (SkillKind s : SkillKind.values()) {
            int i = s.ordinal();
            if (a.allocation()[i] != b.allocation()[i]) {
                skills.add(s.displayName().substring(0, 3) + " " + a.allocation()[i] + "→" + b.allocation()[i]);
                down |= b.allocation()[i] < a.allocation()[i];
            }
        }
        List<String> out = new ArrayList<>();
        int room = skills.isEmpty() ? Roadmap.WHY_LINES : Roadmap.WHY_LINES - 1;
        for (int i = 0; i < lines.size() && out.size() < room; i++) {
            out.add((String) lines.get(i)[1]);
        }
        if (!skills.isEmpty()) {
            out.add(down ? L.t("wynnlabs.road.skills_respec", String.join(", ", skills))
                    : L.t("wynnlabs.road.skills", String.join(", ", skills)));
        }
        return out;
    }

    /** A "name: from → to (±n %)" line weighted by its relative change; none under 2 %. */
    private static void rel(List<Object[]> lines, String name, double from, double to) {
        if (from <= 0 || Math.abs(to / from - 1) < 0.02) {
            return;
        }
        List<String> one = new ArrayList<>();
        line(one, name, from, to, "", true);
        lines.add(new Object[] {Math.abs(to / from - 1), one.get(0)});
    }

    /** What changes in the build with the pick in: spells, health, casting, speed, skill points. */
    static List<String> explain(Build.Eval a, Build.Eval b) {
        List<String> out = new ArrayList<>();
        Damage.Result x = a.result();
        Damage.Result y = b.result();
        line(out, L.t("wynnlabs.road.spell_damage"), x.spellDps(), y.spellDps(), "", true);
        line(out, L.t("wynnlabs.road.main_attack"), x.mainDps(), y.mainDps(), "", true);
        line(out, L.t("wynnlabs.road.effective_health"), x.ehp(), y.ehp(), "", true);
        if (Math.abs(y.casts() - x.casts()) >= 0.05) {
            out.add(L.t("wynnlabs.road.casts", String.format(java.util.Locale.ROOT, "%.1f", x.casts()),
                    String.format(java.util.Locale.ROOT, "%.1f", y.casts())));
        }
        if (Math.round(y.speed()) != Math.round(x.speed())) {
            out.add(L.t("wynnlabs.road.walk_speed", signed(x.speed()), signed(y.speed())));
        }
        if (Math.round(y.xp()) != Math.round(x.xp())) {
            out.add(L.t("wynnlabs.road.xp_bonus", Math.round(x.xp()), Math.round(y.xp())));
        }
        List<String> sk = new ArrayList<>();
        boolean down = false;
        for (SkillKind s : SkillKind.values()) {
            int i = s.ordinal();
            if (a.allocation()[i] != b.allocation()[i]) {
                sk.add(s.displayName().substring(0, 3) + " " + a.allocation()[i] + "→" + b.allocation()[i]);
                down |= b.allocation()[i] < a.allocation()[i];
            }
        }
        if (!sk.isEmpty()) {
            out.add(down ? L.t("wynnlabs.road.skills_respec", String.join(", ", sk))
                    : L.t("wynnlabs.road.skills", String.join(", ", sk)));
        }
        return out;
    }

    private static void line(List<String> out, String name, double from, double to, String unit, boolean pct) {
        if (from <= 0 || Math.abs(to / from - 1) < 0.02) {
            return;                             // under 2 %: not worth a line
        }
        String change = L.t("wynnlabs.road.change", name, Math.round(from), Math.round(to));
        out.add(pct ? change + L.t("wynnlabs.road.change_pct", to >= from ? "+" : "−",
                Math.round(Math.abs(to / from - 1) * 100)) : change);
    }

    private static String signed(double v) {
        long n = Math.round(v);
        return n > 0 ? "+" + n : String.valueOf(n);
    }
}
