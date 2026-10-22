package io.github.anilken.wynnlabs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.SkillKind;
import io.github.anilken.wynnlabs.model.Slot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The best build out of what you wear and carry: one piece per slot (two rings, the weapon),
 * chosen together with the skill points to meet their requirements, scored by {@link Damage}.
 *
 * <p>Coordinate ascent: start from what you wear, try every candidate in every slot, keep any
 * swap that raises the score, repeat until nothing does. For every build tried, skill points
 * go first to the requirements, then one by one to whichever skill raises the score most.
 * Recomputed when your gear, level, skill points or the rules change.
 */
final class Build {

    static final String[] SLOTS = {"HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS", "RING1", "RING2", "BRACELET",
        "NECKLACE", "WEAPON"};

    record Plan(Map<String, Damage.Piece> pieces, int[] allocation, Damage.Result result,
            Map<String, Damage.Piece> worn, int[] wornAllocation, Damage.Result current, int earned,
            java.util.Set<String> carried, int[] wornTarget, Map<String, Damage.Piece> working, int level) {

        boolean contains(ItemStack stack) {
            String key = ItemJson.keyOf(stack);
            for (Damage.Piece p : pieces.values()) {
                if (p != null && p.key().equals(key)) {
                    return true;
                }
            }
            return false;
        }

        /** The planned piece for the slot this stack would go into (first ring for rings). */
        Damage.Piece plannedFor(Slot type) {
            return pieces.get(slotOf(type, 0));
        }

        /**
         * {@link #current} again with its DPS breakdown: what you wear (pieces whose requirements
         * are not met left out) with your own points, or {@code points} instead of them.
         */
        Damage.Detail currentDetail(int[] points, Damage.Piece extra) {
            List<Damage.Piece> armour = armour(working);
            if (extra != null) {
                armour.add(extra);
            }
            return Damage.detail(armour, working.get("WEAPON"), points == null ? wornAllocation : points, level);
        }

        double gainPercent() {
            return current.objective() <= 0 ? 0 : (result.objective() / current.objective() - 1) * 100;
        }

        /**
         * The planned build's score at another level: a piece you wear later is judged at its
         * level on both sides, or the health floor of that level alone would sink it.
         */
        double objectiveAt(int lv) {
            return lv == level ? result.objective()
                    : Damage.evaluate(armour(pieces), pieces.get("WEAPON"), allocation, lv).objective();
        }
    }

    private static volatile Plan plan;
    private static volatile String planKey = "";
    private static int tick;
    private static String planProfile = "";
    /** When the plan (re)started for this character; changes right after a join are not choices. */
    static volatile long startedAt;

    private Build() {}

    /**
     * The % a swap must gain before it is suggested: rules.shopping.minGainPercent for gear of
     * your level, falling to 1 % as the piece you have gets old (3 levels behind keeps the full
     * threshold, shopping.oldGearLevels (8) or more behind needs only 1 %).
     */
    static double gainThreshold(Damage.Piece current, int level) {
        double base = Rules.sub("shopping", "minGainPercent", 10);
        if (current == null) {
            return 0.2;
        }
        int gap = level - current.level();
        double old = Rules.sub("shopping", "oldGearLevels", 8);
        if (gap <= 3) {
            return base;
        }
        if (gap >= old + 4) {
            return 0.3;                         // far behind: any real gain is worth the swap
        }
        if (gap >= old) {
            return Math.min(base, 1);
        }
        double t = (gap - 3) / (old - 3);
        return base + (Math.min(base, 1) - base) * t;
    }

    /**
     * Whether wearing {@code cand} in {@code slot} needs points taken out of a skill (a reset in
     * town), rather than just spending the points still free. {@code placed} is what is already
     * assigned (the points you placed, or the last milestone's spread), not a spread of every
     * earned point: against one of those every requirement looked like a reset. Null: no reset
     * can be told, false.
     */
    static boolean needsRespec(Map<String, Damage.Piece> build, int[] placed, String slot, Damage.Piece cand,
            int earned) {
        if (placed == null) {
            return false;
        }
        Map<String, Damage.Piece> trial = new LinkedHashMap<>(build);
        trial.put(slot, cand);
        int[] min = minPoints(trial.values());
        int deficit = 0;
        int used = 0;
        for (int i = 0; i < min.length; i++) {
            deficit += Math.max(0, min[i] - placed[i]);
            used += placed[i];
        }
        return deficit > Math.max(0, earned - used);
    }

    static Plan plan() {
        return plan;
    }

    static String slotOf(Slot t, int ring) {
        return switch (t) {
            case HELMET -> "HELMET";
            case CHESTPLATE -> "CHESTPLATE";
            case LEGGINGS -> "LEGGINGS";
            case BOOTS -> "BOOTS";
            case RING -> ring == 0 ? "RING1" : "RING2";
            case BRACELET -> "BRACELET";
            case NECKLACE -> "NECKLACE";
            default -> t.klass() != null ? "WEAPON" : null;
        };
    }

