package io.github.anilken.wynnlabs.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ExactPriceTest {

    @Test
    void pricesReadToTheEmerald() {
        assertEquals("2 EB 49 E", BoxListingsPanel.exact(177));
        assertEquals("27 E", BoxListingsPanel.exact(27));
        assertEquals("1 LE", BoxListingsPanel.exact(4096));
        assertEquals("1 LE 12 EB 3 E", BoxListingsPanel.exact(4096 + 12 * 64 + 3));
        assertEquals("0 E", BoxListingsPanel.exact(0));
    }
}
