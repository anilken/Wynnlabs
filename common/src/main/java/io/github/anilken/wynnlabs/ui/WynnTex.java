package io.github.anilken.wynnlabs.ui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Art from the Wynncraft resource pack the game has loaded (buttons, slot icons, tooltip frames),
 * drawn by name at runtime: nothing of it is copied into this mod. Every call reports whether it
 * drew, so a screen falls back to its own drawing when the pack is missing or a file was renamed.
 */
public final class WynnTex {

    private static final Map<String, int[]> SIZE = new ConcurrentHashMap<>();
    /** {u, v, side} of each trimmed texture; empty when the pack does not have it. */
    private static final Map<String, int[]> TRIM = new ConcurrentHashMap<>();

    private WynnTex() {}

    /** "wynn/gui/button/plus" → minecraft:textures/wynn/gui/button/plus.png */
    public static Identifier id(String path) {
        return Identifier.withDefaultNamespace("textures/" + path + ".png");
    }

    /** Width and height of a loaded texture; {0, 0} when the pack does not have it. */
    public static int[] size(String path) {
        return SIZE.computeIfAbsent(path, p -> {
            try {
                var res = Minecraft.getInstance().getResourceManager().getResource(id(p));
                if (res.isEmpty()) {
                    return new int[] {0, 0};
                }
                try (var in = res.get().open(); NativeImage img = NativeImage.read(in)) {
                    return new int[] {img.getWidth(), img.getHeight()};
                }
            } catch (Exception e) {
                return new int[] {0, 0};
            }
        });
    }

    static boolean has(String path) {
        return size(path)[0] > 0;
    }

    /** The whole texture (the first frame of an animated strip) scaled into w×h; false if missing. */
    public static boolean draw(GuiGraphics g, String path, int x, int y, int w, int h) {
        int[] s = size(path);
        if (s[0] == 0) {
            return false;
        }
        int frame = s[1] > s[0] && s[1] % s[0] == 0 ? s[0] : s[1];
        g.blit(RenderPipelines.GUI_TEXTURED, id(path), x, y, 0, 0, w, h, s[0], frame, s[0], s[1]);
        return true;
    }

    /**
     * The picture without the empty margin around it, scaled into size×size; false if missing. The
     * pack's glyphs sit small in a large canvas (a raid's 32 px content-book glyph is 19 px of it,
     * a class's 48 px aspect crystal 16 px), so the whole texture drawn small leaves a dot.
     */
    public static boolean drawTrimmed(GuiGraphics g, String path, int x, int y, int size) {
        int[] t = trim(path);
        if (t == null) {
            return false;
        }
        return region(g, path, x, y, size, size, t[0], t[1], t[2], t[2]);
    }

    /** {u, v, side}: the square around the first frame's visible pixels, centred on them; null when missing. */
    static int[] trim(String path) {
        int[] t = TRIM.computeIfAbsent(path, p -> {
            try {
                var res = Minecraft.getInstance().getResourceManager().getResource(id(p));
                if (res.isEmpty()) {
                    return new int[0];
                }
                try (var in = res.get().open(); NativeImage img = NativeImage.read(in)) {
                    return square(img);
                }
            } catch (Exception e) {
                return new int[0];
            }
        });
        return t.length == 0 ? null : t;
    }

    private static int[] square(NativeImage img) {
        int w = img.getWidth();
        int h = img.getHeight() > w && img.getHeight() % w == 0 ? w : img.getHeight();     // an animated strip: its first frame
        int x0 = w;
        int y0 = h;
        int x1 = -1;
        int y1 = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((img.getPixel(x, y) >>> 24) > 16) {
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
        }
        if (x1 < 0) {
            return new int[] {0, 0, Math.min(w, h)};
        }
        int side = Math.min(Math.min(w, h), Math.max(x1 - x0 + 1, y1 - y0 + 1));
        int u = Math.max(0, Math.min(w - side, (x0 + x1 + 1 - side) / 2));
        int v = Math.max(0, Math.min(h - side, (y0 + y1 + 1 - side) / 2));
        return new int[] {u, v, side};
    }

    /** A uw×vh piece of a texture at (u, v), scaled into w×h; false if the pack does not have it. */
    static boolean region(GuiGraphics g, String path, int x, int y, int w, int h, int u, int v, int uw, int vh) {
        int[] s = size(path);
        if (s[0] == 0) {
            return false;
        }
        g.blit(RenderPipelines.GUI_TEXTURED, id(path), x, y, u, v, w, h, uw, vh, s[0], s[1]);
        return true;
    }

    /**
     * A profession's icon from the pack's profession glyphs (font/profession.png: twelve 16px
     * cells, the gathering ones then the crafting ones, each alphabetical); false if missing.
     */
    static boolean profession(GuiGraphics g, String profession, int x, int y, int size) {
        int i = PROFESSION_CELLS.indexOf(profession);
        return i >= 0 && region(g, "font/profession", x, y, size, size, i * 16, 0, 16, 16);
    }

    private static final java.util.List<String> PROFESSION_CELLS = java.util.List.of("FARMING", "FISHING", "MINING",
            "WOODCUTTING", "ALCHEMISM", "ARMOURING", "COOKING", "JEWELING", "SCRIBING", "TAILORING", "WEAPONSMITHING",
            "WOODWORKING");

    /** A class's portrait from the character select screen ("SHAMAN" → icon/shaman), on its backdrop; false if missing. */
    static boolean classIcon(GuiGraphics g, String cls, int x, int y, int size) {
        String base = "wynn/gui/character_select/icon/" + cls.toLowerCase(java.util.Locale.ROOT);
        if (!has(base)) {
            return false;
        }
        draw(g, base + "_background", x, y, size, size);
        return draw(g, base, x, y, size, size);
    }

    /** A GUI-atlas sprite ("tooltip/legendary_frame"), nine-sliced by its own metadata; false if missing. */
    static boolean sprite(GuiGraphics g, String sprite, int x, int y, int w, int h) {
        if (!has("gui/sprites/" + sprite)) {
            return false;
        }
        g.blitSprite(RenderPipelines.GUI_TEXTURED, Identifier.withDefaultNamespace(sprite), x, y, w, h);
        return true;
    }

    /** Forget sizes (the pack may have changed since): called when the J menu opens. */
    static void clear() {
        SIZE.clear();
        TRIM.clear();
    }
}
