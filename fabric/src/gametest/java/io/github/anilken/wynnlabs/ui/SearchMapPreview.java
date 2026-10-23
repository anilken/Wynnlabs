package io.github.anilken.wynnlabs.ui;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The search tab's map view on its own, over the map tiles Wynntils downloaded on this machine
 * (found through .local-mods-path, or -Dwynnlabs.mapCache): build/preview/search-map.png around
 * Detlas's east with a few areas, a point and the player, and search-map-none.png without tiles.
 * Elsewhere, with no tiles, the first shot is skipped. Run with -PpreviewOnly=searchmap.
 */
public class SearchMapPreview implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String only = System.getProperty("wynnlabs.previewOnly");
        if (only != null && !only.equals("searchmap")) {
            return;
        }
        Path out = Path.of(System.getProperty("wynnlabs.preview", "preview"));
        ctx.getInput().resizeWindow(1920, 1080);
        ctx.runOnClient(mc -> mc.options.guiScale().set(3));
        Path cache = cacheDir();
        if (cache != null) {
            shoot(ctx, out, cache, "search-map");
        } else {
            System.out.println("[search-map preview] no Wynntils map tiles on this machine: skipped");
        }
        shoot(ctx, out, null, "search-map-none");
        if (cache != null) {
            for (int scale : new int[] {2, 4}) {             // the same map at the other GUI scales
                ctx.runOnClient(mc -> mc.options.guiScale().set(scale));
                shoot(ctx, out, cache, "search-map-gui" + scale);
            }
            ctx.runOnClient(mc -> mc.options.guiScale().set(3));
        }
        ctx.setScreen(() -> null);
    }

    private static void shoot(ClientGameTestContext ctx, Path out, Path cache, String name) {
        Holder h = new Holder();
        ctx.setScreen(() -> h.screen = new MapScreen(cache));
        ctx.waitFor(mc -> h.screen.map.ready(), 20 * 30);
        ctx.waitTicks(2);
        ctx.getInput().setCursorPos(2, 2);
        ctx.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(out));
        ctx.runOnClient(mc -> System.out.println("[search-map preview] " + name + ": " + h.screen.map.texturesHeld()
                + " textures, zoom " + h.screen.map.camera().zoom()));
    }

    private static final class Holder {
        MapScreen screen;
    }

    /** The map in a parchment card in the middle of the screen, as the search card will hold it. */
    private static final class MapScreen extends Screen {

        final SearchMapView map;

        MapScreen(Path cache) {
            super(Component.literal("search map"));
            map = new SearchMapView(cache);
            int area = 0xFFA03020;      // a mob area, the sword badge's red
            int merchant = 0xFF2A8AC0;
            map.markers(List.of(
                    new SearchMapView.Marker(1236, -1431, 40, area, "Obelisk Of Inversion", true),
                    new SearchMapView.Marker(1330, -1520, 28, area, "Obelisk Of Inversion", false),
                    new SearchMapView.Marker(1150, -1350, 18, 0xFF3F9A4F, "Lv 73-81 mobs", false),
                    new SearchMapView.Marker(1300, -1380, 0, merchant, "Merchant", false)));
            map.player(1120, -1480);
            map.noMapText(Component.literal("Map image not available"));
            map.fit(300, 180, true);
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float pt) {
            int w = 300;
            int h = 180;
            int x = (width - w) / 2;
            int y = (height - h) / 2;
            g.fill(0, 0, width, height, 0xFF3B2A1D);
            Kit.paper(g, x - 16, y - 16, w + 32, h + 32);
            map.draw(g, font, x, y, w, h, mx, my);
        }

        @Override
        public void removed() {
            map.close();
        }
    }

    /** Wynntils' cache folder: -Dwynnlabs.mapCache, or the game next to .local-mods-path's mods folder. */
    private static Path cacheDir() {
        String prop = System.getProperty("wynnlabs.mapCache");
        if (prop != null) {
            return valid(Path.of(prop));
        }
        for (Path d = Path.of("").toAbsolutePath(); d != null; d = d.getParent()) {
            Path f = d.resolve(".local-mods-path");
            if (Files.isRegularFile(f)) {
                try {
                    String mods = Files.readString(f, StandardCharsets.UTF_8).trim();
                    if (mods.matches("^/[a-zA-Z]/.*")) {         // a Git Bash path: /c/Users/... → C:/Users/...
                        mods = mods.charAt(1) + ":" + mods.substring(2);
                    }
                    return valid(Path.of(mods).resolveSibling("wynntils").resolve("cache"));
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Path valid(Path cache) {
        return Files.isRegularFile(cache.resolve("dataStaticMaps")) ? cache : null;
    }
}
