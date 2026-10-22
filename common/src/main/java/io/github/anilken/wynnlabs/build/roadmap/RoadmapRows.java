package io.github.anilken.wynnlabs.build.roadmap;

import io.github.anilken.wynnlabs.advice.AutoAdvice;
import io.github.anilken.wynnlabs.advice.Cautions;
import io.github.anilken.wynnlabs.build.Build;
import io.github.anilken.wynnlabs.build.Damage;
import io.github.anilken.wynnlabs.build.Exclusive;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap.Gear;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap.Option;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap.Step;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap.Versus;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.market.Shopping;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.SkillKind;
import io.github.anilken.wynnlabs.model.Slot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The road map's rows: a pick's comparison panel ({@link Option}), the rows a whole set puts in,
 * the powder rows of the first milestone, and the rows for what the build plan puts on from your
 * bag. Which build slot each row's pick holds is kept in a {@code placed} map (row name → build slot).
 */
final class RoadmapRows {

    private RoadmapRows() {}

    /** The comparison of one candidate against the build before it. */
    static Option option(ItemData info, String slot, Map<String, Damage.Piece> base, Build.Eval before, int m,
            int earned) {
        return option(info, OwnedGear.pieceOf(info), slot, base, before, m, earned);
    }

    /** {@link #option} with {@code info} judged as {@code piece} (a fresh copy of one you have, say). */
    static Option option(ItemData info, Damage.Piece piece, String slot, Map<String, Damage.Piece> base, Build.Eval before,
            int m, int earned) {
        Map<String, Damage.Piece> trial = new LinkedHashMap<>(base);
        trial.put(slot, piece);
        Build.Eval after = Build.allocate(trial, m, earned);
        if (after == null || before == null || before.result().objective() <= 0) {
            return null;
        }
        double gain = (after.result().objective() / before.result().objective() - 1) * 100;
        double[] a = before.result().factors();
        double[] b = after.result().factors();
        double[] pct = new double[Math.min(a.length, b.length)];
        for (int i = 0; i < pct.length; i++) {
            pct[i] = a[i] > 0 ? (b[i] / a[i] - 1) * 100 : 0;
        }
        List<String> sk = new ArrayList<>();
        boolean down = false;
        for (SkillKind s : SkillKind.values()) {
            int d = after.allocation()[s.ordinal()] - before.allocation()[s.ordinal()];
            if (d != 0) {
                sk.add(s.displayName().substring(0, 3) + " " + (d > 0 ? "+" : "−") + Math.abs(d));
                down |= d < 0;
            }
        }
        GameState.current().requestPrices(List.of(info.name()));
        String pr = Prices.buyText(info.name());
        String price = pr == null ? null : pr.contains(" (") ? pr.substring(0, pr.indexOf(" (")) : pr;
        String src = Shopping.sourceOf(info);
        return new Option(info, gain, price, src == null ? "?" : Shopping.where(src), Cautions.of(info),
                String.join(", ", sk), down, pct, before.result(), after.result());
    }

    /**
     * Drops the earlier picks the milestone's set no longer wears (a ring pick can take the hand
     * an earlier other-hand pick held), so a row never shows a piece that is not in the set.
     */
    static void keepWorn(Map<String, Gear> latest, Step s) {
        if (s.build().isEmpty()) {
            return;
        }
        java.util.Set<String> names = new java.util.HashSet<>();
        s.build().values().forEach(p -> {
            if (p != null) {
                names.add(p.name());
            }
        });
        latest.values().removeIf(g -> !names.contains(g.info().name()));
    }

    /** Records that the pick named {@code name} now holds build slot {@code slot}; an older pick there is gone. */
    static void place(Map<String, String> placed, String name, String slot) {
        placed.entrySet().removeIf(e -> e.getValue().equals(slot) && !e.getKey().equals(name));
        placed.put(name, slot);
    }

