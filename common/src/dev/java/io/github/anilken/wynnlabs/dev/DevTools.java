package io.github.anilken.wynnlabs.dev;

import io.github.anilken.wynnlabs.core.DevHooks;
import io.github.anilken.wynnlabs.core.Perf;
import io.github.anilken.wynnlabs.core.Platform;
import io.github.anilken.wynnlabs.ui.NineSlice;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;

/**
 * Dev builds only: the developer tools behind {@link DevHooks}. A release jar has neither this nor
 * anything it uses (common/src/dev; checkDevTools proves it).
 */
final class DevTools implements DevHooks.Hooks {

    private static final Identifier WINDOW = Identifier.fromNamespaceAndPath("wynnlabs", "kit/window");

    @Override
    public void register(Platform platform) {
        // /wynnlabs shots: pictures of the mod's own menu for its pages (Debug on only)
        platform.registerClientCommand(Shots::command);
        platform.onEndTick(Perf.tick("tick.Shots", Shots::onTick));
    }

    @Override
    public boolean driving() {
        return Shots.capturing();
    }

    @Override
    public int mouseX() {
        return Shots.mouseX();
    }

    @Override
    public int mouseY() {
        return Shots.mouseY();
    }

    @Override
    public void beginFrame() {
        Shots.beginFrame();
    }

    @Override
    public int backdrop() {
        return Shots.backdrop();
    }

    @Override
    public void escape() {
        Shots.escape();
    }

    @Override
    public void frame(Matrix3x2f pose, int x, int y, int w, int h) {
        Shots.frame(pose, x, y, w, h);
    }

    @Override
    public void part(String name, Matrix3x2f pose, int x, int y, int w, int h) {
        Shots.part(name, pose, x, y, w, h);
    }

    /** While a run captures: a modal's dim in the window frame's own shape, so its rounded corners stay clear. */
    @Override
    public boolean dim(GuiGraphics g, int x, int y, int w, int h, int argb) {
        if (!Shots.capturing()) {
            return false;
        }
        NineSlice.draw(g, WINDOW, x, y, w, h, argb);
        return true;
    }
}
