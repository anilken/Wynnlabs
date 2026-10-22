package io.github.anilken.wynnlabs;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.List;

/**
 * One tab of the menu ({@link RoadmapScreen}): it draws its page and takes the clicks, wheel and
 * keys meant for it. The screen keeps the window, the rail and what the tabs share (the hover
 * areas, the flash line, the road map); a tab keeps its own view state. Made with each screen, so
 * the view starts afresh every time the menu opens.
 */
abstract class MenuTab extends MenuPaint {

    MenuTab(RoadmapScreen menu) {
        super(menu);
    }

    /** The text boxes this tab types into, made again on each {@code init} (a new font, a resize). */
    List<EditBox> init() {
        return List.of();
    }

    /** The start of every frame, whichever tab shows: last frame's click areas forgotten. */
    void beginFrame() {
    }

    /** The page, in the area under its title. */
    abstract void draw(GuiGraphics g, int x, int y, int w, int h, int mx, int my, float pt);

    /**
     * Something on the right of the page title (left of {@code right}, at most {@code room} wide);
     * returns the width it took, 0 for nothing.
     */
    int titleRight(GuiGraphics g, int right, int y, int room, int mx, int my) {
        return 0;
    }

    /** A click on the page (logical pixels); true when the tab took it. */
    boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return false;
    }

    /**
     * Whether a click the tab did not take goes on to the menu's shared click areas (copy, track,
     * the compass, the {@code #q} actions); false sends it straight to the screen's widgets.
     */
    boolean sharedClicks() {
        return true;
    }

    /** The mouse wheel over the page (logical pixels, {@code v} up positive). */
    void mouseScrolled(double x, double y, double v) {
    }

    /** A key press the menu did not take first; true when the tab took it. */
    boolean keyPressed(int key) {
        return false;
    }
}
