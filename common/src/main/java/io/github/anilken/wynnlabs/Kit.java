package io.github.anilken.wynnlabs;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

/**
 * The J menu's wood-and-parchment pieces (tools/art/kit.mjs draws them): the window, the rail's
 * planks, the parchment page, buttons, cards, slots, seals, bars and switches. Each call draws
 * on the coordinates it is given, so a screen keeps its layout and only changes its paint.
 * Text on paper goes through {@link #text}: no shadow, colours in their paper tone.
 */
final class Kit {

    private Kit() {}

    /**
     * The design system's numbers, in GUI px (openspec j-menu-restyle, design decision 8): one
     * spacing scale, the line steps, and each control's height and padding. Screens use these
     * instead of picking their own, so text never ends up packed against a border or a line.
     */
    static final class Ui {
        private Ui() {}

        // spacing scale: 2 · 4 · 6 · 8 · 12 · 16 · 24
        static final int S2 = 2;
        static final int S4 = 4;
        static final int S6 = 6;
        static final int S8 = 8;
        static final int S12 = 12;
        static final int S16 = 16;
        static final int S24 = 24;

        /** From one body line to the next (the font's line is 9px). */
        static final int LINE = 12;
        /** From one line to the next in a dense list. */
        static final int LINE_DENSE = 11;
        /** From one 0.85× description line to the next. */
        static final int LINE_SMALL = 10;

        /** Buttons, chips and ledger tabs. */
        static final int BTN_H = 14;
        static final int BTN_PAD = 6;
        /** A tag inside a line ("no downsides", "hard" …). */
        static final int TAG_H = 11;
        static final int TAG_PAD = 4;
        /** Between controls in a row. */
        static final int GAP = 4;
        /** Inside a card, and between cards. */
        static final int CARD_PAD = 8;
        /** The smallest list row, and a row with a title and a sub line. */
        static final int ROW = 20;
        static final int ROW2 = 24;
        /** Slots: a 16px item in 20, a 32px item in 40. */
        static final int SLOT_S = 20;
        static final int SLOT_L = 40;

        /** Where text sits to be centred in a box {@code h} tall starting at {@code top}. */
        static int textY(int top, int h) {
            return top + (h - 8) / 2;
        }

        /** A button's or chip's width for its text. */
        static int btnW(net.minecraft.client.gui.Font font, String text) {
            return font.width(text) + 2 * BTN_PAD;
        }

        /** A tag's width for its text. */
        static int tagW(net.minecraft.client.gui.Font font, String text) {
            return font.width(text) + 2 * TAG_PAD;
        }
    }

    private static final Identifier GRAIN = Identifier.fromNamespaceAndPath("wynnlabs", "textures/gui/sprites/kit/grain.png");
    private static final int GRAIN_SIZE = 256;

    private static Identifier sprite(String name) {
        return Identifier.fromNamespaceAndPath("wynnlabs", "kit/" + name);
    }

    /** A kit sprite over the box: nine-sliced ones through {@link NineSlice}, so borders stay even at scale 3. */
    private static void nine(GuiGraphics g, String name, int x, int y, int w, int h) {
        nine(g, name, x, y, w, h, -1);
    }

    private static void nine(GuiGraphics g, String name, int x, int y, int w, int h, int tint) {
        NineSlice.draw(g, sprite(name), x, y, w, h, tint);
    }

    // ---- frame ----

    /** The window: dark outline, wooden border, brass nails in the corners. */
    static void window(GuiGraphics g, int x, int y, int w, int h) {
        LayoutCheck.frame(g.pose(), x, y, w, h);                 // the preview's layout check: the screen's own box
        // a soft shadow straight under the frame, fading out in three steps
        g.fill(x + 3, y + h, x + w - 3, y + h + 1, 0x66000000);
        g.fill(x + 5, y + h + 1, x + w - 5, y + h + 2, 0x40000000);
        g.fill(x + 8, y + h + 2, x + w - 8, y + h + 3, 0x1C000000);
        nine(g, "window", x, y, w, h);
    }

    private static final int PLANK_W = 100;
    private static final int PLANK_H = 104;

