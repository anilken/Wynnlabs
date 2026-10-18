package io.github.anilken.wynnlabs;

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
final class WynnTex {

    private static final Map<String, int[]> SIZE = new ConcurrentHashMap<>();

    private WynnTex() {}

    /** "wynn/gui/button/plus" → minecraft:textures/wynn/gui/button/plus.png */
    static Identifier id(String path) {
        return Identifier.withDefaultNamespace("textures/" + path + ".png");
    }

    /** Width and height of a loaded texture; {0, 0} when the pack does not have it. */
    static int[] size(String path) {
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
    static boolean draw(GuiGraphics g, String path, int x, int y, int w, int h) {
        int[] s = size(path);
        if (s[0] == 0) {
            return false;
        }
        int frame = s[1] > s[0] && s[1] % s[0] == 0 ? s[0] : s[1];
        g.blit(RenderPipelines.GUI_TEXTURED, id(path), x, y, 0, 0, w, h, s[0], frame, s[0], s[1]);
        return true;
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
    }
}
