package io.github.anilken.wynnlabs;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.Screen;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * {@code /wynnlabs shots} in a dev client on the recorded character: the whole sequence writes one
 * PNG per tab and view to screenshots/wynnlabs/&lt;time&gt;/, each the size of the menu window's box
 * (frame, shadow and close seal) plus a transparent margin, with transparent corners outside the
 * frame and an opaque middle; x3 and x4 draw it larger. Afterwards the menu language, the menu
 * scale, the menu open before (on its tab) and the HUD are as they were; Esc stops a run the same
 * way. Run alone with gradle :fabric:runClientGameTest -PpreviewOnly=shots.
 */
public class ShotsPreview implements FabricClientGameTest {

    private static final int GUI = 2;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String only = System.getProperty("wynnlabs.previewOnly");
        if (only != null && !only.equals("shots")) {
            return;
        }
        Path fixture = Fixtures.root().resolve(System.getProperty("wynnlabs.previewFixture", "shaman-44"));
        boolean debugWas = ctx.computeOnClient(mc -> Settings.on("debug"));
        int langWas = ctx.computeOnClient(mc -> Settings.number("langOverride", -1));
        int scaleWas = ctx.computeOnClient(mc -> Settings.number("menuScale", -1));
        try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
            world.getClientWorld().waitForChunksRender();
            ctx.getInput().resizeWindow(1920, 1080);
            ctx.runOnClient(mc -> {
                mc.options.guiScale().set(GUI);
                mc.resizeDisplay();
            });
            ctx.runOnClient(mc -> {
                FixtureState.load(fixture).install("tr");
                Wyn.clearDown();
                Settings.setNumber("langOverride", 2);      // the menu in Turkish: the run switches to English and back
                Settings.resetNumber("menuScale");          // the game's scale: x3 and x4 change it and put it back
            });
            // the menu open on the bank tab before the command: it comes back on that tab
            ctx.setScreen(RoadmapScreen::new);
            ctx.waitForScreen(RoadmapScreen.class);
            ctx.runOnClient(mc -> ((RoadmapScreen) mc.screen).shotSelect(RoadmapScreen.TAB_BANK, null));
            Screen before = ctx.computeOnClient(mc -> mc.screen);

            // Debug off: a hint and nothing else
            ctx.runOnClient(mc -> {
                Settings.set("debug", false);
                Shots.start("en all");
                check(!Shots.running(), "a run started with Debug off");
                Settings.set("debug", true);
                String[] bad = new String[1];
                check(Shots.parse("en nonsense", bad) == null && "nonsense".equals(bad[0]), "an unknown word was taken");
            });

