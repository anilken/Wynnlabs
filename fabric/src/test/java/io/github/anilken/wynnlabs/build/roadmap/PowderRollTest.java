package io.github.anilken.wynnlabs.build.roadmap;

import com.google.gson.JsonObject;
import io.github.anilken.wynnlabs.build.Build;
import io.github.anilken.wynnlabs.build.Damage;
import io.github.anilken.wynnlabs.build.Tuning;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.game.Fixture;
import io.github.anilken.wynnlabs.game.FixtureState;
import io.github.anilken.wynnlabs.game.Fixtures;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.market.Shopping;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.Roll;
import io.github.anilken.wynnlabs.model.StatRange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The road map weighs what you have and what you would buy alike: both with their powder slots
 * filled (what powdering your own pieces adds is a row of its own, with its cost), a copy you
 * have with its real rolls, and a badly rolled piece you wear against a fresh copy. On the
 * recorded Shaman at Lv 44, whose Heavensent (two slots) and Yume (one) carry no powders.
 */
class PowderRollTest {

    @AfterEach
    void reset() {
        FixtureState.uninstall();
        BeamCache.clear();
    }

    private static Path dir() {
        Path dir = Fixtures.root().resolve("shaman-44");
        assumeTrue(Files.exists(dir.resolve("state.json")), "no shaman-44 fixture");
        return dir;
    }

    private static ItemData gear(String name) {
        return GameState.current().allGear().filter(i -> i.name().equals(name)).findFirst().orElseThrow();
    }

    /**
     * The recorded Shaman with the stack named {@code name} rolled anew: each roll at {@code at}
     * of its range from the worst end (0 the worst, 1 the best) and the overall roll {@code pct};
     * installed.
     */
    private static void shaman(Path tmp, String name, double at, float pct) throws Exception {
        BeamCache.clear();
        Fixture.Data d = Fixture.read(dir());
        List<Fixture.Held> stacks = new ArrayList<>();
        for (Fixture.Held h : d.items().stacks()) {
            stacks.add(h.name().equals(name) ? new Fixture.Held(h.name(), h.count(), rolled(h.view(), at, pct)) : h);
        }
        Fixture.Items it = d.items();
        Path out = tmp.resolve(name.replace(' ', '_') + "-" + Math.round(at * 100) + "-" + Math.round(pct));
        Fixture.write(new Fixture.Data(d.state(), new Fixture.Items(stacks, it.inventory(), it.armour(), it.accessories(),
                it.mainHand(), it.bank()), d.gearDb(), d.prices(), d.rules(), d.settings(), d.tree()), out, null);
        FixtureState.load(out).install("en");
    }

    private static ItemView rolled(ItemView v, double at, float pct) {
        List<Roll> rolls = new ArrayList<>();
        for (Roll r : v.rolled().rolls()) {
            StatRange s = r.range();
            int value = r.value();
            if (s != null) {
                double worst = s.inverted() ? s.max() : s.min();
                double best = s.inverted() ? s.min() : s.max();
                value = (int) Math.round(worst + (best - worst) * at);
            }
            rolls.add(new Roll(r.key(), r.name(), r.unit(), value, s));
        }
        ItemView.Rolled ro = v.rolled();
        return new ItemView(v.kind(), v.type(), v.plain(), v.name(), v.level(), v.professions(), v.data(),
                new ItemView.Rolled(rolls, ro.powders(), ro.rerolls(), pct, ro.shiny(), ro.marks()), v.crafted(), v.box(),
                v.qualityTier(), v.powder(), v.potion(), v.dungeonKey(), v.skillPoint(), v.crystalCount(), v.abilityName());
    }

    /** The % {@code set} gains with {@code p} in {@code slot}, the skill points spread anew. */
    private static double gain(Map<String, Damage.Piece> set, String slot, Damage.Piece p, int m, int earned) {
        Map<String, Damage.Piece> trial = new LinkedHashMap<>(set);
        trial.put(slot, p);
        return (Build.allocate(trial, m, earned).result().objective() / Build.allocate(set, m, earned).result().objective() - 1) * 100;
    }

