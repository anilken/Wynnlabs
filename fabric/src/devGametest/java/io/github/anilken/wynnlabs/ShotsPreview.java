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
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Dev builds only: {@code /wynnlabs shots} in a dev client on the recorded character. The whole run
 * writes one PNG per view it has something to show for (numbered in a row), close-ups under close/
 * and an index.html. Every picture is the window's box (frame, shadow, close seal) with a
 * transparent margin, transparent corners outside the frame and an opaque middle; a tooltip shot
 * that reaches past the frame takes the tooltip in; a close-up is smaller than its picture. x3 and
 * x4 draw larger. Afterwards the menu language, the menu scale, the menu open before (on its tab)
 * and the HUD are as they were; Esc stops a run the same way. Run alone with
 * gradle :fabric:runClientGameTest -PpreviewOnly=shots.
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
            Field tab = RoadmapScreen.class.getDeclaredField("tab");
            tab.setAccessible(true);
            ctx.runOnClient(mc -> tab.setInt(mc.screen, RoadmapScreen.TAB_BANK));
            Screen before = ctx.computeOnClient(mc -> mc.screen);

            // Debug off: a hint and nothing else
            ctx.runOnClient(mc -> {
                Settings.set("debug", false);
                Shots.start("en all");
                check(!Shots.running(), "a run started with Debug off");
                Settings.set("debug", true);
                String[] bad = new String[1];
                check(Shots.parse("en nonsense", bad) == null && "nonsense".equals(bad[0]), "an unknown word was taken");
                check(new Shots.Options(2, "endgame", 4, true).equals(Shots.parse("tr x4 endgame hidename", bad)), "the options");
            });

            Path root = ctx.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("screenshots").resolve("wynnlabs"));
            Path dir = run(ctx, root, "en all hidename");
            int count = Shots.lastCount;
            int skipped = Shots.lastSkipped;
            long millis = Shots.lastMillis;
            Set<String> written = files(dir);
            Set<String> closes = files(dir.resolve("close"));
            int planned = ctx.computeOnClient(mc -> ShotRun.plan(ShotDriver.railOrder(), ShotDriver.tabNames(), "all").size());
            check(written.size() == count && count + skipped == planned,
                    written.size() + " files, " + count + " taken, " + skipped + " skipped of " + planned);
            check(count >= 12 + 8 + 4, "too few views: " + written);
            // numbered in a row
            int n = 0;
            for (String f : written) {
                check(f.startsWith(String.format(java.util.Locale.ROOT, "en-%02d-", ++n)), "out of order: " + f);
            }
            check(Files.isRegularFile(dir.resolve("index.html")), "no index.html");
            String index = Files.readString(dir.resolve("index.html"));
            for (String f : written) {
                check(index.contains("src=\"" + f + "\""), "index.html misses " + f);
            }
            int[] size = expectedSize(GUI, GUI);
            int tips = 0;
            int overflowing = 0;
            for (String f : written) {
                int[] got = checkPicture(dir.resolve(f));
                if (f.endsWith("-tip.png")) {
                    tips++;
                    int[][] r = Shots.RECTS.get(f);
                    check(r != null, "no crop recorded for " + f);
                    if (r[0][2] > r[1][2] || r[0][3] > r[1][3]) {
                        overflowing++;
                        check(tooltipOutside(dir.resolve(f), r[0], r[1]), f + ": nothing drawn past the frame");
                    }
                    check(got[0] >= size[0] - GUI && got[1] >= size[1], f + " is smaller than the window");
                } else {
                    // the crafting tab's saved-recipes ribbon reaches 1 px past the close seal
                    int slack = f.contains("craft") ? GUI : 0;
                    check(got[1] == size[1] && got[0] >= size[0] && got[0] <= size[0] + slack,
                            f + " is " + got[0] + "×" + got[1] + ", expected " + size[0] + "×" + size[1]);
                }
            }
            check(tips >= 3, "too few tooltip shots: " + tips);
            for (String c : closes) {
                String main = c.substring(0, c.indexOf("-close-")) + ".png";
                check(written.contains(main), c + " has no picture beside it");
                int[] cs = pngSize(dir.resolve("close").resolve(c));
                int[] ms = pngSize(dir.resolve(main));
                check(cs[0] < ms[0] && cs[1] < ms[1], c + " is not smaller than " + main);
            }
            check(closes.stream().anyMatch(c -> c.contains("-close-gear")), "no gear panel close-up: " + closes);
            restored(ctx, before, tab);
            StringBuilder sizes = new StringBuilder("GUI " + GUI + ": " + size[0] + "×" + size[1]);

            // larger: the same window at menu scale 3 and 4 (1920 × 1080 holds 426 × 240 at both)
            for (int s : new int[] {3, 4}) {
                Path d = run(ctx, root, "en today x" + s);
                int[] got = checkPicture(d.resolve("en-01-today-main.png"));
                int[] want = expectedSize(GUI, s);
                check(got[0] == want[0] && got[1] == want[1], "x" + s + " is " + got[0] + "×" + got[1] + ", expected "
                        + want[0] + "×" + want[1]);
                sizes.append(", x").append(s).append(": ").append(got[0]).append("×").append(got[1]);
                restored(ctx, before, tab);
            }

            // Esc in the middle: stopped, everything back the same way
            ctx.runOnClient(mc -> Shots.start("en home x4"));
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> check(Settings.number("langOverride", 0) == 1, "the run did not switch to English"));
            ctx.runOnClient(mc -> check(Settings.number("menuScale", 0) == 4, "the run did not switch to x4"));
            ctx.runOnClient(mc -> Shots.escape());
            ctx.waitFor(mc -> !Shots.running(), 20 * 30);
            restored(ctx, before, tab);
            System.out.println("[preview] shots: " + count + " pictures (" + skipped + " views skipped, " + tips + " tooltips, "
                    + overflowing + " past the frame), " + closes.size() + " close-ups in " + millis / 1000.0 + " s, " + dir
                    + "; sizes at 1920×1080 " + sizes);
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
        ctx.waitFor(mc -> !Shots.running(), 20 * 600);
        Set<Path> now = dirs(root);
        now.removeAll(old);
        check(now.size() == 1, "expected one new folder in " + root + ", found " + now);
        return now.iterator().next();
    }

    /**
     * The menu window's box at {@code menuScale} on this 1920 × 1080 window, as a picture without a
     * tooltip past it should be: the frame (the page ± 5) and its shadow (3 under it), the close seal
     * (14 square, 9 above the page and 9 past its right edge), in framebuffer pixels, plus the margin.
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

    /** A picture's size; fails unless it is a PNG with transparent corners and an opaque window middle. */
    private static int[] checkPicture(Path f) throws IOException {
        try (InputStream in = Files.newInputStream(f); NativeImage img = NativeImage.read(NativeImage.Format.RGBA, in)) {
            int w = img.getWidth();
            int h = img.getHeight();
            for (int[] p : new int[][] {{0, 0}, {w - 1, 0}, {0, h - 1}, {w - 1, h - 1}}) {
                int a = img.getPixel(p[0], p[1]) >>> 24;
                check(a == 0, f.getFileName() + ": corner " + p[0] + "," + p[1] + " has alpha " + a);
            }
            int[][] r = Shots.RECTS.get(f.getFileName().toString());
            if (r != null) {
                // the window's own box inside the picture: transparent at its rounded top-left, opaque in its middle
                int m = ShotCrop.MARGIN;
                int wx = r[1][0] - r[0][0] + m;
                int wy = r[1][1] - r[0][1] + m;
                int a = img.getPixel(wx + 1, wy + 1) >>> 24;
                check(a == 0, f.getFileName() + ": the window's top-left corner has alpha " + a);
                a = img.getPixel(wx + r[1][2] / 2, wy + r[1][3] / 2) >>> 24;
                check(a == 255, f.getFileName() + ": the window's middle has alpha " + a);
            }
            return new int[] {w, h};
        }
    }

    /** Whether the picture has drawn pixels outside the window's own box (a tooltip past the frame). */
    private static boolean tooltipOutside(Path f, int[] pic, int[] window) throws IOException {
        int m = ShotCrop.MARGIN;
        int wx0 = window[0] - pic[0] + m;
        int wy0 = window[1] - pic[1] + m;
        int wx1 = wx0 + window[2];
        int wy1 = wy0 + window[3];
        try (InputStream in = Files.newInputStream(f); NativeImage img = NativeImage.read(NativeImage.Format.RGBA, in)) {
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    boolean inside = x >= wx0 && x < wx1 && y >= wy0 && y < wy1;
                    if (!inside && (img.getPixel(x, y) >>> 24) > 128) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static int[] pngSize(Path f) throws IOException {
        try (InputStream in = Files.newInputStream(f); NativeImage img = NativeImage.read(NativeImage.Format.RGBA, in)) {
            return new int[] {img.getWidth(), img.getHeight()};
        }
    }

    /** The language Turkish again, the menu scale unset, the same menu on the bank tab, the HUD and the render flags as before. */
    private static void restored(ClientGameTestContext ctx, Screen before, Field tab) throws IllegalAccessException {
        ctx.runOnClient(mc -> {
            check(Settings.number("langOverride", 0) == 2, "the language was not put back");
            check(Settings.number("menuScale", -1) == -1, "the menu scale was not put back");
            check(mc.screen == before, "the menu open before did not come back: " + mc.screen);
            check(tab.getInt(mc.screen) == RoadmapScreen.TAB_BANK, "the menu came back on another tab");
            check(!mc.options.hideGui, "the HUD stayed hidden");
            check(!Shots.capturing(), "the render flags stayed on");
            check(!BuildWindow.INSTANCE.isOpen(), "the build window stayed open");
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

    /** The PNG files in a folder (none when it is missing). */
    private static Set<String> files(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return new TreeSet<>();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return new TreeSet<>(s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".png")).toList());
        }
    }
}
