package io.github.anilken.wynnlabs.build;

import io.github.anilken.wynnlabs.build.roadmap.Roadmap;
import io.github.anilken.wynnlabs.game.FixtureState;
import io.github.anilken.wynnlabs.game.Fixtures;
import io.github.anilken.wynnlabs.model.Klass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The role goals change what the road map builds (on two classes, the play styles on one): the Tank's far milestone set has the most
 * effective health, the Healer's heals more than the Damage one (max health × Healing
 * Efficiency × casts), and the Damage one hits hardest.
 */
class RoleGoalTest {

    @AfterEach
    void reset() {
        FixtureState.uninstall();
    }

    private record Far(double ehp, double heal, double damage, double perCast, double xp, double speed, double loot, double casts) {}

    private static Far far(Klass k, String goal) throws Exception {
        return far(k, goal, 120);
    }

    /** The set of the milestone at {@code level} (or the last before it) for class {@code k} and the goal. */
    private static Far far(Klass k, String goal, int level) throws Exception {
        FixtureState.load(Fixtures.root().resolve("shaman-44")).as(k).install("en");
        Setup.setGoal(goal);
        List<Roadmap.Step> steps = Roadmap.build();
        Roadmap.Step last = steps.get(0);
        for (Roadmap.Step st : steps) {
            if (st.level() <= level) {
                last = st;
            }
        }
        Build.Eval e = last.set();
        assertTrue(e != null, goal + ": no set at Lv " + last.level());
        Map<String, Damage.Piece> b = last.build();
        double health = 0;
        double eff = 0;
        double loot = 0;
        for (Damage.Piece p : b.values()) {
            if (p != null) {
                health += p.health();
                eff += p.stats().getOrDefault("healingEfficiency", 0.0);
                loot += p.stats().getOrDefault("lootBonus", 0.0) + p.stats().getOrDefault("lootQuality", 0.0);
            }
        }
        Damage.Result r = e.result();
        Far f = new Far(r.ehp(), health * (1 + eff / 100) * r.casts(), r.spellDps() + r.mainDps(), r.dmgPerCast(),
                r.xp(), r.speed(), loot, r.casts());
        Fixtures.note(String.format("%s Lv %d: ehp %.0f, heal %.0f, damage %.0f, per cast %.0f, xp %.0f, speed %.0f, loot %.0f, casts %.2f, points %s",
                goal, last.level(), f.ehp(), f.heal(), f.damage(), f.perCast(), f.xp(), f.speed(), f.loot(), f.casts(),
                java.util.Arrays.toString(e.allocation())).replaceFirst("^", k + " "));
        FixtureState.uninstall();
        return f;
    }

    @Test
    void rolesBuildForTheirJobInEveryClass() throws Exception {
        // two classes with unlike weapons and damage taken: all five took a minute and a half
        for (Klass k : new Klass[] {Klass.SHAMAN, Klass.WARRIOR}) {
            Far damage = far(k, "DAMAGE");
            Far tank = far(k, "TANK");
            Far healer = far(k, "HEALER");
            assertTrue(tank.ehp() > damage.ehp(), k + ": tank ehp " + tank.ehp() + " vs damage " + damage.ehp());
            assertTrue(tank.ehp() >= healer.ehp(), k + ": tank ehp " + tank.ehp() + " vs healer " + healer.ehp());
            assertTrue(healer.heal() > damage.heal(), k + ": healer heal " + healer.heal() + " vs damage " + damage.heal());
            assertTrue(damage.damage() > tank.damage(), k + ": damage " + damage.damage() + " vs tank " + tank.damage());
        }
    }

    @Test
    void playStylesBuildForTheirJob() throws Exception {
        // the levelling goals only matter before the cap, so they are judged at the Lv 90 milestone
        for (Klass k : new Klass[] {Klass.SHAMAN}) {
            Far damage = far(k, "DAMAGE");
            Far boss = far(k, "BOSS");
            Far mobbing = far(k, "MOBBING");
            Far loot = far(k, "LOOT");
            assertTrue(boss.perCast() >= damage.perCast(), k + ": boss per cast " + boss.perCast() + " vs damage " + damage.perCast());
            // packs need casting without pause, and running from one to the next
            assertTrue(mobbing.casts() > damage.casts() || mobbing.speed() > damage.speed(),
                    k + ": mobbing casts " + mobbing.casts() + " speed " + mobbing.speed() + " vs damage " + damage.casts() + " " + damage.speed());
            assertTrue(loot.loot() > damage.loot(), k + ": loot " + loot.loot() + " vs damage " + damage.loot());
            Far xpDamage = far(k, "DAMAGE", 90);
            Far xp = far(k, "XP", 90);
            assertTrue(xp.xp() > xpDamage.xp(), k + ": xp " + xp.xp() + " vs damage " + xpDamage.xp());
        }
    }
}
