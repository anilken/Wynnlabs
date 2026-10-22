package io.github.anilken.wynnlabs.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wynnventory's cached prices are read through reflection; a fake snapshot stands in for its classes. */
class PriceImportTest {

    public static final class Summary {
        private final String name;
        private final int lowest;

        Summary(String name, int lowest) {
            this.name = name;
            this.lowest = lowest;
        }

        public boolean isEmpty() {
            return false;
        }

        public String getName() {
            return name;
        }

        public Integer getTier() {
            return null;
        }

        public Boolean isShiny() {
            return false;
        }

        public Integer getLowestPrice() {
            return lowest;
        }

        public Double getMedian() {
            return lowest * 1.5;
        }

        public Double getAverageMid80PercentPrice() {
            return lowest * 1.2;
        }

        public Integer getUnidentifiedLowestPrice() {
            return null;
        }

        public Double getUnidentifiedAverageMid80PercentPrice() {
            return null;
        }
    }

    public static final class Snapshot {
        private final Summary live;

        Snapshot(Summary live) {
            this.live = live;
        }

        public Summary live() {
            return live;
        }

        public Summary historic() {
            return null;
        }
    }

    @Test
    void aSnapshotIsReadAndAnEqualOneChangesNothing() throws Exception {
        String name = "Test Relik " + System.nanoTime();
        assertTrue(Prices.absorb(new Snapshot(new Summary(name, 4096))));
        Prices.Price p = Prices.stored(name);
        assertNotNull(p);
        assertEquals(4096, p.lowest());
        assertEquals(6144.0, p.median(), 1e-9);
        assertFalse(Prices.absorb(new Snapshot(new Summary(name, 4096))), "the same price is no change");
        assertTrue(Prices.absorb(new Snapshot(new Summary(name, 5000))), "a new price is");
        assertEquals(5000, Prices.stored(name).lowest());
    }
}