    /** The "now" milestone alone, as the Home tab's first pill shows it. */
    private static Roadmap.Step now() {
        return Roadmap.compute(new Roadmap.Inputs(List.of(), Tuning.Choice.PLAIN, true, 0), null).steps().get(0);
    }

    @Test
    void whatYouHaveAndWhatYouWouldBuyCarryTheSamePowders(@TempDir Path tmp) throws Exception {
        // the worn Heavensent rolled at the middle: a bought one is the same piece
        shaman(tmp, "Heavensent", 0.5, 50f);
        Build.Plan plan = Build.plan();
        Damage.Piece worn = plan.pieces().get("WEAPON");
        assertEquals("Heavensent", worn.name());
        Damage.Piece bought = Damage.piece(gear("Heavensent"));
        Damage.Piece own = Damage.withAssumedPowders(worn);
        assertTrue(own.baseDps() > worn.baseDps() * 1.05, "two powders add damage");
        assertEquals(bought.baseDps(), own.baseDps(), 1e-9, "the same powders on both sides");
        assertEquals(bought.weaponShares(), own.weaponShares());
        Damage.Piece yume = plan.pieces().get("HELMET");
        assertEquals("Yume", yume.name());
        assertEquals(Damage.piece(gear("Yume")).health(), Damage.withAssumedPowders(yume).health(), 1e-9,
                "armour: the same health from its slot");
        assertTrue(Damage.withAssumedPowders(yume).health() > yume.health());
        // the build plan's "now" keeps what you have as it is
        assertEquals(worn.baseDps(), Build.plan().pieces().get("WEAPON").baseDps(), 1e-9);
        // a bought copy rolled as yours gains nothing on the road map's powdered build; against
        // the build as worn, its powders alone looked like an upgrade
        int m = plan.level();
        int earned = plan.earned();
        double fair = gain(Roadmap.powdered(plan.pieces()), "WEAPON", bought, m, earned);
        double inflated = gain(plan.pieces(), "WEAPON", bought, m, earned);
        Fixtures.note(String.format(java.util.Locale.ROOT, "a bought Heavensent: %+.1f %% (both powdered), %+.1f %% (powders on one side)",
                fair, inflated));
        assertTrue(Math.abs(fair) < 2, "same piece, same powders: " + fair);
        assertTrue(inflated > fair + 5, "the powders were the gain: " + inflated);
    }

    @Test
    void powderingYourOwnPiecesIsARowWithItsCost() throws Exception {
        BeamCache.clear();
        FixtureState.load(dir()).install("en");
        Roadmap.Step now = now();
        Roadmap.Gear row = now.gear().stream().filter(g -> g.slot().equals("POWDER:WEAPON")).findFirst().orElse(null);
        assertNotNull(row, "a powder row for the weapon: " + now.gear().stream().map(Roadmap.Gear::slot).toList());
        assertTrue(Roadmap.isPowder(row));
        assertEquals("Heavensent", row.info().name());
        // what the powders add to the build you own, as the row says it
        Build.Plan plan = Build.plan();
        double expect = gain(plan.pieces(), "WEAPON", Damage.withAssumedPowders(plan.pieces().get("WEAPON")),
                plan.level(), plan.earned());
        assertEquals(expect, row.gain(), 1e-6);
        assertTrue(row.gain() > 1, "two empty slots on the weapon are worth a row");
        int tier = Damage.powderTier(gear("Heavensent").requirements().level());
        assertTrue(row.source().contains(Prices.fmt(2 * Roadmap.powderPrice(tier))), row.source());
        assertNull(row.price(), "no Trade Market price: you have the piece");
        // the milestones are judged powdered on both sides: the weapon's row is no new piece
        assertFalse(now.gear().stream().anyMatch(g -> !Roadmap.isPowder(g) && g.info().name().equals("Heavensent")));
    }

