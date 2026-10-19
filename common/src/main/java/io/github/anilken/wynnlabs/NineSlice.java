package io.github.anilken.wynnlabs;

import io.github.anilken.wynnlabs.mixin.GuiGraphicsAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.gui.GuiMetadataSection;
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;

/**
 * The kit's nine-sliced sprites, drawn so a border keeps one width along its whole length.
 *
 * <p>The kit's sprites are drawn at 6 texels to a GUI px. With 3 screen pixels to a GUI px (game
 * scale 3, or the menu scale at 3) a screen pixel spans 2 texels, so every pixel centre falls
 * exactly on the line between two texels. Which one the nearest-texel lookup picks then comes down
 * to rounding, and that rounding differs between the two triangles of a quad: a card's 1.5px dark
 * outline came out 4 screen pixels wide down one part of an edge and 5 down the rest, the light
 * line inside it jumped with it, and the other side did the opposite.
 *
 * <p>Here every corner and edge samples a hair (1/64 GUI px) towards the sprite's middle, so a tie
 * always goes the same way, and the same way on both sides of the box. The nine pieces are their
 * own quads with whole-pixel corners; edges and the middle stretch, as the sprites' stretch_inner
 * asks. A sprite that is not nine-sliced that way goes to the game's own blitSprite.
 */
final class NineSlice {

    /** How far, in GUI px of the sprite, each border piece samples towards the middle. */
    private static final float NUDGE = 1f / 64f;

    private NineSlice() {}

    /** {@code id} (a GUI sprite) over x, y, w, h, tinted ARGB ({@code -1} for none). */
    static void draw(GuiGraphics g, Identifier id, int x, int y, int w, int h, int tint) {
        if (w <= 0 || h <= 0) {
            return;
        }
        TextureAtlasSprite sprite = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.GUI).getSprite(id);
        GuiSpriteScaling scaling = sprite.contents().getAdditionalMetadata(GuiMetadataSection.TYPE)
                .orElse(GuiMetadataSection.DEFAULT).scaling();
        if (!(scaling instanceof GuiSpriteScaling.NineSlice nine) || !nine.stretchInner()) {
            g.blitSprite(RenderPipelines.GUI_TEXTURED, id, x, y, w, h, tint);
            return;
        }
        if (LayoutCheck.on()) {
            LayoutCheck.record(g.pose(), x, y, w, h, id.getPath(), false);   // as the blitSprite hook would
        }
        var border = nine.border();
        // a box smaller than its borders gives each side at most half
        int left = Math.min(border.left(), w / 2);
        int right = Math.min(border.right(), w / 2);
        int top = Math.min(border.top(), h / 2);
        int bottom = Math.min(border.bottom(), h / 2);
        int[] xs = {x, x + left, x + w - right, x + w};
        int[] ys = {y, y + top, y + h - bottom, y + h};
        float sw = nine.width();
        float sh = nine.height();
        // where each column and row starts and ends in the sprite (in its GUI px), and which way it leans
        float[] us = {0, left, sw - right, sw};
        float[] vs = {0, top, sh - bottom, sh};
        float[] lean = {NUDGE, 0, -NUDGE};
        var quad = (GuiGraphicsAccessor) g;
        Identifier atlas = sprite.atlasLocation();
        for (int row = 0; row < 3; row++) {
            if (ys[row + 1] <= ys[row]) {
                continue;
            }
            float v0 = sprite.getV((vs[row] + lean[row]) / sh);
            float v1 = sprite.getV((vs[row + 1] + lean[row]) / sh);
            for (int col = 0; col < 3; col++) {
                if (xs[col + 1] <= xs[col]) {
                    continue;
                }
                float u0 = sprite.getU((us[col] + lean[col]) / sw);
                float u1 = sprite.getU((us[col + 1] + lean[col]) / sw);
                quad.wynnlabs$quad(RenderPipelines.GUI_TEXTURED, atlas, xs[col], xs[col + 1], ys[row], ys[row + 1],
                        u0, u1, v0, v1, tint);
            }
        }
    }
}
