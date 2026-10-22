package io.github.anilken.wynnlabs.build;

import io.github.anilken.wynnlabs.build.roadmap.Roadmap;
import io.github.anilken.wynnlabs.game.Fixture;
import io.github.anilken.wynnlabs.game.FixtureState;
import io.github.anilken.wynnlabs.game.Fixtures;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.market.Vault;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.Roll;
import io.github.anilken.wynnlabs.model.SkillKind;
import io.github.anilken.wynnlabs.model.StatRange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Home tab's "now" on the recorded Shaman at Lv 72 with the Wynnterfest 2016 ornaments (a
 * set whose bonus only pays off whole): worn, in the bank, or with pieces just taken off into
 * the bag, the panel names the same best build you own.
 */
class NowSetTest {

    @AfterEach
    void reset() {
        FixtureState.uninstall();
        Vault.preview(Map.of());
    }

    /** Armour, then rings, bracelet, necklace: as rolled on a Lv 72 Shaman. */
    private static final String[] GEAR = {
        "Purified Helmet of the Legends:combatExperience=8:lootBonus=7:manaRegen=13:healthRegenRaw=80:damage=11",
        "Admiral:rawStrength=5:rawDexterity=5:rawIntelligence=5:rawDefence=5:rawAgility=5:combatExperience=15:lootBonus=20"
                + ":exploding=30:elementalDamage=8:elementalMainAttackDamage=20",
        "Greaves of Honor:combatExperience=25:reflection=4:thorns=9:healthRegenRaw=66",
        "Sodeta Boots:combatExperience=27:lifeSteal=19:manaRegen=15:spellDamage=28",
        "Yellow Ornament:combatExperience=6:thunderDamage=6:rawDamage=25",
        "Yellow Ornament:combatExperience=6:thunderDamage=6:rawDamage=25",
        "Red Ornament:combatExperience=6:rawHealth=80:fireDamage=6",
        "Green Ornament:combatExperience=6:damage=6:earthDamage=6"};
    private static final String WEAPON = "Kilauea:rawStrength=10:exploding=11:rawHealth=-585:spellDamage=18"
            + ":rawMainAttackDamage=217:walkSpeed=-10";
    private static final List<String> ORNAMENTS = List.of("Yellow Ornament", "Yellow Ornament", "Red Ornament",
            "Green Ornament");
    private static final String[] ACCESSORY_SLOTS = {"RING1", "RING2", "BRACELET", "NECKLACE"};

    /** Where the gear is: all worn; all in the bank; worn but the leggings and boots in the bag. */
    private enum Where { WORN, BANK, LEGS_OFF }

