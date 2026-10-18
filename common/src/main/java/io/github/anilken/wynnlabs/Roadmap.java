package io.github.anilken.wynnlabs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.Klass;
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
        String pr = Prices.text(info.name(), false);
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
     * The best ring for the hand the milestone's ring pick did not take: a second copy of a ring
     * in the build, or one of this milestone's best rings. Put into {@code baseline} when it clears
     * the same bar as any pick; null when nothing does.
     */
    private static Gear otherRing(Map<String, Damage.Piece> baseline, Map<String, String> placed, List<ItemData> pool,
            int m, int earned, double baseObj, List<String> badSources) {
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
                if (info.requirements().level() > m || there != null && there.key().equals(cand.key())) {
                    continue;
                }
                String src = Shopping.sourceOf(info);
                if (src == null || badSources.stream().anyMatch(src::startsWith)
                        || Exclusive.clash(baseline, slot, Exclusive.group(info)) != null) {
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
        return new Gear(RING2, best, bestGain, Shopping.where(src), Prices.text(best.name(), false), null, null,
                new ArrayList<>(), List.of(), first == null ? List.of() : List.of(first));
    }

    private static Build.Eval fullEval(Map<String, Damage.Piece> build, String slot, Damage.Piece cand, int m, int earned) {
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
        double o = e.result().objective();
        // a floor for effective health (what build-solver asks as min EHP): below it the set
        // pays by the square of the shortfall, so damage is not bought with paper armour
        double floor = ehpFloor(m);
        if (floor > 0 && e.result().ehp() < floor) {
            double f = e.result().ehp() / floor;
            o *= f * f;
        }
        if (prev == null) {
            return o;
        }
        int back = 0;
        for (int i = 0; i < prev.length && i < e.allocation().length; i++) {
            back += Math.max(0, prev[i] - e.allocation()[i]);
        }
        return o * Math.max(0.5, 1 - Rules.sub("roadmap", "respecWeight", 0.2) * back / 100.0);
    }

    /** Levels and the effective health a set there should keep (about three quarters of what typical builds wear). */
    private static final int[][] EHP_FLOOR = {{1, 0}, {50, 1500}, {60, 2500}, {70, 3500}, {80, 5000}, {90, 8000},
        {100, 12000}, {110, 15000}, {120, 20000}};

    /** The effective health floor at level m, scaled by rules.roadmap.ehpFloorScale (0 turns it off). */
    static double ehpFloor(int m) {
        double scale = Rules.sub("roadmap", "ehpFloorScale", 1.0);
        if (scale <= 0) {
            return 0;
        }
        for (int i = 1; i < EHP_FLOOR.length; i++) {
            if (m <= EHP_FLOOR[i][0]) {
                int[] a = EHP_FLOOR[i - 1];
                int[] b = EHP_FLOOR[i];
                return scale * (a[1] + (b[1] - a[1]) * (double) (m - a[0]) / (b[0] - a[0]));
            }
        }
        return scale * EHP_FLOOR[EHP_FLOOR.length - 1][1];
    }

    /**
     * After the slot-by-slot picks: each slot's best few (pools) are tried again in the whole
     * set, with the points spread anew, and the best swap that helps by more than 0.2 % is
     * taken, for up to rules.roadmap.jointPasses rounds. A swapped slot's row is made anew.
     */
    private static void refine(Map<String, Damage.Piece> build, Map<String, String> placed, Map<Slot, List<ItemData>> pools,
            List<Gear> gear, int m, int earned, int[] prev) {
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
                    if (slot == null) {
                        continue;
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
                        || Exclusive.clash(build, slot, Exclusive.group(info)) != null) {
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
        gear.add(new Gear(rowName, pick, gain, Shopping.where(src), Prices.text(pick.name(), false), null, null,
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
            Map<Slot, List<ItemData>> pools, List<Gear> gear, int m, int earned, int[] prev) {
        Build.Eval s0 = Build.allocate(start, m, earned);
        Build.Eval cur = Build.allocate(build, m, earned);
        if (s0 == null || cur == null) {
            return;
        }
        // the same inputs give the same set: kept on disk per character, so a map that
        // nothing changed for comes back in seconds after a restart instead of 20 s
        String ck = beamKey(start, build, pools, m, earned, prev);
        Map<String, Damage.Piece> chosen;
        if (BeamCache.has(ck)) {
            chosen = BeamCache.get(ck, start, build);
            if (chosen == null && !BeamCache.isNone(ck)) {
                chosen = beamBest(start, build, pools, m, earned, prev, s0, cur);
                BeamCache.put(ck, chosen);
            }
        } else {
            chosen = beamBest(start, build, pools, m, earned, prev, s0, cur);
            BeamCache.put(ck, chosen);
        }
        if (chosen == null) {
            return;
        }
        applySet(chosen, start, build, placed, pools, gear, m, earned, prev);
    }

    /** What a milestone's beam depends on: the sets it starts from, the pools, the points before and the rules. */
    private static String beamKey(Map<String, Damage.Piece> start, Map<String, Damage.Piece> build,
            Map<Slot, List<ItemData>> pools, int m, int earned, int[] prev) {
        StringBuilder k = new StringBuilder();
        k.append(m).append('|').append(earned).append('|').append(java.util.Arrays.toString(prev)).append('|');
        k.append(setKey(start)).append('|').append(setKey(build)).append('|');
        pools.forEach((t, l) -> {
            k.append(t.name()).append(':');
            l.forEach(i -> k.append(i.name()).append(','));
        });
        k.append('|').append(Rules.hash());
        return Integer.toHexString(k.toString().hashCode()) + Integer.toHexString(k.reverse().toString().hashCode());
    }

    /** The beam itself: the best set it finds, or null when none beats the slot picks. */
    private static Map<String, Damage.Piece> beamBest(Map<String, Damage.Piece> start, Map<String, Damage.Piece> build,
            Map<Slot, List<ItemData>> pools, int m, int earned, int[] prev, Build.Eval s0, Build.Eval cur) {
        int width = (int) Rules.sub("roadmap", "beamWidth", 200);
        Map<String, Damage.Piece> pieces = new HashMap<>();
        List<BeamState> beam = new ArrayList<>(List.of(new BeamState(new LinkedHashMap<>(start), s0.allocation(),
                judged(s0, prev, m))));
        for (String slot : BEAM_ORDER) {
            Slot t = slot.startsWith("RING") ? Slot.RING : slot.equals("WEAPON") ? null : Slot.valueOf(slot);
            List<ItemData> pool = t == null ? weaponPool(pools) : pools.get(t);
            if (pool == null || pool.isEmpty()) {
                continue;
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
            if (had != null && had.key().equals(want.key())) {
                // the set keeps what the milestone started with here (what you wear, or an
                // earlier pick): the slot-by-slot pick is undone and its row goes, it is no
                // "Yume → Yume" upgrade
                build.put(slot, want);
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
            fj.submit(() -> all.parallelStream().forEach(b -> {
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
            })).get();
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
            String g = p == null ? null : Exclusive.group(p.name());
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

    /** The piece's requirements in skills outside {@code preferred}. */
    private static int offPoints(ItemData info, List<String> preferred) {
        int off = 0;
        for (var r : info.requirements().skills()) {
            if (!preferred.contains(r.skill().name())) {
                off += r.points();
            }
        }
        return off;
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

    /**
     * The upgrade the damage model found for this slot at your level, from the last road map
     * built (J menu, the API) in the last 15 minutes; null when none or too old. Lets tooltips
     * agree with the J menu instead of using the simpler shopping-list score.
     */
    /** Every upgrade name in the last road map (for keeping their prices fresh). */
    static List<String> lastNames() {
        List<String> out = new ArrayList<>();
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

    static Gear lastUpgrade(Slot type) {
        GameState state = GameState.current();
        if (!state.characterId().equals(lastFor) || state.now() - lastBuilt > 15 * 60_000L || lastSteps.isEmpty()) {
            return null;
        }
        for (Gear g : lastSteps.get(0).gear()) {
            if (g.slot().equals(type.name())) {
                return g;
            }
        }
        return null;
    }

    /**
     * The road map for the J menu, without making the player wait. When nothing it depends on
     * changed (same character, level, build plan and rules, under 5 minutes old) it is the last
     * one; when it is out of date the last one is still returned and a fresh one is built in the
     * background ({@link #version} moves on when it is ready). With no map yet for the
     * character it is empty while the first one is built ({@link #progress}). Building one takes
     * a few seconds with milestones up to Lv 120, which froze the game when it ran on the spot.
     */
    static List<Step> cached() {
        GameState state = GameState.current();
        String key = key(state);
        if (key.equals(cachedKey) && state.now() - lastBuilt < 5 * 60_000L && !lastSteps.isEmpty()) {
            return lastSteps;
        }
        if (!lastSteps.isEmpty() && state.characterId().equals(lastFor)) {
            refresh(key);
            return lastSteps;
        }
        // the first map of a character too: built in the background while the menu shows the
        // progress (on the spot it froze the game for seconds at the first J)
        refresh(key);
        return List.of();
    }

    /** The J menu's refresh button: a new map now, whatever its age (the beam cache still skips unchanged milestones). */
    static void forceRefresh() {
        GameState state = GameState.current();
        if (state.inGame()) {
            refresh(key(state));
        }
    }

    /** True while a map is being built in the background. */
    static boolean isBuilding() {
        return building;
    }

    /** How far the map being built is, 0 to 1, by milestones done. */
    static double progress() {
        int total = progressTotal;
        return total <= 0 ? 0 : Math.min(1, progressDone / (double) total);
    }

    /** Builds a fresh map in the background when the last one is out of date (the client tick calls this). */
    static void warm() {
        GameState state = GameState.current();
        if (!state.inGame() || !Rules.adviceHere()) {
            return;                             // an alt with the advice off: no map built
        }
        String key = key(state);
        // the first minute on a character you switched to: its plan settles over a few
        // seconds (inventory, level), and a map from the old plan must not stand for 30 s
        if (!state.characterId().equals(seenFor)) {
            seenFor = state.characterId();
            switchedAt = state.now();
        }
        boolean settling = state.now() - switchedAt < 60_000L;
        // on its own at most every 30 s: the J menu's buttons ask through cached() at once
        long age = state.now() - lastBuilt;
        if (lastSteps.isEmpty() || !state.characterId().equals(lastFor) || age >= 4 * 60_000L
                || !key.equals(cachedKey) && (age >= 30_000L || settling)) {
            refresh(key);
        }
    }

    private static volatile String seenFor = "";
    private static volatile long switchedAt;

    /** Bumped each time a new map is ready, so an open J menu can pick it up. */
    static int version() {
        return version;
    }

    /**
     * What the map depends on, by content: the plan is rebuilt whenever the inventory changes at
     * all and the rules on any write (a mana measurement), which rebuilt the map back to back
     * with nothing changed for it.
     */
    private static String key(GameState state) {
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

    private static void refresh(String key) {
        if (building) {
            again = true;                       // a setting changed mid-build: build once more after it
            return;
        }
        building = true;
        BUILDER.execute(() -> {
            try {
                build();
                cachedKey = key;
                version++;
            } catch (Throwable t) {
                // the game state changed under us (a menu, a world change): the next warm-up tries again
            } finally {
                building = false;
                if (again) {
                    again = false;
                    String now = key(GameState.current());
                    if (!now.equals(cachedKey)) {
                        refresh(now);
                    }
                }
            }
        });
    }

    private static volatile boolean building;
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

    private static volatile String cachedKey = "";

    static List<Step> build() {
        GameState state = GameState.current();
        if (!state.inGame()) {
            return List.of();
        }
        String forId = state.characterId();       // a switch mid-build must not publish this map
        int level = state.level();
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
        List<Integer> levels = milestones(level);
        int last = levels.get(levels.size() - 1);
        // the off-skill limit, tiers and sources go by each milestone's level (maxOff, badTiers,
        // badSources): the pool holds what the last milestone allows, each milestone filters again
        int maxOffLast = maxOff(last);
        List<String> badTiersLast = badTiers(last);

        // Candidates per slot, once
        Map<Slot, List<ItemData>> cands = new EnumMap<>(Slot.class);
        state.allGear().forEach(info -> {
            Slot t = info.slot();
            int lv = info.requirements().level();
            if (!best.containsKey(t) || lv > last || lv < level - 8 || badTiersLast.contains(info.tier().name())
                    || Shopping.walkPenalty(info) > Rules.sub("shopping", "maxWalkSpeedPenalty", 999)
                    || info.base().attackSpeed().isPresent()
                            && Rules.list("shopping", "excludeAttackSpeeds").contains(info.base().attackSpeed().get())) {
                return;
            }
            Klass need = info.requirements().klass().orElse(t.klass());
            if (need != null && need != Klass.NONE && need != cls) {
                return;
            }
            if (offPoints(info, preferred) > maxOffLast) {
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
        int[] prevAlloc = null;                                // the last milestone's set's skill points
        // piece key → its row number and reasons from the milestone that first picked it
        Map<String, Double> firstGain = new HashMap<>();
        Map<String, List<String>> firstWhy = new HashMap<>();
        progressTotal = levels.size();
        progressDone = 0;
        for (int m : levels) {
            if (m <= prev) {
                continue;
            }
            int earnedThen = Math.max(0, (Math.min(m, 101) - 1) * 2);
            int maxOff = maxOff(m);
            List<String> badTiers = badTiers(m);
            List<String> badSources = badSources(m);
            // today's Trade Market listings only matter for what you would buy soon, not 70 levels on
            boolean listingsMatter = m - level <= (int) Rules.sub("shopping", "aheadLevels", 5);
            List<Gear> gear = new ArrayList<>();
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
            List<ItemData> ringPool = new ArrayList<>();     // this milestone's best rings, for the other hand
            for (Slot t : slots) {
                ItemData top = null;
                boolean emptyBuild = false;                 // the pick fills an empty hand (no score to compare)
                double topScore = allocM != null ? 0 : best.getOrDefault(t, 0.0) + 0.5;
                double topReal = Double.NaN;            // the pick's own gain (topScore may be lowered for availability)
                String topSlot = null;
                ItemData alt = null;
                double altScore = topScore;
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
                for (ItemData info : cands.getOrDefault(t, List.of())) {
                    if (wornNames.contains(info.name().toLowerCase(java.util.Locale.ROOT))
                            || info.requirements().level() < floor || badTiers.contains(info.tier().name())
                            || offPoints(info, preferred) > maxOff) {
                        trace(m, t, info, "skip: worn / below window / tier / off-skill " + offPoints(info, preferred) + ">" + maxOff);
                        continue;
                    }
                    // a few levels ahead only for the comparison panel's alternatives
                    boolean future = info.requirements().level() > m;
                    if (info.requirements().level() > m + peek || future && allocM == null) {
                        trace(m, t, info, "skip: above the milestone");
                        continue;
                    }
                    int total = info.requirements().skills().stream().mapToInt(r -> r.points()).sum();
                    if (total > earnedThen) {
                        trace(m, t, info, "skip: needs " + total + " points > " + earnedThen);
                        continue;
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
                            if (slot == null || there != null && there.key().equals(cand.key())) {
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
                        if (slotUsed != null && Build.needsRespec(baseline, allocM, slotUsed, Damage.piece(info), earnedThen)) {
                            sc -= Rules.sub("shopping", "respecPenaltyPercent", 3);
                        }
                        String srcP = Shopping.sourceOf(info);
                        if (srcP != null && badSources.stream().anyMatch(srcP::startsWith)) {
                            trace(m, t, info, "skip: source " + srcP);
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
                    double eff = sc;
                    if (allocM != null && listingsMatter && Shopping.tradable(info)) {
                        Prices.Price live = state.price(info.name());
                        String src = Shopping.sourceOf(info);
                        boolean merchant = src != null && (src.startsWith("MERCHANT") || src.startsWith("DUNGEON_MERCHANT"));
                        if (!merchant && live != null && live.count() != null && live.count() == 0) {
                            eff = sc * 0.6;
                        }
                    }
                    if (allocM != null) {
                        ranked.add(new Object[] {info, sc, eff, slotUsed, eff == sc ? 1.0 : 0.6});
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
                            if (Build.needsRespec(baseline, allocM, slotUsed, Damage.piece(info), earnedThen)) {
                                full -= Rules.sub("shopping", "respecPenaltyPercent", 3);
                            }
                            if (full < Rules.sub("shopping", "minGainPercent", 10)
                                    && full < Build.gainThreshold(baseline.get(slotUsed), m)) {
                                trace(m, t, info, String.format(java.util.Locale.ROOT, "skip: full %.2f%% under the bar %.2f%% (slot %s holds Lv %d)",
                                        full, Build.gainThreshold(baseline.get(slotUsed), m), slotUsed,
                                        baseline.get(slotUsed) == null ? -1 : baseline.get(slotUsed).level()));
                                continue;
                            }
                            trace(m, t, info, String.format(java.util.Locale.ROOT, "judged %.2f%% in %s", full, slotUsed));
                            eff = full * (double) r[4];     // keeps the "no listings" factor
                            sc = full;
                        }
                        if (side) {
                            if (eff > altScore) {
                                altScore = eff;
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
                    topReal = Double.NaN;
                    alt = null;
                }
                if (top == null) {
                    continue;
                }
                String source = Shopping.sourceOf(top);
                if (source == null || badSources.stream().anyMatch(source::startsWith)) {
                    continue;
                }
                String price = Prices.text(top.name(), false);
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
                            String pr = Prices.text(gi.name(), false);
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
                            .filter(o -> o[0] != chosen && o[2] != null)
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
                                .filter(o -> o[0] != chosen && o[2] != null
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
                beamSearch(startSet, baseline, placed, pools, gear, m, earnedThen, prevAlloc);
                exactSearch(baseline, placed, pools, gear, m, earnedThen, prevAlloc);
                refine(baseline, placed, pools, gear, m, earnedThen, prevAlloc);
                Build.Eval be = Build.allocate(baseline, m, earnedThen);
                if (be != null) {
                    baseObj = be.result().objective();
                    baseJ = judged(be, prevAlloc, m);
                    allocM = be.allocation();
                }
            }
            // The other hand: Wynncraft takes the same ring twice, and one pick per slot type left
            // the second ring at what you wear now all the way to Lv 120
            if (allocM != null && baseline != null) {
                Gear second = otherRing(baseline, placed, ringPool, m, earnedThen, baseObj, badSources);
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
                if (lv <= prev || lv > m) {
                    continue;
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
                        + note, Prices.text(info.name(), false),
                        null, null, List.of(), List.of(), List.of()));
                if (baseline != null) {
                    String slot = Build.slotOf(info.slot(), 0);
                    if (slot != null) {
                        baseline.put(slot, Damage.piece(info));
                        place(placed, slotName, slot);
                    }
                }
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
            Build.Eval set = baseline == null ? null : Build.allocate(baseline, m, earnedThen);
            if (set != null) {
                prevAlloc = set.allocation();
            }
            steps.add(new Step(m, gear, abil, places, m == level ? 0 : sp, rowGain, rowWhy,
                    baseline == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(baseline)),
                    set));
            prev = m;
            progressDone++;
        }
        if (!forId.equals(GameState.current().characterId())) {
            return steps;                           // built for the character you just left
        }
        lastSteps = steps;
        lastBuilt = state.now();
        lastFor = forId;
        BeamCache.save();
        return steps;
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