    @Test
    void aBoughtPieceCostsItsPowdersToo() throws Exception {
        BeamCache.clear();
        FixtureState.load(dir()).install("en");
        GameState state = GameState.current();
        ItemData w = state.allGear().filter(i -> i.slot() != null && i.slot().isWeapon() && i.powderSlots() > 0
                && Shopping.tradable(i) && Roadmap.cost(state, i) >= 0 && Damage.powderHint(i) != null)
                .findFirst().orElse(null);
        assumeTrue(w != null, "no priced weapon with powder slots");
        Map<String, Damage.Piece> set = new LinkedHashMap<>();
        set.put("WEAPON", Damage.piece(w));
        double with = Roadmap.spend(state, set, java.util.Set.of(), java.util.Set.of());
        JsonObject off = new JsonObject();
        JsonObject model = new JsonObject();
        model.addProperty("assumePowders", 0);
        off.add("model", model);
        Rules.update(off);
        double without = Roadmap.spend(state, set, java.util.Set.of(), java.util.Set.of());
        int tier = Damage.powderTier(w.requirements().level());
        assertEquals(w.powderSlots() * Roadmap.powderPrice(tier), with - without, 1e-6, w.name());
        // what you have costs nothing, its powders included (the powder rows say what they cost)
        set.put("WEAPON", Damage.withAssumedPowders(Build.plan().pieces().get("WEAPON")));
        assertEquals(0, Roadmap.spend(state, set, java.util.Set.of(), java.util.Set.of()), 1e-9);
    }

    @Test
    void aCopyYouHaveIsJudgedAtItsOwnRoll(@TempDir Path tmp) throws Exception {
        shaman(tmp, "Heavensent", 0.0, 0f);
        now();                                              // the run reads what you have
        ItemData info = gear("Heavensent");
        Damage.Piece own = Roadmap.pieceOf(info);
        Damage.Piece mid = Damage.piece(info);
        assertNotNull(own.stack(), "the copy you have, not one from the item data");
        assertEquals(3, own.stats().get("combatExperience"), 1e-9, "its own 3 % XP, the worst roll");
        assertEquals(2, own.stats().get("manaRegen"), 1e-9);
        assertEquals(8, mid.stats().get("combatExperience"), 1e-9, "a bought one: the middle of 3-13");
        // with the same powders as one you would buy
        assertEquals(mid.baseDps(), own.baseDps(), 1e-9);
    }

    @Test
    void aBadlyRolledWornPieceIsWeighedAgainstAFreshCopy(@TempDir Path tmp) throws Exception {
        shaman(tmp, "Heavensent", 0.0, 5f);
        JsonObject low = new JsonObject();
        JsonObject shop = new JsonObject();
        shop.addProperty("minGainPercent", 3);
        low.add("shopping", shop);
        Rules.update(low);
        Roadmap.Step now = now();
        Roadmap.Gear row = now.gear().stream().filter(g -> !Roadmap.isPowder(g) && g.info().name().equals("Heavensent"))
                .findFirst().orElse(null);
        Fixtures.note("weak Heavensent: " + now.gear().stream().map(g -> g.slot() + " " + g.info().name() + " "
                + Math.round(g.gain() * 10) / 10.0).toList());
        assertNotNull(row, "a 5 % roll: a fresh copy is the pick");
        assertTrue(row.gain() >= 3, "and it clears the bar: " + row.gain());
        Damage.Piece placed = now.build().get("WEAPON");
        assertEquals("Heavensent", placed.name());
        assertNull(placed.stack(), "the fresh copy, bought");
        assertTrue(Roadmap.spend(GameState.current(), Map.of("WEAPON", placed), java.util.Set.of(),
                Roadmap.ownedCounts(GameState.current()).keySet()) > 0, "it costs what a copy costs");
    }

    @Test
    void aWellEnoughRolledWornPieceIsNoUpgradeOverItself(@TempDir Path tmp) throws Exception {
        // the same worst rolls, but the game says 40 %: above rules.rerollBelowPercent (30)
        shaman(tmp, "Heavensent", 0.0, 40f);
        JsonObject low = new JsonObject();
        JsonObject shop = new JsonObject();
        shop.addProperty("minGainPercent", 3);
        low.add("shopping", shop);
        Rules.update(low);
        Roadmap.Step now = now();
        assertFalse(now.gear().stream().anyMatch(g -> !Roadmap.isPowder(g) && g.info().name().equals("Heavensent")),
                "what you wear is no upgrade: " + now.gear().stream().map(g -> g.info().name()).toList());
    }
}
