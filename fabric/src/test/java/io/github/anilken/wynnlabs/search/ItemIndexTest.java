package io.github.anilken.wynnlabs.search;

import com.google.gson.JsonParser;
import io.github.anilken.wynnlabs.model.Klass;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading Wynntils' cache files into the item index: every kind, the fields the tab shows, missing files, build time. */
class ItemIndexTest {

    @Test
    void everyKindIsRead() {
        ItemIndex idx = SearchFixtures.index();
        assertNull(idx.missing());
        for (Kind k : Kind.values()) {
            assertTrue(idx.counts().getOrDefault(k, 0) > 0, k + " missing");
        }
        assertEquals(15, idx.counts().get(Kind.GEAR));
        assertEquals(3, idx.counts().get(Kind.INGREDIENT));
    }

    @Test
    void gearFields() {
        Entry p = SearchFixtures.index().byName("papyrus");
        assertNotNull(p);
        assertEquals(Kind.GEAR, p.kind());
        assertEquals(77, p.level());
        assertEquals("rare", p.tier());
        assertEquals("chestplate", p.type());
        assertEquals(Klass.NONE, p.klass());
        assertTrue(p.normalDrop());
        assertTrue(p.tradable());
        assertEquals("minecraft:leather_chestplate", p.icon().id());
        assertEquals(6f, p.icon().model());
        assertTrue(p.stats().stream().anyMatch(s -> s.key().equals("manaRegen") && s.min() == 4 && s.max() == 16));

        Entry abdomen = SearchFixtures.index().byName("Abdomen");
        assertFalse(abdomen.tradable());
        Entry.Origin o = abdomen.origins().get(0);
        assertEquals("dungeonMerchant", o.type());
        assertEquals("Infested Pit", o.name());
        assertEquals(List.of(Area.point(-157, 54, -1819)), o.areas());
    }

    @Test
    void typeListsAndObtainList() {
        ItemIndex idx = SearchFixtures.index();
        List<String> anthem = idx.byName("Anthem").origins().stream().map(Entry.Origin::type).toList();
        assertEquals(List.of("event", "merchant"), anthem);
        Entry.Origin cave = idx.byName("Aerolia Boots").origins().get(0);
        assertEquals("caveCompletion", cave.type());
        assertEquals("Mossy Grotto", cave.name());
        // the data's "nowhere" (99999) is no place; a dropMeta without coordinates neither
        assertTrue(idx.byName("Bloodborne").origins().get(0).areas().isEmpty());
        assertTrue(idx.byName("Astrolabe").origins().get(0).areas().isEmpty());
    }

    @Test
    void ingredientMaterialSetFields() {
        ItemIndex idx = SearchFixtures.index();
        Entry eff = idx.byName("Accursed Effigy");
        assertEquals(2, eff.stars());
        assertNull(eff.tier());
        assertEquals(List.of("armouring", "tailoring"), eff.professions());
        assertEquals(15, eff.modifiers().get("defenceRequirement"));
        Entry.Origin mob = eff.origins().get(0);
        assertEquals("mob", mob.type());
        assertEquals("Obelisk Of Inversion", mob.name());
        assertEquals(2, mob.areas().size());
        assertEquals(80, mob.areas().get(0).radius());
        // a single [x, y, z, r] list
        assertEquals(1, idx.byName("7-Yottabyte Storage Component").origins().get(0).areas().size());
        assertEquals(List.of("mining"), idx.byName("Copper Ingot").professions());
        Entry bear = idx.byName("Bear Body");
        assertEquals("Bear", bear.set());
        assertEquals(List.of("Bear Body", "Bear Mask", "Bear Head"), idx.setPieces(bear));
    }

    @Test
    void missingGearFileGivesAnEmptyIndexWithAReason(@TempDir Path dir) {
        ItemIndex idx = ItemIndex.read(dir, List.of());
        assertTrue(idx.entries().isEmpty());
        assertEquals("no dataStaticGear", idx.missing());
    }

    @Test
    void fullSizeBuildUnderOneSecond(@TempDir Path dir) throws Exception {
        int total = SearchFixtures.writeSynthetic(dir);
        ItemIndex.read(dir, List.of());                    // warm the JIT once
        long t0 = System.nanoTime();
        ItemIndex idx = ItemIndex.read(dir, LootChests.load(null));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("[search] synthetic index of " + total + " items built in " + ms + " ms");
        assertEquals(total, idx.entries().size());
        assertTrue(ms < 1000, "index build took " + ms + " ms");
    }

    /** On the real cache (WYNNLABS_CACHE=<game>/wynntils/cache), skipped elsewhere. */
    @Test
    void realCacheWhenPresent() throws Exception {
        String env = System.getenv("WYNNLABS_CACHE");
        Assumptions.assumeTrue(env != null && Files.isRegularFile(Path.of(env, ItemIndex.GEAR)), "no real cache");
        Path dir = Path.of(env);
        long t0 = System.nanoTime();
        ItemIndex idx = ItemIndex.read(dir, LootChests.load(null));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        int gear = JsonParser.parseString(Files.readString(dir.resolve(ItemIndex.GEAR), StandardCharsets.UTF_8))
                .getAsJsonObject().size();
        System.out.println("[search] real index: " + idx.counts() + " in " + ms + " ms, chests " + idx.places().chests().size());
        assertEquals(gear, idx.counts().get(Kind.GEAR));
        assertTrue(ms < 1000, "real index build took " + ms + " ms");
    }
}
