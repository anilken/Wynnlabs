package io.github.anilken.wynnlabs.build.roadmap;

import io.github.anilken.wynnlabs.game.FixtureState;
import io.github.anilken.wynnlabs.game.Fixtures;
import io.github.anilken.wynnlabs.market.Shopping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The comparison panel's options on the recorded Shaman: the pick first, the rest best gain
 * first; at least {@link PanelOptions#count} shown where there are, with two the Trade Market
 * sells among them when there are; the Trade Market filter shows only those. And where an
 * item comes from, in words for people.
 */
class PanelOptionsTest {

    @AfterEach
    void reset() {
        FixtureState.uninstall();
    }

    private static Path shaman() {
        Path dir = Fixtures.root().resolve("shaman-44");
        assumeTrue(Files.exists(dir.resolve("state.json")), "no shaman-44 fixture");
        return dir;
    }

    @Test
    void optionsAreSortedAndKeepTheMarket() throws Exception {
        FixtureState.load(shaman()).install("en");
        List<Roadmap.Step> steps = Roadmap.build();
        int panels = 0;
        for (Roadmap.Step s : steps) {
            for (Roadmap.Gear g : s.gear()) {
                List<Roadmap.Option> all = g.options();
                if (all == null || all.size() < 2) {
                    continue;
                }
                panels++;
                String at = "Lv " + s.level() + " " + g.slot();
                for (int i = 2; i < all.size(); i++) {
                    assertTrue(all.get(i - 1).gain() >= all.get(i).gain(), at + ": best gain first");
                }
                List<Roadmap.Option> shown = PanelOptions.shown(all, false, 44);
                assertEquals(all.get(0), shown.get(0), at + ": the pick first");
                assertTrue(shown.size() >= Math.min(PanelOptions.count(), all.size()), at + ": at least " + PanelOptions.count());
                long market = all.stream().filter(PanelOptions::onMarket).count();
                long shownMarket = shown.stream().filter(PanelOptions::onMarket).count();
                assertTrue(shownMarket >= Math.min(2, market), at + ": two the Trade Market sells");
                List<Roadmap.Option> tm = PanelOptions.shown(all, true, 44);
                assertEquals(market, tm.size(), at + ": the filter keeps every one on the market");
                assertTrue(tm.stream().allMatch(PanelOptions::onMarket), at + ": only the market");
                Fixtures.note(at + ": " + all.size() + " options, " + shown.size() + " shown, " + market + " on the market");
            }
        }
        assertTrue(panels > 0, "some panels");
    }

    @Test
    void sourcesReadForPeople() throws Exception {
        FixtureState.load(shaman()).install("en");
        assertEquals("Legendary Island (challenge)", Shopping.where("CHALLENGE: Legendary Island"));
        assertEquals("The Canyon Colossus raid", Shopping.where("RAID: The Canyon Colossus"));
        assertEquals("Scavenger's Workshop (tinkering)", Shopping.where("TINKERING: Scavenger's Workshop"));
    }
}