            Path root = ctx.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("screenshots").resolve("wynnlabs"));
            Path dir = run(ctx, root, "en all hidename");
            List<String> expected = ctx.computeOnClient(mc -> {
                String[] names = new String[RoadmapScreen.tabCount()];
                for (int t = 0; t < names.length; t++) {
                    names[t] = RoadmapScreen.tabName(t);
                }
                boolean plan = Plans.current() != null;
                List<String> out = new ArrayList<>();
                for (ShotRun.Shot s : ShotRun.plan(RoadmapScreen.railOrder(), names, "all")) {
                    if (plan || !"plan".equals(s.state())) {
                        out.add(s.file("en", out.size() + 1));
                    }
                }
                return out;
            });
            Set<String> written = files(dir);
            check(written.equals(new TreeSet<>(expected)), "files " + written + ", expected " + expected);
            int[] size = expectedSize(GUI, GUI);
            for (String f : written) {
                int[] got = checkPicture(dir.resolve(f));
                // the crafting tab's saved-recipes ribbon reaches 1 px past the close seal
                int slack = f.contains("craft") ? GUI : 0;
                check(got[1] == size[1] && got[0] >= size[0] && got[0] <= size[0] + slack,
                        f + " is " + got[0] + "×" + got[1] + ", expected " + size[0] + "×" + size[1]);
            }
            restored(ctx, before);
            StringBuilder sizes = new StringBuilder("GUI " + GUI + ": " + size[0] + "×" + size[1]);

            // larger: the same window at menu scale 3 and 4 (1920 × 1080 holds 426 × 240 at both)
            for (int s : new int[] {3, 4}) {
                Path d = run(ctx, root, "en today x" + s);
                int[] got = checkPicture(d.resolve("en-01-today.png"));
                int[] want = expectedSize(GUI, s);
                check(got[0] == want[0] && got[1] == want[1], "x" + s + " is " + got[0] + "×" + got[1] + ", expected "
                        + want[0] + "×" + want[1]);
                sizes.append(", x").append(s).append(": ").append(got[0]).append("×").append(got[1]);
                restored(ctx, before);
            }

            // Esc in the middle: stopped, everything back the same way
            ctx.runOnClient(mc -> Shots.start("en today x4"));
            ctx.waitTicks(4);
            ctx.runOnClient(mc -> check(Settings.number("langOverride", 0) == 1, "the run did not switch to English"));
            ctx.runOnClient(mc -> check(Settings.number("menuScale", 0) == 4, "the run did not switch to x4"));
            ctx.runOnClient(mc -> Shots.escape());
            ctx.waitFor(mc -> !Shots.running(), 20 * 30);
            restored(ctx, before);
            System.out.println("[preview] shots: " + written.size() + " files in " + dir + "; sizes at 1920×1080 " + sizes);
        } catch (IOException | ReflectiveOperationException e) {
            throw new RuntimeException(e);
        } finally {
            ctx.runOnClient(mc -> {
                Settings.set("debug", debugWas);
                if (langWas == -1) {
                    Settings.resetNumber("langOverride");
                } else {
                    Settings.setNumber("langOverride", langWas);
                }
                if (scaleWas == -1) {
                    Settings.resetNumber("menuScale");
                } else {
                    Settings.setNumber("menuScale", scaleWas);
                }
            });
            FixtureState.uninstall();
        }
    }

    /** Runs the command to its end; its new folder. */
    private static Path run(ClientGameTestContext ctx, Path root, String options) throws IOException {
        Set<Path> old = dirs(root);
        ctx.runOnClient(mc -> Shots.start(options));
        check(ctx.computeOnClient(mc -> Shots.running()), "the run did not start: " + options);
        ctx.waitFor(mc -> !Shots.running(), 20 * 180);
        Set<Path> now = dirs(root);
        now.removeAll(old);
        check(now.size() == 1, "expected one new folder in " + root + ", found " + now);
        return now.iterator().next();
    }

    /**
     * The menu window's box at {@code menuScale} on this 1920 × 1080 window, as the picture should
     * be: the frame (the page ± 5) and its shadow (3 under it), the close seal (14 square, 9 above
     * the page and 9 past its right edge), in framebuffer pixels, plus the margin on each side.
     */
    private static int[] expectedSize(int gui, int menuScale) {
        int lw = 1920 / menuScale;
        int lh = 1080 / menuScale;
        MenuLayout l = MenuLayout.of(lw, lh);
        int left = l.x() - 5;
        int top = Math.min(l.y() - 5, Math.max(0, l.y() - 9));
        int right = Math.max(l.x() + l.w() + 5, Math.min(l.x() + l.w() + 9, lw));
        int bottom = l.y() + l.h() + 5 + 3;
        int[] px = ShotCrop.pixels(ShotCrop.toGui(left, top, right - left, bottom - top, (double) gui / menuScale), gui);
        return new int[] {px[2] + 2 * ShotCrop.MARGIN, px[3] + 2 * ShotCrop.MARGIN};
    }

    /** A picture's size; fails unless it is a PNG with transparent corners outside the frame and an opaque middle. */
    private static int[] checkPicture(Path f) throws IOException {
        try (InputStream in = Files.newInputStream(f); NativeImage img = NativeImage.read(NativeImage.Format.RGBA, in)) {
            int w = img.getWidth();
            int h = img.getHeight();
            int m = ShotCrop.MARGIN;
            // the margin's corners, and just inside the box at its top corners: the frame's rounded
            // corners and the room beside the seal show nothing of the game
            for (int[] p : new int[][] {{0, 0}, {w - 1, 0}, {0, h - 1}, {w - 1, h - 1}, {m + 1, m + 1}, {m, h - m - 1}}) {
                int a = img.getPixel(p[0], p[1]) >>> 24;
                check(a == 0, f.getFileName() + ": pixel " + p[0] + "," + p[1] + " has alpha " + a);
            }
            int a = img.getPixel(w / 2, h / 2) >>> 24;
            check(a == 255, f.getFileName() + ": the middle has alpha " + a);
            return new int[] {w, h};
        }
    }

    /** The language Turkish again, the menu scale unset, the same menu on the bank tab, the HUD and the render flags as before. */
    private static void restored(ClientGameTestContext ctx, Screen before) throws ReflectiveOperationException {
        Field tab = RoadmapScreen.class.getDeclaredField("tab");
        tab.setAccessible(true);
        ctx.runOnClient(mc -> {
            check(Settings.number("langOverride", 0) == 2, "the language was not put back");
            check(Settings.number("menuScale", -1) == -1, "the menu scale was not put back");
            check(mc.screen == before, "the menu open before did not come back: " + mc.screen);
            check(tab.getInt(mc.screen) == RoadmapScreen.TAB_BANK, "the menu came back on another tab");
            check(!mc.options.hideGui, "the HUD stayed hidden");
            check(!Shots.capturing(), "the render flags stayed on");
        });
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    private static Set<Path> dirs(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return new HashSet<>();
        }
        try (Stream<Path> s = Files.list(root)) {
            return new HashSet<>(s.toList());
        }
    }

    private static Set<String> files(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return new TreeSet<>(s.map(p -> p.getFileName().toString()).toList());
        }
    }
}
