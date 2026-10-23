package io.github.anilken.wynnlabs.search;

import io.github.anilken.wynnlabs.model.Klass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sort, filters, kind counts, the background worker and the latency on a full-size index. */
class SearchQueryTest {

    private static final Map<String, Double> PRICES = Map.of("Papyrus", 5000.0, "About-Face", 900.0, "Accursed Effigy", 64.0);
    private static final SearchQuery.Facts FACTS = new SearchQuery.Facts(Klass.MAGE,
            n -> n.equals("Papyrus") || n.equals("Acevro"), PRICES::get);

    private static List<String> names(SearchQuery.Result r) {
        return r.hits().stream().map(h -> h.entry().name()).toList();
    }

    @Test
    void emptyTextListsEverything() {
        SearchQuery.Result r = SearchQuery.of("").run(SearchFixtures.index(), FACTS);
        assertEquals(SearchFixtures.index().entries().size(), r.hits().size());
        assertEquals(Hit.Match.ALL, r.hits().get(0).match());
    }

    @Test
    void ownedAndMyClass() {
        SearchQuery q = new SearchQuery("", SearchQuery.Sort.NAME, Set.of(), 0, Integer.MAX_VALUE, true, true, false);
        SearchQuery.Result r = q.run(SearchFixtures.index(), FACTS);
        // Acevro is owned but is a bow (archer): only Papyrus is left for a mage
        assertEquals(List.of("Papyrus"), names(r));
    }

    @Test
    void kindCountsFollowTheOtherFilters() {
        SearchQuery all = new SearchQuery("", SearchQuery.Sort.NAME, Set.of(Kind.INGREDIENT), 70, 80, false, false, false);
        SearchQuery.Result r = all.run(SearchFixtures.index(), FACTS);
        assertTrue(r.hits().stream().allMatch(h -> h.entry().kind() == Kind.INGREDIENT));
        assertEquals(List.of("Accursed Effigy"), names(r));
        // gear of level 70-80 is counted although the kind filter hides it
        assertEquals(2, r.counts().get(Kind.GEAR));                    // Acevro (72), Papyrus (77)
        assertEquals(1, r.counts().get(Kind.INGREDIENT));
    }

    @Test
    void onMarketNeedsATradableItemWithAPrice() {
        SearchQuery q = new SearchQuery("", SearchQuery.Sort.NAME, Set.of(), 0, Integer.MAX_VALUE, false, false, true);
        assertEquals(List.of("About-Face", "Accursed Effigy", "Papyrus"), names(q.run(SearchFixtures.index(), FACTS)));
    }

    @Test
    void priceSortPutsUnknownLast() {
        SearchQuery q = new SearchQuery("", SearchQuery.Sort.PRICE, Set.of(), 0, Integer.MAX_VALUE, false, false, false);
        List<String> n = names(q.run(SearchFixtures.index(), FACTS));
        assertEquals(List.of("Accursed Effigy", "About-Face", "Papyrus"), n.subList(0, 3));
        assertEquals(SearchFixtures.index().entries().size(), n.size());
    }

    @Test
    void levelSort() {
        SearchQuery q = new SearchQuery("", SearchQuery.Sort.LEVEL, Set.of(), 0, Integer.MAX_VALUE, false, false, false);
        List<Hit> hits = q.run(SearchFixtures.index(), FACTS).hits();
        for (int i = 1; i < hits.size(); i++) {
            assertTrue(hits.get(i - 1).entry().level() <= hits.get(i).entry().level());
        }
    }

    @Test
    void workerKeepsTheLatest() throws Exception {
        SearchWorker w = new SearchWorker();
        try {
            // the worker searches the loaded index; before loading it is empty, so this checks the plumbing
            w.submit(SearchQuery.of("a"), FACTS);
            int last = w.submit(SearchQuery.of("papyrus"), FACTS);
            long until = System.currentTimeMillis() + 5000;
            while (w.busy() && System.currentTimeMillis() < until) {
                Thread.sleep(5);
            }
            assertTrue(last > 0 && !w.busy());
            assertEquals("papyrus", w.latest().query().text());
        } finally {
            w.close();
        }
    }

    /** The spec's latency: 100 queries on a full-size index, median under 5 ms and the slowest under 20 ms. */
    @Test
    void latencyOnAFullSizeIndex(@TempDir Path dir) throws Exception {
        SearchFixtures.writeSynthetic(dir);
        ItemIndex idx = ItemIndex.read(dir, List.of());
        List<String> queries = new ArrayList<>();
        String[] seeds = {"ar", "bel", "cor", "dra", "elfen", "gorha", "isjor", "kalum", "morn", "nexor", "paqua", "rissol",
            "torum", "velwyn", "xanyr", "zel", "mana steal", "life", "spell dam", "walk speed", "essence", "corel", "draek",
            "papirus", "abdomen", "belcor", "wynzel", "solris", "umvel", "qua"};
        for (int i = 0; i < 100; i++) {
            String s = seeds[i % seeds.length];
            queries.add(i < seeds.length ? s : s.substring(0, Math.max(1, s.length() - i % 3)));
        }
        for (int i = 0; i < 3; i++) {                                  // warm the JIT
            for (String q : queries) {
                SearchQuery.of(q).run(idx, FACTS);
            }
        }
        long[] times = new long[queries.size()];
        for (int i = 0; i < queries.size(); i++) {
            long t0 = System.nanoTime();
            SearchQuery.of(queries.get(i)).run(idx, FACTS);
            times[i] = System.nanoTime() - t0;
        }
        Arrays.sort(times);
        double median = times[times.length / 2] / 1e6;
        double max = times[times.length - 1] / 1e6;
        System.out.printf("[search] %d queries on %d items: median %.2f ms, slowest %.2f ms%n",
                times.length, idx.entries().size(), median, max);
        assertTrue(median < 5, "median " + median + " ms");
        assertTrue(max < 20, "slowest " + max + " ms");
    }
}
