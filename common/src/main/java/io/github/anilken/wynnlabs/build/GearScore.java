package io.github.anilken.wynnlabs.build;

import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.game.WornGear;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.Roll;
import io.github.anilken.wynnlabs.model.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.Optional;

/**
 * How much a piece of gear is worth to the build, scored with the rule weights and the
 * weapon's damage, so pieces of one slot can be compared.
 */
public final class GearScore {

    private GearScore() {}

    /**
     * What a piece adds to the build, in %, worn in its best slot at its own level (or yours,
     * whichever is higher) with the points that level brings; NaN when it does not fit.
     */
    public static double buildGain(Build.Plan plan, ItemData info, int level) {
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
        double base = plan.objectiveAt(lv);
        return Double.isNaN(best) || base <= 0 ? Double.NaN : (best / base - 1) * 100;
    }

    /** Score of any gear you can wear, crafted or not; NaN for anything else. */
    public static double scoreAny(ItemStack stack) {
        Optional<ItemView> p = stack.isEmpty() ? Optional.empty() : GameState.current().view(stack);
        if (p.isPresent() && p.get().isGear()) {
            return score(p.get());
        }
        if (p.isPresent() && p.get().isCrafted()) {
            return score(p.get().crafted());
        }
        return Double.NaN;
    }

    /** Crafted gear scored like the rest: level, DPS or health, then its rolled stats. */
    public static double score(ItemView.Crafted c) {
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

    /**
     * A rough strength number from the rule weights: level and rarity, the weapon's average DPS
     * or the armour's health, then each rolled stat (capped, so one big roll cannot win alone).
     */
    public static double score(ItemView g) {
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
    public static double score(ItemData info) {
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
                double d = scoreDps(WornGear.bestWeapon());
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
}