    /**
     * The rail's planks: the plank texture repeated down the rail (a few pieces, it is 104px
     * tall), then a dark edge with a light line along the right.
     */
    static void planks(GuiGraphics g, int x, int y, int w, int h) {
        LayoutCheck.textArea(g.pose(), x, y, w, h);                // the preview's layout check: text may sit here
        for (int oy = 0; oy < h; oy += PLANK_H) {
            int ph = Math.min(PLANK_H, h - oy);
            g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite("planks"), PLANK_W, PLANK_H, 0, 0, x, y + oy, Math.min(w, PLANK_W), ph);
        }
        g.fill(x + w - 2, y, x + w, y + h, Theme.WOOD_EDGE);
        g.fill(x + w - 3, y, x + w - 2, y + h, Theme.WOOD_HI);
    }

    /** The parchment page: shadow, torn rim with its inner rule, grain and corner ornaments. */
    static void paper(GuiGraphics g, int x, int y, int w, int h) {
        LayoutCheck.textArea(g.pose(), x, y, w, h);                // the preview's layout check: text may sit here
        g.fill(x + 2, y + h, x + w + 2, y + h + 2, 0x55000000);
        nine(g, "paper", x, y, w, h);
        // the grain over the flat middle, in as few 256px pieces as the page needs
        int gx0 = x + 6;
        int gy0 = y + 6;
        int gw = w - 12;
        int gh = h - 12;
        for (int oy = 0; oy < gh; oy += GRAIN_SIZE) {
            for (int ox = 0; ox < gw; ox += GRAIN_SIZE) {
                int pw = Math.min(GRAIN_SIZE, gw - ox);
                int ph = Math.min(GRAIN_SIZE, gh - oy);
                g.blit(RenderPipelines.GUI_TEXTURED, GRAIN, gx0 + ox, gy0 + oy, 0, 0, pw, ph, GRAIN_SIZE, GRAIN_SIZE);
            }
        }
        nine(g, "corner_tl", x + 2, y + 2, 13, 13);
        nine(g, "corner_tr", x + w - 15, y + 2, 13, 13);
        nine(g, "corner_bl", x + 2, y + h - 15, 13, 13);
        nine(g, "corner_br", x + w - 15, y + h - 15, 13, 13);
    }

    /**
     * The red close seal on the window's top-right corner, over the brass nail there (the window
     * drawn by {@link #window} 5px around the panel). Returns its click rectangle.
     */
    static int[] closeOutside(GuiGraphics g, int screenW, int px, int py, int pw, boolean hover) {
        int size = 14;
        int x = px + pw + 2 - size / 2;         // the nail sits 3px in from the frame's outer corner
        int y = py - 2 - size / 2;
        // a window with no room past the corner: pushed in until it is whole, over the frame's corner
        x = Math.min(x, screenW - size);
        y = Math.max(y, 0);
        close(g, x, y, size, hover);
        LayoutCheck.frame(g.pose(), x, y, size, size);
        return new int[] {x, y, size, size};
    }

    /** The red close seal, {@code size} square. */
    static void close(GuiGraphics g, int x, int y, int size, boolean hover) {
        nine(g, hover ? "close_hover" : "close", x, y, size, size);
    }

    /** The class medal (23×23): a brass hexagon with a paper face. */
    static void medal(GuiGraphics g, int x, int y) {
        nine(g, "medal", x, y, 23, 23);
    }

    /** The active tab's gold arrow, reaching right from {@code x}. */
    static void tip(GuiGraphics g, int x, int y, int h) {
        nine(g, "tip", x, y, 6, h);
    }

    // ---- controls ----

    static final int REST = 0;
    static final int HOVER = 1;
    static final int ON = 2;

    /** A wooden button, lighter on hover, gold when on (selected or primary). */
    static void button(GuiGraphics g, int x, int y, int w, int h, int state) {
        nine(g, state == ON ? "gold" : state == HOVER ? "wood_hover" : "wood", x, y, w, h);
    }

    /** A button's text colour for its state: cream on wood, ink on gold. */
    static int buttonText(int state) {
        return state == ON ? Theme.INK : Theme.CREAM;
    }

    /** A ledger tab above a list (open at the bottom): wood, or gold when on. */
    static void ledger(GuiGraphics g, int x, int y, int w, int h, boolean on) {
        nine(g, on ? "ledger_gold" : "ledger_wood", x, y, w, h);
    }

    /** A parchment card with a small shadow, and an optional coloured strip along its top. */
    static void card(GuiGraphics g, int x, int y, int w, int h, int accent) {
        card(g, x, y, w, h, accent, false);
    }

    /** A card; {@code lit} gives it a gold edge and a lighter face (hover, or the selected one). */
    static void card(GuiGraphics g, int x, int y, int w, int h, int accent, boolean lit) {
        // a 2px shadow under the card, so cards in a column stay apart
        g.fill(x + 2, y + h, x + w - 2, y + h + 1, 0x88624E3C);
        g.fill(x + 3, y + h + 1, x + w - 3, y + h + 2, 0x44624E3C);
        nine(g, lit ? "card_lit" : "card", x, y, w, h);
        if (accent != 0) {
            g.fill(x + 2, y + 1, x + w - 2, y + 3, accent);
        }
    }

    /** A card drawn dark (the ability tree's node panel). */
    static void darkPanel(GuiGraphics g, int x, int y, int w, int h) {
        nine(g, "card", x, y, w, h, 0xFF4A5560);
    }

    /**
     * A half-circle gauge (58x29), {@code frac} of it filled from the left, the rest {@code bg};
     * {@code cx, cy} its centre on the flat side. Two sprites at four times the GUI density:
     * the track, and the fill cut off at the angle (a vertical cut, which the ring's thickness
     * makes near enough radial). {@code r} and {@code t} are the sprite's 29 and 5.
     */
    static void gauge(GuiGraphics g, int cx, int cy, int r, int t, double frac, int bg, int fg, int fgDeep) {
        int x = cx - 29;
        int y = cy - 29;
        g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite("gauge_track"), x, y, 58, 29, bg);
        double f = Math.max(0, Math.min(1, frac));
        if (f <= 0) {
            return;
        }
        int cut = (int) Math.round(cx - 29 * Math.cos(Math.PI * f));
        g.enableScissor(x, y, Math.min(x + 58, cut + 1), cy);
        g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite("gauge_fill"), x, y, 58, 29, fg);
        g.disableScissor();
    }

    /** The ability tree's board: dark slate with a faint grain, shaded towards its rim. */
    static void slate(GuiGraphics g, int x, int y, int w, int h) {
        nine(g, "card", x, y, w, h, 0xFF3A424A);
        g.fill(x + 2, y + 2, x + w - 2, y + h - 2, 0xFF262B30);
        int gx0 = x + 2;
        int gy0 = y + 2;
        int gw = w - 4;
        int gh = h - 4;
        for (int oy = 0; oy < gh; oy += GRAIN_SIZE) {
            for (int ox = 0; ox < gw; ox += GRAIN_SIZE) {
                int pw = Math.min(GRAIN_SIZE, gw - ox);
                int ph = Math.min(GRAIN_SIZE, gh - oy);
                g.blit(RenderPipelines.GUI_TEXTURED, GRAIN, gx0 + ox, gy0 + oy, 0, 0, pw, ph, GRAIN_SIZE, GRAIN_SIZE, 0x60FFFFFF);
            }
        }
        g.fill(x + 2, y + 2, x + w - 2, y + 5, 0x48000000);
        g.fill(x + 2, y + h - 5, x + w - 2, y + h - 2, 0x48000000);
        g.fill(x + 2, y + 5, x + 5, y + h - 5, 0x48000000);
        g.fill(x + w - 5, y + 5, x + w - 2, y + h - 5, 0x48000000);
    }

    /** A stat plaque. */
    static void plaque(GuiGraphics g, int x, int y, int w, int h) {
        nine(g, "plaque", x, y, w, h);
    }

    /** An inset item slot; {@code ring} (0 = none) is a 1px tier-colour line just inside it. */
    static void slot(GuiGraphics g, int x, int y, int size, int ring) {
        nine(g, "slot", x, y, size, size);
        if (ring != 0) {
            // on a small slot the ring is its edge, so it never touches the item
            int in = size <= 20 ? 0 : 1;
            outline(g, x + in, y + in, size - 2 * in, size - 2 * in, ring);
        }
    }

    /** A 1px rectangle outline. */
    static void outline(GuiGraphics g, int x, int y, int w, int h, int colour) {
        g.fill(x, y, x + w, y + 1, colour);
        g.fill(x, y + h - 1, x + w, y + h, colour);
        g.fill(x, y + 1, x + 1, y + h - 1, colour);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, colour);
    }

    /** A rounded seal or pill in a colour (gain seals, the nearest bank / Trade Market). */
    static void seal(GuiGraphics g, int x, int y, int w, int h, int colour) {
        nine(g, "seal", x, y, w, h, colour);
    }

    /** An outlined pill. */
    static void pillLine(GuiGraphics g, int x, int y, int w, int h, int colour) {
        nine(g, "pill_line", x, y, w, h, colour);
    }

    /**
     * A bar: dark rim, three-row fill (light, mid, dark) up to {@code frac}. {@code rim} adds
     * the brass ring the XP bar has.
     */
    static void bar(GuiGraphics g, int x, int y, int w, int h, double frac, int hi, int mid, int lo, boolean rim) {
        if (rim) {
            nine(g, "bar_rim", x - 1, y - 1, w + 2, h + 2);
        }
        nine(g, "bar", x, y, w, h);
        int fw = (int) Math.round((w - 2) * Math.max(0, Math.min(1, frac)));
        if (fw > 0 && h > 2) {
            int iy = y + 1;
            int ih = h - 2;
            int top = Math.max(1, ih / 4);
            int bottom = Math.max(1, ih / 4);
            g.fill(x + 1, iy, x + 1 + fw, iy + top, hi);
            g.fill(x + 1, iy + top, x + 1 + fw, iy + ih - bottom, mid);
            g.fill(x + 1, iy + ih - bottom, x + 1 + fw, iy + ih, lo);
        }
    }

    /** A teal bar (experience, progress). */
    static void tealBar(GuiGraphics g, int x, int y, int w, int h, double frac, boolean rim) {
        bar(g, x, y, w, h, frac, Theme.TEAL_HI, Theme.TEAL, Theme.TEAL_LO, rim);
    }

    /** Notches every tenth of a bar (the XP bar). */
    static void notches(GuiGraphics g, int x, int y, int w, int h) {
        for (int i = 1; i < 10; i++) {
            int nx = x + 1 + (w - 2) * i / 10;
            g.fill(nx, y + 1, nx + 1, y + h - 1, 0x662E2E2E);
        }
    }

    /** A text field on paper: a light inset well with a brown edge, gold while focused. */
    static void field(GuiGraphics g, int x, int y, int w, int h, boolean focused) {
        g.fill(x, y, x + w, y + h, 0xFFF4EAD0);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, 0xFFC4B184);
        outline(g, x, y, w, h, focused ? Theme.GOLD_DEEP : Theme.INK_2);
    }

    /** A sliding switch (19×11). */
    static void toggle(GuiGraphics g, int x, int y, boolean on) {
        nine(g, on ? "switch_on" : "switch_off", x, y, 19, 11);
    }

    /** A section title: the title in ink where it always was, then a brass ◆ and a rule to {@code x + w}. */
    static void section(GuiGraphics g, Font font, String title, int x, int y, int w) {
        g.drawString(font, title, x, y, Theme.INK_2, false);
        int dx = x + font.width(title) + 3;
        nine(g, "diamond", dx, y + 2, 5, 5);
        int lx = dx + 8;
        if (lx < x + w) {
            g.fill(lx, y + 4, x + w, y + 5, Theme.LINE);
        }
    }

    /** A list row's paint: a soft stripe on odd rows, a lighter fill and a gold strip on hover or selection. */
    static void row(GuiGraphics g, int x, int y, int w, int h, int index, boolean lit) {
        if (lit) {
            g.fill(x, y, x + w, y + h, 0x88FFF3C8);
            g.fill(x, y, x + 2, y + h, Theme.GOLD_DEEP);
        } else if (index % 2 == 1) {
            g.fill(x, y, x + w, y + h, Theme.STRIPE);
        }
    }

    // ---- text ----

    /** Text on paper: no shadow, its colours turned to their paper tone. */
    static void text(GuiGraphics g, Font font, Component c, int x, int y, int colour) {
        g.drawString(font, ink(c.getVisualOrderText()), x, y, colour, false);
    }

    /** Plain text on paper in one colour, no shadow. */
    static void text(GuiGraphics g, Font font, String s, int x, int y, int colour) {
        g.drawString(font, s, x, y, colour, false);
    }

    /**
     * A sequence whose styled colours are swapped for their paper tone. Runs of one style share
     * the swapped style, so nothing is allocated per character.
     */
    static FormattedCharSequence ink(FormattedCharSequence seq) {
        return sink -> {
            Style[] last = new Style[2];
            return seq.accept((index, style, cp) -> {
                if (style != last[0]) {
                    last[0] = style;
                    last[1] = paperStyle(style);
                }
                return sink.accept(index, last[1], cp);
            });
        };
    }

    static Style paperStyle(Style s) {
        TextColor c = s.getColor();
        if (c == null) {
            return s;
        }
        int p = Theme.paperRgb(c.getValue());
        return (p & 0xFFFFFF) == (c.getValue() & 0xFFFFFF) ? s : s.withColor(p & 0xFFFFFF);
    }
}
