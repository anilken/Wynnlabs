package io.github.anilken.wynnlabs.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The build window's controls and click areas: its buttons in the mod's sprites, the chosen
 * chips, the click areas drawn this frame, and text cut to a width in the window's font.
 */
final class BuildControls {

    static final int CHOSEN = HomeTab.CHOSEN;

    /** A click area drawn this frame: its box, what it does, and on what. */
    record Btn(int x, int y, int w, int h, String act, Object arg) {}

    Font font;
    final List<Btn> btns = new ArrayList<>();

    /** A wood button (Kit.button). */
    static final int WOOD = 0;
    /** The purple primary button (Kit.primary). */
    static final int PRIMARY = 1;
    /** An icon-only button's width. */
    static final int ICON_W = 16;

    /**
     * One control in the mod's button sprites: {@code text} after the icon when the whole of it
     * fits {@code maxW} comfortably, else the icon alone, centred, with the label as the first
     * tooltip line (the one rule for every control here: text when it fits, never cut). Returns
     * its width. {@code on}: a selected wood control (gold); {@code text} null: always the icon.
     */
    int control(GuiGraphics g, BuildWindow.Host host, int x, int y, int h, String icon, String text, int kind, boolean on, int maxW,
            String act, Object arg, List<Component> tip, int mx, int my) {
        int full = text == null ? Integer.MAX_VALUE : (icon == null ? 0 : 11) + font.width(text) + 12;
        boolean words = text != null && full <= maxW;
        int w = words ? full : ICON_W;
        boolean hov = in(mx, my, x, y, w, h);
        int state = on ? Kit.ON : hov ? Kit.HOVER : Kit.REST;
        if (kind == PRIMARY) {
            Kit.primary(g, x, y, w, h, hov);
        } else {
            Kit.button(g, x, y, w, h, state);
        }
        int ink = kind == PRIMARY ? Theme.CREAM : Kit.buttonText(state);
        int iy = y + (h - 8) / 2;
        if (words) {
            int tx = x + 6;
            if (icon != null) {
                Art.icon(g, icon, tx, iy, 8, ink);
                tx += 11;
            }
            g.drawString(font, text, tx, y + (h - 8) / 2, ink, false);
        } else if (icon != null) {
            Art.icon(g, icon, x + (w - 8) / 2, iy, 8, ink);
        }
        if (act != null) {
            btn(x, y, w, h, act, arg);
        }
        List<Component> lines = new ArrayList<>();
        if (!words && text != null) {
            lines.add(Component.literal(text).withStyle(ChatFormatting.WHITE));
        }
        if (tip != null) {
            lines.addAll(tip);
        }
        if (!lines.isEmpty()) {
            host.tip(x, y, w, h, lines);
        }
        return w;
    }

    /** {@link #control}'s width without drawing it. */
    int controlW(String icon, String text, int maxW) {
        int full = text == null ? Integer.MAX_VALUE : (icon == null ? 0 : 11) + font.width(text) + 12;
        return text != null && full <= maxW ? full : ICON_W;
    }

    void btn(int x, int y, int w, int h, String act, Object arg) {
        btns.add(new Btn(x, y, w, h, act, arg));
    }

    static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    String fit(String s, int w, boolean bold) {
        if (s == null || w <= 0) {
            return "";
        }
        if (width(s, bold) <= w) {
            return s;
        }
        String cut = font.plainSubstrByWidth(s, Math.max(0, w - width("…", bold)));
        while (!cut.isEmpty() && width(cut + "…", bold) > w) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }

    int width(String s, boolean bold) {
        return bold ? font.width(Component.literal(s).withStyle(UiFont::bold)) : font.width(s);
    }

    /** A chosen item's chip: the kit's pill, washed purple, with its purple outline (lighter on hover). */
    static void chip(GuiGraphics g, int x, int y, int w, int h, boolean hover) {
        Kit.seal(g, x, y, w, h, hover ? 0xFFEFE6FA : 0xFFE4D8F2);
        Kit.pillLine(g, x, y, w, h, CHOSEN);
    }
}
