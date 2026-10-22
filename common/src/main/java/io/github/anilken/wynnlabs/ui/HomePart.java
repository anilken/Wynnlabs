package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.model.Tier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * A part of the home tab: the font and menu size of this frame, and the text helpers its
 * drawing shares (fitting, wrapping, the tiers' colours, the cards).
 */
abstract class HomePart {

    /** This frame's font, set by {@link HomeTab#draw} before any part draws. */
    Font font;
    /** The large menu this frame: bigger icons, and the tiles' extra lines. */
    boolean large;

    /** Sets this frame's font and menu size. */
    final void frame(Font font, boolean large) {
        this.font = font;
        this.large = large;
    }

    /**
     * A name in at most two lines no wider than {@code w}: whole when it fits, else broken at the
     * space that makes the longer line shortest; the second line ends in "…" when still too long.
     */
    List<String> wrap2(String s, int w) {
        return wrap2(s, w, false);
    }

    /** {@link #wrap2(String, int)} measured as drawn: in bold when {@code bold}. */
    List<String> wrap2(String s, int w, boolean bold) {
        if (width(s, bold) <= w) {
            return List.of(s);
        }
        String a = null;
        String b = null;
        int best = Integer.MAX_VALUE;
        for (int i = s.indexOf(' '); i > 0; i = s.indexOf(' ', i + 1)) {
            String l1 = s.substring(0, i);
            String l2 = s.substring(i + 1);
            int m = Math.max(width(l1, bold), width(l2, bold));
            if (width(l1, bold) <= w && m < best) {
                best = m;
                a = l1;
                b = l2;
            }
        }
        return a == null ? List.of(fit(s, w, bold)) : List.of(a, fit(b, w, bold));
    }

    /** A card with its shadow under the whole width (Kit.card's stops short of the corners). */
    static void card(GuiGraphics g, int x, int y, int w, int h, int accent, boolean lit) {
        Kit.card(g, x, y, w, h, accent, lit);
        g.fill(x + 1, y + h, x + 2, y + h + 1, 0x88624E3C);
        g.fill(x + w - 2, y + h, x + w - 1, y + h + 1, 0x88624E3C);
        g.fill(x + 1, y + h + 1, x + 3, y + h + 2, 0x44624E3C);
        g.fill(x + w - 3, y + h + 1, x + w - 1, y + h + 2, 0x44624E3C);
    }

    /** Text wrapped to {@code w} from line {@code y}, the lines that end above {@code bottom}; the next line's y. */
    int lines(GuiGraphics g, String text, int x, int y, int w, int bottom, int colour) {
        for (net.minecraft.util.FormattedCharSequence l : font.split(Component.literal(text), w)) {
            if (y + 9 > bottom) {
                break;
            }
            g.drawString(font, Kit.ink(l), x, y, colour, false);
            y += 10;
        }
        return y;
    }

    void ink(GuiGraphics g, String s, int x, int y, int colour) {
        Kit.text(g, font, s, x, y, colour);
    }

    void ink(GuiGraphics g, Component c, int x, int y, int colour) {
        Kit.text(g, font, c, x, y, Theme.paperRgb(colour));
    }

    String fit(String s, int w) {
        return fit(s, w, false);
    }

    /**
     * {@code s} cut with "…" to {@code w}, measured as drawn: bold text is wider (a pixel per
     * letter in the game's own font), so a name drawn bold is fitted in bold.
     */
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

    /** "Aspect of the Monolith" → "Monolith", for a narrow tile. */
    static String shortAspect(String name) {
        for (String p : new String[] {"Aspect of the ", "Aspect of "}) {
            if (name.startsWith(p)) {
                return name.substring(p.length());
            }
        }
        return name;
    }

    static int tierColour(Tier t) {
        if (t == null) {
            return Theme.INK;
        }
        return switch (t) {
            case MYTHIC -> Theme.TIER_MYTHIC;
            case FABLED -> Theme.TIER_FABLED;
            case LEGENDARY -> Theme.TIER_LEGENDARY;
            case RARE -> Theme.TIER_RARE;
            case UNIQUE -> Theme.TIER_UNIQUE;
            case SET -> Theme.TIER_SET;
            default -> Theme.INK;
        };
    }

    static ChatFormatting tierChat(Tier t) {
        if (t == null) {
            return ChatFormatting.WHITE;
        }
        return switch (t) {
            case MYTHIC -> ChatFormatting.DARK_PURPLE;
            case FABLED -> ChatFormatting.RED;
            case LEGENDARY -> ChatFormatting.AQUA;
            case RARE -> ChatFormatting.LIGHT_PURPLE;
            case UNIQUE -> ChatFormatting.YELLOW;
            case SET -> ChatFormatting.GREEN;
            default -> ChatFormatting.WHITE;
        };
    }
}
