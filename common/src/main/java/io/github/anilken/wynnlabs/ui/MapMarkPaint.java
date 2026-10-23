package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.search.MapMark;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Draws one of the search tab's marks on Wynntils' map ({@link MapMark}): a spawn area as a
 * translucent disc with a ring in its badge's colour, sized by the map's zoom; a point as the
 * badge on a small seal. The label shows while the mouse is over it. Rows off the screen are
 * skipped, so a close zoom costs no more than the screen's height.
 */
public final class MapMarkPaint {

    private MapMarkPaint() {}

    /** At (x, z) on screen, the map at {@code pxPerBlock} pixels per block. */
    public static void draw(GuiGraphics g, MapMark m, float x, float z, boolean hovered, float pxPerBlock) {
        int rgb = Theme.sourceFill(m.badge()) & 0xFFFFFF;
        int cx = Math.round(x);
        int cz = Math.round(z);
        int top;
        if (m.point()) {
            Kit.seal(g, cx - 5, cz - 5, 11, 11, 0xFF000000 | rgb);
            Art.icon(g, m.badge(), cx - 4, cz - 4, 8, Theme.CREAM);
            if (m.focus()) {
                g.renderOutline(cx - 7, cz - 7, 15, 15, Theme.GOLD);
            }
            top = cz - 7;
        } else {
            // the map's own colours are earthy, so the badge colour is lifted towards white and
            // the area gets a white halo outside a thick ring: it reads on sand, grass and water
            int r = MapMark.radiusPx(m.radius(), pxPerBlock);
            boolean strong = m.focus() || hovered;
            int lr = (rgb >> 16 & 0xFF) + ((0xFF - (rgb >> 16 & 0xFF)) * 2 / 5);
            int lg = (rgb >> 8 & 0xFF) + ((0xFF - (rgb >> 8 & 0xFF)) * 2 / 5);
            int lb = (rgb & 0xFF) + ((0xFF - (rgb & 0xFF)) * 2 / 5);
            int bright = lr << 16 | lg << 8 | lb;
            disc(g, cx, cz, r + 2, 0, (strong ? 0xFF : 0xD0) << 24 | 0xFFFFFF, 2);
            disc(g, cx, cz, r, (strong ? 0x70 : 0x50) << 24 | bright, 0xFF000000 | bright, strong ? 4 : 3);
            top = cz - r - 2;
        }
        if (hovered && m.label() != null) {
            Font font = Minecraft.getInstance().font;
            String text = m.label();
            int w = font.width(text);
            int lx = cx - w / 2;
            int ly = top - 12;
            g.fill(lx - 3, ly - 2, lx + w + 3, ly + 10, 0xC0101010);
            g.drawString(font, text, lx, ly, 0xFFFFFFFF, true);
        }
    }

    /** A disc of radius {@code r} filled with {@code fill} and edged with a {@code t}-pixel {@code ring}. */
    static void disc(GuiGraphics g, int cx, int cz, int r, int fill, int ring, int t) {
        int from = Math.max(-r, -cz - 1);
        int to = Math.min(r, g.guiHeight() - cz + 1);
        int inner = Math.max(0, r - t);
        for (int dy = from; dy <= to; dy++) {
            int half = (int) Math.sqrt((double) r * r - (double) dy * dy);
            int y = cz + dy;
            if (Math.abs(dy) > inner) {
                g.fill(cx - half, y, cx + half + 1, y + 1, ring);
                continue;
            }
            int in = (int) Math.sqrt((double) inner * inner - (double) dy * dy);
            g.fill(cx - half, y, cx - in, y + 1, ring);
            g.fill(cx - in, y, cx + in + 1, y + 1, fill);
            g.fill(cx + in + 1, y, cx + half + 1, y + 1, ring);
        }
    }
}
