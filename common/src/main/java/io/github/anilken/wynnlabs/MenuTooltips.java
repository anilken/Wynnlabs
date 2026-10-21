package io.github.anilken.wynnlabs;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import org.joml.Vector2i;
import org.joml.Vector2ic;

/**
 * The "Tooltip size in menus" setting: while a menu is open (inventory, bank, a shop, any
 * container screen) tooltips are drawn at the chosen size, so the advice lines under them still
 * fit on screen. Elsewhere, and at 100%, nothing changes.
 *
 * <p>Hooked through Wynntils' tooltip positioning ({@code WynntilsEvents.Tooltips}): Wynntils
 * asks for a positioner before it places a tooltip, and its own fitting (universal scale, fit to
 * screen) stands aside when one is set. So ours replaces that fitting in menus rather than
 * shrinking on top of it: the smaller of the two scales is drawn, and smaller still only when the
 * tooltip would not fit the window's height. The positioner scales the pose around the tooltip's
 * corner (where the game puts it beside the mouse) and places the scaled box inside the window;
 * {@link #end} undoes the pose once the tooltip is drawn.
 */
final class MenuTooltips {

    /** The setting's key in {@link Settings#STEPS}. */
    static final String KEY = "menuTooltipScale";
    /** The gap between the mouse and the tooltip, as the game draws it. */
    static final int OFFSET = 12;
    /** The frame around the text (3) and a pixel of air: kept inside the window too. */
    static final int EDGE = 4;
    /** Never smaller than this, whatever the tooltip's length. */
    static final float MIN = 0.25f;

    private static final Positioner POSITIONER = new Positioner();

    private MenuTooltips() {}

    /** The size chosen for menus (0.7 … 1), or 1 when no menu is open. */
    static float wanted() {
        int pct = Settings.stepValue(KEY);
        if (pct >= 100 || pct <= 0 || !(Minecraft.getInstance().screen instanceof AbstractContainerScreen<?>)) {
            return 1f;
        }
        return pct / 100f;
    }

    /**
     * The positioner for the tooltip about to be placed, or null to leave it to Wynntils and the
     * game. {@code wynntils}: Wynntils' universal tooltip scale (1 when its fitting is off).
     */
    static ClientTooltipPositioner positioner(GuiGraphics g, float wynntils) {
        float s = wanted();
        if (s >= 1f) {
            return null;
        }
        POSITIONER.begin(g, s, wynntils > 0 ? wynntils : 1f);
        return POSITIONER;
    }

    /** The tooltip is drawn: its pose back as it was. */
    static void end(GuiGraphics g) {
        POSITIONER.end(g);
    }

    /**
     * The scale drawn: the smaller of ours and Wynntils' (never both multiplied), and smaller
     * still when the tooltip, frame included, would be taller than the window.
     */
    static float scale(float ours, float wynntils, int tooltipH, int screenH) {
        float t = Math.min(ours, wynntils);
        int box = tooltipH + 2 * EDGE;
        if (box * t > screenH) {
            t = screenH / (float) box;
        }
        return Math.max(MIN, t);
    }

    /**
     * Where the scaled tooltip's text starts, in GUI pixels, into {@code out}: beside the mouse as
     * the game places it, flipped to the left at the window's right edge, and moved up or down so
     * the scaled box sits inside the window.
     */
    static void place(int screenW, int screenH, int mouseX, int mouseY, int w, int h, float t, float[] out) {
        float sw = w * t;
        float sh = h * t;
        float edge = EDGE * t;
        float x = mouseX + OFFSET;
        if (x + sw + edge > screenW) {
            x = Math.max(mouseX - OFFSET - sw, edge);
        }
        float y = mouseY - OFFSET;
        if (y + sh + edge > screenH) {
            y = screenH - sh - edge;
        }
        out[0] = x;
        out[1] = Math.max(y, edge);
    }

    /** {@code v} GUI pixels moved onto the nearest window pixel, so the text starts on the pixel grid. */
    static float snap(float v, double guiScale) {
        return guiScale > 0 ? (float) (Math.round(v * guiScale) / guiScale) : v;
    }

    /**
     * One positioner for every tooltip (render thread only, nothing allocated per frame): it
     * pushes the scaled pose when the game asks where to draw, and hands back the pose's origin.
     */
    private static final class Positioner implements ClientTooltipPositioner {

        private static final Vector2ic ORIGIN = new Vector2i(0, 0);
        private final float[] at = new float[2];
        private GuiGraphics g;
        private float ours;
        private float wynntils;
        private boolean pushed;

        void begin(GuiGraphics g, float ours, float wynntils) {
            end(this.g);                            // a tooltip whose end never came: its pose first
            this.g = g;
            this.ours = ours;
            this.wynntils = wynntils;
        }

        @Override
        public Vector2ic positionTooltip(int screenW, int screenH, int mouseX, int mouseY, int w, int h) {
            float t = scale(ours, wynntils, h, screenH);
            place(screenW, screenH, mouseX, mouseY, w, h, t, at);
            double gs = Minecraft.getInstance().getWindow().getGuiScale();
            var pose = g.pose();
            pose.pushMatrix();
            pose.translate(snap(at[0], gs), snap(at[1], gs));
            pose.scale(t, t);
            pushed = true;
            return ORIGIN;
        }

        void end(GuiGraphics g) {
            if (pushed && g != null && g == this.g) {
                g.pose().popMatrix();
            }
            pushed = false;
            this.g = null;
        }
    }
}