    /** An identified piece with these rolls ("Name:stat=value:…"). */
    private static ItemView view(Map<String, ItemData> db, String key) {
        String[] parts = key.split(":");
        ItemData d = db.get(parts[0]);
        List<Roll> rolls = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            String[] kv = parts[i].split("=");
            StatRange r = d.stats().stream().filter(s -> s.key().equals(kv[0])).findFirst().orElse(null);
            rolls.add(new Roll(kv[0], r == null ? kv[0] : r.name(), r == null ? "RAW" : r.unit(), Integer.parseInt(kv[1]), r));
        }
        return new ItemView(ItemView.Kind.GEAR, "GearItem", false, null, null, null, d,
                new ItemView.Rolled(rolls, List.of(), 0, null, false,
                        new ItemView.Marks(true, true, new EnumMap<>(SkillKind.class), false)),
                null, null, null, null, null, null, null, null, null);
    }

    /** The recorded Shaman at Lv 72 with its points spread as in game, the gear {@code where}; installed. */
    private static void shaman(Path tmp, Where where) throws Exception {
        Path dir = Fixtures.root().resolve("shaman-44");
        assumeTrue(Files.exists(dir.resolve("state.json")), "no shaman-44 fixture");
        Fixture.Data d = Fixture.read(dir);
        Map<String, ItemData> db = new LinkedHashMap<>();
        d.gearDb().gear().forEach(g -> db.putIfAbsent(g.name(), g));
        List<Fixture.Held> stacks = new ArrayList<>();
        for (String g : GEAR) {
            stacks.add(new Fixture.Held(g.split(":")[0], 1, view(db, g)));
        }
        stacks.add(new Fixture.Held("Kilauea", 1, view(db, WEAPON)));
        int weapon = stacks.size() - 1;
        List<Integer> inv = new ArrayList<>();
        for (int i = 0; i < 41; i++) {
            inv.add(null);
        }
        inv.set(1, weapon);
        Map<String, Integer> armour = new LinkedHashMap<>();
        List<Integer> acc = new ArrayList<>();
        List<Fixture.BankSlot> bank = new ArrayList<>();
        String[] slots = {"HEAD", "CHEST", "LEGS", "FEET"};
        for (int i = 0; i < GEAR.length; i++) {
            boolean worn = where == Where.WORN || where == Where.LEGS_OFF && i != 2 && i != 3;
            if (where == Where.BANK) {
                bank.add(new Fixture.BankSlot("acct:Account Bank p. 1", i, stacks.get(i).name(), 1, stacks.get(i).view()));
            } else if (!worn) {
                inv.set(20 + i, i);                          // taken off into the bag
            }
            if (i < 4) {
                armour.put(slots[i], worn ? i : null);
            } else if (worn) {
                acc.add(i);
                inv.set(9 + i - 4, i);                      // the accessory slots are bag slots 9-12
            }
        }
        Map<SkillKind, Fixture.Skill> skills = new EnumMap<>(SkillKind.class);
        int[] a = {26, 28, 18, 40, 30};
        for (SkillKind s : SkillKind.values()) {
            skills.put(s, new Fixture.Skill(a[s.ordinal()], a[s.ordinal()], 0, 0, 0));
        }
        Fixture.State s = d.state();
        Fixture.State at72 = new Fixture.State(s.format(), s.recordedAt(), s.inGame(), s.klass(), 72, s.characterId(),
                s.characters(), s.characterIds(), s.today(), skills, true, 0, s.professions(), s.quests(),
                s.abilityPlan(), s.position(), s.spots(), s.xpRates(), s.pricesInstalled(), 1);
        Path out = tmp.resolve(where.name());
        Fixture.write(new Fixture.Data(at72, new Fixture.Items(stacks, inv, armour, acc, weapon, bank),
                d.gearDb(), d.prices(), d.rules(), d.settings(), d.tree()), out, null);
        FixtureState fx = FixtureState.load(out);
        fx.install("en");
        Vault.preview(fx.bankStacks());
    }

    /** The "now" milestone alone, as the Home tab's first pill shows it. */
    private static Roadmap.Step now() {
        return Roadmap.compute(new Roadmap.Inputs(List.of(), Tuning.Choice.PLAIN, true, 0), null).steps().get(0);
    }

    private static List<String> accessories(Map<String, Damage.Piece> set) {
        List<String> out = new ArrayList<>();
        for (String slot : ACCESSORY_SLOTS) {
            out.add(set.get(slot) == null ? "-" : set.get(slot).name());
        }
        return out;
    }

    /** rules.model.goalXp set to {@code v}. */
    private static void goalXp(double v) {
        com.google.gson.JsonObject model = new com.google.gson.JsonObject();
        model.addProperty("goalXp", v);
        com.google.gson.JsonObject change = new com.google.gson.JsonObject();
        change.add("model", model);
        io.github.anilken.wynnlabs.core.Rules.update(change);
    }

    private static Damage.Piece gear(String name) {
        return Damage.piece(GameState.current().allGear().filter(i -> i.name().equals(name)).findFirst().orElseThrow());
    }

    @Test
    void theOrnamentsWornAreKept(@TempDir Path tmp) throws Exception {
        shaman(tmp, Where.WORN);
        // "now" looks 20 levels down like every milestone: on the Damage goal (XP at 0.3) two
        // Photon, Fantasy and the Zhight necklace (Lv 55-61) may take the ornaments' place, but
        // only when they win on damage and health alone
        Map<String, Damage.Piece> picked = new LinkedHashMap<>(now().build());
        if (!accessories(picked).equals(ORNAMENTS)) {
            Map<String, Damage.Piece> ornaments = new LinkedHashMap<>(picked);
            ornaments.put("RING1", gear("Yellow Ornament"));
            ornaments.put("RING2", gear("Yellow Ornament"));
            ornaments.put("BRACELET", gear("Red Ornament"));
            ornaments.put("NECKLACE", gear("Green Ornament"));
            goalXp(0);
            double p = Build.allocate(picked, 72, 142).result().objective();
            double o = Build.allocate(ornaments, 72, 142).result().objective();
            Fixtures.note(String.format(java.util.Locale.ROOT, "without XP: %s %.0f, ornaments %.0f", accessories(picked), p, o));
            assertTrue(p >= o, "on the Damage goal the picks win without XP");
        }
        // where XP counts in full the worn set is not broken up one piece at a time
        goalXp(1);
        Roadmap.Step s = now();
        assertEquals(ORNAMENTS, accessories(s.build()), "the set stays on");
        for (Roadmap.Gear g : s.gear()) {
            assertTrue(!g.slot().startsWith("RING") && !g.slot().equals("BRACELET") && !g.slot().equals("NECKLACE"),
                    "no accessory swap for the worn set: " + g.slot() + " " + g.info().name());
        }
        assertTrue(Roadmap.bagRows().isEmpty(), "nothing to put on from the bag");
    }

    @Test
    void theOrnamentsInTheBankArePutOnWhole(@TempDir Path tmp) throws Exception {
        // nothing worn: one piece at a time the search took Glimmer, Glimmer, Growth and Talisman
        // Charm, though the four ornaments in the bank beat them together
        shaman(tmp, Where.BANK);
        // On the Damage goal XP is a side gain (rules.model.goalXp 0.3), and most of what the
        // ornaments give is XP (+44 %): there two Myrrh, Cattail and Talisman Charm win on damage
        // alone (about +19 % spell DPS; 31318 against 28844 with XP left out), which is right.
        Map<String, Damage.Piece> picked = new LinkedHashMap<>(now().build());
        Map<String, Damage.Piece> ornaments = new LinkedHashMap<>(picked);
        ornaments.put("RING1", gear("Yellow Ornament"));
        ornaments.put("RING2", gear("Yellow Ornament"));
        ornaments.put("BRACELET", gear("Red Ornament"));
        ornaments.put("NECKLACE", gear("Green Ornament"));
        goalXp(0);
        assertTrue(Build.allocate(picked, 72, 142).result().objective()
                >= Build.allocate(ornaments, 72, 142).result().objective(), "on the Damage goal the picks win without XP");
        // where XP counts in full (goalXp 1, as before the fighting goals weighed it less) the set
        // is worth it: the search must see that only the four together pay
        goalXp(1);
        Roadmap.Step s = now();
        assertEquals(ORNAMENTS, accessories(s.build()), "the set from the bank: " + s.build().keySet());
        Map<String, Damage.Piece> apart = new LinkedHashMap<>(s.build());
        apart.put("RING1", gear("Glimmer"));
        apart.put("RING2", gear("Glimmer"));
        apart.put("BRACELET", gear("Growth"));
        apart.put("NECKLACE", gear("Talisman Charm"));
        double withSet = s.set().result().objective();
        double without = Build.allocate(apart, 72, 142).result().objective();
        Fixtures.note(String.format(java.util.Locale.ROOT, "ornaments %.0f, Glimmer/Growth/Talisman %.0f", withSet, without));
        assertTrue(withSet > without, "the set beats the four pieces it was traded for");
    }

    @Test
    void anEmptySlotOffersWhatYouTookOff(@TempDir Path tmp) throws Exception {
        // the leggings and boots taken off into the bag: the slots showed a tick and "all good"
        shaman(tmp, Where.LEGS_OFF);
        Map<String, Roadmap.Gear> rows = Roadmap.bagRows();
        assertEquals(List.of("LEGGINGS", "BOOTS"), new ArrayList<>(rows.keySet()));
        assertEquals("Greaves of Honor", rows.get("LEGGINGS").info().name());
        assertEquals("Sodeta Boots", rows.get("BOOTS").info().name());
        for (Roadmap.Gear g : rows.values()) {
            assertTrue(g.gain() > 0, g.slot() + " gains " + g.gain());
            assertTrue(Roadmap.fromBag(g), g.slot());
        }
        // the rows follow the plan: put back on, they go
        FixtureState.uninstall();
        shaman(tmp, Where.WORN);
        assertTrue(Roadmap.bagRows().isEmpty());
    }
}
