package io.github.anilken.wynnlabs.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.anilken.wynnlabs.coach.Coach;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.game.FixtureState;
import io.github.anilken.wynnlabs.game.Fixtures;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.SkillKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Spells measured and calibrated one by one: the spell text read right (Totem's ticks, the
 * arrows of Arrow Storm), the damage between casts taken off each cast, and on four recorded
 * characters (src/test/resources/reference/calibration, ids left out) the calibrated model
 * landing within 25 % of what each spell deals.
 */
class SpellCalibrationTest {

    @AfterEach
    void reset() {
        FixtureState.uninstall();
    }

    private static Path refs() {
        return Fixtures.root().getParent().resolve("reference").resolve("calibration");
    }

    private static JsonObject read(String name) throws Exception {
        return JsonParser.parseString(Files.readString(refs().resolve(name), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static List<String> lines(JsonArray a) {
        List<String> out = new ArrayList<>();
        a.forEach(e -> out.add(e.getAsString()));
        return out;
    }

    /** The class's spells as the profile reads them from the recorded tree text. */
    private static JsonArray parsed(JsonObject fix) {
        JsonArray out = new JsonArray();
        for (var e : fix.getAsJsonArray("tree")) {
            JsonObject n = e.getAsJsonObject();
            List<String> ls = lines(n.getAsJsonArray("text"));
            String combo = ls.stream().filter(l -> l.startsWith("Click Combo")).findFirst().orElse("");
            out.add(ClassProfiles.spell(n.get("name").getAsString(), comboIndex(combo), ls));
        }
        JsonObject model = new JsonObject();
        model.add("spells", out);
        return ClassProfiles.keepCalibration(out, model);
    }

    private static int comboIndex(String combo) {
        String[] c = combo.replace("Click Combo:", "").trim().split("-");
        boolean second = c[1].equals(c[0]);
        boolean third = c[2].equals(c[0]);
        return !second && third ? 1 : second && third ? 2 : !second ? 3 : 4;
    }

    private static JsonObject spell(JsonArray spells, String name) {
        for (var e : spells) {
            if (e.getAsJsonObject().get("name").getAsString().equals(name)) {
                return e.getAsJsonObject();
            }
        }
        throw new AssertionError("no spell " + name);
    }

    private static double conv(JsonObject spell, String el) {
        JsonObject c = spell.getAsJsonObject("conv");
        return c.has(el) ? c.get(el).getAsDouble() : 0;
    }

    // ---- the spell text ----

    @Test
    void totemCountsEveryTickOfItsLife() throws Exception {
        JsonObject totem = spell(parsed(read("shaman-lv72.json")), "Totem");
        // 12 % (6 % + 6 % air) every 0.4 s for 30 s: 75 ticks
        assertEquals(75, totem.get("hits").getAsInt());
        assertEquals(4.5, conv(totem, "neutral"), 1e-9);
        assertEquals(4.5, conv(totem, "air"), 1e-9);
        assertEquals(30, totem.get("duration").getAsDouble(), 1e-9);
        assertTrue(ClassProfiles.lasting(totem), "kept up alongside the casts");
        JsonObject aura = spell(parsed(read("shaman-lv72.json")), "Aura");
        assertEquals(1.5, conv(aura, "neutral"), 1e-9, "one go: its 1.4 s is no tick clock");
        assertFalse(aura.has("hits"));
        assertFalse(ClassProfiles.lasting(aura));
    }

    @Test
    void perHitDamageCountsTheHitsTheTextNames() throws Exception {
        JsonObject storm = spell(parsed(read("archer-lv70.json")), "Arrow Storm");
        // "a stream of 8 arrows", 40 % each (35 % + 5 % thunder): not the three it used to be
        assertEquals(8, storm.get("hits").getAsInt());
        assertEquals(2.8, conv(storm, "neutral"), 1e-9);
        assertEquals(0.4, conv(storm, "thunder"), 1e-9);
        JsonObject multihit = ClassProfiles.spell("Multihit", 4, List.of("Click Combo: RIGHT-LEFT-LEFT",
                "Unleashes a rapid flurry of 8 hits to enemies", "facing you, dealing heavy damage.",
                "Mana Cost: 40", "Total Damage: 40% (of your DPS, per hit)", "( Damage: 30%)", "( Water: 10%)"));
        assertEquals(8, multihit.get("hits").getAsInt());
        assertEquals(2.4, conv(multihit, "neutral"), 1e-9);
        assertEquals(0.8, conv(multihit, "water"), 1e-9);
        JsonObject unnamed = ClassProfiles.spell("Volley", 3, List.of("Mana Cost: 30",
                "Total Damage: 50% (of your DPS, per arrow)", "( Damage: 50%)"));
        assertEquals(3, unnamed.get("hits").getAsInt(), "no count in the text: three, as before");
        JsonObject smoke = ClassProfiles.spell("Smoke Bomb", 3, List.of("Throws a bomb that slowly emanates smoke,",
                "damaging all enemies in it every 0.5s.", "Mana Cost: 35", "Total Damage: 35% (of your DPS)",
                "( Damage: 25%)", "( Air: 5%)", "( Earth: 5%)", "Duration: 5s"));
        assertEquals(10, smoke.get("hits").getAsInt());
        assertEquals(2.5, conv(smoke, "neutral"), 1e-9);
        assertFalse(ClassProfiles.lasting(smoke), "5 s: cast in turn, its window held open");
    }

    @Test
    void readingAgainKeepsEachSpellsCalibration() {
        JsonArray fresh = new JsonArray();
        fresh.add(ClassProfiles.spell("Aura", 3, List.of("Mana Cost: 40", "Total Damage: 180% (of your DPS)",
                "( Damage: 150%)", "( Water: 30%)")));
        JsonObject old = new JsonObject();
        JsonArray oldSpells = new JsonArray();
        JsonObject aura = new JsonObject();
        aura.addProperty("name", "Aura");
        aura.addProperty("index", 3);
        aura.addProperty("cal", 2.4);
        oldSpells.add(aura);
        old.add("spells", oldSpells);
        old.addProperty("spellsParsed", 1);
        assertEquals(2.4, ClassProfiles.keepCalibration(fresh, old).get(0).getAsJsonObject().get("cal").getAsDouble());
        old.addProperty("spellsParsed", ClassProfiles.SPELLS_PARSED);
        JsonObject rules = new JsonObject();
        rules.add("model", old);
        assertNull(ClassProfiles.spellsUpgrade(rules), "current rules are left alone");
    }

    // ---- the measuring ----

    @Test
    void theBackgroundIsTakenOffEachCast() {
        JsonObject m = new JsonObject();
        // 30 s of fighting between casts at 400 a second (puppets, Totem, main attack)
        Coach.bookBackground(m, 6000, 15);
        Coach.bookBackground(m, 6000, 15);
        assertEquals(400, Coach.backgroundRate(m), 1e-9);
        assertEquals(30, m.getAsJsonObject("background").get("seconds").getAsDouble(), 1e-9);
        // a 1.5 s window that heard 2000: 600 of it was the background
        assertEquals(1400, Coach.net(2000, Coach.backgroundRate(m), 1.5), 1e-9);
        assertEquals(0, Coach.net(300, 400, 1.5), "never below nothing");
        Coach.book(m, "Aura", 2000, 6, 40, 1.5, 1000, 0, true);
        JsonObject aura = m.getAsJsonObject("spells").getAsJsonObject("Aura");
        assertEquals(1400, aura.get("perCast").getAsDouble(), 1e-9);
        assertEquals(2000, aura.get("raw").getAsDouble(), 1e-9);
        assertEquals(1.4, aura.get("ratio").getAsDouble(), 1e-9);
        assertEquals(6, aura.get("hits").getAsDouble(), 1e-9);
        assertEquals(40, aura.get("mana").getAsDouble(), 1e-9);
        // a ticking spell is held against the part of it that fits in the window
        assertEquals(0.3, Coach.inWindow(1.5, 5), 1e-9);
        assertEquals(1, Coach.inWindow(1.5, 0), 1e-9);
    }

    @Test
    void withSummonsAroundCastsWaitForTheBackground() {
        JsonObject m = new JsonObject();
        Coach.book(m, "Aura", 5000, 9, 40, 1.5, 1000, 0, true);
        assertFalse(m.getAsJsonObject("spells").getAsJsonObject("Aura").has("ratio"),
                "puppets' hits would count as the spell's");
        Coach.book(m, "Aura", 5000, 9, 40, 1.5, 1000, 0, false);
        assertEquals(5, m.getAsJsonObject("spells").getAsJsonObject("Aura").get("ratio").getAsDouble(), 1e-9);
    }

    @Test
    void aGearChangeDoesNotMakeOldCastsWrong() {
        // the model said 1000 and the spell dealt 2000; then a new weapon: 1500 and 3000
        JsonObject m = new JsonObject();
        for (int i = 0; i < 100; i++) {
            Coach.book(m, "Meteor", 2000, 1, 50, 1.5, 1000, 0, false);
        }
        for (int i = 0; i < 100; i++) {
            Coach.book(m, "Meteor", 3000, 1, 50, 1.5, 1500, 0, false);
        }
        assertEquals(2.0, m.getAsJsonObject("spells").getAsJsonObject("Meteor").get("ratio").getAsDouble(), 1e-9);
    }

    @Test
    void theWideBoundsWaitForSteadyCasts() {
        assertEquals(1.4, Coach.bounded(3.2, false));
        assertEquals(3.2, Coach.bounded(3.2, true));
        assertEquals(10, Coach.bounded(40, true));
        assertEquals(0.5, Coach.bounded(0.1, true));
        JsonObject m = new JsonObject();
        Random r = new Random(7);
        for (int i = 0; i < 49; i++) {
            Coach.book(m, "Bash", 3000 * (0.8 + 0.4 * r.nextDouble()), 1, 40, 1.5, 1000, 0, false);
        }
        JsonObject bash = m.getAsJsonObject("spells").getAsJsonObject("Bash");
        assertFalse(bash.get("steady").getAsBoolean(), "49 casts");
        Coach.book(m, "Bash", 3000, 1, 40, 1.5, 1000, 0, false);
        assertTrue(bash.get("steady").getAsBoolean(), "50 casts close together");
        // a few huge casts among nothing: never steady, however many
        JsonObject wild = new JsonObject();
        for (int i = 0; i < 300; i++) {
            Coach.book(wild, "Bash", i % 4 == 0 ? 40000 : 100, 1, 40, 1.5, 1000, 0, false);
        }
        assertFalse(wild.getAsJsonObject("spells").getAsJsonObject("Bash").get("steady").getAsBoolean());
    }

    @Test
    void aSmallMoveIsNotWritten() {
        JsonObject m = new JsonObject();
        for (int i = 0; i < 40; i++) {
            Coach.book(m, "Bash", 1050, 1, 40, 1.5, 1000, 0, false);
        }
        JsonObject model = new JsonObject();
        JsonArray spells = new JsonArray();
        JsonObject bash = new JsonObject();
        bash.addProperty("name", "Bash");
        bash.addProperty("index", 1);
        bash.addProperty("share", 1.0);
        spells.add(bash);
        model.add("spells", spells);
        model.addProperty("spellCalibration", 1.0);
        assertNull(Coach.calibrationChanges(m, model, new Damage.Breakdown()), "5 % off: no rules write");
        for (int i = 0; i < 200; i++) {
            Coach.book(m, "Bash", 1300, 1, 40, 1.5, 1000, 0, false);
        }
        JsonObject change = Coach.calibrationChanges(m, model, new Damage.Breakdown());
        assertNotNull(change);
        // the last ~200 casts: most of them at 1.3
        assertEquals(1.26, change.getAsJsonObject("model").getAsJsonArray("spells").get(0).getAsJsonObject()
                .get("cal").getAsDouble(), 0.02);
    }

    // ---- the recorded characters ----

    /** A recorded character's worn build: the armour, the weapon and the points it assigned. */
    private record Worn(List<Damage.Piece> armour, Damage.Piece weapon, int[] assigned, int level) {

        Damage.Detail detail() {
            return Damage.detail(armour, weapon, assigned, level);
        }
    }

    private static Worn worn(JsonObject fix) {
        List<Damage.Piece> armour = new ArrayList<>();
        Damage.Piece weapon = null;
        for (var e : fix.getAsJsonObject("items").entrySet()) {
            String name = e.getValue().getAsString();
            ItemData info = GameState.current().allGear().filter(i -> i.name().equalsIgnoreCase(name)).findFirst()
                    .orElse(null);
            if (info == null) {
                Fixtures.note(fix.get("name").getAsString() + ": no " + name + " in the item data, left out");
                continue;
            }
            Damage.Piece p = Damage.piece(info);
            if (e.getKey().equals("WEAPON")) {
                weapon = p;
            } else {
                armour.add(p);
            }
        }
        JsonObject a = fix.getAsJsonObject("assigned");
        int[] assigned = new int[SkillKind.values().length];
        for (SkillKind k : SkillKind.values()) {
            assigned[k.ordinal()] = a.has(k.name()) ? a.get(k.name()).getAsInt() : 0;
        }
        return new Worn(armour, weapon, assigned, fix.get("level").getAsInt());
    }

    /** The character's own model settings over the recorded Shaman's rules. */
    private static void useRules(JsonObject fix, JsonArray spells) {
        JsonObject model = fix.getAsJsonObject("rules").deepCopy();
        model.add("spells", spells);
        model.addProperty("summonCalibration", 1.0);
        if (!model.has("spellCalibration")) {
            model.addProperty("spellCalibration", 1.0);
        }
        JsonObject change = new JsonObject();
        change.add("model", model);
        Rules.update(change);
    }

    private static List<Path> characters() throws Exception {
        try (Stream<Path> s = Files.list(refs())) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    @Test
    void theCalibratedModelLandsNearEachSpell() throws Exception {
        assumeTrue(Files.isDirectory(refs()), "no recorded characters");
        FixtureState.load(Fixtures.root().resolve("shaman-44")).install("en");
        List<String> off = new ArrayList<>();
        for (Path f : characters()) {
            JsonObject fix = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            String who = fix.get("name").getAsString();
            Worn w = worn(fix);
            JsonObject rec = fix.getAsJsonObject("recorded");
            double gap = rec.get("avgPerCast").getAsDouble() / rec.get("modelPerCast").getAsDouble();

            // before: the spells as the old rules had them, one calibration for all
            useRules(fix, fix.getAsJsonObject("rules").getAsJsonArray("spells"));
            Damage.Breakdown before = w.detail().breakdown();
            double oldModel = Coach.modelPerCast(before);
            double window = gap * oldModel;             // what a cast window heard, in this build's terms
            JsonObject assumed = fix.getAsJsonObject("assumed");
            double bgShare = assumed.get("backgroundShare").getAsDouble();
            double bgRate = bgShare * window / 1.5;

            // after: the spells read again, then measured and calibrated
            JsonArray spells = parsed(fix);
            useRules(fix, spells);
            Damage.Breakdown fresh = w.detail().breakdown();
            Map<String, Double> truth = new LinkedHashMap<>();
            JsonObject targets = assumed.getAsJsonObject("targets");
            double sum = 0;
            int n = 0;
            for (Damage.SpellPart sp : fresh.spells()) {
                if (!sp.lasting()) {
                    double t = sp.perCast() * (targets.has(sp.name()) ? targets.get(sp.name()).getAsDouble() : 1);
                    truth.put(sp.name(), t);
                    sum += t;
                    n++;
                }
            }
            double k = (1 - bgShare) * window / (sum / n);    // the spells' part of the window, split as assumed
            truth.replaceAll((s, t) -> t * k);

            Random r = new Random(who.hashCode());
            JsonObject m = new JsonObject();
            boolean summons = fresh.summonRaw()[0] > 0;
            List<Damage.SpellPart> cast = fresh.spells().stream().filter(sp -> !sp.lasting()).toList();
            for (int c = 1; c <= 400; c++) {
                // between casts: one to three seconds of the background alone
                double gapSeconds = 1 + 2 * r.nextDouble();
                Coach.bookBackground(m, bgRate * gapSeconds * noise(r, 0.3), gapSeconds);
                Damage.SpellPart sp = cast.get(c % cast.size());
                double raw = truth.get(sp.name()) * noise(r, 0.5) + bgRate * 1.5 * noise(r, 0.3);
                Coach.book(m, sp.name(), raw, 3, 40, 1.5, sp.perCast(), sp.duration(), summons);
                if (c >= Coach.CAL_CASTS && c % 10 == 0) {
                    JsonObject change = Coach.calibrationChanges(m, Rules.get().getAsJsonObject("model"),
                            w.detail().breakdown());
                    if (change != null) {
                        Rules.update(change);
                    }
                }
            }
            Damage.Breakdown after = w.detail().breakdown();
            for (Damage.SpellPart sp : after.spells()) {
                if (sp.lasting()) {
                    continue;
                }
                double t = truth.get(sp.name());
                double old = before.spells().stream().filter(o -> o.index() == sp.index()).findFirst()
                        .map(o -> o.perCast() * o.cal()).orElse(0.0);
                double cal = sp.perCast() * sp.cal();
                String line = String.format("%s %s: measured %.0f, model before %.0f (%.2fx), read again %.0f (%.2fx),"
                        + " calibrated %.0f (%.2fx, cal %.2f)", who, sp.name(), t, old, t / old, sp.perCast(),
                        t / sp.perCast(), cal, t / cal, sp.cal());
                Fixtures.note(line);
                if (Math.abs(cal / t - 1) > 0.25) {
                    off.add(line);
                }
            }
            if (summons) {
                // the meter hears every main attack hit, not the goal's weight on it
                double main = after.mainTotal();
                double modelBg = main + after.summonTotal();
                String line = String.format("%s between casts: measured %.0f/s, model %.0f/s (main %.0f + summons %.0f,"
                        + " summonCalibration %.2f)", who, bgRate, modelBg, main, after.summonTotal(),
                        Rules.sub("model", "summonCalibration", 1.0));
                Fixtures.note(line);
                if (Math.abs(modelBg / bgRate - 1) > 0.25) {
                    off.add(line);
                }
            }
        }
        assertTrue(off.isEmpty(), "off by more than 25 %:\n  " + String.join("\n  ", off));
    }

    /** A factor around 1 (mean 1): how many mobs a cast caught, how many puppets were swinging. */
    private static double noise(Random r, double spread) {
        return Math.exp(spread * r.nextGaussian() - spread * spread / 2);
    }

    @Test
    void bashMatchesWhatTheGameShowed() throws Exception {
        // a Lv 3 Warrior's Bash, its only spell: the damage numbers after each cast, against the
        // model of what it wore (the formula without any tree, calibration or summons in the way)
        assumeTrue(Files.isDirectory(refs()), "no recorded characters");
        FixtureState.load(Fixtures.root().resolve("shaman-44")).install("en");
        JsonObject fix = read("warrior-lv3.json");
        JsonArray spells = parsed(fix);
        useRules(fix, spells);
        JsonObject model = new JsonObject();
        model.addProperty("spellCalibration", 1.0);
        JsonObject change = new JsonObject();
        change.add("model", model);
        Rules.update(change);
        Damage.SpellPart bash = worn(fix).detail().breakdown().spells().stream()
                .filter(sp -> sp.name().equals("Bash")).findFirst().orElseThrow();
        double measured = fix.getAsJsonObject("recorded").get("avgPerCast").getAsDouble();
        Fixtures.note(String.format("warrior-lv3 Bash: model %.1f, measured in game %.1f", bash.perCast(), measured));
        assertEquals(measured, bash.perCast(), measured * 0.15);
    }
}
