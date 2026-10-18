package io.github.anilken.wynnlabs;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The screenshot's pixels become a valid PNG with the same colours. */
class ScreenshotPngTest {

    @Test
    void pixelsRoundTrip() throws Exception {
        int[] px = {0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFFFF, 0xFF101010, 0xFF808080};
        byte[] png = WynnLabs.png(3, 2, px);
        assertEquals((byte) 0x89, png[0]);
        assertEquals('P', png[1]);
        var img = javax.imageio.ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(3, img.getWidth());
        assertEquals(2, img.getHeight());
        for (int i = 0; i < px.length; i++) {
            assertEquals(px[i], img.getRGB(i % 3, i / 3), "pixel " + i);
        }
    }
}
