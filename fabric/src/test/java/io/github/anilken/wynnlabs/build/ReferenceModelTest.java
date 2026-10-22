package io.github.anilken.wynnlabs.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.game.FixtureState;
import io.github.anilken.wynnlabs.game.Fixtures;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.SetData;
import io.github.anilken.wynnlabs.model.SkillKind;
import io.github.anilken.wynnlabs.model.StatRange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The build model against WynnBuilder (src/test/resources/reference/wynnbuilder, how to refresh:
 * tools/reference/README.md): each reference build, with WynnBuilder's skill points and the
 * items' max rolls, must give the same effective health and melee DPS within its tolerance.
 * The formula is checked, not the allocator, so the stored points are used as they are.
 */
class ReferenceModelTest {

    @AfterEach
    void reset() {
        FixtureState.uninstall();
    }

    private static Path refs() {
        return Fixtures.root().getParent().resolve("reference").resolve("wynnbuilder");
    }

    /** The item with every identification at its max roll (the larger magnitude), as WynnBuilder shows it. */
    private static ItemData maxRolls(ItemData i) {
        List<StatRange> st = new ArrayList<>();
        for (StatRange r : i.stats()) {
            int v = Math.abs(r.max()) >= Math.abs(r.min()) ? r.max() : r.min();
            st.add(new StatRange(r.key(), r.name(), r.unit(), r.inverted(), v, v));
        }
        return new ItemData(i.name(), i.slot(), i.tier(), i.powderSlots(), i.requirements(), i.base(), st, i.set(),
                i.restriction());
    }

    @Test
    void modelMatchesWynnBuilder() throws Exception {
        Path dir = refs();
        assumeTrue(Files.isDirectory(dir), "no reference builds");
        FixtureState.load(Fixtures.root().resolve("shaman-44")).install("en");
        // WynnBuilder shows the relik's main attack per hit at its base; the model's ×1.05 for the
        // relik's three beams (rules.model.mainAttackClassMult) is a play choice, so it is off here
        JsonObject m = new JsonObject();
        m.addProperty("mainAttackClassMult", 1.0);
        m.addProperty("assumePowders", 0);                // the reference builds are entered without powders
        JsonObject change = new JsonObject();
        change.add("model", m);
        Rules.update(change);
        List<String> off = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        for (Path f : files) {
            JsonObject ref = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            int level = ref.get("level").getAsInt();
            double tol = ref.has("tolerance") ? ref.get("tolerance").getAsDouble() : 0.05;
            List<Damage.Piece> armour = new ArrayList<>();
            Damage.Piece weapon = null;
            for (var e : ref.getAsJsonObject("items").entrySet()) {
                String name = e.getValue().getAsString();
                ItemData info = GameState.current().allGear().filter(i -> i.name().equalsIgnoreCase(name)).findFirst()
                        .orElseThrow(() -> new AssertionError(f.getFileName() + ": no item " + name + " in the item data"));
                Damage.Piece p = Damage.piece(maxRolls(info));
                if (e.getKey().equals("WEAPON")) {
                    weapon = p;
                } else {
                    armour.add(p);
                }
            }
            JsonObject a = ref.getAsJsonObject("assigned");
            int[] assigned = new int[SkillKind.values().length];
            for (SkillKind k : SkillKind.values()) {
                assigned[k.ordinal()] = a.has(k.name()) ? a.get(k.name()).getAsInt() : 0;
            }
            Map<String, SetData> sets = new HashMap<>(Damage.SETS);
            Damage.Result r;
            try {
                if (ref.has("wynnbuilderLacksSetKeys")) {
                    withoutSetKeys(ref.getAsJsonArray("wynnbuilderLacksSetKeys"));
                }
                r = Damage.evaluate(armour, weapon, assigned, level);
            } finally {
                Damage.SETS.putAll(sets);
            }
            JsonObject wb = ref.getAsJsonObject("wynnbuilder");
            check(off, f, "effective health", r.ehp(), wb, "effectiveHealth", tol);
            check(off, f, "melee DPS", r.mainDps(), wb, "meleeDps", tol);
        }
        assertTrue(off.isEmpty(), "off from WynnBuilder:\n  " + String.join("\n  ", off));
    }

    /**
     * WynnBuilder's set data drops some of the Wynncraft API's set bonus keys (Corrupted's raw
     * main attack and spell damage at 3+ pieces), so for its numbers the model leaves them out too.
     */
    private static void withoutSetKeys(JsonArray keys) {
        Set<String> drop = new HashSet<>();
        keys.forEach(k -> drop.add(k.getAsString()));
        for (var e : new ArrayList<>(Damage.SETS.entrySet())) {
            SetData d = e.getValue();
            Map<Integer, SetData.Bonus> bonuses = new HashMap<>();
            d.bonuses().forEach((n, b) -> bonuses.put(n, new SetData.Bonus(b.major(),
                    b.minor().stream().filter(v -> !drop.contains(v.key())).toList())));
            Damage.SETS.put(e.getKey(), new SetData(d.name(), d.cleanName(), d.pieces(), bonuses));
        }
    }

    private static void check(List<String> off, Path f, String what, double ours, JsonObject wb, String key, double tol) {
        if (!wb.has(key)) {
            return;
        }
        double theirs = wb.get(key).getAsDouble();
        double share = theirs == 0 ? 0 : ours / theirs - 1;
        String line = String.format("%s %s: model %.1f, WynnBuilder %.1f (%+.1f%%)", f.getFileName(), what, ours, theirs, share * 100);
        Fixtures.note(line);
        if (Math.abs(share) > tol) {
            off.add(line);
        }
    }

    @Test
    void aSummonersMainAttackStaysASideAct() throws Exception {
        // the recorded Shaman is a Summoner: with its own rules (weight 0.23), the main attack must
        // not outweigh spells and puppets together, even on the Lv 100 build's 13 000 melee DPS
        FixtureState.load(Fixtures.root().resolve("shaman-44")).install("en");
        double w = Rules.sub("model", "mainAttackWeight", 0.5);
        for (String name : new String[] {"roadmap-lv100.json", "roadmap-lv120.json"}) {
            JsonObject ref = JsonParser.parseString(Files.readString(refs().resolve(name), StandardCharsets.UTF_8)).getAsJsonObject();
            List<Damage.Piece> armour = new ArrayList<>();
            Damage.Piece weapon = null;
            for (var e : ref.getAsJsonObject("items").entrySet()) {
                String item = e.getValue().getAsString();
                ItemData info = GameState.current().allGear().filter(i -> i.name().equalsIgnoreCase(item)).findFirst().orElseThrow();
                Damage.Piece p = Damage.piece(maxRolls(info));
                if (e.getKey().equals("WEAPON")) {
                    weapon = p;
                } else {
                    armour.add(p);
                }
            }
            JsonObject a = ref.getAsJsonObject("assigned");
            int[] assigned = new int[SkillKind.values().length];
            for (SkillKind k : SkillKind.values()) {
                assigned[k.ordinal()] = a.has(k.name()) ? a.get(k.name()).getAsInt() : 0;
            }
            Damage.Result r = Damage.evaluate(armour, weapon, assigned, ref.get("level").getAsInt());
            double main = w * r.mainDps();
            double rest = r.factors()[0] - main;           // spells and summons
            Fixtures.note(String.format("%s: weighted main %.0f, spells and summons %.0f", name, main, rest));
            assertTrue(main <= rest, name + ": weighted main attack " + Math.round(main) + " > spells and summons " + Math.round(rest));
        }
    }
}
