package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.core.Settings;
import io.github.anilken.wynnlabs.mixin.FontAccessor;
import io.github.anilken.wynnlabs.mixin.ScreenFontAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/**
 * The mod's screens read like a web page: their text uses Noto Sans instead of the pixel font. The
 * game draws a TTF from a glyph texture without smoothing, which is only sharp when the texture
 * matches the GUI scale, so there is one font definition per scale (wynnlabs:ui_*_s1..s6) and
 * the one for the scale in use is picked at draw time. Sizes: tiny, small, base, large, big;
 * each plain and bold. Text that asks for another font (the game's icon fonts) keeps it. The
 * "webFont" setting turns it all off.
 */
final class UiFont {

    private static Font font;
    private static Font base;
    /** The scale a screen draws at under the menu scale setting; 0: the window's. */
    private static int drawScale;

    private UiFont() {}

    /** While a screen draws at the menu scale: that scale's font; 0 to go back to the window's. */
    static void drawScale(int scale) {
        drawScale = scale;
    }

    private static int scale() {
        if (drawScale > 0) {
            return Math.max(1, Math.min(6, drawScale));
        }
        try {
            return Math.max(1, Math.min(6, (int) Math.round(Minecraft.getInstance().getWindow().getGuiScale())));
        } catch (Throwable t) {
            return 2;
        }
    }

    /** wynnlabs:ui[_size][_bold]_s{scale} */
    private static FontDescription desc(String size, boolean bold) {
        String id = "ui" + (size.isEmpty() ? "" : "_" + size) + (bold ? "_bold" : "") + "_s" + scale();
        return new FontDescription.Resource(Identifier.fromNamespaceAndPath("wynnlabs", id));
    }

    private static boolean ours(FontDescription d) {
        return d instanceof FontDescription.Resource r && r.id().getNamespace().equals("wynnlabs");
    }

    private static boolean boldFont(Style s) {
        return s.isBold() || ours(s.getFont()) && ((FontDescription.Resource) s.getFont()).id().getPath().contains("_bold");
    }

    /** The UI font, rebuilt when the game's font object changes (resource reload). */
    static Font get() {
        Font game = Minecraft.getInstance().font;
        if (!Settings.on("webFont")) {
            return game;
        }
        if (font == null || base != game) {
            base = game;
            Font.Provider inner = ((FontAccessor) game).wynnlabs$provider();
            font = new Font(new Font.Provider() {
                @Override
                public GlyphSource glyphs(FontDescription d) {
                    return inner.glyphs(FontDescription.DEFAULT.equals(d) ? desc("", false) : d);
                }

                @Override
                public EffectGlyph effect() {
                    return inner.effect();
                }
            });
        }
        return font;
    }

    /** Puts the UI font on one of our screens; call from its init(). */
    static void apply(Screen screen) {
        try {
            ((ScreenFontAccessor) screen).wynnlabs$setFont(get());
        } catch (Throwable t) {
            // keep the game's font
        }
    }

    /** A heading style: Noto Sans Bold with the web font, the game's bold without it. */
    static Style bold(Style s) {
        return Settings.on("webFont") ? s.withFont(desc("", true)).withBold(false) : s.withBold(true);
    }

    /**
     * Text that would be drawn scaled by {@code factor}: the Noto Sans size closest to it instead, so
     * it stays sharp (micro < 0.65 ≤ tiny < 0.8 ≤ small < 1 ≤ large < 1.4 ≤ big).
     */
    static Style sized(Style s, float factor) {
        String size = factor < 0.65f ? "micro" : factor < 0.8f ? "tiny" : factor < 1f ? "small" : factor < 1.4f ? "large" : "big";
        return s.withFont(desc(size, boldFont(s))).withBold(false);
    }
}
