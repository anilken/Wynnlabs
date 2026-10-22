package io.github.anilken.wynnlabs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.SetData;
import io.github.anilken.wynnlabs.model.SkillKind;
import io.github.anilken.wynnlabs.model.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The road ahead, level by level: at each milestone the gear that becomes the best for its slot
 * (with price and where it comes from), the ability tree nodes planned for those levels and
 * places to level. Built from Wynntils' item and map data, the rules and the saved ability
 * plan; no network apart from the price lookups Prices already does.
 */
final class Roadmap {

    /** {@code alt}: something better you cannot buy (quest reward, untradable), or null. */
    record Gear(String slot, ItemData info, double gain, String source, String price, ItemData alt, String altSource,
            List<String> detail, List<String> alternatives, List<Option> options) {}

    /**
     * One choice for a slot, for the comparison panel: what it costs, what it takes away, and
     * the gain split into the parts the build score multiplies (Damage.FACTORS).
     */
    record Option(ItemData info, double gain, String price, String source, List<String> cautions, String skills,
            boolean respec, double[] factorPct, Damage.Result from, Damage.Result to) {}

    /** The comparison of one candidate against the build before it. */
    static Option option(ItemData info, String slot, Map<String, Damage.Piece> base, Build.Eval before, int m,
            int earned) {
        Map<String, Damage.Piece> trial = new LinkedHashMap<>(base);
        trial.put(slot, Damage.piece(info));
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
     * {@code vsWorn}: slot type name ("HELMET", "RING", "RELIK" …) → the % the build of this
     * milestone (every pick up to its level, judged at its level and skill points) loses when
     * that slot goes back to what you wear now, for every slot changed by then. This is the
     * number for a "worn → pick" row; {@link Gear#gain} is only the step over the build just
     * before the pick (the last milestone's pick, the slots picked before it at this level).
     * A piece keeps the number of the milestone that first picked it, so the same piece shows
     * the same number on every tab. {@code set}: the milestone's whole set ({@code build}) at its
     * level, with its skill points; null when it does not fit or there is no build plan.
     */
    record Step(int level, List<Gear> gear, List<String> abilities, List<String> places, int skillPoints,
            Map<String, Double> vsWorn, Map<String, List<String>> whyVsWorn, Map<String, Damage.Piece> build,
            Build.Eval set) {

        Step(int level, List<Gear> gear, List<String> abilities, List<String> places, int skillPoints) {
            this(level, gear, abilities, places, skillPoints, Map.of(), Map.of(), Map.of(), null);
        }

        Step(int level, List<Gear> gear, List<String> abilities, List<String> places, int skillPoints,
                Map<String, Double> vsWorn) {
            this(level, gear, abilities, places, skillPoints, vsWorn, Map.of(), Map.of(), null);
        }
    }

    /**
     * {@link Step#vsWorn} and {@link Step#whyVsWorn} of one milestone: per slot type name the %
     * and the reason lines ({@link #why}), from the same two builds.
     */
    record Versus(Map<String, Double> gain, Map<String, List<String>> why) {}

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
     */
    static Map<String, Double> vsWorn(Map<String, Damage.Piece> build, Map<String, Damage.Piece> worn,
            Map<String, String> placed, int m, int earned) {
        return versus(build, worn, placed, m, earned).gain();
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

    /** The road map's name for a pick in the second ring slot ({@link Gear#slot}); the first ring is "RING". */
    static final String RING2 = "RING2";

    /** Records that the pick named {@code name} now holds build slot {@code slot}; an older pick there is gone. */
    private static void place(Map<String, String> placed, String name, String slot) {
        placed.entrySet().removeIf(e -> e.getValue().equals(slot) && !e.getKey().equals(name));
        placed.put(name, slot);
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
            String row = k == Slot.RING ? (rings == 1 || ringRow++ > 0 ? RING2 : Slot.RING.name()) : info.slot().name();
            if (baseline != null) {
                baseline.put(slot, Damage.piece(info));
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
            TriConsumer tc = trace;
            if (tc != null && !out.isEmpty()) {
                tc.accept(level, Slot.UNKNOWN, "chosen items: " + out + " taken out to fit the skill points");
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
                detail.addAll(explain(before, after));
            }
            Option first = before == null ? null : option(info, slot, start, before, level, earned);
            String src = Shopping.sourceOf(info);
            rows.add(new Gear(row, info, Double.NaN, src == null ? "?" : Shopping.where(src), Prices.buyText(info.name()),
                    null, null, detail, List.of(), first == null ? List.of() : List.of(first)));
            place(placed, row, slot);
        }
        return rows;
    }

    /**
     * The best ring for the hand the milestone's ring pick did not take: a second copy of a ring
     * in the build, or one of this milestone's best rings. Put into {@code baseline} when it clears
     * the same bar as any pick; null when nothing does.
     */
    private static Gear otherRing(Map<String, Damage.Piece> baseline, Map<String, String> placed, List<ItemData> pool,
            int m, int earned, double baseObj, List<String> badSources, java.util.function.Predicate<ItemData> affordable) {
        if (baseObj <= 0) {
            return null;
        }
        java.util.LinkedHashSet<ItemData> cands = new java.util.LinkedHashSet<>();
        for (String s : new String[] {"RING1", "RING2"}) {
            Damage.Piece p = baseline.get(s);
            ItemData info = p == null ? null : findGear(p.name());
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
                Damage.Piece cand = Damage.piece(info);
                if (info.requirements().level() > m || there != null && there.key().equals(cand.key()) || !affordable.test(info)) {
                    continue;
                }
                if (Shopping.excluded(info, badSources) || Exclusive.clash(baseline, slot, Exclusive.group(info)) != null) {
                    continue;
                }
                double obj = full(baseline, slot, cand, m, earned);
                if (Double.isNaN(obj)) {
                    continue;
                }
                double gain = (obj / baseObj - 1) * 100;
                trace(m, Slot.RING, info, String.format(java.util.Locale.ROOT, "other hand %.2f%% in %s", gain, slot));
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
        Option first = before == null ? null : option(best, bestSlot, baseline, before, m, earned);
        baseline.put(bestSlot, Damage.piece(best));
        place(placed, RING2, bestSlot);
        String src = Shopping.sourceOf(best);
        return new Gear(RING2, best, bestGain, Shopping.where(src), Prices.buyText(best.name()), null, null,
                new ArrayList<>(), List.of(), first == null ? List.of() : List.of(first));
    }

    private static Build.Eval fullEval(Map<String, Damage.Piece> build, String slot, Damage.Piece cand, int m, int earned) {
        tick();
        Map<String, Damage.Piece> trial = new LinkedHashMap<>(build);
        trial.put(slot, cand);
        return Build.allocate(trial, m, earned);
    }

    /**
     * A set's score with the respec charge: the skill points it takes away from the last
     * milestone's set need a reset in town, and a road map that turns Strength into Agility
     * into Intelligence every ten levels is no plan. rules.roadmap.respecWeight per 100 points
     * taken back (0.2: a fifth off for a full turn). Points added are free: you earn them.
     */
    static double judged(Build.Eval e, int[] prev, int m) {
        // the effective health floor (what build-solver asks as min EHP) is part of the score
        // itself now (Damage.ehpFloor), so the skill points are spread with it in mind too
        double o = e.result().objective();
        if (prev == null) {
            return o;
        }
        int back = 0;
        for (int i = 0; i < prev.length && i < e.allocation().length; i++) {
            back += Math.max(0, prev[i] - e.allocation()[i]);
        }
        return o * Math.max(0.5, 1 - Rules.sub("roadmap", "respecWeight", 0.2) * back / 100.0);
    }

    /**
     * After the slot-by-slot picks: each slot's best few (pools) are tried again in the whole
     * set, with the points spread anew, and the best swap that helps by more than 0.2 % is
     * taken, for up to rules.roadmap.jointPasses rounds. A swapped slot's row is made anew.
     */
    private static void refine(Map<String, Damage.Piece> build, Map<String, String> placed, Map<Slot, List<ItemData>> pools,
            List<Gear> gear, int m, int earned, int[] prev, java.util.Set<String> fixed) {
        Build.Eval cur = Build.allocate(build, m, earned);
        if (cur == null) {
            return;
        }
        double best = judged(cur, prev, m);
        int passes = (int) Rules.sub("roadmap", "jointPasses", 1);
        for (int pass = 0; pass < passes; pass++) {
            boolean moved = false;
            for (var e : pools.entrySet()) {
                Slot t = e.getKey();
                for (String slot : t == Slot.RING ? new String[] {"RING1", "RING2"} : new String[] {Build.slotOf(t, 0)}) {
                    if (slot == null || fixed.contains(slot)) {
                        continue;                       // a chosen item stays
                    }
                    Damage.Piece there = build.get(slot);
                    ItemData pick = null;
                    Build.Eval pickEval = null;
                    double pickJ = best * 1.002;
                    for (ItemData info : e.getValue()) {
                        if (info.requirements().level() > m) {
                            continue;
                        }
                        Damage.Piece cand = Damage.piece(info);
                        if (there != null && there.key().equals(cand.key())
                                || Exclusive.clash(build, slot, Exclusive.group(info)) != null) {
                            continue;
                        }
                        Build.Eval ev = fullEval(build, slot, cand, m, earned);
                        if (ev == null) {
                            continue;
                        }
                        double j = judged(ev, prev, m);
                        if (j > pickJ) {
                            pickJ = j;
                            pick = info;
                            pickEval = ev;
                        }
                    }
                    if (pick == null) {
                        continue;
                    }
                    double gain = (pickJ / best - 1) * 100;
                    trace(m, t, pick, String.format(java.util.Locale.ROOT, "joint swap %.2f%% in %s", gain, slot));
                    swapRow(build, placed, gear, pools, slot, t, pick, pickEval, gain, m, earned);
                    best = pickJ;
                    moved = true;
                }
            }
            if (!moved) {
                break;
            }
        }
    }

    /** Puts {@code pick} into a build slot and makes the row it shows (the pick that held it, or a new one). */
    private static void swapRow(Map<String, Damage.Piece> build, Map<String, String> placed, List<Gear> gear,
            Map<Slot, List<ItemData>> pools, String slot, Slot t, ItemData pick, Build.Eval after, double gain, int m, int earned) {
        Build.Eval before = Build.allocate(build, m, earned);
        String name = null;
        for (var pe : placed.entrySet()) {
            if (pe.getValue().equals(slot)) {
                name = pe.getKey();
            }
        }
        if (name == null) {
            name = t != Slot.RING ? t.name() : placed.containsKey(Slot.RING.name()) ? RING2 : Slot.RING.name();
        }
        String rowName = name;
        gear.removeIf(g -> g.slot().equals(rowName));
        Option first = before == null ? null : option(pick, slot, build, before, m, earned);
        // the comparison panel: the pick, then the slot's best others with their prices, weaker
        // ones too, so a cheaper piece is one click away with what it costs you
        List<Option> options = new ArrayList<>();
        if (first != null) {
            options.add(first);
            List<ItemData> pool = t != null && t.isWeapon() ? weaponPool(pools) : pools.get(t);
            List<Option> others = new ArrayList<>();
            // the pool is ranked: its first eight are judged for the panel's four (all forty
            // took most of a map that the beam cache had otherwise made quick)
            int tried = 0;
            for (ItemData info : pool == null ? List.<ItemData>of() : pool) {
                if (tried >= 8) {
                    break;
                }
                if (info == pick || info.name().equals(pick.name()) || info.requirements().level() > m
                        || !panelAllows(info, m) || Exclusive.clash(build, slot, Exclusive.group(info)) != null) {
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
            detail.addAll(explain(before, after));
        }
        gear.add(new Gear(rowName, pick, gain, Shopping.where(src), Prices.buyText(pick.name()), null, null,
                detail, List.of(), options));
        build.put(slot, Damage.piece(pick));
        place(placed, rowName, slot);
    }

    /** The order the beam fills a set: the weapon sets the element, then armour, then the accessories. */
    private static final String[] BEAM_ORDER = {"WEAPON", "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS", "RING1", "RING2",
        "BRACELET", "NECKLACE"};

    private record BeamState(Map<String, Damage.Piece> build, int[] alloc, double score) {}

    /**
     * A wide search over whole sets, after build-solver's beam (RawFish69/build-solver, written
     * anew): from the milestone's starting set, slot by slot in BEAM_ORDER, every kept partial
     * set tries each of the slot's best rules.roadmap.jointPool (40) or keeps what it has; the
     * rules.roadmap.beamWidth (200) best by a quick score go on (the weapon spreads the points
     * anew, the rest move only what the piece needs). The best few at the end
     * (rules.roadmap.beamFinal, 40) are judged with the points spread anew. When that beats the
     * slot-by-slot set, its slots replace the picks.
     */
    private static void beamSearch(Map<String, Damage.Piece> start, Map<String, Damage.Piece> build, Map<String, String> placed,
            Map<Slot, List<ItemData>> pools, List<Gear> gear, int m, int earned, int[] prev, java.util.Set<String> fixed) {
        Build.Eval s0 = Build.allocate(start, m, earned);
        Build.Eval cur = Build.allocate(build, m, earned);
        if (s0 == null || cur == null) {
            return;
        }
        // the same inputs give the same set: kept on disk per character, so a map that
        // nothing changed for comes back in seconds after a restart instead of 20 s
        String ck = beamKey(start, build, pools, m, earned, prev, fixed);
        Map<String, Damage.Piece> chosen;
        if (BeamCache.has(ck)) {
            chosen = BeamCache.get(ck, start, build);
            if (chosen == null && !BeamCache.isNone(ck)) {
                chosen = beamBest(start, build, pools, m, earned, prev, s0, cur, fixed);
                BeamCache.put(ck, chosen);
            }
        } else {
            chosen = beamBest(start, build, pools, m, earned, prev, s0, cur, fixed);
            BeamCache.put(ck, chosen);
        }
        if (chosen == null) {
            return;
        }
        applySet(chosen, start, build, placed, pools, gear, m, earned, prev);
    }

    /** What a milestone's beam depends on: the sets it starts from, the pools, the points before and the rules. */
    private static String beamKey(Map<String, Damage.Piece> start, Map<String, Damage.Piece> build,
            Map<Slot, List<ItemData>> pools, int m, int earned, int[] prev, java.util.Set<String> fixed) {
        StringBuilder k = new StringBuilder();
        k.append(m).append('|').append(earned).append('|').append(java.util.Arrays.toString(prev)).append('|');
        k.append(setKey(start)).append('|').append(setKey(build)).append('|');
        pools.forEach((t, l) -> {
            k.append(t.name()).append(':');
            l.forEach(i -> k.append(i.name()).append(','));
        });
        k.append('|').append(Rules.hash());
        // the scoring model: results an older one chose are not reused
        k.append("|model:").append(BeamCache.modelVersion());
        if (!fixed.isEmpty()) {
            k.append("|fixed:").append(fixed);      // the chosen items' slots; without any the key is as it was
        }
        Tuning.Choice tuned = Tuning.active();
        if (tuned != null) {
            k.append("|tuned:").append(tuned.key());   // the strip's weights score the sets otherwise
        }
        return Integer.toHexString(k.toString().hashCode()) + Integer.toHexString(k.reverse().toString().hashCode());
    }

    /** The beam itself: the best set it finds, or null when none beats the slot picks. */
    private static Map<String, Damage.Piece> beamBest(Map<String, Damage.Piece> start, Map<String, Damage.Piece> build,
            Map<Slot, List<ItemData>> pools, int m, int earned, int[] prev, Build.Eval s0, Build.Eval cur,
            java.util.Set<String> fixed) {
        int width = (int) Rules.sub("roadmap", "beamWidth", 200);
        Map<String, Damage.Piece> pieces = new HashMap<>();
        List<BeamState> beam = new ArrayList<>(List.of(new BeamState(new LinkedHashMap<>(start), s0.allocation(),
                judged(s0, prev, m))));
        for (String slot : BEAM_ORDER) {
            Slot t = slot.startsWith("RING") ? Slot.RING : slot.equals("WEAPON") ? null : Slot.valueOf(slot);
            List<ItemData> pool = t == null ? weaponPool(pools) : pools.get(t);
            if (pool == null || pool.isEmpty() || fixed.contains(slot)) {
                continue;                                           // a chosen item stays
            }
            List<BeamState> next = new ArrayList<>(beam);          // keeping the slot as it is
            for (BeamState st : beam) {
                Damage.Piece there = st.build().get(slot);
                for (ItemData info : pool) {
                    if (info.requirements().level() > m) {
                        continue;
                    }
                    Damage.Piece cand = pieces.computeIfAbsent(info.name(), n -> Damage.piece(info));
                    if (there != null && there.key().equals(cand.key())
                            || Exclusive.clash(st.build(), slot, Exclusive.group(info)) != null) {
                        continue;
                    }
                    tick();
                    Build.Eval ev = t == null ? fullEval(st.build(), slot, cand, m, earned)
                            : Build.tryEval(st.build(), st.alloc(), slot, cand, m, earned);
                    if (ev == null) {
                        continue;
                    }
                    Map<String, Damage.Piece> b = new LinkedHashMap<>(st.build());
                    b.put(slot, cand);
                    next.add(new BeamState(b, ev.allocation(), judged(ev, prev, m)));
                }
            }
            // the quick score moves only the points a piece needs: the best few by it are scored
            // again with the points spread anew, so a set that needs a new spread is not cut early
            next.sort((x, y) -> Double.compare(y.score(), x.score()));
            int exact = (int) Rules.sub("roadmap", "beamExact", 20);
            for (int i = 0; i < next.size() && i < exact; i++) {
                BeamState st = next.get(i);
                Build.Eval ev = Build.allocate(st.build(), m, earned);
                if (ev != null) {
                    next.set(i, new BeamState(st.build(), ev.allocation(), judged(ev, prev, m)));
                }
            }
            // the same set reached two ways counts once (the two rings in either hand too)
            Map<String, BeamState> seen = new LinkedHashMap<>();
            next.sort((x, y) -> Double.compare(y.score(), x.score()));
            for (BeamState st : next) {
                seen.putIfAbsent(setKey(st.build()), st);
                if (seen.size() >= width) {
                    break;
                }
            }
            beam = new ArrayList<>(seen.values());
        }
        // judge the best few with the points spread anew
        BeamState best = null;
        Build.Eval bestEval = null;
        double bestJ = judged(cur, prev, m) * 1.002;
        int fin = (int) Rules.sub("roadmap", "beamFinal", 40);
        for (int i = 0; i < beam.size() && i < fin; i++) {
            Build.Eval ev = Build.allocate(beam.get(i).build(), m, earned);
            if (ev != null && judged(ev, prev, m) > bestJ) {
                bestJ = judged(ev, prev, m);
                best = beam.get(i);
                bestEval = ev;
            }
        }
        if (best == null) {
            return null;
        }
        double from = judged(cur, prev, m);
        TriConsumer tc = trace;
        if (tc != null) {
            tc.accept(m, Slot.RING, String.format(java.util.Locale.ROOT, "beam set %+.2f%% over the slot picks", (bestJ / from - 1) * 100));
        }
        return best.build();
    }

    /**
     * The set pieces a milestone can put together, by set: what you have (worn, carried or
     * banked, whatever its level or where it once dropped) and every piece you could get there
     * ({@code found}: the milestone's level, tier, source and budget, not only each slot's best
     * forty, as a set piece alone rarely ranks high). Set weapons too, of your class's kind,
     * unless a chosen item holds the weapon.
     */
    private static Map<String, List<ItemData>> setPieces(Map<Slot, List<ItemData>> cands, List<ItemData> found,
            java.util.Set<String> owned, int m, java.util.Set<String> fixed) {
        Map<String, List<ItemData>> out = new LinkedHashMap<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        boolean weapon = !fixed.contains("WEAPON");
        java.util.function.Consumer<ItemData> add = info -> {
            if (info.set().isPresent() && info.slot() != null && (weapon || !info.slot().isWeapon())
                    && info.requirements().level() <= m && seen.add(info.name())) {
                out.computeIfAbsent(info.set().get().name(), k -> new ArrayList<>()).add(info);
            }
        };
        cands.values().forEach(l -> l.stream().filter(i -> owned.contains(i.name())).forEach(add));
        found.forEach(add);
        return out;
    }

    /** A whole-set try: the build with a set put on or taken off, and the slots changed for it. */
    record Seed(Map<String, Damage.Piece> build, java.util.Set<String> held) {}

    /**
     * The searched set with whole sets changed at once ({@link #wholeSets}): a set bonus only
     * shows with its pieces together, so a search that changes one piece at a time never puts a
     * set on (nothing worn and the Wynnterfest ornaments in the bank: Glimmer, Growth … one by
     * one) nor takes one off for pieces that beat it only together. For every set
     * ({@link #setPieces}): all of it on, and for each smaller count it has a bonus at the pieces
     * that do most alone (Goblin's hood and cloak without the boots); for every set the build
     * wears: that set off, each of its slots holding the pool's best. A ring you can have twice
     * fills both hands and counts twice (Black Catalyst, Constellation).
     */
    static List<Seed> setSeeds(Map<String, Damage.Piece> build, Build.Eval cur, Map<Slot, List<ItemData>> pools,
            Map<String, List<ItemData>> sets, Map<String, Integer> owned, java.util.Set<String> buyable,
            java.util.Set<String> fixed, int m, int earned, int[] prev) {
        Map<String, Seed> out = new LinkedHashMap<>();
        double curJ = judged(cur, prev, m);
        String now = setKey(build);
        java.util.function.Consumer<Seed> keep = seed -> {
            String k = setKey(seed.build());
            if (!k.equals(now) && !clashes(seed.build())) {
                out.putIfAbsent(k, seed);
            }
        };
        List<String> hands = new ArrayList<>();
        for (String h : new String[] {"RING1", "RING2"}) {
            if (!fixed.contains(h)) {
                hands.add(h);
            }
        }
        // a set put on, whole or in part
        for (List<ItemData> pieces : sets.values()) {
            SetData set = pieces.get(0).set().get();
            // where its pieces go: a slot each (of two for one slot, the one that does more
            // alone), a ring in each free hand it can fill: twice when you have two or can buy it
            java.util.Set<String> bySlot = new java.util.HashSet<>();
            List<ItemData> rings = new ArrayList<>();
            for (ItemData info : pieces) {
                if (info.slot() == Slot.RING) {
                    int copies = buyable.contains(info.name()) ? 2 : Math.min(2, owned.getOrDefault(info.name(), 1));
                    for (int c = 0; c < copies; c++) {
                        rings.add(info);
                    }
                    continue;
                }
                String slot = Build.slotOf(info.slot(), 0);
                if (slot != null && !fixed.contains(slot)) {
                    bySlot.add(slot);
                }
            }
            int n = bySlot.size() + Math.min(rings.size(), hands.size());
            int lowest = Integer.MAX_VALUE;
            for (int k : set.bonuses().keySet()) {
                SetData.Bonus b = set.bonuses().get(k);
                if (k >= 2 && k <= n && (!b.minor().isEmpty() || !b.major().isEmpty())) {
                    lowest = Math.min(lowest, k);
                }
            }
            if (lowest == Integer.MAX_VALUE) {
                continue;                               // no bonus it can reach here
            }
            // each piece judged alone in the build, best first: {slot, piece, judged alone}; a
            // ring once per copy, the first in the hand where it does more, the second in the other
            List<Object[]> spots = new ArrayList<>();
            Map<String, Object[]> best = new LinkedHashMap<>();
            for (ItemData info : pieces) {
                String slot = info.slot() == Slot.RING ? null : Build.slotOf(info.slot(), 0);
                if (slot == null || fixed.contains(slot)) {
                    continue;
                }
                double j = aloneJ(build, cur.allocation(), curJ, slot, info, m, earned, prev);
                Object[] had = best.get(slot);
                if (had == null || j > (double) had[2]) {
                    best.put(slot, new Object[] {slot, info, j});
                }
            }
            spots.addAll(best.values());
            java.util.Set<String> ringNames = new java.util.HashSet<>();
            for (ItemData info : rings) {
                if (!ringNames.add(info.name())) {
                    continue;
                }
                List<Object[]> byHand = new ArrayList<>();
                for (String h : hands) {
                    byHand.add(new Object[] {h, info, aloneJ(build, cur.allocation(), curJ, h, info, m, earned, prev)});
                }
                byHand.sort((x, y) -> Double.compare((double) y[2], (double) x[2]));
                long copies = rings.stream().filter(r -> r == info).count();
                for (int c = 0; c < copies && c < byHand.size(); c++) {
                    spots.add(byHand.get(c));
                }
            }
            spots.sort((x, y) -> Double.compare((double) y[2], (double) x[2]));
            int[] handsLeft = {hands.size()};             // the best rings, as many as there are hands
            spots.removeIf(sp -> ((String) sp[0]).startsWith("RING") && handsLeft[0]-- <= 0);
            java.util.Set<Integer> counts = new java.util.TreeSet<>();
            for (int k : set.bonuses().keySet()) {
                if (k >= lowest && k < spots.size()) {
                    counts.add(k);
                }
            }
            counts.add(spots.size());
            for (int k : counts) {
                Map<String, Damage.Piece> seed = new LinkedHashMap<>(build);
                java.util.Set<String> held = new java.util.HashSet<>();
                List<Object[]> ringsOn = new ArrayList<>();
                for (int i = 0; i < k; i++) {
                    Object[] sp = spots.get(i);
                    ItemData info = (ItemData) sp[1];
                    String slot = (String) sp[0];
                    if (slot.startsWith("RING")) {
                        ringsOn.add(sp);
                        continue;
                    }
                    if (!sameName(seed.get(slot), info)) {      // the one you wear keeps its rolls
                        seed.put(slot, Damage.piece(info));
                    }
                    held.add(slot);
                }
                putRings(seed, ringsOn, hands, held);
                keep.accept(new Seed(seed, held));
            }
        }
        // a set taken off whole
        java.util.Set<String> worn = new java.util.LinkedHashSet<>();
        build.values().forEach(p -> {
            if (p != null) {
                p.stats().keySet().stream().filter(k -> k.startsWith(Damage.SET)).forEach(worn::add);
            }
        });
        for (String set : worn) {
            Map<String, Damage.Piece> seed = new LinkedHashMap<>(build);
            java.util.Set<String> held = new java.util.HashSet<>();
            int ring = 0;
            for (String slot : BEAM_ORDER) {
                Damage.Piece p = seed.get(slot);
                if (p == null || !p.stats().containsKey(set) || fixed.contains(slot) || slot.equals("WEAPON")) {
                    continue;
                }
                Slot t = slot.startsWith("RING") ? Slot.RING : Slot.valueOf(slot);
                List<ItemData> other = pools.getOrDefault(t, List.of()).stream()
                        .filter(i -> i.set().map(si -> !set.equals(Damage.SET + si.name())).orElse(true)).toList();
                if (other.isEmpty()) {
                    continue;
                }
                // the two hands: the pool's best two, or its best twice
                ItemData pick = other.get(t == Slot.RING ? Math.min(ring++, other.size() - 1) : 0);
                seed.put(slot, Damage.piece(pick));
                held.add(slot);
            }
            keep.accept(new Seed(seed, held));
        }
        return new ArrayList<>(out.values());
    }

    /**
     * A set piece judged alone in the build, in {@code slot}. Quick ({@link Build#tryEval}: only
     * the points it needs move), as it only orders them.
     */
    private static double aloneJ(Map<String, Damage.Piece> build, int[] alloc, double curJ, String slot, ItemData info,
            int m, int earned, int[] prev) {
        if (sameName(build.get(slot), info)) {
            return curJ;                                // worn there already
        }
        tick();
        Build.Eval ev = Build.tryEval(build, alloc, slot, Damage.piece(info), m, earned);
        return ev == null ? Double.NEGATIVE_INFINITY : judged(ev, prev, m);
    }

    /**
     * The set's rings ({hand, piece, ...}, best first) into the free hands: a ring a hand already
     * holds stays there with its rolls, the others go to the hand they were judged in, or the
     * one left.
     */
    private static void putRings(Map<String, Damage.Piece> seed, List<Object[]> rings, List<String> hands,
            java.util.Set<String> held) {
        List<String> free = new ArrayList<>(hands);
        List<Object[]> left = new ArrayList<>();
        for (Object[] r : rings) {
            ItemData info = (ItemData) r[1];
            String h = free.stream().filter(x -> sameName(seed.get(x), info)).findFirst().orElse(null);
            if (h == null) {
                left.add(r);
            } else {
                free.remove(h);
                held.add(h);
            }
        }
        for (Object[] r : left) {
            if (free.isEmpty()) {
                break;
            }
            String h = free.contains((String) r[0]) ? (String) r[0] : free.get(0);
            free.remove(h);
            seed.put(h, Damage.piece((ItemData) r[1]));
            held.add(h);
        }
    }

    private static boolean sameName(Damage.Piece p, ItemData info) {
        return p != null && p.name().equalsIgnoreCase(info.name());
    }

    /**
     * After the search: the best of {@link #setSeeds} replaces the build when it beats it by
     * more than 0.2 %, a row for each slot it changes, and the seeds are made again from the new
     * build until none helps (rules.roadmap.setRounds, 3), so an armour set and a set of
     * accessories can both go on. The best rules.roadmap.setPolish (2) seeds first get one pass
     * of slot swaps around the set ({@link #polish}): a set whose other slots must change with it
     * (the points it needs, the element it brings) is not cut early; the build itself gets the
     * same pass for the bar to beat. The search itself is left as it was, so a milestone without
     * a set to try comes out the same.
     */
    private static void wholeSets(Map<String, Damage.Piece> start, Map<String, Damage.Piece> build,
            Map<String, String> placed, Map<Slot, List<ItemData>> pools, List<Gear> gear, int m, int earned, int[] prev,
            Map<String, List<ItemData>> sets, Map<String, Integer> owned, List<ItemData> found,
            java.util.Set<String> fixed) {
        java.util.Set<String> buyable = new java.util.HashSet<>();
        found.forEach(i -> buyable.add(i.name()));
        int rounds = (int) Rules.sub("roadmap", "setRounds", 3);
        for (int round = 0; round < rounds; round++) {
            Build.Eval cur = Build.allocate(build, m, earned);
            if (cur == null) {
                return;
            }
            double curJ = judged(cur, prev, m);
            List<Seed> seeds = setSeeds(build, cur, pools, sets, owned, buyable, fixed, m, earned, prev);
            List<Object[]> scored = new ArrayList<>();   // {seed, judged}
            for (Seed seed : seeds) {
                tick();
                Build.Eval ev = Build.allocate(seed.build(), m, earned);
                if (ev != null) {
                    scored.add(new Object[] {seed, judged(ev, prev, m)});
                }
            }
            if (scored.isEmpty()) {
                return;
            }
            scored.sort((x, y) -> Double.compare((double) y[1], (double) x[1]));
            // a seed far behind is not one pass from winning (rules.roadmap.setPolishWithin, 5 %)
            int polish = (int) Rules.sub("roadmap", "setPolish", 2);
            double near = curJ * (1 - Rules.sub("roadmap", "setPolishWithin", 0.05));
            boolean polished = false;
            for (int i = 0; i < scored.size() && i < polish && (double) scored.get(i)[1] >= near; i++) {
                Seed s = (Seed) scored.get(i)[0];
                Map<String, Damage.Piece> b = new LinkedHashMap<>(s.build());
                double j = polish(b, (double) scored.get(i)[1], s.held(), pools, fixed, m, earned, prev);
                scored.set(i, new Object[] {new Seed(b, s.held()), j});
                polished = true;
            }
            // the bar: the build with the same pass, or a set would win on swaps refine makes
            // anyway; only needed when a polished seed beats the build as it is
            boolean ahead = scored.stream().anyMatch(sc -> (double) sc[1] > curJ * 1.002);
            double bar = polished && ahead
                    ? polish(new LinkedHashMap<>(build), curJ, java.util.Set.of(), pools, fixed, m, earned, prev) : curJ;
            Object[] best = null;
            for (Object[] sc : scored) {
                if ((double) sc[1] > bar * 1.002 && (best == null || (double) sc[1] > (double) best[1])) {
                    best = sc;
                }
            }
            if (best == null) {
                return;
            }
            TriConsumer tc = trace;
            if (tc != null) {
                tc.accept(m, Slot.RING, String.format(java.util.Locale.ROOT, "whole set %+.2f%% over the search (round %d)",
                        ((double) best[1] / curJ - 1) * 100, round + 1));
            }
            applySet(((Seed) best[0]).build(), start, build, placed, pools, gear, m, earned, prev);
        }
    }

    /**
     * One quick pass over the slots a seed did not set: each tries the first
     * rules.roadmap.setPolishPool (5) of its pool and takes the best swap that helps by more
     * than 0.2 %. Changes {@code b}; its judged score after.
     */
    private static double polish(Map<String, Damage.Piece> b, double j, java.util.Set<String> held,
            Map<Slot, List<ItemData>> pools, java.util.Set<String> fixed, int m, int earned, int[] prev) {
        int top = (int) Rules.sub("roadmap", "setPolishPool", 5);
        for (var e : pools.entrySet()) {
            Slot t = e.getKey();
            for (String slot : t == Slot.RING ? new String[] {"RING1", "RING2"} : new String[] {Build.slotOf(t, 0)}) {
                if (slot == null || fixed.contains(slot) || held.contains(slot)) {
                    continue;
                }
                Damage.Piece there = b.get(slot);
                Damage.Piece pick = null;
                double pickJ = j * 1.002;
                int tried = 0;
                for (ItemData info : e.getValue()) {
                    if (tried >= top) {
                        break;
                    }
                    Damage.Piece cand = Damage.piece(info);
                    if (info.requirements().level() > m || there != null && there.key().equals(cand.key())
                            || Exclusive.clash(b, slot, Exclusive.group(info)) != null) {
                        continue;
                    }
                    tried++;
                    Build.Eval ev = fullEval(b, slot, cand, m, earned);
                    if (ev != null && judged(ev, prev, m) > pickJ) {
                        pickJ = judged(ev, prev, m);
                        pick = cand;
                    }
                }
                if (pick != null) {
                    b.put(slot, pick);
                    j = pickJ;
                }
            }
        }
        return j;
    }

    /** Puts a set the beam chose into the milestone's build, a row for each slot it changes. */
    private static void applySet(Map<String, Damage.Piece> chosen, Map<String, Damage.Piece> start,
            Map<String, Damage.Piece> build, Map<String, String> placed, Map<Slot, List<ItemData>> pools,
            List<Gear> gear, int m, int earned, int[] prev) {
        for (String slot : BEAM_ORDER) {
            Damage.Piece want = chosen.get(slot);
            Damage.Piece have = build.get(slot);
            if (want == null || have != null && have.key().equals(want.key())) {
                continue;
            }
            Damage.Piece had = start.get(slot);
            if (had != null && (had.key().equals(want.key()) || had.name().equalsIgnoreCase(want.name()))) {
                // the set keeps what the milestone started with here (what you wear, or an
                // earlier pick): the slot-by-slot pick is undone and its row goes, it is no
                // "Yume → Yume" upgrade. By name too: a set seed puts a piece you have back
                // with its plain stats, and the one you have keeps its rolls
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
            ItemData info = findGear(want.name());
            if (info == null) {
                continue;
            }
            Slot t = slot.startsWith("RING") ? Slot.RING : slot.equals("WEAPON") ? info.slot() : Slot.valueOf(slot);
            Build.Eval before = Build.allocate(build, m, earned);
            Build.Eval after = fullEval(build, slot, want, m, earned);
            double gain = before == null || after == null ? Double.NaN
                    : (judged(after, prev, m) / judged(before, prev, m) - 1) * 100;
            swapRow(build, placed, gear, pools, slot, t, info, after, Double.isNaN(gain) ? 0 : gain, m, earned);
        }
    }

    /**
     * Every combination around the set the beam found: per slot what it holds and the best
     * rules.roadmap.exactPool - 1 (2) others by a single swap into it, the two rings as an
     * unordered pair (the same ring twice too). The beam scores half-built sets quickly and can
     * miss a set where several pieces change at once (on the recorded Shaman this finds 2 to 5 %
     * at Lv 44, 50 and 90; 2 to 7 s a milestone, but a wide beam alone does as well in 20 s, so it
     * is off unless rules.roadmap.exactPool is 2 or more). Judged in full on rules.roadmap.exactThreads low-priority
     * threads (at most 8, a third of the cores), so the game keeps its share.
     */
    private static void exactSearch(Map<String, Damage.Piece> build, Map<String, String> placed,
            Map<Slot, List<ItemData>> pools, List<Gear> gear, int m, int earned, int[] prev) {
        int k = (int) Rules.sub("roadmap", "exactPool", 0);
        Build.Eval cur = Build.allocate(build, m, earned);
        if (k < 2 || cur == null) {
            return;
        }
        double curJ = judged(cur, prev, m);
        String[] slots = {"WEAPON", "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS", "RING1", "BRACELET", "NECKLACE"};
        Map<String, List<Damage.Piece>> cands = new LinkedHashMap<>();
        Map<String, ItemData> infoOf = new HashMap<>();
        for (String slot : slots) {
            Slot t = slot.startsWith("RING") ? Slot.RING : slot.equals("WEAPON") ? null : Slot.valueOf(slot);
            List<ItemData> pool = t == null ? weaponPool(pools) : pools.get(t);
            List<Damage.Piece> top = new ArrayList<>();
            for (String held : slot.equals("RING1") ? new String[] {"RING1", "RING2"} : new String[] {slot}) {
                Damage.Piece h = build.get(held);
                if (h != null && top.stream().noneMatch(q -> q.key().equals(h.key()))) {
                    top.add(h);
                }
            }
            if (pool != null) {
                List<Object[]> ranked = new ArrayList<>();
                for (ItemData info : pool) {
                    Damage.Piece cand = Damage.piece(info);
                    if (info.requirements().level() > m || top.stream().anyMatch(q -> q.key().equals(cand.key()))
                            || Exclusive.clash(build, slot, Exclusive.group(info)) != null) {
                        continue;
                    }
                    Build.Eval ev = fullEval(build, slot, cand, m, earned);
                    if (ev != null) {
                        ranked.add(new Object[] {cand, judged(ev, prev, m)});
                        infoOf.put(cand.key(), info);
                    }
                }
                ranked.sort((x, y) -> Double.compare((double) y[1], (double) x[1]));
                int room = k - 1 + (slot.equals("RING1") ? 1 : 0);
                for (int i = 0; i < ranked.size() && i < room; i++) {
                    top.add((Damage.Piece) ranked.get(i)[0]);
                }
            }
            if (!top.isEmpty()) {
                cands.put(slot, top);
            }
        }
        // the sets without their rings, then each with every ring pair
        List<Map<String, Damage.Piece>> sets = new ArrayList<>(List.of(new LinkedHashMap<>(build)));
        for (var e : cands.entrySet()) {
            if (e.getKey().equals("RING1")) {
                continue;
            }
            List<Map<String, Damage.Piece>> next = new ArrayList<>();
            for (Map<String, Damage.Piece> b : sets) {
                for (Damage.Piece p : e.getValue()) {
                    Map<String, Damage.Piece> c = new LinkedHashMap<>(b);
                    c.put(e.getKey(), p);
                    next.add(c);
                }
            }
            sets = next;
        }
        List<Damage.Piece[]> rings = new ArrayList<>();
        List<Damage.Piece> rs = cands.getOrDefault("RING1", List.of());
        for (int i = 0; i < rs.size(); i++) {
            for (int j = i; j < rs.size(); j++) {
                rings.add(new Damage.Piece[] {rs.get(i), rs.get(j)});
            }
        }
        if (rings.isEmpty()) {
            rings.add(new Damage.Piece[] {build.get("RING1"), build.get("RING2")});
        }
        TriConsumer tr = trace;
        if (tr != null) {
            StringBuilder c = new StringBuilder();
            cands.forEach((sl, ps) -> c.append(sl).append(ps.stream().map(Damage.Piece::name).toList()).append(' '));
            tr.accept(m, Slot.RING, "exact over " + sets.size() * (long) rings.size() + " sets: " + c);
        }
        long started = System.nanoTime();
        double[] best = {curJ * 1.002};
        Object[] bestSet = {null};
        int threads = (int) Rules.sub("roadmap", "exactThreads",
                Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 3)));
        java.util.concurrent.ForkJoinPool fj = new java.util.concurrent.ForkJoinPool(Math.max(1, threads), fp -> {
            var th = java.util.concurrent.ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(fp);
            th.setPriority(Thread.MIN_PRIORITY);
            th.setDaemon(true);
            th.setName("wynnlabs-exact");
            return th;
        }, null, false);
        List<Map<String, Damage.Piece>> all = sets;
        try {
            Tuning.Choice tuned = Tuning.active();             // the workers score with the same tuning
            fj.submit(() -> all.parallelStream().forEach(b -> Tuning.with(tuned, () -> {
                for (Damage.Piece[] rp : rings) {
                    Map<String, Damage.Piece> c = new LinkedHashMap<>(b);
                    c.put("RING1", rp[0]);
                    c.put("RING2", rp[1]);
                    if (clashes(c)) {
                        continue;
                    }
                    Build.Eval ev = Build.allocate(c, m, earned);
                    if (ev == null) {
                        continue;
                    }
                    double j = judged(ev, prev, m);
                    synchronized (best) {
                        if (j > best[0]) {
                            best[0] = j;
                            bestSet[0] = c;
                        }
                    }
                }
                return null;
            }))).get();
        } catch (Exception e) {
            if (tr != null) {
                tr.accept(m, Slot.RING, "exact failed: " + e);
            }
            return;                                 // the beam's set stands
        } finally {
            fj.shutdown();
        }
        if (tr != null) {
            tr.accept(m, Slot.RING, String.format(java.util.Locale.ROOT, "exact took %d ms, best %.0f against %.0f",
                    (System.nanoTime() - started) / 1_000_000, best[0], curJ));
        }
        if (bestSet[0] == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Damage.Piece> win = (Map<String, Damage.Piece>) bestSet[0];
        // the ring that is already in a hand stays in that hand
        Damage.Piece r1 = win.get("RING1");
        Damage.Piece r2 = win.get("RING2");
        if (r1 != null && r2 != null && build.get("RING2") != null && build.get("RING2").key().equals(r1.key())
                && (build.get("RING1") == null || !build.get("RING1").key().equals(r2.key()))) {
            win.put("RING1", r2);
            win.put("RING2", r1);
        }
        TriConsumer tc = trace;
        if (tc != null) {
            tc.accept(m, Slot.RING, String.format(java.util.Locale.ROOT, "exact set %+.2f%% over the beam's", (best[0] / curJ - 1) * 100));
        }
        for (String slot : BEAM_ORDER) {
            Damage.Piece want = win.get(slot);
            Damage.Piece have = build.get(slot);
            if (want == null || have != null && have.key().equals(want.key())) {
                continue;
            }
            ItemData info = infoOf.containsKey(want.key()) ? infoOf.get(want.key()) : findGear(want.name());
            if (info == null) {
                continue;
            }
            Slot t = slot.startsWith("RING") ? Slot.RING : slot.equals("WEAPON") ? info.slot() : Slot.valueOf(slot);
            Build.Eval before = Build.allocate(build, m, earned);
            Build.Eval after = fullEval(build, slot, want, m, earned);
            double gain = before == null || after == null ? 0 : (judged(after, prev, m) / judged(before, prev, m) - 1) * 100;
            swapRow(build, placed, gear, pools, slot, t, info, after, gain, m, earned);
        }
    }

    /** Two pieces of one exclusive group (Ornate Shadow, the Hives …) in a set. */
    private static boolean clashes(Map<String, Damage.Piece> set) {
        java.util.Set<String> groups = new java.util.HashSet<>();
        for (Damage.Piece p : set.values()) {
            String g = Exclusive.group(p);         // by name, or a set marked illegal
            if (g != null && !groups.add(g)) {
                return true;
            }
        }
        return false;
    }

    /** The weapon pool: the class's weapon type is the one slot type in pools that is a weapon. */
    private static List<ItemData> weaponPool(Map<Slot, List<ItemData>> pools) {
        for (var e : pools.entrySet()) {
            if (e.getKey().isWeapon()) {
                return e.getValue();
            }
        }
        return null;
    }

    /** A set as its pieces, the two rings in either order the same. */
    private static String setKey(Map<String, Damage.Piece> b) {
        StringBuilder k = new StringBuilder();
        for (String slot : BEAM_ORDER) {
            if (slot.equals("RING2")) {
                continue;
            }
            if (slot.equals("RING1")) {
                String r1 = b.get("RING1") == null ? "-" : b.get("RING1").key();
                String r2 = b.get("RING2") == null ? "-" : b.get("RING2").key();
                k.append(r1.compareTo(r2) <= 0 ? r1 + "|" + r2 : r2 + "|" + r1).append(';');
                continue;
            }
            k.append(b.get(slot) == null ? "-" : b.get(slot).key()).append(';');
        }
        return k.toString();
    }

    /** {@link #vsWorn} with the reason lines of each slot. */
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
                why.put(e.getKey(), new ArrayList<>(lines.subList(0, Math.min(lines.size(), WHY_LINES))));
            }
        }
        return new Versus(out, why);
    }

    /** How many reason lines {@link #why} gives at most. */
    static final int WHY_LINES = 4;

    /**
     * Why build {@code b} beats {@code a}, biggest change first, at most {@link #WHY_LINES}
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
        int room = skills.isEmpty() ? WHY_LINES : WHY_LINES - 1;
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

    private static final Slot[] SLOTS = {Slot.HELMET, Slot.CHESTPLATE, Slot.LEGGINGS,
        Slot.BOOTS, Slot.RING, Slot.BRACELET, Slot.NECKLACE};
    /** Milestones after "now": every multiple of 10 up to the level cap (Lv 50, 60 … 120). */
    private static final int STEP = 10;

    /** The levels the road map looks at: yours, then every 10 levels up to 120. */
    private static List<Integer> milestones(int level) {
        List<Integer> out = new ArrayList<>(List.of(level));
        for (int m = (level / STEP + 1) * STEP; m <= 120; m += STEP) {
            out.add(m);
        }
        return out;
    }

    /**
     * Skill points a level-{@code m} build may put into skills outside rules.shopping.preferredSkills
     * for one piece: the rule's floor or 15 % of the points earned by then.
     */
    /** Tests only: told why each candidate was skipped or how it scored ({@code m, slot, line}). */
    static volatile TriConsumer trace;

    interface TriConsumer {
        void accept(int m, Slot slot, String line);
    }

    private static void trace(int m, Slot t, ItemData info, String what) {
        TriConsumer tc = trace;
        if (tc != null) {
            tc.accept(m, t, info.name() + " Lv" + info.requirements().level() + ": " + what);
        }
    }

    /** For the trace: how the build's parts move with {@code cand} in {@code slot}, points spread anew. */
    private static String parts(Map<String, Damage.Piece> base, String slot, Damage.Piece cand, int m, int earned) {
        Build.Eval a = Build.allocate(base, m, earned);
        Map<String, Damage.Piece> with = new LinkedHashMap<>(base);
        with.put(slot, cand);
        Build.Eval b = Build.allocate(with, m, earned);
        if (a == null || b == null) {
            String why = Build.whyNot(b == null ? with : base, earned);
            return why != null ? why : "no allocation";
        }
        Damage.Result x = a.result();
        Damage.Result y = b.result();
        double[] fx = x.factors();
        double[] fy = y.factors();
        StringBuilder f = new StringBuilder();
        String[] names = {"dmg", "life", "mana", "speed", "xp", "feel"};
        for (int i = 0; i < Math.min(fx.length, fy.length) && i < names.length; i++) {
            f.append(String.format(java.util.Locale.ROOT, " %s %.3f→%.3f", names[i], fx[i], fy[i]));
        }
        return String.format(java.util.Locale.ROOT,
                "obj %.0f→%.0f spell %.0f→%.0f main %.0f→%.0f ehp %.0f→%.0f casts %.2f→%.2f speed %.0f→%.0f xp %.0f→%.0f |%s",
                x.objective(), y.objective(), x.spellDps(), y.spellDps(), x.mainDps(), y.mainDps(), x.ehp(), y.ehp(),
                x.casts(), y.casts(), x.speed(), y.speed(), x.xp(), y.xp(), f);
    }

    static int maxOff(int m) {
        return (int) Math.max(Rules.sub("shopping", "maxOffSkillPoints", 10),
                Math.max(0, (Math.min(m, 101) - 1) * 2) * 0.15);
    }

    /** From shopping.endgameLevel (80) on, every tier and raid gear count. */
    private static boolean endgame(int m) {
        return m >= Rules.sub("shopping", "endgameLevel", 80);
    }

    static List<String> badTiers(int m) {
        return endgame(m) ? List.of() : Rules.list("shopping", "excludeTiers");
    }

    static List<String> badSources(int m) {
        List<String> out = new ArrayList<>(Rules.list("shopping", "excludeSources"));
        if (endgame(m)) {
            out.remove("RAID");
        }
        return out;
    }

    /**
     * Whether the comparison panel may show this piece at milestone {@code m}: the tiers and
     * sources that milestone leaves out stay out of its panel too (a Mythic under "Hide Mythic
     * upgrades" before the endgame level).
     */
    private static boolean panelAllows(ItemData info, int m) {
        return !badTiers(m).contains(info.tier().name()) && !Shopping.excluded(info, badSources(m));
    }

    /**
     * How much a piece's gain counts for while you would buy it soon: 1 when it is listed now
     * (or its price is unknown); 0.9 for Wynnventory's cache (listed, no count), 0.85 for few
     * or old listings, 0.6 when nothing is listed now (an old price, or none).
     */
    static double availability(Prices.Price p) {
        if (p == null) {
            return 1;
        }
        return switch (p.confidence()) {
            case NONE, HISTORY -> 0.6;
            case THIN, STALE -> 0.85;
            case CACHE -> 0.9;
            case OK -> 1;
        };
    }

    /** A gain scaled by an availability factor: a gain shrinks, a loss grows. */
    private static double scaled(double gain, double factor) {
        return gain >= 0 ? gain * factor : gain / factor;
    }

    /**
     * The points the piece's requirements ask for in skills outside {@code preferred}, beyond
     * what the set already holds there ({@code cover}: assigned plus the gear's, per skill; null
     * counts every requirement point).
     */
    static int offPoints(ItemData info, List<String> preferred, double[] cover) {
        int off = 0;
        for (var r : info.requirements().skills()) {
            if (!preferred.contains(r.skill().name())) {
                double have = cover == null ? 0 : cover[r.skill().ordinal()];
                off += (int) Math.max(0, Math.ceil(r.points() - have));
            }
        }
        return off;
    }

    /** The skill points you placed, by skill; null while the Character Info menu has not shown them. */
    private static int[] assignedPoints(GameState state) {
        int[] a = new int[SkillKind.values().length];
        int sum = 0;
        for (SkillKind s : SkillKind.values()) {
            a[s.ordinal()] = state.assigned(s);
            sum += a[s.ordinal()];
        }
        return sum > 0 ? a : null;
    }

    /** The build's score with {@code cand} in {@code slot} and the skill points spread anew; NaN if it cannot be worn. */
    private static double full(Map<String, Damage.Piece> build, String slot, Damage.Piece cand, int m, int earned) {
        Map<String, Damage.Piece> trial = new LinkedHashMap<>(build);
        trial.put(slot, cand);
        Build.Eval e = Build.allocate(trial, m, earned);
        return e == null ? Double.NaN : e.result().objective();
    }

    /** Slot → language key of its name. */
    private static final Map<String, String> SLOT_NAMES = Map.of("HELMET", "wynnlabs.road.slot_helmet",
            "CHESTPLATE", "wynnlabs.road.slot_chestplate", "LEGGINGS", "wynnlabs.road.slot_leggings",
            "BOOTS", "wynnlabs.road.slot_boots", "RING", "wynnlabs.road.slot_ring",
            "BRACELET", "wynnlabs.road.slot_bracelet", "NECKLACE", "wynnlabs.road.slot_necklace",
            "WEAPON", "wynnlabs.road.slot_weapon");

    private Roadmap() {}

    static String slotName(String slot) {
        return L.t(SLOT_NAMES.getOrDefault(slot, "wynnlabs.road.slot_weapon"));
    }

    private static volatile List<Step> lastSteps = List.of();
    private static volatile long lastBuilt;
    private static volatile String lastFor = "";
    /** The level the last map was built at, and whether it came from the file (a past session). */
    private static volatile int lastLevel;
    private static volatile boolean lastFromFile;
    /** The character whose saved map was looked for (once per character). */
    private static volatile String loadedFor;

    /**
     * Every upgrade name in the road map built this session (for keeping their prices fresh); none
     * for a map read back from a past session, whose prices stay as they were.
     */
    static List<String> lastNames() {
        List<String> out = new ArrayList<>();
        if (lastFromFile) {
            return out;
        }
        for (Step s : lastSteps) {
            for (Gear g : s.gear()) {
                out.add(g.info().name());
                if (g.options() != null) {
                    for (Option o : g.options()) {
                        out.add(o.info().name());
                    }
                }
            }
        }
        return out;
    }

    /**
     * What the build plan puts on from your bag right now, as rows by slot type name ("LEGGINGS",
     * "RING", {@link #RING2}, "RELIK" …): a piece you took off or carry that the best build you
     * own wears, with the % your build gains with it over what you wear (the road map's rows are
     * picks over that build, so they never name it, and an empty slot looked fine). Follows the
     * plan, which is worked out again within seconds of a change in what you wear or carry, so
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
            ItemData info = findGear(piece.name());
            if (info == null) {
                return;
            }
            String row = slot.equals("RING1") ? Slot.RING.name() : slot.equals("RING2") ? RING2 : info.slot().name();
            placed.put(row, slot);
            infos.put(row, info);
        });
        Map<String, Gear> rows = new LinkedHashMap<>();
        if (!placed.isEmpty()) {
            Versus v = versus(p.pieces(), p.worn(), placed, p.level(), p.earned());
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

    private static volatile BagRows bagRows;

    /**
     * The upgrade the damage model found for this slot at your level, from the character's road
     * map (built this session or read back from the last one); null when there is none. Lets
     * tooltips agree with the J menu. Never starts a build.
     */
    static Gear lastUpgrade(Slot type) {
        List<Step> steps = cached();
        if (steps.isEmpty()) {
            return null;
        }
        for (Gear g : steps.get(0).gear()) {
            if (g.slot().equals(type.name())) {
                return g;
            }
        }
        return null;
    }

    /**
     * The character's road map: the one built last (this session, or read back from the file of
     * the last one), empty when none was ever built. Never starts a build: the J menu's button,
     * its refresh and the explicit actions ({@link #forceRefresh}) do.
     */
    static List<Step> cached() {
        String id = GameState.current().characterId();
        load(id);
        return id.equals(lastFor) ? lastSteps : List.of();
    }

    /** The character's saved map, read once when the character is known (a missing or broken file: none). */
    private static synchronized void load(String id) {
        if (id == null || id.isEmpty() || id.equals(loadedFor) || id.equals(lastFor) && !lastSteps.isEmpty()) {
            return;
        }
        loadedFor = id;
        RoadmapStore.Loaded l = RoadmapStore.load(id);
        if (l == null || !id.equals(GameState.current().characterId())) {
            return;
        }
        lastSteps = l.steps();
        lastFor = id;
        lastBuilt = l.builtAt();
        lastLevel = l.level();
        cachedKey = l.key();
        lastFromFile = true;
        version++;
    }

    /** Tests: the saved map is looked for again, and nothing is kept in memory. */
    static synchronized void forget() {
        lastSteps = List.of();
        lastFor = "";
        loadedFor = null;
        cachedKey = "";
        lastFromFile = false;
        staleKey = null;
    }

    /** Tests: {@link #stale} reads the inputs again at once (it rereads them each half second). */
    static void recheck() {
        staleKey = null;
    }

    /**
     * The map is out of date: what it was built from (level, gear, skill points, archetype, rules,
     * language: {@link #key}) is not what it is now. {@code level}: the level changed (to
     * {@code now}); {@code ageMs}: how long ago it was built.
     */
    record Stale(boolean level, int now, int builtLevel, long ageMs) {}

    private static volatile String staleKey;
    private static volatile long staleAt;

    /** Why the character's map is out of date, or null when it is current or there is none. Read again each half second. */
    static Stale stale() {
        GameState state = GameState.current();
        if (cached().isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        String k = staleKey;
        if (k == null || now - staleAt > 500) {
            k = key(state);
            staleKey = k;
            staleAt = now;
        }
        if (k.equals(cachedKey)) {
            return null;
        }
        return new Stale(state.level() != lastLevel, state.level(), lastLevel, Math.max(0, state.now() - lastBuilt));
    }

    /** Whether the character has a map at all (built or saved): else the gear panel offers to calculate one. */
    static boolean hasMap() {
        return !cached().isEmpty();
    }

    /** The J menu's button and refresh, and the explicit actions: a new map now, in the background. */
    static void forceRefresh() {
        GameState state = GameState.current();
        if (state.inGame()) {
            refresh();
        }
    }

    /** Cancel: the map being built is dropped, the last one stays. */
    static void cancelBuild() {
        again = false;
        Job j = buildJob;
        if (j != null) {
            j.cancel();
        }
    }

    /** True while a map is being built in the background. */
    static boolean isBuilding() {
        return building;
    }

    /** How far the map being built is, 0 to 1, by milestones done. */
    static double progress() {
        Job j = buildJob;
        if (j != null) {
            return Math.min(1, j.done / (double) Math.max(1, j.total));
        }
        int total = progressTotal;
        return total <= 0 ? 0 : Math.min(1, progressDone / (double) total);
    }

    /** Tests: waits for the builder to finish what it was given. */
    static void awaitIdle() throws Exception {
        BUILDER.submit(() -> { }).get(120, java.util.concurrent.TimeUnit.SECONDS);
        while (building) {
            BUILDER.submit(() -> { }).get(120, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    /** Bumped each time a new map is ready, so an open J menu can pick it up. */
    static int version() {
        return version;
    }

    /**
     * What the map depends on, by content: the character, level, language, rules, and the build
     * plan's pieces, what is worn and the skill points. A map whose key is not today's is out of
     * date ({@link #stale}); it is never rebuilt for that on its own.
     */
    static String key(GameState state) {
        Build.Plan p = Build.plan();
        StringBuilder plan = new StringBuilder();
        if (p != null) {
            p.pieces().forEach((slot, piece) -> plan.append(slot).append('=').append(piece == null ? "-" : piece.key()).append(';'));
            if (p.worn() != null) {
                p.worn().forEach((slot, piece) -> plan.append(slot).append('~').append(piece == null ? "-" : piece.key()).append(';'));
            }
            plan.append(java.util.Arrays.toString(p.wornAllocation()));
        }
        // the map's text (where a piece comes from, the reasons) is written in the language of
        // the day it was built: a switch to English left "mob'lardan düşer" on the rows
        return state.characterId() + ":" + state.level() + ":" + L.code() + ":" + Rules.hash() + ":"
                + plan.toString().hashCode();
    }

    private static void refresh() {
        if (building) {
            again = true;                       // asked again mid-build (a setting changed): once more after it
            return;
        }
        building = true;
        Job j = new Job();
        buildJob = j;
        BUILDER.execute(() -> {
            try {
                build(j);
                version++;
            } catch (Throwable t) {
                // cancelled, or the game state changed under us (a menu, a world change): the last map stays
            } finally {
                buildJob = null;
                building = false;
                if (again && !j.cancelled) {
                    again = false;
                    refresh();
                }
                again = false;
            }
        });
    }

    private static volatile boolean building;
    private static volatile Job buildJob;
    private static volatile int progressDone;
    private static volatile int progressTotal;
    private static volatile boolean again;
    private static volatile int version;
    private static final java.util.concurrent.ExecutorService BUILDER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "wynnlabs-roadmap");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });

    /** The inputs key the character's map was built for. */
    private static volatile String cachedKey = "";

    /**
     * What one computation takes: the items to build around (Locks), the tuning strip, your level
     * only or every milestone, and how many whole builds at your level to rank (0: none).
     */
    record Inputs(List<String> around, Tuning.Choice tuning, boolean nowOnly, int builds) {
        /** The road map as it always was: nothing chosen, Balanced, no budget, every milestone. */
        static final Inputs PLAIN = new Inputs(List.of(), Tuning.Choice.PLAIN, false, 0);
    }

    /**
     * A computation's result: the milestones, the ranked builds at your level ({@link #rank}),
     * the chosen items it held, why it held none ({@code problem}: they cannot be worn
     * together), the milestones where the budget left a pick out, and whom it was for.
     */
    record Computed(List<Step> steps, List<Candidate> builds, List<String> around, Locks.Problem problem,
            java.util.Set<Integer> budgetCut, String forId, int level) {}

    /**
     * One whole build at your level, for the window's result list: the set by build slot, its
     * skill points and result, its score (the tuned, judged objective), what its buys cost on the
     * Trade Market (chosen items and what you own count nothing), its labels ("best", "cheap",
     * "tanky", "fast") and the slots the chosen items hold.
     */
    record Candidate(Map<String, Damage.Piece> set, Build.Eval eval, double score, double price, List<String> labels,
            java.util.Set<String> fixed) {}

    /**
     * A computation's progress and its stop: the window's loading view reads it, its Cancel and a
     * newer request set {@link #cancel}; the next check ({@link #tick}) then ends the run.
     */
    static final class Job {
        /** 0 preparing the items, 1 filling the slots, 2 trying whole sets, 3 checking skill points, 4 checking the budget, 5 ranking builds. */
        volatile int phase;
        volatile int done;
        volatile int total = 1;
        final java.util.concurrent.atomic.AtomicLong tried = new java.util.concurrent.atomic.AtomicLong();
        /** The best set's score so far (NaN before the first). */
        volatile double best = Double.NaN;
        volatile boolean cancelled;

        void cancel() {
            cancelled = true;
        }
    }

    /** A run stopped by its Job: nothing it found is kept. */
    static final class Cancelled extends RuntimeException {
        Cancelled() {
            super("cancelled", null, false, false);
        }
    }

    private static final ThreadLocal<Job> JOB = new ThreadLocal<>();

    /** A set tried: counted for the loading view, and the run ends here when it was cancelled. */
    private static void tick() {
        Job j = JOB.get();
        if (j != null) {
            j.tried.incrementAndGet();
            if (j.cancelled) {
                throw new Cancelled();
            }
        }
    }

    private static void phase(int p) {
        Job j = JOB.get();
        if (j != null) {
            j.phase = p;
            if (j.cancelled) {
                throw new Cancelled();
            }
        }
    }

    /** The road map for the J menu: built as it always was (nothing chosen, no tuning), kept and saved for the character. */
    static List<Step> build() {
        return build(null);
    }

    /** {@link #build} with a job for its progress and Cancel (the background build). */
    static List<Step> build(Job job) {
        GameState state = GameState.current();
        String key = state.inGame() ? key(state) : "";
        Computed c = compute(Inputs.PLAIN, job);
        if (c == null) {
            return List.of();
        }
        if (!c.forId().equals(GameState.current().characterId())) {
            return c.steps();                       // built for the character you just left
        }
        lastSteps = c.steps();
        lastBuilt = GameState.current().now();
        lastFor = c.forId();
        lastLevel = c.level();
        cachedKey = key;
        lastFromFile = false;
        loadedFor = c.forId();
        staleKey = null;
        RoadmapStore.save(c.forId(), c.steps(), key, c.level(), lastBuilt);
        BeamCache.save();
        return c.steps();
    }

    /**
     * Works a road map out from {@code in} with its tuning in force ({@link Tuning#with}); null
     * outside the game. {@code job} (null for the J menu's map) gets the progress and can stop
     * the run ({@link Cancelled}). Safe off the game thread, as {@link #build} always was.
     */
    static Computed compute(Inputs in, Job job) {
        Job before = JOB.get();
        JOB.set(job);
        try {
            return Tuning.with(in.tuning(), () -> computeTuned(in, job));
        } finally {
            if (before == null) {
                JOB.remove();
            } else {
                JOB.set(before);
            }
        }
    }

    private static Computed computeTuned(Inputs in, Job job) {
        GameState state = GameState.current();
        if (!state.inGame()) {
            return null;
        }
        String forId = state.characterId();       // a switch mid-build must not publish this map
        // The items you chose (Locks), at any level: a set around a piece above your level is
        // planned for the level it needs (the highest chosen item's), which then sets the skill
        // points and the other slots' candidates. Pieces that cannot be worn together there:
        // the map as without them, and why (problem)
        List<ItemData> lockedAll = Locks.resolve(state, in.around());
        int planLevel = Math.max(state.level(), lockedAll.stream().mapToInt(ItemData::level).max().orElse(0));
        Locks.Problem problem0 = Locks.problem(lockedAll, planLevel);
        int level = problem0 == null ? planLevel : state.level();
        long budget = in.tuning().budget();
        // what you have (worn, carried, banked): it costs nothing under a budget
        Map<String, Integer> ownedCount = ownedCounts(state);
        java.util.Set<String> owned = ownedCount.keySet();
        // (a ring by name is no proof: the second hand's copy is a buy). A price that is only
        // history over the budget is no skip: it costs the piece a little in the ranking
        // (overBudget) instead, since the market may well have it cheaper now
        java.util.function.Predicate<ItemData> affordable = info -> budget <= 0
                || info.slot() != Slot.RING && owned.contains(info.name())
                || cost(state, info) >= 0 && (cost(state, info) <= budget || uncertainPrice(state, info));
        java.util.function.Predicate<ItemData> overBudget = info -> budget > 0
                && !(info.slot() != Slot.RING && owned.contains(info.name())) && cost(state, info) > budget;
        Klass cls = state.classType();
        Slot weapon = Slot.weaponOf(cls);
        List<Slot> slots = new ArrayList<>(List.of(SLOTS));
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

        List<String> preferred = Rules.list("shopping", "preferredSkills");
        List<Integer> levels = in.nowOnly() ? List.of(level) : milestones(level);
        int last = levels.get(levels.size() - 1);
        // tiers and sources go by each milestone's level (badTiers, badSources): the pool holds
        // what the last milestone allows, each milestone filters again. The off-skill limit is
        // only checked there, against that milestone's skill points: a piece asking for 60
        // Dexterity costs nothing off-skill where the spread holds 80 of it anyway
        List<String> badTiersLast = badTiers(last);

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

        Map<String, Integer> abilities = state.abilityPlan();
        java.util.Set<String> placesShown = new java.util.HashSet<>();
        List<Step> steps = new ArrayList<>();
        int prev = level - 1;
        Build.Plan plan = Build.plan();
        Map<String, Damage.Piece> baseline = plan == null ? null : new LinkedHashMap<>(plan.pieces());
        // what you wear now, for the "worn → pick" gain of each milestone (Step.vsWorn)
        Map<String, Damage.Piece> wornNow = plan == null ? null
                : new LinkedHashMap<>(plan.worn() != null ? plan.worn() : plan.pieces());
        Map<String, String> placed = new LinkedHashMap<>();     // slot type name → build slot of its latest pick
        // the last milestone's set's skill points; before the first, the ones you placed (when the
        // Character Info menu showed them), so "now" does not quietly take your Defence away
        int[] prevAlloc = assignedPoints(state);
        // piece key → its row number and reasons from the milestone that first picked it
        Map<String, Double> firstGain = new HashMap<>();
        Map<String, List<String>> firstWhy = new HashMap<>();
        // The items you chose to build around (Locks): held in their slots on every milestone, the
        // rest fitted to them. When they cannot be worn together the map is built as without them
        // and says why (aroundProblem)
        phase(0);
        List<ItemData> locked = lockedAll;
        Locks.Problem problem = problem0;
        if (problem != null) {
            locked = List.of();
        }
        java.util.Set<String> fixed = new java.util.LinkedHashSet<>();     // build slots the chosen items hold
        java.util.Set<Slot> lockedKinds = java.util.EnumSet.noneOf(Slot.class);   // slot kinds with nothing left to pick
        long lockedRings = locked.stream().filter(i -> Locks.kind(i) == Slot.RING).count();
        for (ItemData info : locked) {
            Slot k = Locks.kind(info);
            if (k != Slot.RING || lockedRings >= Locks.RINGS) {
                lockedKinds.add(k);
            }
        }
        if (!locked.isEmpty()) {
            phase(3);
        }
        List<Gear> lockRows = locked.isEmpty() ? List.of() : lockIn(locked, baseline, placed, fixed, level);
        boolean ringFixed = fixed.contains("RING1") || fixed.contains("RING2");
        java.util.Set<String> lockNames = new java.util.HashSet<>();
        locked.forEach(i -> lockNames.add(i.name()));
        java.util.Set<Integer> budgetCut = new java.util.TreeSet<>();
        List<Candidate> ranked0 = List.of();
        if (job == null) {
            progressTotal = levels.size();
            progressDone = 0;
        } else {
            job.total = levels.size() + (in.builds() > 0 ? 1 : 0);
            job.done = 0;
        }
        for (int m : levels) {
            if (m <= prev) {
                continue;
            }
            phase(1);
            int earnedThen = Math.max(0, (Math.min(m, 101) - 1) * 2);
            int maxOff = maxOff(m);
            List<String> badTiers = badTiers(m);
            List<String> badSources = badSources(m);
            // today's Trade Market listings only matter for what you would buy soon, not 70 levels on
            boolean listingsMatter = m - level <= (int) Rules.sub("shopping", "aheadLevels", 5);
            List<Gear> gear = new ArrayList<>(steps.isEmpty() ? lockRows : List.of());   // the chosen items: rows of the first milestone
            boolean preferMarket = Rules.sub("shopping", "preferTradeMarket", 1) > 0;
            // With a build plan, everything is judged by the damage model: the % the build gains
            // with the piece in, starting from the best build you own and adding each pick
            double baseObj = 0;
            double baseJ = 0;                                   // baseObj with the respec charge (judged)
            int[] allocM = null;
            if (baseline != null) {
                Build.Eval be = Build.allocate(baseline, m, earnedThen);
                if (be != null) {
                    baseObj = be.result().objective();
                    baseJ = judged(be, prevAlloc, m);
                    allocM = be.allocation();
                }
            }
            Map<Slot, List<ItemData>> pools = new java.util.EnumMap<>(Slot.class);   // the best few per slot type
            Map<String, Damage.Piece> startSet = baseline == null ? null : new LinkedHashMap<>(baseline);
            Map<String, String> placedStart = new LinkedHashMap<>(placed);
            List<ItemData> ringPool = new ArrayList<>();     // this milestone's best rings, for the other hand
            List<ItemData> setGear = new ArrayList<>();      // set pieces you could get here, for whole sets
            for (Slot t : slots) {
                if (lockedKinds.contains(t.isWeapon() ? Slot.WEAPON : t)) {
                    continue;                               // a chosen item holds it
                }
                ItemData top = null;
                boolean emptyBuild = false;                // the pick fills an empty hand (no score to compare)
                double topScore = allocM != null ? 0 : best.getOrDefault(t, 0.0) + 0.5;
                double topReal = Double.NaN;            // the pick's own gain (topScore may be lowered for availability)
                String topSlot = null;
                ItemData alt = null;
                double altScore = topScore;
                double altReal = Double.NaN;            // the alternative's own gain
                List<Object[]> pool = new ArrayList<>();      // {info, % gain} for the alternatives
                List<Object[]> ranked = new ArrayList<>();    // {info, % gain, gain for availability, slot, its factor}
                int peek = (int) Rules.sub("shopping", "aheadLevels", 5);
                // what you already wear is no upgrade, even when a better roll of it would score higher
                java.util.Set<String> wornNames = new java.util.HashSet<>();
                for (ItemStack w : AutoAdvice.worn(t)) {
                    if (!w.isEmpty()) {
                        wornNames.add(ItemJson.clean(w.getHoverName().getString()).trim().toLowerCase(java.util.Locale.ROOT));
                    }
                }
                // a later milestone only weighs gear made for around that level: a piece 20 levels
                // below it is not the pick there, and trying every piece up to Lv 120 took seconds
                int floor = m == level ? Integer.MIN_VALUE : m - (int) Rules.sub("shopping", "milestoneWindow", 20);
                // what this milestone's set already holds per skill (its points and the gear's): a
                // requirement within it asks for nothing more
                double[] cover = allocM == null ? null : Build.cover(baseline, allocM);
                for (ItemData info : cands.getOrDefault(t, List.of())) {
                    if (wornNames.contains(info.name().toLowerCase(java.util.Locale.ROOT))
                            || info.requirements().level() < floor || badTiers.contains(info.tier().name())) {
                        trace(m, t, info, "skip: worn / below window / tier");
                        continue;
                    }
                    // the weapon decides the element, so it is never thrown out for its skills
                    int off = t.isWeapon() ? 0 : offPoints(info, preferred, cover);
                    if (off > maxOff) {
                        trace(m, t, info, "skip: off-skill " + off + " points past the set's > " + maxOff);
                        continue;
                    }
                    if (!affordable.test(info)) {
                        // over the budget, or a tradable piece with no known price: never suggested under one
                        trace(m, t, info, "skip: over the budget");
                        budgetCut.add(m);
                        continue;
                    }
                    // a few levels ahead only for the comparison panel's alternatives
                    boolean future = info.requirements().level() > m;
                    if (info.requirements().level() > m + peek || future && allocM == null) {
                        trace(m, t, info, "skip: above the milestone");
                        continue;
                    }
                    // a quest the piece asks for that you have not done: not before its level
                    // (Sodeta Boots want Lost Soles); from there on it stays, with a caution
                    QuestBook.Entry quest = Shopping.questPending(state, info);
                    if (quest != null && quest.level() > m) {
                        trace(m, t, info, "skip: quest " + quest.name() + " is Lv " + quest.level());
                        continue;
                    }
                    int total = info.requirements().skills().stream().mapToInt(r -> r.points()).sum();
                    if (total > earnedThen) {
                        trace(m, t, info, "skip: needs " + total + " points > " + earnedThen);
                        continue;
                    }
                    if (!future && info.set().isPresent()) {
                        // a set piece alone may rank below the best forty and still pay with its set
                        // by where it comes from in the world (a listed piece's source reads TRADE_MARKET)
                        if (!Shopping.excluded(info, badSources)) {
                            setGear.add(info);
                        }
                    }
                    double sc;
                    String slotUsed = null;
                    if (allocM != null) {
                        sc = Double.NaN;
                        Damage.Piece cand = Damage.piece(info);
                        for (String slot : t == Slot.RING ? new String[] {"RING1", "RING2"}
                                : new String[] {Build.slotOf(t, 0)}) {
                            // the piece an earlier milestone already put in this slot is no new pick:
                            // its only "gain" would be the community nudge
                            Damage.Piece there = slot == null ? null : baseline.get(slot);
                            if (slot == null || fixed.contains(slot) || there != null && there.key().equals(cand.key())) {
                                continue;
                            }
                            // Wynncraft refuses a second piece of an exclusive group (Ornate Shadow, the Hives …)
                            String clash = Exclusive.clash(baseline, slot, Exclusive.group(info));
                            if (clash != null) {
                                trace(m, t, info, "skip in " + slot + ": " + Exclusive.group(info) + " with " + clash);
                                continue;
                            }
                            // a weapon sets the element, so the points must be spread anew for it:
                            // with the old spread every weapon unlike the one in the build ranked low
                                    tick();
                            double v = t.isWeapon() ? full(baseline, slot, cand, m, earnedThen)
                                    : Build.trySlot(baseline, allocM, slot, cand, m, earnedThen);
                            if (!Double.isNaN(v) && (Double.isNaN(sc) || v > sc)) {
                                sc = v;
                                slotUsed = slot;
                            }
                        }
                        if (Double.isNaN(sc) || baseObj <= 0 && !t.isWeapon()) {
                            if (trace != null) {
                                // the allocator's reason, for the first slot it could take
                                String why = null;
                                for (String slot : t == Slot.RING ? new String[] {"RING1", "RING2"} : new String[] {Build.slotOf(t, 0)}) {
                                    if (slot != null) {
                                        Map<String, Damage.Piece> trial = new LinkedHashMap<>(baseline);
                                        trial.put(slot, cand);
                                        why = Build.whyNot(trial, earnedThen);
                                        if (why == null) {
                                            break;
                                        }
                                    }
                                }
                                trace(m, t, info, "skip: " + (why != null ? why
                                        : baseObj <= 0 ? "nothing to score against yet (no weapon in the build)" : "does not fit the build"));
                            }
                            continue;
                        }
                        // with no weapon yet (a new character) the build scores nothing: weapons
                        // are ranked by their raw score, the rest once one is in
                        sc = baseObj <= 0 ? sc : (sc / baseObj - 1) * 100;
                        // a nudge toward what players actually pick (rules.community)
                        sc += Rules.communityBonus(info.name());
                        // and away from what needs a skill reset in town
                        if (slotUsed != null && Build.needsRespec(baseline, prevAlloc, slotUsed, Damage.piece(info), earnedThen)) {
                            sc -= Rules.sub("shopping", "respecPenaltyPercent", 3);
                        }
                        if (Shopping.excluded(info, badSources) && !owned.contains(info.name())) {
                            trace(m, t, info, "skip: source " + Shopping.origin(info));
                            continue;               // never offer what cannot be obtained
                        }
                        trace(m, t, info, String.format(java.util.Locale.ROOT, "ranked %.2f%% in %s", sc, slotUsed)
                                + (trace != null && slotUsed != null ? " | " + parts(baseline, slotUsed, Damage.piece(info), m, earnedThen) : ""));
                        pool.add(new Object[] {info, sc, slotUsed});
                        if (future) {
                            continue;                   // shown as an alternative, not picked yet
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
                            factor = availability(state.price(info.name()));
                        }
                    }
                    if (allocM != null && overBudget.test(info)) {
                        factor *= Rules.sub("shopping", "overBudgetFactor", 0.8);   // its old price is over the budget
                    }
                    double eff = scaled(sc, factor);
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
                if (allocM != null) {
                    // trySlot keeps the skill points and only moves what the piece needs, so it ranks
                    // roughly; the best few are judged again with the points spread anew (as the
                    // comparison panel does), or the pick could show below its own alternatives
                    ranked.sort((p1, p2) -> Double.compare((double) p2[2], (double) p1[2]));
                    int keep = (int) Rules.sub("roadmap", "rescore", 6);
                    if (t == Slot.RING) {
                        ranked.stream().limit(keep).forEach(r -> ringPool.add((ItemData) r[0]));
                    }
                    pools.put(t, ranked.stream().limit((long) Rules.sub("roadmap", "jointPool", 40))
                            .map(r -> (ItemData) r[0]).toList());
                    int market = 0;
                    int other = 0;
                    String altSlot = null;
                    List<Object[]> cleared = new ArrayList<>();     // {info, eff, gain, slot}: market picks over the bar
                    for (Object[] r : ranked) {
                        ItemData info = (ItemData) r[0];
                        boolean side = preferMarket && !Shopping.tradable(info);
                        if (side ? other++ >= keep : market++ >= keep) {
                            trace(m, t, info, "skip: not in the best " + keep);
                            continue;
                        }
                        String slotUsed = (String) r[3];
                        double sc = (double) r[1];
                        double eff = (double) r[2];
                        if (slotUsed != null) {
                            Build.Eval fe = fullEval(baseline, slotUsed, Damage.piece(info), m, earnedThen);
                            if (fe == null || baseJ <= 0 && !t.isWeapon()) {
                                if (trace != null) {
                                    Map<String, Damage.Piece> trial = new LinkedHashMap<>(baseline);
                                    trial.put(slotUsed, Damage.piece(info));
                                    String why = Build.whyNot(trial, earnedThen);
                                    trace(m, t, info, "skip: " + (why != null ? why
                                            : fe == null ? "no allocation" : "nothing to score against yet (no weapon in the build)"));
                                }
                                continue;
                            }
                            if (baseJ <= 0) {
                                // the first weapon of an empty build: by its raw score, no bar to clear
                                eff = judged(fe, prevAlloc, m) * (double) r[4];
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
                            double full = (judged(fe, prevAlloc, m) / baseJ - 1) * 100 + Rules.communityBonus(info.name());
                            if (Build.needsRespec(baseline, prevAlloc, slotUsed, Damage.piece(info), earnedThen)) {
                                full -= Rules.sub("shopping", "respecPenaltyPercent", 3);
                            }
                            // for the choice only (the row shows the gain itself): an untradable
                            // piece from a dungeon, its merchant, a raid or a quest takes a run of
                            // its own, so it must clear the bar by this much more (Admiral wants
                            // Redbeard's Hair); what you have needs no buy and counts a little extra
                            double pickBy = full;
                            if (owned.contains(info.name())) {
                                pickBy += Rules.sub("shopping", "ownedBonusPercent", 2);
                            } else if (Shopping.effort(info)) {
                                pickBy -= Rules.sub("shopping", "untradableEffortPercent", 5);
                            }
                            if (pickBy < Rules.sub("shopping", "minGainPercent", 10)
                                    && pickBy < Build.gainThreshold(baseline.get(slotUsed), m)) {
                                trace(m, t, info, String.format(java.util.Locale.ROOT, "skip: full %.2f%% (%.2f%% to choose by) under the bar %.2f%% (slot %s holds Lv %d)",
                                        full, pickBy, Build.gainThreshold(baseline.get(slotUsed), m), slotUsed,
                                        baseline.get(slotUsed) == null ? -1 : baseline.get(slotUsed).level()));
                                continue;
                            }
                            trace(m, t, info, String.format(java.util.Locale.ROOT, "judged %.2f%% in %s", full, slotUsed));
                            eff = scaled(pickBy, (double) r[4]);     // keeps the "no listings" factor
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
                        Object[] pick = closest(cleared, topScore, owned, listingsMatter);
                        if (pick != null && pick[0] != top) {
                            trace(m, t, (ItemData) pick[0], "tie-break over " + top.name());
                            top = (ItemData) pick[0];   // topScore stays the best's: as good a buy
                            topReal = (double) pick[2];
                            topSlot = (String) pick[3];
                        }
                    }
                    if (top == null && alt != null) {
                        topSlot = altSlot;
                    }
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
                    continue;
                }
                String source = Shopping.sourceOf(top);
                if (source == null || Shopping.excluded(top, badSources) && !owned.contains(top.name())) {
                    continue;                       // what cannot be had, unless you have it already
                }
                String price = Prices.buyText(top.name());
                String altSource = alt == null ? null : Shopping.sourceOf(alt);
                double gain = allocM != null ? (Double.isNaN(topReal) ? topScore : topReal) : topScore - best.getOrDefault(t, 0.0);
                if (emptyBuild) {
                    gain = Double.NaN;              // against an empty hand a % means nothing
                }
                List<String> detail = new ArrayList<>();
                Build.Eval before = allocM != null ? Build.allocate(baseline, m, earnedThen) : null;
                // At least three other options for the slot, best first, with what each costs you
                List<String> others = new ArrayList<>();
                ItemData chosen = top;
                pool.stream()
                        .filter(o -> o[0] != chosen)
                        .sorted((p1, p2) -> Double.compare((double) p2[1], (double) p1[1]))
                        .limit((long) Rules.sub("roadmap", "alternatives", 4))
                        .forEach(o -> {
                            ItemData gi = (ItemData) o[0];
                            state.requestPrices(List.of(gi.name()));
                            String pr = Prices.buyText(gi.name());
                            String pShort = pr == null ? (Shopping.tradable(gi) ? "" : " · " + L.t("wynnlabs.road.untradable"))
                                    : " · " + (pr.contains(" (") ? pr.substring(0, pr.indexOf(" (")) : pr);
                            List<String> c = Cautions.of(gi);
                            others.add(L.t("wynnlabs.road.alternative", gi.name(), gi.requirements().level(), pShort,
                                    Math.round((double) o[1])) + (c.isEmpty() ? "" : " · " + String.join(", ", c)));
                        });
                // The comparison panel: the pick first, then the best others (up to five in all),
                // weaker ones too: a cheaper piece a few % behind is still a choice
                List<Option> options = new ArrayList<>();
                if (before != null) {
                    String topS = topSlot != null ? topSlot : Build.slotOf(t, 0);
                    Option first = topS == null ? null : option(top, topS, baseline, before, m, earnedThen);
                    if (first != null) {
                        options.add(first);
                    }
                    pool.stream()
                            .filter(o -> o[0] != chosen && o[2] != null && panelAllows((ItemData) o[0], m))
                            .sorted((p1, p2) -> Double.compare((double) p2[1], (double) p1[1]))
                            .limit((long) Rules.sub("roadmap", "alternatives", 6))
                            .forEach(o -> {
                                Option op = option((ItemData) o[0], (String) o[2], baseline, before, m, earnedThen);
                                if (op != null) {
                                    options.add(op);
                                }
                            });
                    // the best few may all sit above your level: keep some you can wear today in
                    // the panel too, even when they lose a little (the panel says so in red)
                    int nowMin = (int) Rules.sub("roadmap", "alternativesNow", 3);
                    long nowHave = options.stream().filter(op -> op.info().requirements().level() <= level).count();
                    if (nowHave < nowMin) {
                        java.util.Set<String> shown = new java.util.HashSet<>();
                        options.forEach(op -> shown.add(op.info().name()));
                        pool.stream()
                                .filter(o -> o[0] != chosen && o[2] != null && panelAllows((ItemData) o[0], m)
                                        && ((ItemData) o[0]).requirements().level() <= level
                                        && !shown.contains(((ItemData) o[0]).name()))
                                .sorted((p1, p2) -> Double.compare((double) p2[1], (double) p1[1]))
                                .limit(nowMin - nowHave)
                                .forEach(o -> {
                                    Option op = option((ItemData) o[0], (String) o[2], baseline, before, m, earnedThen);
                                    if (op != null) {
                                        options.add(op);
                                    }
                                });
                    }
                }
                gear.add(new Gear(t.name(), top, gain, Shopping.where(source), price, alt,
                        altSource == null ? null : Shopping.where(altSource), detail, others, options));
                if (allocM != null) {
                    baseline.put(topSlot != null ? topSlot : Build.slotOf(t, 0), Damage.piece(top));
                    place(placed, t.name(), topSlot != null ? topSlot : Build.slotOf(t, 0));
                    Build.Eval be = Build.allocate(baseline, m, earnedThen);
                    if (before != null && be != null) {
                        detail.addAll(explain(before, be));
                    }
                    if (be != null) {
                        baseObj = be.result().objective();
                        baseJ = judged(be, prevAlloc, m);
                        allocM = be.allocation();
                    }
                } else {
                    best.put(t, topScore);
                }
            }
            // Slot by slot, an early pick never sees the later ones: the best few of every slot
            // are tried again against the whole set, until no swap helps
            if (allocM != null && baseline != null) {
                phase(2);
                beamSearch(startSet, baseline, placed, pools, gear, m, earnedThen, prevAlloc, fixed);
                if (fixed.isEmpty()) {
                    exactSearch(baseline, placed, pools, gear, m, earnedThen, prevAlloc);   // its ring pairs know no chosen hand
                }
                wholeSets(startSet, baseline, placed, pools, gear, m, earnedThen, prevAlloc,
                        setPieces(cands, setGear, owned, m, fixed), ownedCount, setGear, fixed);
                refine(baseline, placed, pools, gear, m, earnedThen, prevAlloc, fixed);
                Build.Eval be = Build.allocate(baseline, m, earnedThen);
                if (be != null) {
                    baseObj = be.result().objective();
                    baseJ = judged(be, prevAlloc, m);
                    allocM = be.allocation();
                }
            }
            // The other hand: Wynncraft takes the same ring twice, and one pick per slot type left
            // the second ring at what you wear now all the way to Lv 120
            if (allocM != null && baseline != null && !ringFixed) {         // a chosen ring holds the other hand
                Gear second = otherRing(baseline, placed, ringPool, m, earnedThen, baseObj, badSources, affordable);
                if (second != null) {
                    gear.add(second);
                    Build.Eval be = Build.allocate(baseline, m, earnedThen);
                    if (be != null) {
                        baseObj = be.result().objective();
                        allocM = be.allocation();
                    }
                }
            }
            // Community targets (rules.roadmap.targets): e.g. the Adventurer's set at 26-29
            for (String name : Rules.list("roadmap", "targets")) {
                ItemData info = findGear(name);
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
                        baseline.put(slot, Damage.piece(info));
                        place(placed, slotName, slot);
                    }
                }
            }
            // The budget: the buys this milestone's set holds (what you own and the chosen items
            // cost nothing) stay within it, else its own picks go back to what it started with
            if (budget > 0 && baseline != null && startSet != null) {
                phase(4);
                if (spend(state, baseline, fixed, owned) > budget) {
                    baseline.clear();
                    baseline.putAll(startSet);
                    gear.removeIf(g -> !lockRows.contains(g));
                    placed.clear();
                    placed.putAll(placedStart);
                    budgetCut.add(m);
                }
            }
            if (m == level && in.builds() > 0 && baseline != null && startSet != null) {
                phase(5);
                ranked0 = rank(startSet, baseline, pools, fixed, level, earnedThen, in.builds(), budget, state, owned);
            }
            List<String> abil = new ArrayList<>();
            for (var e : abilities.entrySet()) {
                if (e.getValue() > prev && e.getValue() <= m) {
                    abil.add(e.getKey());
                }
            }
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
            int sp = Math.max(0, (Math.min(m, 101) - Math.min(Math.max(prev, level), 101)) * 2);
            Versus sinceNow = baseline == null ? new Versus(Map.of(), Map.of())
                    : versus(baseline, wornNow, placed, m, earnedThen);
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
            // the spread shown and carried on: the greedy one polished by moving points between
            // skills (once per milestone; the candidates above are ranked with the quick one)
            Build.Eval set = baseline == null ? null : Build.allocateBest(baseline, m, earnedThen);
            if (set != null) {
                prevAlloc = set.allocation();
            }
            steps.add(new Step(m, gear, abil, places, m == level ? 0 : sp, rowGain, rowWhy,
                    baseline == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(baseline)),
                    set));
            prev = m;
            if (job == null) {
                progressDone++;
            } else {
                job.done++;
                if (set != null && Double.isNaN(job.best)) {
                    job.best = judged(set, null, m);
                }
            }
        }
        return new Computed(steps, ranked0, locked.stream().map(ItemData::name).toList(), problem, budgetCut, forId, level);
    }

    /**
     * What you have, by name and how many: worn, carried and banked where you can reach it (a buy
     * you need not make; a ring you have twice can fill both hands).
     */
    static Map<String, Integer> ownedCounts(GameState state) {
        Map<String, Integer> out = new HashMap<>();
        try {
            for (Slot t : Slot.values()) {
                if (t.isArmour() || t.isAccessory() || t.klass() != null) {
                    for (ItemStack w : AutoAdvice.worn(t)) {
                        if (!w.isEmpty()) {
                            out.merge(ItemJson.clean(w.getHoverName().getString()).trim(), 1, Integer::sum);
                        }
                    }
                }
            }
            java.util.Set<ItemStack> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (Slot t : List.of(Slot.RING, Slot.BRACELET, Slot.NECKLACE)) {
                seen.addAll(AutoAdvice.worn(t));            // a worn accessory sits in the bag too: once
            }
            for (ItemStack s : state.inventory()) {
                if (!s.isEmpty() && !seen.contains(s)) {
                    out.merge(ItemJson.clean(s.getHoverName().getString()).trim(), s.getCount(), Integer::sum);
                }
            }
            // only banks you can open from here: a copy on another character's page is no
            // buy you can skip
            for (Vault.Group g : Vault.groups()) {
                if (g.reachable()) {
                    int here = g.places().stream().filter(s -> Vault.reachable(s.page())).mapToInt(Vault.Stored::count).sum();
                    out.merge(g.name(), Math.max(1, here), Integer::sum);
                }
            }
        } catch (Throwable t) {
            // a menu changed under us: what was read so far
        }
        return out;
    }

    /**
     * What a piece costs on the Trade Market under a budget: what buying it is likely to cost
     * (Prices.Price#buyPrice: the middle of the market for an old price), 0 for one that cannot
     * be traded and has none (a quest reward), -1 for a tradable piece with no known price
     * (never suggested under a budget).
     */
    static double cost(GameState state, ItemData info) {
        Prices.Price p = state.price(info.name());
        Double v = p == null ? null : p.buyPrice();
        if (v != null) {
            return v;
        }
        return Shopping.tradable(info) ? -1 : 0;
    }

    /** A tradable piece whose price is only history: nothing listed now, or only old listings. */
    static boolean uncertainPrice(GameState state, ItemData info) {
        Prices.Price p = state.price(info.name());
        return Shopping.tradable(info) && p != null && p.typical() != null && p.uncertain();
    }

    /**
     * The pick among {@code cleared} ({info, eff, gain, slot}, every market piece over the bar)
     * within rules.shopping.costTieBreakPercent (15 %) of the best: what you have first, then,
     * when {@code prices} (bought soon), the cheaper; else the best. Null when nothing is close.
     */
    private static Object[] closest(List<Object[]> cleared, double best, java.util.Set<String> owned, boolean prices) {
        double within = Rules.sub("shopping", "costTieBreakPercent", 15) / 100.0;
        if (within <= 0 || best <= 0) {
            return null;
        }
        Object[] out = null;
        for (Object[] c : cleared) {
            if ((double) c[1] < best * (1 - within)) {
                continue;
            }
            if (out == null || better(c, out, owned, prices)) {
                out = c;
            }
        }
        return out;
    }

    /** {@link #closest}'s order: what you have, then the cheaper (when {@code prices}), then the higher gain. */
    private static boolean better(Object[] a, Object[] b, java.util.Set<String> owned, boolean prices) {
        ItemData ia = (ItemData) a[0];
        ItemData ib = (ItemData) b[0];
        boolean oa = ia.slot() != Slot.RING && owned.contains(ia.name());
        boolean ob = ib.slot() != Slot.RING && owned.contains(ib.name());
        if (oa != ob) {
            return oa;
        }
        if (prices && !oa) {
            double ca = Shopping.buyCost(ia);
            double cb = Shopping.buyCost(ib);
            if (ca != cb) {
                return ca < cb;
            }
        }
        return (double) a[1] > (double) b[1];
    }

    /** What the buys in {@code set} cost: every piece outside {@code fixed} that you do not have. */
    static double spend(GameState state, Map<String, Damage.Piece> set, java.util.Set<String> fixed, java.util.Set<String> owned) {
        double sum = 0;
        for (var e : set.entrySet()) {
            Damage.Piece p = e.getValue();
            if (p == null || fixed.contains(e.getKey()) || p.stack() != null
                    || !e.getKey().startsWith("RING") && owned.contains(p.name())) {
                continue;
            }
            ItemData info = findGear(p.name());
            double c = info == null ? 0 : cost(state, info);
            if (info != null && uncertainPrice(state, info)) {
                // an old price is a soft limit (the ranking pays for it): the set counts the
                // lowest one seen, or a single such piece would undo the whole milestone
                c = state.price(info.name()).typical();
            }
            sum += c < 0 ? Double.POSITIVE_INFINITY : c;
        }
        return sum;
    }

    /** How many slots outside {@code fixed} two sets hold different pieces in, the two rings as a pair either way round. */
    static int distance(Map<String, Damage.Piece> a, Map<String, Damage.Piece> b, java.util.Set<String> fixed) {
        int d = 0;
        for (String slot : BEAM_ORDER) {
            if (slot.startsWith("RING") || fixed.contains(slot)) {
                continue;
            }
            if (!nameOf(a.get(slot)).equals(nameOf(b.get(slot)))) {
                d++;
            }
        }
        List<String> ra = new ArrayList<>();
        List<String> rb = new ArrayList<>();
        for (String slot : new String[] {"RING1", "RING2"}) {
            if (!fixed.contains(slot)) {
                ra.add(nameOf(a.get(slot)));
                rb.add(nameOf(b.get(slot)));
            }
        }
        for (String r : ra) {
            if (!rb.remove(r)) {
                d++;
            }
        }
        return d;
    }

    private static String nameOf(Damage.Piece p) {
        return p == null ? "-" : p.name();
    }

    /** How far two builds must differ, in slots outside the chosen ones, to be listed apart. */
    static final int MIN_DISTANCE = 2;
    /** Builds within this share of the best count as "as good" for the Cheapest label. */
    static final double CHEAP_WITHIN = 0.03;

    /**
     * Whole builds at your level for the window, best first: the set the milestone found, the
     * sets one or two swaps from it (each slot's best three from the milestone's pools), each
     * worn whole (skill points spread anew) and within the budget, the chosen slots never
     * touched. Diversity: going down by score, a build is kept only when it differs from every
     * build kept before in at least {@link #MIN_DISTANCE} slots outside the chosen ones (the two
     * rings as a pair), so the list never holds near-copies that swap one ring. At most
     * {@code n}; labelled by {@link #label}.
     */
    private static List<Candidate> rank(Map<String, Damage.Piece> start, Map<String, Damage.Piece> best,
            Map<Slot, List<ItemData>> pools, java.util.Set<String> fixed, int level, int earned, int n, long budget,
            GameState state, java.util.Set<String> owned) {
        Map<String, List<Damage.Piece>> options = new LinkedHashMap<>();
        for (String slot : BEAM_ORDER) {
            if (fixed.contains(slot)) {
                continue;
            }
            Slot t = slot.startsWith("RING") ? Slot.RING : slot.equals("WEAPON") ? null : Slot.valueOf(slot);
            List<ItemData> pool = t == null ? weaponPool(pools) : pools.get(t);
            List<Damage.Piece> top = new ArrayList<>();
            if (start.get(slot) != null && !nameOf(start.get(slot)).equals(nameOf(best.get(slot)))) {
                top.add(start.get(slot));                       // what the slot held before: often a cheaper way
            }
            for (ItemData info : pool == null ? List.<ItemData>of() : pool) {
                if (top.size() >= 3) {
                    break;
                }
                if (info.requirements().level() <= level && !info.name().equals(nameOf(best.get(slot)))) {
                    top.add(Damage.piece(info));
                }
            }
            options.put(slot, top);
        }
        List<Map<String, Damage.Piece>> sets = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.function.Consumer<Map<String, Damage.Piece>> add = s -> {
            if (seen.add(setKey(s)) && !clashes(s)) {
                sets.add(s);
            }
        };
        add.accept(new LinkedHashMap<>(best));
        List<String> slots = new ArrayList<>(options.keySet());
        for (int i = 0; i < slots.size(); i++) {
            for (Damage.Piece a : options.get(slots.get(i))) {
                Map<String, Damage.Piece> one = new LinkedHashMap<>(best);
                one.put(slots.get(i), a);
                add.accept(one);
                for (int j = i + 1; j < slots.size(); j++) {
                    for (Damage.Piece b : options.get(slots.get(j))) {
                        Map<String, Damage.Piece> two = new LinkedHashMap<>(one);
                        two.put(slots.get(j), b);
                        add.accept(two);
                    }
                }
            }
        }
        List<Candidate> all = new ArrayList<>();
        for (Map<String, Damage.Piece> s : sets) {
            tick();
            double price = spend(state, s, fixed, owned);
            if (budget > 0 && price > budget || Double.isInfinite(price) && budget > 0) {
                continue;
            }
            Build.Eval e = Build.allocate(s, level, earned);
            if (e == null) {
                continue;
            }
            all.add(new Candidate(s, e, judged(e, null, level), Double.isInfinite(price) ? Double.NaN : price, List.of(), fixed));
        }
        all.sort((x, y) -> Double.compare(y.score(), x.score()));
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : all) {
            if (out.size() >= n) {
                break;
            }
            boolean apart = true;
            for (Candidate k : out) {
                apart &= distance(c.set(), k.set(), fixed) >= MIN_DISTANCE;
            }
            if (apart) {
                out.add(c);
                Job j = JOB.get();
                if (j != null && (Double.isNaN(j.best) || c.score() > j.best)) {
                    j.best = c.score();
                }
            }
        }
        return label(out);
    }

    /**
     * The labels, each on one build only and only where it sets that build apart: "best" on the
     * first; "cheap" on the build with the strictly lowest price among those within
     * {@link #CHEAP_WITHIN} of the best score; "tanky" on the strictly highest effective health;
     * "fast" on the strictly highest walk speed (above 0). A build keeps one label, the first
     * of that order it earns; a tie gives none.
     */
    static List<Candidate> label(List<Candidate> builds) {
        if (builds.isEmpty()) {
            return builds;
        }
        String[] labels = new String[builds.size()];
        labels[0] = "best";
        double top = builds.get(0).score();
        int cheap = unique(builds, c -> c.score() >= top * (1 - CHEAP_WITHIN) && !Double.isNaN(c.price()) ? -c.price() : Double.NaN);
        if (cheap > 0 && labels[cheap] == null && builds.get(cheap).price() < builds.get(0).price()) {
            labels[cheap] = "cheap";
        }
        int tanky = unique(builds, c -> c.eval().result().ehp());
        if (tanky > 0 && labels[tanky] == null) {
            labels[tanky] = "tanky";
        }
        int fast = unique(builds, c -> c.eval().result().speed() > 0 ? c.eval().result().speed() : Double.NaN);
        if (fast > 0 && labels[fast] == null) {
            labels[fast] = "fast";
        }
        List<Candidate> out = new ArrayList<>();
        for (int i = 0; i < builds.size(); i++) {
            Candidate c = builds.get(i);
            out.add(new Candidate(c.set(), c.eval(), c.score(), c.price(), labels[i] == null ? List.of() : List.of(labels[i]), c.fixed()));
        }
        return out;
    }

    /** The one build with the highest value (NaN: left out); -1 when none or two share it. */
    private static int unique(List<Candidate> builds, java.util.function.ToDoubleFunction<Candidate> value) {
        int at = -1;
        double best = Double.NEGATIVE_INFINITY;
        boolean tie = false;
        for (int i = 0; i < builds.size(); i++) {
            double v = value.applyAsDouble(builds.get(i));
            if (Double.isNaN(v)) {
                continue;
            }
            if (v > best + 1e-9) {
                best = v;
                at = i;
                tie = false;
            } else if (Math.abs(v - best) <= 1e-9) {
                tie = true;
            }
        }
        return tie ? -1 : at;
    }

    static JsonObject json() {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Step s : build()) {
            JsonObject j = new JsonObject();
            j.addProperty("level", s.level());
            JsonArray g = new JsonArray();
            for (Gear x : s.gear()) {
                JsonObject gj = new JsonObject();
                gj.addProperty("slot", x.slot());
                gj.addProperty("name", x.info().name());
                gj.addProperty("level", x.info().requirements().level());
                gj.addProperty("tier", x.info().tier().name());
                gj.addProperty("gain", Double.isNaN(x.gain()) ? null : Math.round(x.gain() * 10) / 10.0);
                if (Double.isNaN(x.gain())) {
                    gj.addProperty("target", true);
                }
                gj.addProperty("from", x.source());
                gj.addProperty("price", x.price());
                gj.addProperty("tradable", Shopping.tradable(x.info()));
                if (x.alt() != null) {
                    gj.addProperty("alternative", x.alt().name() + " (Lv. " + x.alt().requirements().level()
                            + ", " + x.altSource() + ")");
                }
                if (x.options() != null) {
                    JsonArray opts = new JsonArray();
                    for (Option opt : x.options()) {
                        JsonObject oj = new JsonObject();
                        oj.addProperty("name", opt.info().name());
                        oj.addProperty("level", opt.info().requirements().level());
                        oj.addProperty("gain", Math.round(opt.gain() * 10) / 10.0);
                        oj.addProperty("from", opt.source());
                        oj.addProperty("tradable", Shopping.tradable(opt.info()));
                        opts.add(oj);
                    }
                    gj.add("options", opts);
                }
                g.add(gj);
            }
            j.add("gear", g);
            JsonArray a = new JsonArray();
            s.abilities().forEach(a::add);
            j.add("abilities", a);
            JsonArray p = new JsonArray();
            s.places().forEach(p::add);
            j.add("places", p);
            j.addProperty("skillPoints", s.skillPoints());
            arr.add(j);
        }
        o.add("steps", arr);
        return o;
    }

    /**
     * The local API's /roadmap: the map the J menu has (never rebuilt here, so the game does not
     * stall), plus per milestone the rows the menu shows: the latest pick per slot up to that
     * milestone, the number on the row (gain against what you wear) and its reasons.
     */
    /**
     * {@link #json} worked out on the road map's own background thread (one build at a time,
     * after any warm-up already running), for API callers: the game thread never waits for it.
     */
    static JsonObject jsonOffThread() throws Exception {
        return BUILDER.submit(Roadmap::json).get(120, java.util.concurrent.TimeUnit.SECONDS);
    }

    static JsonObject jsonLive() {
        List<Step> steps = lastSteps;
        JsonObject o = new JsonObject();
        o.addProperty("builtAgoSeconds", steps.isEmpty() ? -1 : (GameState.current().now() - lastBuilt) / 1000);
        o.addProperty("building", building);
        JsonArray arr = new JsonArray();
        Map<String, Gear> latest = new LinkedHashMap<>();
        for (Step s : steps) {
            for (Gear g : s.gear()) {
                latest.put(g.slot(), g);
            }
            keepWorn(latest, s);
            JsonObject j = new JsonObject();
            j.addProperty("level", s.level());
            if (s.set() != null) {
                JsonObject set = new JsonObject();
                s.build().forEach((slot, p) -> set.addProperty(slot, p == null ? null : p.name()));
                JsonObject pts = new JsonObject();
                for (SkillKind k : SkillKind.values()) {
                    pts.addProperty(k.name(), s.set().allocation()[k.ordinal()]);
                }
                set.add("skillPoints", pts);
                set.addProperty("spell", Math.round(s.set().result().spellDps()));
                set.addProperty("ehp", Math.round(s.set().result().ehp()));
                set.addProperty("build", Math.round(s.set().result().objective()));
                j.add("set", set);
            }
            JsonArray rows = new JsonArray();
            for (var e : latest.entrySet()) {
                Gear g = e.getValue();
                JsonObject r = new JsonObject();
                r.addProperty("slot", e.getKey());
                r.addProperty("name", g.info().name());
                r.addProperty("level", g.info().requirements().level());
                r.addProperty("tier", g.info().tier().name());
                Double row = s.vsWorn().get(e.getKey());
                r.addProperty("row", row == null ? null : Math.round(row * 10) / 10.0);
                r.addProperty("step", Double.isNaN(g.gain()) ? null : Math.round(g.gain() * 10) / 10.0);
                JsonArray why = new JsonArray();
                s.whyVsWorn().getOrDefault(e.getKey(), List.of()).forEach(why::add);
                r.add("why", why);
                if (g.options() != null) {
                    JsonArray opts = new JsonArray();
                    for (Option opt : g.options()) {
                        opts.add(opt.info().name() + " Lv" + opt.info().requirements().level() + " "
                                + Math.round(opt.gain() * 10) / 10.0 + "%");
                    }
                    r.add("options", opts);
                }
                rows.add(r);
            }
            j.add("rows", rows);
            arr.add(j);
        }
        o.add("milestones", arr);
        return o;
    }

    /**
     * The local API's /roadmap/trace: builds the map once more on the background builder with
     * the trace on, and returns why each candidate for {@code slot} at {@code level} was kept,
     * scored or dropped. Waits at most 60 s; the game keeps running meanwhile.
     */
    static JsonObject traceJson(int level, Slot slot) throws Exception {
        List<String> lines = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.Future<?> f = BUILDER.submit(() -> {
            trace = (m, t, line) -> {
                if (m == level && t == slot) {
                    lines.add(line);
                }
            };
            try {
                build();
            } finally {
                trace = null;
            }
        });
        f.get(60, java.util.concurrent.TimeUnit.SECONDS);
        JsonObject o = new JsonObject();
        o.addProperty("level", level);
        o.addProperty("slot", slot.name());
        JsonArray a = new JsonArray();
        synchronized (lines) {
            lines.forEach(a::add);
        }
        o.add("candidates", a);
        return o;
    }

    /** Gear by name, ignoring case (rule lists are upper-cased). */
    private static ItemData findGear(String name) {
        return GameState.current().allGear().filter(i -> i.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    /** Milestones are kept in order of level for the screen. */
    static Map<Integer, Step> byLevel(List<Step> steps) {
        Map<Integer, Step> m = new LinkedHashMap<>();
        steps.forEach(s -> m.put(s.level(), s));
        return m;
    }
}