    /** Puts {@code pick} (as {@code piece}) into a build slot and makes the row it shows (the pick that held it, or a new one). */
    static void swapRow(Map<String, Damage.Piece> build, Map<String, String> placed, List<Gear> gear,
            Map<Slot, List<ItemData>> pools, String slot, Slot t, ItemData pick, Damage.Piece piece, Build.Eval after, double gain, int m, int earned) {
        Build.Eval before = Build.allocate(build, m, earned);
        String name = null;
        for (var pe : placed.entrySet()) {
            if (pe.getValue().equals(slot)) {
                name = pe.getKey();
            }
        }
        if (name == null) {
            name = t != Slot.RING ? t.name() : placed.containsKey(Slot.RING.name()) ? Roadmap.RING2 : Slot.RING.name();
        }
        String rowName = name;
        gear.removeIf(g -> g.slot().equals(rowName));
        Option first = before == null ? null : option(pick, piece, slot, build, before, m, earned);
        // the comparison panel: the pick, then the slot's best others with their prices, weaker
        // ones too, so a cheaper piece is one click away with what it costs you
        List<Option> options = new ArrayList<>();
        if (first != null) {
            options.add(first);
            List<ItemData> pool = t != null && t.isWeapon() ? SetJudge.weaponPool(pools) : pools.get(t);
            List<Option> others = new ArrayList<>();
            // the pool is ranked: its first eight are judged for the panel's four (all forty
            // took most of a map that the beam cache had otherwise made quick)
            int tried = 0;
            for (ItemData info : pool == null ? List.<ItemData>of() : pool) {
                if (tried >= 8) {
                    break;
                }
                if (info == pick || info.name().equals(pick.name()) || info.requirements().level() > m
                        || !CandidateRules.panelAllows(info, m) || Exclusive.clash(build, slot, Exclusive.group(info)) != null) {
                    continue;
                }
                tried++;
                Option op = option(info, slot, build, before, m, earned);
                if (op != null) {
                    others.add(op);
                }
            }
            others.sort((x, y) -> Double.compare(y.gain(), x.gain()));
            others.stream().limit((long) Rules.sub("roadmap", "alternatives", 4)).forEach(options::add);
        }
        String src = Shopping.sourceOf(pick);
        List<String> detail = new ArrayList<>();
        if (before != null && after != null) {
            detail.addAll(RoadmapGains.explain(before, after));
        }
        gear.add(new Gear(rowName, pick, gain, Shopping.where(src), Prices.buyText(pick.name()), null, null,
                detail, List.of(), options));
        build.put(slot, piece);
        place(placed, rowName, slot);
    }

    /** Puts a set the search chose into the milestone's build, a row for each slot it changes. */
    static void applySet(Map<String, Damage.Piece> chosen, Map<String, Damage.Piece> start,
            Map<String, Damage.Piece> build, Map<String, String> placed, Map<Slot, List<ItemData>> pools,
            List<Gear> gear, int m, int earned, int[] prev) {
        for (String slot : Roadmap.BEAM_ORDER) {
            Damage.Piece want = chosen.get(slot);
            Damage.Piece have = build.get(slot);
            if (want == null || have != null && have.key().equals(want.key())) {
                continue;
            }
            Damage.Piece had = start.get(slot);
            if (had != null && (had.key().equals(want.key()) || had.name().equalsIgnoreCase(want.name())
                    && want.stack() == null && !OwnedGear.buysOwned(want))) {
                // the set keeps what the milestone started with here (what you wear, or an
                // earlier pick): the slot-by-slot pick is undone and its row goes, it is no
                // "Yume → Yume" upgrade. By name too: a set seed puts a piece you have back
                // with its plain stats (one whose rolls are not known), and the one you have
                // keeps its rolls. A better roll you have, or a fresh copy to buy, is a change
                build.put(slot, had);
                String row = null;
                for (var pe : placed.entrySet()) {
                    if (pe.getValue().equals(slot)) {
                        row = pe.getKey();
                    }
                }
                String r = row;
                if (r != null) {
                    gear.removeIf(g -> g.slot().equals(r));
                }
                continue;
            }
            ItemData info = Roadmap.findGear(want.name());
            if (info == null) {
                continue;
            }
            Slot t = slot.startsWith("RING") ? Slot.RING : slot.equals("WEAPON") ? info.slot() : Slot.valueOf(slot);
            Build.Eval before = Build.allocate(build, m, earned);
            Build.Eval after = SetJudge.fullEval(build, slot, want, m, earned);
            double gain = before == null || after == null ? Double.NaN
                    : (SetJudge.judged(after, prev, m) / SetJudge.judged(before, prev, m) - 1) * 100;
            swapRow(build, placed, gear, pools, slot, t, info, want, after, Double.isNaN(gain) ? 0 : gain, m, earned);
        }
    }

    /** A row that powders a piece you have rather than naming a piece to get. */
    static boolean isPowder(Gear g) {
        return g != null && g.slot().startsWith("POWDER:");
    }

