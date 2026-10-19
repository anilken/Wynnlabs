package io.github.anilken.wynnlabs;

import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix3x2f;

/**
 * Where the developer tools of a dev build (common/src/dev: the {@code /wynnlabs shots} screenshot
 * tool) hook into the menu. A release jar has no dev classes: {@link #ON} is then a constant
 * {@code false}, every hook site is written {@code if (DevHooks.ON) …}, and the JIT drops them, so
 * the hooks cost nothing per frame.
 *
 * <p>A dev build has {@code DevTools} in this package; it is found once, when this class loads
 * (the first time {@link WynnLabs#init} asks), never in a frame.
 */
public final class DevHooks {

    private DevHooks() {}

    /** The developer tools' side of the hooks. */
    interface Hooks {
        /** Once at start-up: their commands and tick listeners. */
        void register(Platform platform);

        /** Whether a tool drives the menu now (a screenshot run). */
        boolean driving();

        /** The mouse position the driven menu draws with (GUI pixels). */
        int mouseX();

        int mouseY();

        /** The driven menu starts a frame. */
        void beginFrame();

        /** The colour drawn behind the driven menu instead of the world. */
        int backdrop();

        /** Esc in the driven menu. */
        void escape();

        /** A box of the mod's window drawn under {@code pose} (frame and shadow, close seal, side panel). */
        void frame(Matrix3x2f pose, int x, int y, int w, int h);

        /** A named panel or element drawn under {@code pose} (the gear panel, a quest row …). */
        void part(String name, Matrix3x2f pose, int x, int y, int w, int h);

        /** A modal's dim over the menu window; false when the menu draws its usual one. */
        boolean dim(GuiGraphics g, int x, int y, int w, int h, int argb);
    }

    private static final Hooks HOOKS = load();

    /** True only in a dev build: the developer tools are in the jar. */
    public static final boolean ON = HOOKS != null;

    private static Hooks load() {
        try {
            Class<?> c = Class.forName("io.github.anilken.wynnlabs.DevTools", true, DevHooks.class.getClassLoader());
            return (Hooks) c.getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            return null;                                    // a release jar
        } catch (ReflectiveOperationException | LinkageError e) {
            System.out.println("[WynnLabs] developer tools unavailable: " + e);
            return null;
        }
    }

    static void register(Platform platform) {
        if (ON) {
            HOOKS.register(platform);
        }
    }

    static boolean driving() {
        return ON && HOOKS.driving();
    }

    static int mouseX() {
        return ON ? HOOKS.mouseX() : 0;
    }

    static int mouseY() {
        return ON ? HOOKS.mouseY() : 0;
    }

    static void beginFrame() {
        if (ON) {
            HOOKS.beginFrame();
        }
    }

    static int backdrop() {
        return ON ? HOOKS.backdrop() : 0;
    }

    static void escape() {
        if (ON) {
            HOOKS.escape();
        }
    }

    static void frame(Matrix3x2f pose, int x, int y, int w, int h) {
        if (ON) {
            HOOKS.frame(pose, x, y, w, h);
        }
    }

    static void part(String name, Matrix3x2f pose, int x, int y, int w, int h) {
        if (ON) {
            HOOKS.part(name, pose, x, y, w, h);
        }
    }

    static boolean dim(GuiGraphics g, int x, int y, int w, int h, int argb) {
        return ON && HOOKS.dim(g, x, y, w, h, argb);
    }
}
