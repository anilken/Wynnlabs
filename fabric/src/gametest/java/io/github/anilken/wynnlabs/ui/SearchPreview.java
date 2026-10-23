package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.game.Fixtures;
import io.github.anilken.wynnlabs.search.ItemIndex;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * The search tab in the J menu preview: the item index read from a game folder's Wynntils cache
 * ({@code -PpreviewWynntils=<folder>}, read only) or, without one, from the trimmed test copies;
 * then shots of a search for an ingredient and for a piece of gear, its folding sections open,
 * GUI scales 2 and 4, and the empty box.
 */
final class SearchPreview {

    private SearchPreview() {}

    /** Starts reading the index before the menu does (the index is read once a session). */
    static void loadIndex() throws IOException {
        String dir = System.getProperty("wynnlabs.previewWynntils");
        Path game;
        if (dir != null) {
            game = Path.of(dir);
        } else {
            game = Files.createTempDirectory("wynnlabs-search-preview");
            Path cache = Files.createDirectories(game.resolve("wynntils/cache"));
            try (Stream<Path> files = Files.list(Fixtures.root().getParent().resolve("search/cache"))) {
                for (Path f : files.toList()) {
                    Files.copy(f, cache.resolve(f.getFileName().toString()));
                }
            }
        }
        ItemIndex.startLoading(game, null);
    }

    static void shots(ClientGameTestContext ctx, Path out) {
        ctx.waitFor(mc -> ItemIndex.get().missing() == null || !"loading".equals(ItemIndex.get().missing()), 20 * 60);
        ctx.getInput().setCursorPos(2, 2);
        ctx.runOnClient(mc -> ((RoadmapScreen) mc.screen).openTab(RoadmapScreen.TAB_SEARCH));
        ctx.waitTicks(5);
        shot(ctx, out, "search-tab-empty");
        search(ctx, "lucky rab");
        shot(ctx, out, "search-tab-lucky");
        search(ctx, "papyrus");
        shot(ctx, out, "search-tab-papyrus");
        ctx.runOnClient(mc -> {
            SearchCardSections s = ((RoadmapScreen) mc.screen).search.card.sections;
            for (String f : new String[] {"openOwned", "openStats", "openRelated"}) {
                flag(s, f);
            }
        });
        ctx.waitTicks(5);
        shot(ctx, out, "search-tab-papyrus-sections");
        for (int scale : new int[] {2, 4}) {
            ctx.runOnClient(mc -> {
                mc.options.guiScale().set(scale);
                mc.resizeDisplay();
            });
            ctx.waitTicks(5);
            shot(ctx, out, "search-tab-gui" + scale);
        }
        ctx.runOnClient(mc -> {
            mc.options.guiScale().set(3);
            mc.resizeDisplay();
        });
        ctx.waitTicks(3);
    }

    private static void search(ClientGameTestContext ctx, String text) {
        ctx.runOnClient(mc -> {
            SearchTab t = ((RoadmapScreen) mc.screen).search;
            t.box.setValue(text);
            t.selName = null;
        });
        ctx.waitTicks(20);                          // the worker answers, the card picks its sources
    }

    private static void flag(Object o, String field) {
        try {
            Field f = o.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.setBoolean(o, true);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void shot(ClientGameTestContext ctx, Path out, String name) {
        ctx.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(out));
    }
}