    /**
     * The first milestone's powder rows: for each piece of {@code raw} (the build you own, its
     * powders as they are) with empty slots, what filling them adds, when that is at least
     * rules.roadmap.powderRowMin (1 %). The milestones weigh every piece powdered, so these
     * rows are the only place powders show up as a gain. The row's slot is "POWDER:" and its
     * build slot ({@link #isPowder}); its source says which powders and what they cost.
     */
    static List<Gear> powderRows(Map<String, Damage.Piece> raw, int m, int earned) {
        List<Gear> out = new ArrayList<>();
        Build.Eval before = Build.allocate(raw, m, earned);
        if (before == null || before.result().objective() <= 0) {
            return out;
        }
        for (String slot : Build.SLOTS) {
            Damage.Piece p = raw.get(slot);
            Object[] add = Damage.addedPowders(p);
            ItemData info = add == null ? null : Roadmap.findGear(p.name());
            if (info == null) {
                continue;
            }
            Map<String, Damage.Piece> trial = new LinkedHashMap<>(raw);
            trial.put(slot, Damage.withAssumedPowders(p));
            Build.Eval after = Build.allocate(trial, m, earned);
            if (after == null) {
                continue;
            }
            double gain = (after.result().objective() / before.result().objective() - 1) * 100;
            if (gain < Rules.sub("roadmap", "powderRowMin", 1)) {
                continue;
            }
            out.add(new Gear("POWDER:" + slot, info, gain, powderText(add), null, null, null,
                    new ArrayList<>(RoadmapGains.explain(before, after)), List.of(), List.of()));
        }
        return out;
    }

    /** "Add 2× Thunder VI powder, about 12 EB" for {element, count, tier} ({@link Damage#addedPowders}). */
    private static String powderText(Object[] add) {
        String[] roman = {"I", "II", "III", "IV", "V", "VI", "VII"};
        int n = (int) add[1];
        int tier = (int) add[2];
        String cost = Prices.fmt(n * CandidateRules.powderPrice(tier));
        String tierName = roman[Math.max(0, Math.min(6, tier - 1))];
        String el = (String) add[0];
        if (el == null) {
            // armour: any element gives the same health; the rules may name the ones to use
            List<String> picks = AutoAdvice.powderPicks(false, n);
            if (picks.isEmpty()) {
                return L.n("wynnlabs.road.powder_row_armour", n, n, tierName, cost);
            }
            return L.t("wynnlabs.road.powder_row", n, String.join("/", new java.util.LinkedHashSet<>(picks)), tierName, cost);
        }
        String elName = switch (el) {
            case "thunder" -> L.t("wynnlabs.damage.el_thunder");
            case "water" -> L.t("wynnlabs.damage.el_water");
            case "fire" -> L.t("wynnlabs.damage.el_fire");
            case "air" -> L.t("wynnlabs.damage.el_air");
            default -> L.t("wynnlabs.damage.el_earth");
        };
        return L.t("wynnlabs.road.powder_row", n, elName, tierName, cost);
    }

    /**
     * What the build plan puts on from your bag right now, as rows by slot type name ("LEGGINGS",
     * "RING", {@link Roadmap#RING2}, "RELIK" …): a piece you took off or carry that the best build
     * you own wears, with the % your build gains with it over what you wear (the road map's rows
     * are picks over that build, so they never name it, and an empty slot looked fine). Follows
     * the plan, which is worked out again within seconds of a change in what you wear or carry, so
     * it needs no new road map. Made once per plan.
     */
    static Map<String, Gear> bagRows() {
        Build.Plan p = Build.plan();
        if (p == null || p.worn() == null) {
            return Map.of();
        }
        BagRows c = bagRows;
        if (c != null && c.plan() == p && c.lang().equals(L.code())) {
            return c.rows();
        }
        Map<String, String> placed = new LinkedHashMap<>();
        Map<String, ItemData> infos = new HashMap<>();
        p.pieces().forEach((slot, piece) -> {
            Damage.Piece was = p.worn().get(slot);
            if (piece == null || was != null && was.key().equals(piece.key())) {
                return;
            }
            ItemData info = Roadmap.findGear(piece.name());
            if (info == null) {
                return;
            }
            String row = slot.equals("RING1") ? Slot.RING.name() : slot.equals("RING2") ? Roadmap.RING2 : info.slot().name();
            placed.put(row, slot);
            infos.put(row, info);
        });
        Map<String, Gear> rows = new LinkedHashMap<>();
        if (!placed.isEmpty()) {
            Versus v = RoadmapGains.versus(p.pieces(), p.worn(), placed, p.level(), p.earned());
            String where = L.t("wynnlabs.shop.where_bag");
            placed.keySet().forEach(row -> rows.put(row, new Gear(row, infos.get(row), v.gain().getOrDefault(row, Double.NaN),
                    where, null, null, null, v.why().getOrDefault(row, List.of()), List.of(), List.of())));
        }
        bagRows = new BagRows(p, L.code(), java.util.Collections.unmodifiableMap(rows));
        return bagRows.rows();
    }

    /** A row of {@link #bagRows}: its gain is against what you wear, not a milestone's. */
    static boolean fromBag(Gear g) {
        BagRows c = bagRows;
        return c != null && g != null && c.rows().get(g.slot()) == g;
    }

    private record BagRows(Build.Plan plan, String lang, Map<String, Gear> rows) {}

    /** The bag rows made last, for the plan and language they were made in; no initializer (a hot swap leaves none). */
    private static volatile BagRows bagRows;
}
