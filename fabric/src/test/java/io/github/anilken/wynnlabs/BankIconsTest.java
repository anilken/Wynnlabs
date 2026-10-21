package io.github.anilken.wynnlabs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Old bank pages' pictures: which later copy (or Wynntils' gear data) gives today's, and only the picture changes. */
class BankIconsTest {

    private static final long DAY = 86_400_000L;
    private static final long OLD = 10 * DAY;
    private static final long NEW = 15 * DAY;

    private static String item(String id, float model, String name) {
        return "{\"id\":\"" + id + "\",\"count\":1,\"components\":{\"minecraft:custom_name\":\"" + name + "\","
                + "\"minecraft:lore\":[\"a line\"],\"minecraft:custom_model_data\":{\"floats\":[" + model + "],"
                + "\"strings\":[\"item_tier_rare\"]}}}";
    }

    private static Vault.Stored box(String box, long seen, float model) {
        return new Vault.Stored("Unidentified Box", 60, "acct:Account Bank p. 1", seen, 1, false, "gearbox",
                null, null, null, null, "", 0, item("minecraft:potion", model, "Unidentified Box"), box);
    }

    private static Vault.Stored named(String name, boolean gear, long seen, String id, float model) {
        return new Vault.Stored(name, 1, "acct:Account Bank p. 1", seen, 1, gear, gear ? "gear" : "rune",
                "SELL", "a note", "en_us", "MAGE", "", 0, item(id, model, name), null);
    }

    private static float model(String json) {
        return JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("components")
                .getAsJsonObject("minecraft:custom_model_data").getAsJsonArray("floats").get(0).getAsFloat();
    }

    private static String repaint(Vault.Stored s, List<Vault.Stored> all) {
        return BankIcons.repaint(s, BankIcons.newest(all), n -> null);
    }

    @Test
    void identitiesAreTheBoxTypeAndTierThenTheTierOrElseTheName() {
        assertEquals(List.of("box:NECKLACE|RARE", "boxtier:RARE"), BankIcons.identities(box("NECKLACE|RARE|61|65", OLD, 1)));
        assertEquals(List.of("gear:Some Wand"), BankIcons.identities(named("Some Wand", true, OLD, "minecraft:potion", 1)));
        assertEquals(List.of("item:Az Rune"), BankIcons.identities(named("Az Rune", false, OLD, "minecraft:potion", 1)));
    }

    @Test
    void anOldBoxTakesTheNewestBoxOfItsTypeAndTier() {
        Vault.Stored old = box("WAND|RARE|1|5", OLD, 2695);
        Vault.Stored mid = box("WAND|RARE|6|10", 12 * DAY, 2698);
        Vault.Stored fresh = box("WAND|RARE|11|15", NEW, 2700);
        Vault.Stored otherType = box("HELMET|RARE|1|5", 13 * DAY, 2701);   // newer than old, but not its type
        List<Vault.Stored> all = List.of(old, mid, fresh, otherType);
        assertEquals(2700f, model(repaint(old, all)));
        assertSame(fresh.item(), repaint(fresh, all));          // the newest stays as it is
    }

    @Test
    void aBoxWithNoNewerCopyOfItsTypeTakesItsTiers() {
        Vault.Stored necklace = box("NECKLACE|RARE|61|65", OLD, 2695);
        Vault.Stored wand = box("WAND|RARE|1|5", NEW, 2700);
        Vault.Stored unique = box("WAND|UNIQUE|1|5", NEW, 2702);
        assertEquals(2700f, model(repaint(necklace, List.of(necklace, wand, unique))));
    }

    @Test
    void onlyThePictureChanges() {
        Vault.Stored old = named("Az Rune", false, OLD, "minecraft:potion", 3828);
        Vault.Stored fresh = named("Az Rune", false, NEW, "minecraft:potion", 3833);
        JsonObject was = JsonParser.parseString(old.item()).getAsJsonObject();
        JsonObject now = JsonParser.parseString(repaint(old, List.of(old, fresh))).getAsJsonObject();
        JsonArray floats = new JsonArray();
        floats.add(3833f);
        was.getAsJsonObject("components").getAsJsonObject("minecraft:custom_model_data").add("floats", floats);
        assertEquals(was, now);                                 // the name, lore and model strings are the saved item's
    }

    @Test
    void nothingNewerOrNotTheSameThingLeavesTheItemAlone() {
        Vault.Stored old = named("Az Rune", false, OLD, "minecraft:potion", 3828);
        Vault.Stored olderStill = named("Az Rune", false, 5 * DAY, "minecraft:potion", 1);
        Vault.Stored sameLogin = named("Az Rune", false, OLD + 60_000L, "minecraft:potion", 3833);
        Vault.Stored otherBase = named("Az Rune", false, NEW, "minecraft:paper", 7);
        Vault.Stored otherName = named("Ek Rune", false, NEW, "minecraft:potion", 3834);
        assertSame(old.item(), repaint(old, List.of(old, olderStill, otherBase, otherName)));
        // a minute later is the same pack: a different picture then is a different thing
        assertSame(old.item(), repaint(old, List.of(old, sameLogin)));
    }

    @Test
    void differentPicturesSeenWithOnePackAreNotTaken() {
        // two horses read seconds apart with different pictures: which one an old copy was is unknown
        Vault.Stored old = named("Horse", false, OLD, "minecraft:potion", 2800);
        Vault.Stored a = named("Horse", false, NEW, "minecraft:potion", 2845);
        Vault.Stored b = named("Horse", false, NEW - 5_000L, "minecraft:potion", 2854);
        assertSame(old.item(), repaint(old, List.of(old, a, b)));
    }

    @Test
    void gearTakesWynntilsPictureBeforeALaterCopy() {
        Vault.Stored old = named("Some Wand", true, OLD, "minecraft:potion", 4414);
        Vault.Stored later = named("Some Wand", true, NEW, "minecraft:potion", 4414);
        JsonArray f = new JsonArray();
        f.add(4423f);
        BankIcons.Pic wynntils = new BankIcons.Pic("minecraft:potion", f, null, 0, false);
        Map<String, BankIcons.Pic> newest = BankIcons.newest(List.of(old, later));
        assertEquals(4423f, model(BankIcons.repaint(old, newest, n -> n.equals("Some Wand") ? wynntils : null)));
        assertEquals(4423f, model(BankIcons.repaint(later, newest, n -> wynntils)));
        // Wynntils on another base item (a vanilla head) is not taken; with no answer the later copy is
        BankIcons.Pic head = new BankIcons.Pic("minecraft:creeper_head", f, null, 0, false);
        Vault.Stored fresh = named("Some Wand", true, NEW, "minecraft:potion", 4430);
        assertEquals(4430f, model(BankIcons.repaint(old, BankIcons.newest(List.of(old, fresh)), n -> head)));
    }
}
