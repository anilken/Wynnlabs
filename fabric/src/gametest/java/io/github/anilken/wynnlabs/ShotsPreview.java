package io.github.anilken.wynnlabs;

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
 * PNG per tab and view to screenshots/wynnlabs/&lt;time&gt;/, and afterwards the menu language,
 * the menu open before (on its tab) and the HUD are as they were; Esc stops a run the same way.
 * Run alone with gradle :fabric:runClientGameTest -PpreviewOnly=shots.
 */
public class ShotsPreview implements FabricClientGameTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String only = System.getProperty("wynnlabs.previewOnly");
        if (only != null && !only.equals("shots")) {
            return;
        }
        Path fixture = Fixtures.root().resolve(System.getProperty("wynnlabs.previewFixture", "shaman-44"));
        boolean debugWas = ctx.computeOnClient(mc -> Settings.on("debug"));
        int langWas = ctx.computeOnClient(mc -> Settings.number("langOverride", -1));
        try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
            world.getClientWorld().waitForChunksRender();
            ctx.getInput().resizeWindow(1280, 720);
            ctx.runOnClient(mc -> mc.options.guiScale().set(2));
            ctx.runOnClient(mc -> {
                FixtureState.load(fixture).install("tr");
                Wyn.clearDown();
                Settings.setNumber("langOverride", 2);      // the menu in Turkish: the run switches to English and back
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
            Set<Path> old = dirs(root);
            ctx.runOnClient(mc -> Shots.start("en all hidename"));
            check(ctx.computeOnClient(mc -> Shots.running()), "the run did not start");
            ctx.waitFor(mc -> !Shots.running(), 20 * 180);
            Path dir = newest(root, old);
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
            for (String f : written) {
                try (InputStream in = Files.newInputStream(dir.resolve(f))) {
                    byte[] head = in.readNBytes(PNG.length);
                    check(java.util.Arrays.equals(head, PNG), f + " is not a PNG");
                }
                check(Files.size(dir.resolve(f)) > 10_000, f + " is nearly empty");
            }
            restored(ctx, before);

            // Esc in the middle: stopped, everything back the same way
            ctx.runOnClient(mc -> Shots.start("en today"));
            ctx.waitTicks(4);
            ctx.runOnClient(mc -> check(Settings.number("langOverride", 0) == 1, "the run did not switch to English"));
            ctx.runOnClient(mc -> Shots.escape());
            ctx.waitFor(mc -> !Shots.running(), 20 * 30);
            restored(ctx, before);
            System.out.println("[preview] shots: " + written.size() + " files in " + dir);
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
            });
            FixtureState.uninstall();
        }
    }

    /** The language Turkish again, the same menu on the bank tab, the HUD and the render flags as before. */
    private static void restored(ClientGameTestContext ctx, Screen before) throws ReflectiveOperationException {
        Field tab = RoadmapScreen.class.getDeclaredField("tab");
        tab.setAccessible(true);
        ctx.runOnClient(mc -> {
            check(Settings.number("langOverride", 0) == 2, "the language was not put back");
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
            return Set.of();
        }
        try (Stream<Path> s = Files.list(root)) {
            return new HashSet<>(s.toList());
        }
    }

    /** The run's folder: the one that was not there before. */
    private static Path newest(Path root, Set<Path> old) throws IOException {
        Set<Path> now = dirs(root);
        now.removeAll(old);
        check(now.size() == 1, "expected one new folder in " + root + ", found " + now);
        return now.iterator().next();
    }

    private static Set<String> files(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return new TreeSet<>(s.map(p -> p.getFileName().toString()).toList());
        }
    }
}
