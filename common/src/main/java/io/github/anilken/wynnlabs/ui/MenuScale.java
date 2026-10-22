package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.core.Settings;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * The "Menu scale" setting: the mod's screens drawn as if the game's GUI scale were 2, 3 or 4
 * while the game keeps its own (at 1920×1080 on Auto the game picks 4, and the menu can still look
 * as it does at 3). A screen works in a logical size (window pixels ÷ the menu scale), draws under
 * a pose scaled by game scale ÷ menu scale, and turns mouse positions into logical ones and
 * tooltip positions back. A scale that would leave less than 426×240 steps down until it fits.
 */
public final class MenuScale {

    /** While a scaled screen draws its tooltip: its logical size, which GuiGraphics reports as the GUI size. */
    private static int tooltipW;
    private static int tooltipH;

    private MenuScale() {}

    /** The chosen menu scale, 0 for "same as the game". */
    static int setting() {
        return Settings.number("menuScale", 0);
    }

    /** The scale the mod's screens draw at: the setting, stepped down until 426×240 fits; the game's when unset. */
    static int effective() {
        var w = Minecraft.getInstance().getWindow();
        int game = (int) Math.round(w.getGuiScale());
        int s = setting();
        if (s <= 0) {
            return game;
        }
        while (s > 1 && (w.getWidth() / s < 426 || w.getHeight() / s < 240)) {
            s--;
        }
        return s;
    }

    /** Logical pixels per game GUI pixel: game scale ÷ menu scale (1 when they agree). */
    public static double factor() {
        return (double) Minecraft.getInstance().getWindow().getGuiScale() / effective();
    }

    /**
     * Draws a screen at the menu scale: sets its logical size, scales the pose, picks the UI font
     * for that scale, and hands the body the logical mouse position.
     */
    static void render(Screen screen, GuiGraphics g, int mx, int my, Body body) {
        double f = factor();
        var w = Minecraft.getInstance().getWindow();
        screen.width = (int) Math.round(w.getGuiScaledWidth() * f);
        screen.height = (int) Math.round(w.getGuiScaledHeight() * f);
        if (f == 1) {
            body.draw((int) mx, my);
            return;
        }
        g.pose().pushMatrix();
        g.pose().scale((float) (1 / f), (float) (1 / f));
        UiFont.drawScale(effective());
        try {
            body.draw((int) Math.floor(mx * f), (int) Math.floor(my * f));
            // the tooltip the body asked for, now and at the menu's scale instead of the game's at
            // the end of the frame, placed inside the window by the screen's logical size
            var deferred = (io.github.anilken.wynnlabs.mixin.GuiGraphicsAccessor) g;
            Runnable tip = deferred.wynnlabs$deferred();
            if (tip != null) {
                deferred.wynnlabs$setDeferred(null);
                g.nextStratum();
                tooltipW = screen.width;
                tooltipH = screen.height;
                try {
                    tip.run();
                } finally {
                    tooltipW = 0;
                    tooltipH = 0;
                }
            }
        } finally {
            UiFont.drawScale(0);
            g.pose().popMatrix();
        }
    }

    public static int tooltipWidth() {
        return tooltipW;
    }

    public static int tooltipHeight() {
        return tooltipH;
    }

    /** A mouse event in logical pixels. */
    static MouseButtonEvent event(MouseButtonEvent e) {
        double f = factor();
        return f == 1 ? e : new MouseButtonEvent(e.x() * f, e.y() * f, e.buttonInfo());
    }

    /** A mouse coordinate in logical pixels. */
    static double at(double v) {
        return v * factor();
    }

    interface Body {
        void draw(int mx, int my);
    }
}