    /** Works the plan out in the background; one at a time. */
    private static final java.util.concurrent.ExecutorService WORKER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "wynnlabs-build");
                t.setDaemon(true);
                return t;
            });
    private static volatile boolean computing;

    /**
     * Every two seconds: when anything the plan depends on changed, take what it needs from the
     * game here ({@link #prepare}), work the plan out on a background thread, and publish it back
     * on the game thread. The game never waits for the optimiser.
     */
    static void onTick(Minecraft mc) {
        if (++tick % 40 != 0 || mc.player == null || computing) {
            return;
        }
        try {
            String key = signature(mc.player);
            if (key.equals(planKey)) {
                return;
            }
            Plan before0 = plan;
            boolean fresh = !Profile.id().equals(planProfile) || before0 == null;
            if (fresh) {
                planProfile = Profile.id();
                startedAt = System.currentTimeMillis();
            }
            Plan before = fresh ? null : before0;
            Inputs in = prepare();
            computing = true;
            WORKER.execute(() -> {
                Plan result = null;
                Throwable error = null;
                try {
                    result = compute(in);
                } catch (Throwable t) {
                    error = t;
                }
                Plan done = result;
                Throwable failed = error;
                mc.execute(() -> {
                    computing = false;
                    if (failed != null) {
                        System.out.println("[WynnLabs] build plan failed: " + failed);
                        return;                     // planKey unchanged: tried again in two seconds
                    }
                    plan = done;
                    planKey = key;
                    if (System.currentTimeMillis() - startedAt > 30_000) {
                        Feedback.check(before, done);
                    }
                });
            });
        } catch (Throwable t) {
            computing = false;
            System.out.println("[WynnLabs] build plan failed: " + t);
        }
    }

    /**
     * What the plan depends on, cheaply: each carried and worn stack by identity and count (a
     * swap, a pickup or a sale brings new stacks), the assigned points, the level and the rules.
     */
    private static String signature(LocalPlayer player) {
        java.util.List<Object> stacks = new java.util.ArrayList<>();
        java.util.List<Integer> counts = new java.util.ArrayList<>();
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            stacks.add(s.isEmpty() ? null : s);
            counts.add(s.getCount());
        }
        // what you wear as the mod sees it (a mount menu can hide armour slots in the inventory)
        for (var slot : new net.minecraft.world.entity.EquipmentSlot[] {net.minecraft.world.entity.EquipmentSlot.HEAD,
            net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS,
            net.minecraft.world.entity.EquipmentSlot.FEET}) {
            ItemStack w = AutoAdvice.armour(player, slot);
            stacks.add(w.isEmpty() ? null : w);
            counts.add(w.getCount());
        }
        int[] assigned = new int[SkillKind.values().length];
        for (SkillKind s : SkillKind.values()) {
            assigned[s.ordinal()] = MenuWatcher.assigned(s);
        }
        return signature(stacks, counts, assigned, Wyn.level(), Rules.version);
    }

    /** The signature from plain values (tests call this). Null stands for an empty slot. */
    static String signature(java.util.List<Object> stacks, java.util.List<Integer> counts, int[] assigned, int level,
            int rules) {
        StringBuilder sb = new StringBuilder(stacks.size() * 12 + 32);
        for (int i = 0; i < stacks.size(); i++) {
            Object s = stacks.get(i);
            sb.append(s == null ? "-" : Integer.toHexString(System.identityHashCode(s)) + "x" + counts.get(i)).append(',');
        }
        for (int a : assigned) {
            sb.append(a).append(',');
        }
        sb.append(level).append(',').append(rules);
        return sb.toString();
    }

    /** Tests only: the plan the advice reads, as onTick would have left it. */
    static void use(Plan p) {
        plan = p;
    }

    /**
     * Everything {@link #compute(Inputs)} needs from the game, read on the game thread: the level
     * and class, what you wear and carry as model pieces, the points you placed, and which worn
     * pieces actually work (their requirements met). The pieces carry their stats; the
     * optimiser never reads the game.
     */
    record Inputs(int level, Klass cls, int earned, Map<String, Damage.Piece> worn,
                  Map<String, List<Damage.Piece>> cands, java.util.Set<String> carried, int[] assignedNow,
                  Map<String, Damage.Piece> working) {}

    static Plan compute() {
        return compute(prepare());
    }

    static Inputs prepare() {
        GameState state = GameState.current();
        int level = state.level();
        Klass cls = state.classType();
        int earned = Math.max(0, (Math.min(level, 101) - 1) * 2);

        // What you wear now
        Map<String, Damage.Piece> worn = new LinkedHashMap<>();
        for (String slot : SLOTS) {
            worn.put(slot, null);
        }
        for (Slot t : List.of(Slot.HELMET, Slot.CHESTPLATE, Slot.LEGGINGS, Slot.BOOTS,
                Slot.BRACELET, Slot.NECKLACE)) {
            List<ItemStack> w = AutoAdvice.worn(t);
            worn.put(slotOf(t, 0), w.isEmpty() ? null : Damage.piece(w.get(0)));
        }
        List<ItemStack> rings = AutoAdvice.worn(Slot.RING);
        worn.put("RING1", rings.size() > 0 ? Damage.piece(rings.get(0)) : null);
        worn.put("RING2", rings.size() > 1 ? Damage.piece(rings.get(1)) : null);
        worn.put("WEAPON", Damage.piece(AutoAdvice.bestWeapon()));

        // Candidates from the inventory
        Map<String, List<Damage.Piece>> cands = new LinkedHashMap<>();
        for (String slot : SLOTS) {
            List<Damage.Piece> l = new ArrayList<>();
            l.add(null);                        // leaving a slot empty is always possible
            if (worn.get(slot) != null) {
                l.add(worn.get(slot));
            }
            cands.put(slot, l);
        }
        List<ItemStack> inv = state.inventory();
        java.util.Set<String> carried = new java.util.HashSet<>();
        for (ItemStack s : inv) {
            Damage.Piece p = Damage.piece(s);
            if (p == null || p.level() > level || isWornStack(worn, s)) {
                continue;
            }
            carried.add(p.key());
            var parsed = state.view(s);
            if (parsed.isPresent() && !fitsClass(parsed.get(), cls)) {
                continue;
            }
            if (p.type() == Slot.RING) {
                cands.get("RING1").add(p);
                cands.get("RING2").add(p);
            } else {
                String slot = slotOf(p.type(), 0);
                if (slot != null && cands.containsKey(slot)) {
                    cands.get(slot).add(p);
                }
            }
        }

        int[] assignedNow = new int[SkillKind.values().length];
        for (SkillKind s : SkillKind.values()) {
            assignedNow[s.ordinal()] = state.assigned(s);
        }
        // what you wear now counts only where the game lets it work: a piece whose requirements are
        // not met (Yume without its 25 Agility) gives nothing, the slot is as good as empty
        Map<String, Damage.Piece> working = new LinkedHashMap<>(worn);
        for (String slot : SLOTS) {
            Damage.Piece wp = worn.get(slot);
            if (wp == null || wp.stack() == null) {
                continue;
            }
            var gi = state.view(wp.stack());
            if (gi.isPresent() && gi.get().isGear() && gi.get().rolled() != null
                    && !gi.get().rolled().marks().all()) {
                working.put(slot, null);
            }
        }
        return new Inputs(level, cls, earned, worn, cands, carried, assignedNow, working);
    }

    /** The plan from {@link #prepare}'s snapshot: the optimiser, safe off the game thread. */
    static Plan compute(Inputs in) {
        Damage.CLASS_FOR_THREAD.set(in.cls());
        try {
            return computeFrom(in);
        } finally {
            Damage.CLASS_FOR_THREAD.remove();
        }
    }

    private static Plan computeFrom(Inputs in) {
        int level = in.level();
        int earned = in.earned();
        Map<String, Damage.Piece> worn = in.worn();
        Map<String, List<Damage.Piece>> cands = in.cands();
        java.util.Set<String> carried = in.carried();
        Map<String, Damage.Piece> working = in.working();
        int[] assignedNow = in.assignedNow().clone();
        int assignedSum = 0;
        for (int a : assignedNow) {
            assignedSum += a;
        }
        // Points not read yet (Character Info not opened): judge what you wear at its best
        // spread, so the gear comparison is not inflated by "zero points"
        boolean pointsKnown = assignedSum > 0 || earned == 0;
        // The spread that fits what you wear right now: requirements first, the rest where it helps
        Eval wornBest = allocateBest(worn, level, earned);
        if (!pointsKnown && wornBest != null) {
            assignedNow = wornBest.allocation().clone();
        }
        Damage.Result current = Damage.evaluate(armour(working), working.get("WEAPON"), assignedNow, level);

        // Coordinate ascent over the slots
        Map<String, Damage.Piece> best = new LinkedHashMap<>(worn);
        Eval bestEval = allocate(best, level, earned);
        for (int pass = 0; pass < 3; pass++) {
            boolean improved = false;
            for (String slot : SLOTS) {
                for (Damage.Piece c : cands.get(slot)) {
                    if (c == best.get(slot) || usedElsewhere(best, slot, c)) {
                        continue;
                    }
                    Map<String, Damage.Piece> trial = new LinkedHashMap<>(best);
                    trial.put(slot, c);
                    Eval e = allocate(trial, level, earned);
                    // Swapping out something you wear must be worth it (rules.shopping.minGainPercent);
                    // filling an empty slot only has to help
                    double need = best.get(slot) == null ? 1.002
                            : 1 + gainThreshold(best.get(slot), level) / 100.0;
                    if (e != null && (bestEval == null || e.result().objective() > bestEval.result().objective() * need)) {
                        best = trial;
                        bestEval = e;
                        improved = true;
                    }
                }
            }
            if (!improved) {
                break;
            }
        }
        // Two slots at once: pieces that only pay off together (shared requirements, a set,
        // two small gains that pass the threshold only as a pair). Bag items only, so it is cheap.
        if (bestEval != null) {
            List<String> slots = List.of(SLOTS);
            for (int i = 0; i < slots.size(); i++) {
                for (int j = i + 1; j < slots.size(); j++) {
                    String s1 = slots.get(i);
                    String s2 = slots.get(j);
                    for (Damage.Piece c1 : cands.get(s1)) {
                        if (c1 == null || c1 == best.get(s1)) {
                            continue;
                        }
                        for (Damage.Piece c2 : cands.get(s2)) {
                            if (c2 == null || c2 == best.get(s2) || c1 == c2) {
                                continue;
                            }
                            Map<String, Damage.Piece> trial = new LinkedHashMap<>(best);
                            trial.put(s1, c1);
                            trial.put(s2, c2);
                            if (usedElsewhere(trial, s1, c1) || usedElsewhere(trial, s2, c2)) {
                                continue;
                            }
                            Eval e = allocate(trial, level, earned);
                            double need = 1 + Math.max(best.get(s1) == null ? 0.2 : gainThreshold(best.get(s1), level),
                                    best.get(s2) == null ? 0.2 : gainThreshold(best.get(s2), level)) / 100.0;
                            if (e != null && e.result().objective() > bestEval.result().objective() * need) {
                                best = trial;
                                bestEval = e;
                            }
                        }
                    }
                }
            }
        }
        if (bestEval == null) {
            return null;
        }
        // the chosen build's points polished by moving them between skills (once, not per swap)
        bestEval = improve(best, bestEval, level);
        // A plan that takes points back out of a skill needs a reset in town: only worth it
        // for the full threshold, not the lowered one old gear gets
        boolean respec = false;
        for (int i = 0; i < assignedNow.length; i++) {
            respec |= bestEval.allocation()[i] < assignedNow[i];
        }
        double gainPct = current.objective() <= 0 ? 0 : (bestEval.result().objective() / current.objective() - 1) * 100;
        if (respec && gainPct < Rules.sub("shopping", "minGainPercent", 10) && !best.equals(worn)) {
            Eval keep = wornBest;
            best = new LinkedHashMap<>(worn);
            // the allocator can miss your own allocation (e.g. Def 8 only for Chained Pixels): then yours
            bestEval = keep != null ? keep : new Eval(assignedNow.clone(), current);
        }
        // Never advise something weaker than what you wear with the points you placed: the allocator
        // can miss a hand-made allocation (Def 8 just for Chained Pixels), so yours wins then
        if (current.objective() > 0 && bestEval.result().objective() < current.objective()) {
            best = new LinkedHashMap<>(worn);
            bestEval = new Eval(assignedNow.clone(), current);
        }
        return new Plan(best, bestEval.allocation(), bestEval.result(), worn, assignedNow, current, earned, carried,
                wornBest == null ? bestEval.allocation() : wornBest.allocation(), working, level);
    }

    private static boolean fitsClass(ItemView item, Klass cls) {
        Klass need = null;
        if (item.isGear()) {
            need = item.data().slot().klass();
            if (need == null || need == Klass.NONE) {
                need = item.data().requirements().klass().orElse(null);
            }
        } else if (item.isCrafted()) {
            need = item.crafted().requiredClass();
            if (need == null || need == Klass.NONE) {
                need = item.crafted().slot().klass();
            }
        }
        return need == null || need == Klass.NONE || need == cls;
    }

    private static boolean isWornStack(Map<String, Damage.Piece> worn, ItemStack s) {
        for (Damage.Piece p : worn.values()) {
            if (p != null && p.stack() == s) {
                return true;
            }
        }
        return false;
    }

    private static boolean usedElsewhere(Map<String, Damage.Piece> build, String slot, Damage.Piece c) {
        if (c == null) {
            return false;
        }
        for (var e : build.entrySet()) {
            if (!e.getKey().equals(slot) && e.getValue() != null && e.getValue().stack() == c.stack()) {
                return true;
            }
        }
        return false;
    }

    private static List<Damage.Piece> armour(Map<String, Damage.Piece> build) {
        List<Damage.Piece> out = new ArrayList<>();
        build.forEach((k, v) -> {
            if (!k.equals("WEAPON") && v != null) {
                out.add(v);
            }
        });
        return out;
    }

    record Eval(int[] allocation, Damage.Result result) {}

    /**
     * The fewest points you must assign per skill to wear all these pieces, the way Wynncraft
     * equips them: one at a time, each checked against what is on before it. A piece's own
     * positive bonus never counts for itself; its own penalty does (Coeur Brise: Defence 12 with
     * −3 needs 15 assigned, seen in game). Weapons and crafted gear never help other pieces.
     * Starts from the simple bound and raises the points where the equip order still fails.
     */
    static int[] minPoints(java.util.Collection<Damage.Piece> pieces) {
        int n = SkillKind.values().length;
        List<Damage.Piece> list = new ArrayList<>();
        for (Damage.Piece p : pieces) {
            if (p != null) {
                list.add(p);
            }
        }
        double[] gear = new double[n];
        for (Damage.Piece p : list) {
            if (p.helpsRequirements()) {
                for (SkillKind s : SkillKind.values()) {
                    gear[s.ordinal()] += bonus(p, s);
                }
            }
        }
        int[] a = new int[n];
        for (Damage.Piece p : list) {
            for (var e : p.reqs().entrySet()) {
                int i = e.getKey().ordinal();
                double others = gear[i] - (p.helpsRequirements() ? bonus(p, e.getKey()) : 0);
                double own = Math.min(0, bonus(p, e.getKey()));
                a[i] = Math.max(a[i], (int) Math.ceil(e.getValue() - others - own));
            }
        }
        for (int i = 0; i < n; i++) {
            a[i] = Math.max(0, a[i]);
        }
        int bound = java.util.Arrays.stream(a).sum();
        // Equip order: greedy, pieces that give the most first; raise points until all fit
        for (int round = 0; round < 30; round++) {
            int[] deficit = equipDeficit(list, a);
            boolean ok = true;
            for (int i = 0; i < n; i++) {
                if (deficit[i] > 0) {
                    a[i] += deficit[i];
                    ok = false;
                }
            }
            if (ok) {
                break;
            }
        }
        if (java.util.Arrays.stream(a).sum() <= bound) {
            return a;                           // the order-free bound is met: nothing can be cheaper
        }
        // Otherwise the greedy order may have raised two skills where one order needs only one
        // (A needs Str and gives Dex, B needs Dex and gives Str): try the orders of the pieces
        // that give points, the rest last, and keep the cheapest
        List<Damage.Piece> movers = new ArrayList<>();
        List<Damage.Piece> rest = new ArrayList<>();
        for (Damage.Piece p : list) {
            boolean gives = false;
            if (p.helpsRequirements()) {
                for (SkillKind sk : SkillKind.values()) {
                    gives |= bonus(p, sk) > 0;
                }
            }
            (gives ? movers : rest).add(p);
        }
        if (movers.size() > MAX_ORDER_PIECES) {
            return a;
        }
        int[][] best = {a};
        int[] bestSum = {java.util.Arrays.stream(a).sum()};
        permute(movers, 0, rest, best, bestSum);
        return best[0];
    }

    /** More pieces that give skill points than this and the greedy order is kept (8! orders is too many per build). */
    private static final int MAX_ORDER_PIECES = 5;

    private static void permute(List<Damage.Piece> movers, int k, List<Damage.Piece> rest, int[][] best, int[] bestSum) {
        if (k == movers.size()) {
            List<Damage.Piece> order = new ArrayList<>(movers);
            order.addAll(rest);
            int[] need = orderNeed(order);
            int sum = java.util.Arrays.stream(need).sum();
            if (sum < bestSum[0]) {
                bestSum[0] = sum;
                best[0] = need;
            }
            return;
        }
        for (int i = k; i < movers.size(); i++) {
            java.util.Collections.swap(movers, k, i);
            permute(movers, k + 1, rest, best, bestSum);
            java.util.Collections.swap(movers, k, i);
        }
    }

    /**
     * The fewest points per skill for this exact equip order: each piece checked against the
     * points plus the bonuses of the pieces before it (its own penalty counts, its own bonus not),
     * and at the end against everything worn but its own bonus (a later penalty pops it off).
     */
    static int[] orderNeed(List<Damage.Piece> order) {
        int n = SkillKind.values().length;
        double[] prefix = new double[n];
        int[] need = new int[n];
        for (Damage.Piece p : order) {
            for (var e : p.reqs().entrySet()) {
                int i = e.getKey().ordinal();
                double own = Math.min(0, bonus(p, e.getKey()));
                need[i] = Math.max(need[i], (int) Math.ceil(e.getValue() - prefix[i] - own));
            }
            if (p.helpsRequirements()) {
                for (SkillKind sk : SkillKind.values()) {
                    prefix[sk.ordinal()] += bonus(p, sk);
                }
            }
        }
        for (Damage.Piece p : order) {
            for (var e : p.reqs().entrySet()) {
                int i = e.getKey().ordinal();
                double own = p.helpsRequirements() ? Math.max(0, bonus(p, e.getKey())) : 0;
                need[i] = Math.max(need[i], (int) Math.ceil(e.getValue() - (prefix[i] - own)));
            }
        }
        for (int i = 0; i < n; i++) {
            need[i] = Math.max(0, need[i]);
        }
        return need;
    }

    /** Points missing per skill when equipping in the best greedy order with these assigned. */
    private static int[] equipDeficit(List<Damage.Piece> list, int[] assigned) {
        int n = SkillKind.values().length;
        double[] have = new double[n];
        for (int i = 0; i < n; i++) {
            have[i] = assigned[i];
        }
        List<Damage.Piece> left = new ArrayList<>(list);
        while (!left.isEmpty()) {
            Damage.Piece next = null;
            double bestGive = Double.NEGATIVE_INFINITY;
            for (Damage.Piece p : left) {
                if (!fits(p, have)) {
                    continue;
                }
                double give = 0;
                if (p.helpsRequirements()) {
                    for (SkillKind s : SkillKind.values()) {
                        give += bonus(p, s);
                    }
                }
                if (give > bestGive) {
                    bestGive = give;
                    next = p;
                }
            }
            if (next == null) {
                break;
            }
            left.remove(next);
            if (next.helpsRequirements()) {
                for (SkillKind s : SkillKind.values()) {
                    have[s.ordinal()] += bonus(next, s);
                }
            }
        }
        int[] deficit = new int[n];
        // whatever could not go on, plus anything a later penalty pushed out ("pops off")
        for (Damage.Piece p : list) {
            boolean stuck = left.contains(p);
            for (var e : p.reqs().entrySet()) {
                int i = e.getKey().ordinal();
                double own = p.helpsRequirements() ? Math.max(0, bonus(p, e.getKey())) : 0;
                double avail = have[i] - own;
                if (stuck || avail < e.getValue()) {
                    deficit[i] = Math.max(deficit[i], (int) Math.ceil(e.getValue() - avail));
                }
            }
        }
        return deficit;
    }

    private static boolean fits(Damage.Piece p, double[] have) {
        for (var e : p.reqs().entrySet()) {
            double own = Math.min(0, bonus(p, e.getKey()));
            if (have[e.getKey().ordinal()] + own < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    /**
     * What a set holds per skill toward requirements: the assigned points plus the bonuses of
     * the pieces whose bonuses count for others (indexed by SkillKind.ordinal()).
     */
    static double[] cover(Map<String, Damage.Piece> build, int[] alloc) {
        double[] c = new double[alloc.length];
        for (int i = 0; i < c.length; i++) {
            c[i] = alloc[i];
        }
        for (Damage.Piece p : build.values()) {
            if (p != null && p.helpsRequirements()) {
                for (SkillKind s : SkillKind.values()) {
                    c[s.ordinal()] += bonus(p, s);
                }
            }
        }
        return c;
    }

    private static double bonus(Damage.Piece p, SkillKind s) {
        return p.stats().getOrDefault(RAW_KEY[s.ordinal()], 0.0);
    }

    /**
     * The skill points what you wear gives, from the pieces' own stats. Wynntils' gear count
     * reads 0 with the game in Turkish, which marked met requirements unmet.
     */
    static int wornBonus(SkillKind s) {
        Plan p = plan;
        if (p == null || p.worn() == null) {
            return 0;
        }
        double sum = 0;
        for (var e : p.worn().entrySet()) {
            // armour and accessories only: the game's totals leave the weapon's points out
            // (Character Info showed Int 27 = 19 assigned + 8 from gear, not the relik's +10)
            if (e.getValue() != null && !e.getKey().equals("WEAPON")) {
                sum += bonus(e.getValue(), s);
            }
        }
        return (int) Math.round(sum);
    }

    /**
     * Your skill points in one skill: assigned as the Character Info menu last showed them
     * (MenuWatcher; Wynntils' own count reads 0 with the game in Turkish), gear from what you
     * wear when Wynntils has less, tomes and set bonuses from Wynntils.
     */
    static int totalPoints(SkillKind s) {
        int gear = Math.max(Wyn.gearPoints(s), wornBonus(s));
        return MenuWatcher.assigned(s) + gear + Wyn.tomePoints(s) + Wyn.setBonusPoints(s);
    }

    /** "rawStrength" … per skill, built once: bonus() runs millions of times for a road map. */
    private static final String[] RAW_KEY = java.util.Arrays.stream(SkillKind.values())
            .map(s -> "raw" + s.name().charAt(0) + s.name().substring(1).toLowerCase(java.util.Locale.ROOT))
            .toArray(String[]::new);

    /**
     * The build's score with one slot swapped for a candidate, keeping the given skill points
     * but moving just enough of them to meet the candidate's requirements. NaN if impossible.
     * Cheap: one evaluation, for ranking hundreds of items.
     */
    static double trySlot(Map<String, Damage.Piece> build, int[] alloc, String slot, Damage.Piece cand, int level,
            int earned) {
        Eval e = tryEval(build, alloc, slot, cand, level, earned);
        return e == null ? Double.NaN : e.result().objective();
    }

    /** {@link #trySlot} with the skill points it ended on; null if impossible. */
    static Eval tryEval(Map<String, Damage.Piece> build, int[] alloc, String slot, Damage.Piece cand, int level,
            int earned) {
        Map<String, Damage.Piece> trial = new LinkedHashMap<>(build);
        trial.put(slot, cand);
        int n = SkillKind.values().length;
        int[] min = minPoints(trial.values());
        int[] a = alloc.clone();
        int deficit = 0;
        for (int i = 0; i < n; i++) {
            int want = min[i];
            if (a[i] < want) {
                deficit += want - a[i];
                a[i] = want;
            }
        }
        List<Damage.Piece> armour = armour(trial);
        Damage.Piece weapon = trial.get("WEAPON");
        // take the deficit from the skill whose loss costs least: the one holding the most points
        // is mostly the main damage skill, and the piece's gain vanished into its loss
        Damage.Result last = null;
        while (deficit > 0) {
            int from = -1;
            int fromTake = 0;
            Damage.Result fromRes = null;
            for (int i = 0; i < n; i++) {
                if (a[i] <= min[i]) {
                    continue;
                }
                int take = Math.min(deficit, a[i] - min[i]);
                a[i] -= take;
                Damage.Result r = Damage.evaluate(armour, weapon, a, level);
                a[i] += take;
                if (fromRes == null || r.objective() > fromRes.objective()) {
                    from = i;
                    fromTake = take;
                    fromRes = r;
                }
            }
            if (from < 0) {
                return null;
            }
            a[from] -= fromTake;
            deficit -= fromTake;
            last = fromRes;
        }
        int used = 0;
        for (int v : a) {
            used += v;
        }
        if (used > earned) {
            return null;
        }
        return new Eval(a, last != null ? last : Damage.evaluate(armour, weapon, a, level));
    }

    /** {@link #whyNot(Map, int, int[])} without knowing what other gear could give. */
    static String whyNot(Map<String, Damage.Piece> build, int earned) {
        return whyNot(build, earned, null);
    }

    /**
     * Why a build has no allocation ({@link #allocate} gives null), for the road map trace: a
     * skill that would need more than 100 assigned after the gear's own points, or more points
     * in all than earned. Past 100 is only impossible when even the most the milestone's other
     * gear could give there ({@code reach}, per skill: the best bonus of each armour and
     * accessory slot in its window, and your tomes) leaves it past 100; otherwise the skill needs
     * that much from gear (Ascended Guardian against an Intelligence set: 10 Defence from the
     * armour, not "impossible"). Null when it fits.
     */
    static String whyNot(Map<String, Damage.Piece> build, int earned, int[] reach) {
        int[] min = minPoints(build.values());
        StringBuilder over = new StringBuilder();
        StringBuilder fromGear = new StringBuilder();
        int used = 0;
        for (SkillKind k : SkillKind.values()) {
            int v = min[k.ordinal()];
            used += v;
            if (v <= 100) {
                continue;
            }
            // what the build's own gear gives here already counts in v; reach is what the slots
            // could give at best, so v past 100 by more than that cannot be met by any set
            int could = reach == null ? 0 : reach[k.ordinal()];
            String name = k.name().toLowerCase(java.util.Locale.ROOT);
            if (v - could > 100) {
                over.append(over.length() > 0 ? ", " : "").append(v).append(' ').append(name);
            } else {
                fromGear.append(fromGear.length() > 0 ? ", " : "").append(v - 100).append(' ').append(name);
            }
        }
        if (over.length() > 0) {
            return L.t("wynnlabs.road.why_over", over);
        }
        if (fromGear.length() > 0) {
            return L.t("wynnlabs.road.why_from_gear", fromGear);
        }
        if (used > earned) {
            return L.t("wynnlabs.road.why_points", used, earned, used - earned);
        }
        return null;
    }

    /**
     * The most points per skill a set could get from gear other than a build's own pieces:
     * each armour and accessory slot's best bonus there among {@code pieces} (the two rings
     * twice), plus your tomes. For {@link #whyNot}.
     */
    static int[] reach(java.util.Collection<Damage.Piece> pieces) {
        int n = SkillKind.values().length;
        Map<String, double[]> bySlot = new java.util.HashMap<>();
        for (Damage.Piece p : pieces) {
            if (p == null || p.type() == null || p.type().isWeapon() || !p.helpsRequirements()) {
                continue;
            }
            double[] b = bySlot.computeIfAbsent(p.type().name(), k -> new double[n]);
            for (SkillKind s : SkillKind.values()) {
                b[s.ordinal()] = Math.max(b[s.ordinal()], bonus(p, s));
            }
        }
        int[] out = new int[n];
        for (SkillKind s : SkillKind.values()) {
            double sum = 0;
            for (var e : bySlot.entrySet()) {
                sum += e.getValue()[s.ordinal()] * (e.getKey().equals(Slot.RING.name()) ? 2 : 1);
            }
            out[s.ordinal()] = (int) Math.floor(sum) + Wyn.tomePoints(s);
        }
        return out;
    }

    /**
     * How far a build is from wearable: the points past 100 in any skill plus the points it needs
     * beyond those earned; 0 when {@link #allocate} can spread them.
     */
    static int shortfall(Map<String, Damage.Piece> build, int earned) {
        int[] min = minPoints(build.values());
        int over = 0;
        int used = 0;
        for (int v : min) {
            over += Math.max(0, v - 100);
            used += v;
        }
        return over + Math.max(0, used - earned);
    }

    /**
     * Makes room for the pieces in {@code fixed} (build slots the player chose): while the skill
     * points cannot be met, the piece outside them whose leaving brings the build closest to
     * wearable comes out (on a tie the lowest level). The slots emptied, in order; the build is
     * changed in place. Stops with what is left when only the fixed pieces remain.
     */
    static List<String> fitAround(Map<String, Damage.Piece> build, java.util.Set<String> fixed, int earned) {
        List<String> dropped = new ArrayList<>();
        int now = shortfall(build, earned);
        while (now > 0) {
            String pick = null;
            int best = Integer.MAX_VALUE;
            int bestLevel = Integer.MAX_VALUE;
            for (var e : build.entrySet()) {
                if (fixed.contains(e.getKey()) || e.getValue() == null) {
                    continue;
                }
                Map<String, Damage.Piece> trial = new LinkedHashMap<>(build);
                trial.remove(e.getKey());
                int s = shortfall(trial, earned);
                if (s < best || s == best && e.getValue().level() < bestLevel) {
                    best = s;
                    bestLevel = e.getValue().level();
                    pick = e.getKey();
                }
            }
            if (pick == null) {
                break;
            }
            build.remove(pick);
            dropped.add(pick);
            now = best;
        }
        return dropped;
    }

    /**
     * Skill points for a build: the least that meets every requirement (counting the skill
     * points the gear gives), then the rest one step at a time to the skill that helps most.
     * Null when the requirements need more points than you have.
     */
    static Eval allocate(Map<String, Damage.Piece> build, int level, int earned) {
        Memo mm = memo();
        if (mm == null) {
            return allocateFresh(build, level, earned);
        }
        return mm.get(mm.greedy, memoKey(build, level, earned), () -> allocateFresh(build, level, earned));
    }

    private static Eval allocateFresh(Map<String, Damage.Piece> build, int level, int earned) {
        int n = SkillKind.values().length;
        int[] min = minPoints(build.values());
        int[] alloc = new int[n];
        int used = 0;
        for (int i = 0; i < n; i++) {
            alloc[i] = min[i];
            if (alloc[i] > 100) {
                return null;
            }
            used += alloc[i];
        }
        if (used > earned) {
            return null;
        }
        List<Damage.Piece> armour = armour(build);
        Damage.Piece weapon = build.get("WEAPON");
        int left = earned - used;
        // The free points go wherever the model gains most. rules.model.skillPriority only breaks
        // ties now: a fixed 60 % share for it pulled points out of damage (a Lv 120 ring lost a
        // third of its spell damage that way)
        List<String> prio = Rules.list("model", "skillPriority");
        int step = Math.max(1, left / 25);
        while (left > 0) {
            int give = Math.min(step, left);
            int bestSkill = -1;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                if (alloc[i] + give > 100) {
                    continue;
                }
                alloc[i] += give;
                double sc = Damage.evaluate(armour, weapon, alloc, level).objective();
                alloc[i] -= give;
                boolean tie = Math.abs(sc - bestScore) <= 1e-9 * Math.max(1, Math.abs(sc));
                if (sc > bestScore && !tie || tie && bestSkill >= 0 && prio.contains(SkillKind.values()[i].name())
                        && !prio.contains(SkillKind.values()[bestSkill].name())) {
                    bestScore = sc;
                    bestSkill = i;
                }
            }
            if (bestSkill < 0) {
                break;
            }
            alloc[bestSkill] += give;
            left -= give;
        }
        return new Eval(alloc, Damage.evaluate(armour, weapon, alloc, level));
    }

    /**
     * {@link #allocate}, then a local search on its spread: points moved from one skill to
     * another (20, 10, 5, 3 or 1 at a time, never under a requirement or past 100), the best move
     * kept until none helps. The greedy pass gives each chunk to the best single skill, so a
     * skill that only pays off in quantity (Defence and Agility through effective health,
     * Intelligence through spell cost) starved. Several times the cost of {@link #allocate}:
     * for the spreads you are shown, not for ranking candidates.
     */
    static Eval allocateBest(Map<String, Damage.Piece> build, int level, int earned) {
        Memo mm = memo();
        if (mm == null) {
            Eval e = allocate(build, level, earned);
            return e == null ? null : improve(build, e, level);
        }
        return mm.get(mm.best, memoKey(build, level, earned), () -> {
            Eval e = allocate(build, level, earned);
            return e == null ? null : improve(build, e, level);
        });
    }

    /**
     * One run's memo of {@link #allocate} and {@link #allocateBest}: a road map judges the same
     * set many times (each start of its search, the rows, the comparison panel), and the spread
     * depends only on the set, the level and the points earned while the run's rules and tuning
     * hold. Set for a thread with {@link #withMemo}; the search's workers share one. Kept for
     * one milestone ({@link #clear}), so it never grows past what one milestone tried.
     */
    static final class Memo {
        final java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<Eval>> greedy =
                new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<Eval>> best =
                new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.concurrent.atomic.AtomicLong hits = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong misses = new java.util.concurrent.atomic.AtomicLong();

        private Eval get(java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<Eval>> in, String key,
                java.util.function.Supplier<Eval> work) {
            java.util.Optional<Eval> had = in.get(key);
            if (had != null) {
                hits.incrementAndGet();
                return had.orElse(null);
            }
            misses.incrementAndGet();
            // computed outside the map's lock: two threads may both work one out, the same
            Eval e = work.get();
            in.putIfAbsent(key, java.util.Optional.ofNullable(e));
            return e;
        }

        void clear() {
            greedy.clear();
            best.clear();
        }
    }

    /** The memo's slot per thread, made on first use (a field set where it is declared stays null after a hot swap). */
    private static volatile ThreadLocal<Memo> memoSlot;

    private static ThreadLocal<Memo> memoSlot() {
        ThreadLocal<Memo> t = memoSlot;
        if (t == null) {
            synchronized (Build.class) {
                if (memoSlot == null) {
                    memoSlot = new ThreadLocal<>();
                }
                t = memoSlot;
            }
        }
        return t;
    }

    /** The memo in force on this thread, or null. */
    static Memo memo() {
        ThreadLocal<Memo> t = memoSlot;
        return t == null ? null : t.get();
    }

    /** Runs {@code work} with {@code m} as this thread's memo (null: none), the one before restored after. */
    static <T> T withMemo(Memo m, java.util.function.Supplier<T> work) {
        ThreadLocal<Memo> t = memoSlot();
        Memo before = t.get();
        t.set(m);
        try {
            return work.get();
        } finally {
            if (before == null) {
                t.remove();
            } else {
                t.set(before);
            }
        }
    }

    /** A set as its slots and pieces in the map's order (the order the model sums them in), the level and the points. */
    private static String memoKey(Map<String, Damage.Piece> build, int level, int earned) {
        StringBuilder k = new StringBuilder(256);
        k.append(level).append('/').append(earned);
        for (var e : build.entrySet()) {
            k.append(';').append(e.getKey()).append('=').append(e.getValue() == null ? "-" : e.getValue().key());
        }
        return k.toString();
    }

    /** The local search of {@link #allocateBest} from {@code start}'s spread; deterministic. */
    static Eval improve(Map<String, Damage.Piece> build, Eval start, int level) {
        int n = SkillKind.values().length;
        int[] min = minPoints(build.values());
        List<Damage.Piece> armour = armour(build);
        Damage.Piece weapon = build.get("WEAPON");
        int[] a = start.allocation().clone();
        Damage.Result cur = start.result();
        int[] moves = {20, 10, 5, 3, 1};
        for (int round = 0; round < 200; round++) {
            int bestFrom = -1;
            int bestTo = -1;
            int bestK = 0;
            Damage.Result bestRes = null;
            double bar = cur.objective() + 1e-9 * Math.max(1, Math.abs(cur.objective()));
            for (int k : moves) {
                for (int from = 0; from < n; from++) {
                    if (a[from] - k < min[from]) {
                        continue;
                    }
                    for (int to = 0; to < n; to++) {
                        if (to == from || a[to] + k > 100) {
                            continue;
                        }
                        a[from] -= k;
                        a[to] += k;
                        Damage.Result r = Damage.evaluate(armour, weapon, a, level);
                        a[from] += k;
                        a[to] -= k;
                        if (r.objective() > bar && (bestRes == null || r.objective() > bestRes.objective())) {
                            bestFrom = from;
                            bestTo = to;
                            bestK = k;
                            bestRes = r;
                        }
                    }
                }
            }
            if (bestRes == null) {
                break;
            }
            a[bestFrom] -= bestK;
            a[bestTo] += bestK;
            cur = bestRes;
        }
        return new Eval(a, cur);
    }

    static JsonObject json() {
        Plan p = plan;
        JsonObject o = new JsonObject();
        if (p == null) {
            o.addProperty("note", "no plan yet (needs a character in game)");
            return o;
        }
        JsonObject pieces = new JsonObject();
        p.pieces().forEach((slot, piece) -> {
            JsonObject j = new JsonObject();
            j.addProperty("name", piece == null ? null : piece.name());
            Damage.Piece w = p.worn().get(slot);
            j.addProperty("worn", w == null ? null : w.name());
            j.addProperty("change", piece != null && (w == null || !piece.key().equals(w.key())));
            pieces.add(slot, j);
        });
        o.add("slots", pieces);
        JsonObject skills = new JsonObject();
        for (SkillKind s : SkillKind.values()) {
            JsonObject j = new JsonObject();
            j.addProperty("target", p.wornTarget()[s.ordinal()]);
            j.addProperty("targetForBestBuild", p.allocation()[s.ordinal()]);
            j.addProperty("assigned", p.wornAllocation()[s.ordinal()]);
            skills.add(s.name(), j);
        }
        o.add("skillPoints", skills);
        o.addProperty("earnedPoints", p.earned());
        o.add("planned", Damage.json(p.result()));
        o.add("current", Damage.json(p.current()));
        o.addProperty("gainPercent", Math.round(p.gainPercent() * 10) / 10.0);
        int[] need = minPoints(p.pieces().values());
        JsonObject np = new JsonObject();
        for (SkillKind sk : SkillKind.values()) {
            np.addProperty(sk.name(), need[sk.ordinal()]);
        }
        o.add("minPoints", np);
        JsonArray notes = new JsonArray();
        notes.add("objective = (spell DPS + mainAttackWeight x main DPS) x XP x speed x survival, see rules.model");
        notes.add("parts: the factors the objective multiplies (damage is the weighted DPS sum; life uses effectiveHealthElemental)");
        o.add("notes", notes);
        return o;
    }
}
