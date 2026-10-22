package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.SkillKind;
import io.github.anilken.wynnlabs.model.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What you have, as the road map judges it: the copies you own with their real rolls, how many
 * of each, the piece a candidate is judged as (yours, or a fresh one to buy), your pieces with
 * their empty powder slots filled, and the skill points you placed.
 */
final class OwnedGear {

    private OwnedGear() {}

    /**
     * The copies you have, worked out at the start of each run for the game state it ran on:
     * by name, the best roll you have (worn, carried, or in a bank you can reach) as a piece with
     * its real rolls and its empty powder slots filled, and that copy's overall roll (NaN when
     * the item shows none). Null before the first run; no initializer (a hot swap leaves none).
     */
    private static volatile Owned ownedRolls;

    private record Owned(GameState state, Map<String, Damage.Piece> best, Map<String, Float> percent) {}

    /** {@link #ownedRolls} for {@code state}: every copy you can reach, the best roll per name kept. */
    static void readOwned(GameState state) {
        List<ItemStack> stacks = new ArrayList<>();
        try {
            for (Slot t : Slot.values()) {
                if (t.isArmour() || t.isAccessory() || t.klass() != null) {
                    stacks.addAll(AutoAdvice.worn(t));
                }
            }
            stacks.addAll(state.inventory());
            // only banks you can open from here, as ownedCounts counts them
            for (Vault.Group g : Vault.groups()) {
                if (g.reachable()) {
                    for (Vault.Stored s : g.places()) {
                        if (s.gear() && Vault.reachable(s.page())) {
                            stacks.add(Vault.stackOf(s));
                        }
                    }
                }
            }
        } catch (Throwable t) {
            // a menu changed under us: what was read so far
        }
        Map<String, Damage.Piece> best = new HashMap<>();
        Map<String, Float> percent = new HashMap<>();
        for (ItemStack s : stacks) {
            try {
                if (s == null || s.isEmpty()) {
                    continue;
                }
                var v = state.view(s);
                if (v.isEmpty() || !v.get().isGear() || v.get().unidentified()) {
                    continue;                           // a bank copy saved without its item: judged at the middle roll
                }
                Damage.Piece p = Damage.piece(s);
                if (p == null) {
                    continue;
                }
                float q = v.get().rolled().hasOverall() ? v.get().rolled().overallPercent() : Float.NaN;
                Float had = percent.get(p.name());
                // the first copy found stays on a tie (worn before carried before banked); a
                // known roll beats an unknown one
                if (had == null || !Float.isNaN(q) && (Float.isNaN(had) || q > had)) {
                    best.put(p.name(), Damage.withAssumedPowders(p));
                    percent.put(p.name(), q);
                }
            } catch (Throwable t) {
                // a stack that cannot be read: left out
            }
        }
        ownedRolls = new Owned(state, best, percent);
    }

    /**
     * The piece the road map judges {@code info} as: the best copy you have, with its own rolls,
     * when it knows one; else a piece you would buy, every roll at the middle of its range.
     */
    static Damage.Piece pieceOf(ItemData info) {
        Damage.Piece own = owned(info.name());
        return own != null ? own : Damage.piece(info);
    }

    /**
     * The piece a milestone's slot ranking judges {@code info} as, or null to leave it out.
     * {@code wornRoll} is null when you do not wear it in that slot type, else the overall roll of
     * the copy you wear (NaN when it shows none). Not worn: {@link #pieceOf}. Worn: a better roll
     * you have elsewhere; else, when the worn copy rolled below rules.rerollBelowPercent (30 %),
     * a fresh copy to buy at the middle roll (it must then beat the worn one by
     * rules.shopping.minGainPercent); else nothing (what you wear is no upgrade).
     */
    static Damage.Piece judgeAs(ItemData info, Float wornRoll, java.util.Set<String> wornKeys, long budget,
            GameState state) {
        if (wornRoll == null) {
            return pieceOf(info);
        }
        Damage.Piece own = owned(info.name());
        float ownRoll = ownedPercent(info.name());
        if (own != null && !wornKeys.contains(own.key()) && !wornKeys.contains(own.key().replace("|powdered", ""))
                && !Float.isNaN(ownRoll) && (Float.isNaN(wornRoll) || ownRoll > wornRoll)) {
            return own;
        }
        if (!Float.isNaN(wornRoll) && wornRoll < Rules.num("rerollBelowPercent", 30)) {
            return fresh(info, budget, state);
        }
        return null;
    }

    /** A fresh copy of {@code info} to buy at the middle roll; null when it cannot be bought (untradable, over the budget). */
    static Damage.Piece fresh(ItemData info, long budget, GameState state) {
        if (!Shopping.tradable(info)) {
            return null;
        }
        double c = CandidateRules.cost(state, info);
        return budget > 0 && (c < 0 || c > budget) ? null : Damage.piece(info);
    }

    /** The best copy you have of {@code name} with its rolls ({@link #readOwned}), or null. */
    private static Damage.Piece owned(String name) {
        Owned o = ownedRolls;
        return o == null || o.state() != GameState.current() ? null : o.best().get(name);
    }

    /** The overall roll of the best copy you have of {@code name}: NaN when none is known or it shows none. */
    static float ownedPercent(String name) {
        Owned o = ownedRolls;
        Float q = o == null || o.state() != GameState.current() ? null : o.percent().get(name);
        return q == null ? Float.NaN : q;
    }

    /**
     * A piece from the item data for a name you have a known copy of: a fresh copy to buy, at
     * its price, instead of the one you have.
     */
    static boolean buysOwned(Damage.Piece p) {
        return p != null && p.stack() == null && owned(p.name()) != null;
    }

    /** {@code set} with every piece you have powdered in its empty slots ({@link Damage#withAssumedPowders}). */
    static Map<String, Damage.Piece> powdered(Map<String, Damage.Piece> set) {
        Map<String, Damage.Piece> out = new LinkedHashMap<>();
        set.forEach((slot, p) -> out.put(slot, Damage.withAssumedPowders(p)));
        return out;
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

    /** The skill points you placed, by skill; null while the Character Info menu has not shown them. */
    static int[] assignedPoints(GameState state) {
        int[] a = new int[SkillKind.values().length];
        int sum = 0;
        for (SkillKind s : SkillKind.values()) {
            a[s.ordinal()] = state.assigned(s);
            sum += a[s.ordinal()];
        }
        return sum > 0 ? a : null;
    }
}
