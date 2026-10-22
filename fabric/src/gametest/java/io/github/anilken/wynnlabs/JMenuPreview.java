package io.github.anilken.wynnlabs;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The J menu without the game: a dev client loads the recorded Shaman (src/test/resources/fixtures),
 * opens the menu in a test world and saves a screenshot of the now tab and every milestone tab to
 * build/preview. Run with gradle runClientGameTest.
 */
public class JMenuPreview implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String only = System.getProperty("wynnlabs.previewOnly");
        if (only != null && !only.equals("jmenu")) {
            return;
        }
        Path out = Path.of(System.getProperty("wynnlabs.preview", "preview"));
        Path fixture = Fixtures.root().resolve(System.getProperty("wynnlabs.previewFixture", "shaman-44"));
        try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
            world.getClientWorld().waitForChunksRender();
            Files.createDirectories(out);
            // a 1080p window at GUI scale 3
            ctx.getInput().resizeWindow(1920, 1080);
            ctx.runOnClient(mc -> mc.options.guiScale().set(3));
            ctx.runOnClient(mc -> {
                FixtureState.load(fixture).install(System.getProperty("wynnlabs.previewLang", "tr"));
                Wyn.clearDown();                // marked down before the recording was in
            });
            ctx.setScreen(RoadmapScreen::new);
            ctx.waitForScreen(RoadmapScreen.class);
            ctx.getInput().setCursorPos(2, 2);      // the window's corner: no tooltip over the menu
            // nothing is built on its own: the gear panel offers to; the button builds it, with
            // its progress inside the panel
            ctx.waitTicks(2);
            shot(ctx, out, "jmenu-none");
            ctx.runOnClient(mc -> Roadmap.forceRefresh());
            ctx.waitTicks(2);
            shot(ctx, out, "jmenu-loading");
            ctx.waitFor(mc -> !Roadmap.cached().isEmpty() && !Roadmap.isBuilding(), 20 * 60);
            ctx.waitTicks(20);
            // a setting changed by a click: the old map stays up while a fresh one is built
            ctx.runOnClient(mc -> {
                Setup.setGoal("DAMAGE");
                Roadmap.forceRefresh();
            });
            ctx.waitTicks(2);
            shot(ctx, out, "jmenu-refreshing");
            ctx.waitFor(mc -> !Roadmap.isBuilding(), 20 * 60);
            ctx.waitTicks(20);
            // the goal list, open
            ctx.runOnClient(mc -> flag(((RoadmapScreen) mc.screen).home, "goalOpen", true));
            ctx.waitTicks(3);
            shot(ctx, out, "jmenu-goals");
            ctx.runOnClient(mc -> flag(((RoadmapScreen) mc.screen).home, "goalOpen", false));
            // every rail tab in the rail's order (jmenu-tab-<n>: the tab's number, not its place)
            Field railField = RoadmapScreen.class.getDeclaredField("RAIL");
            railField.setAccessible(true);
            for (int t : (int[]) railField.get(null)) {
                ctx.runOnClient(mc -> set(mc.screen, "tab", t));
                ctx.waitTicks(10);
                shot(ctx, out, "jmenu-tab-" + t);
            }
            // the ability tree scrolled to its end: the last page and the ultimates
            ctx.runOnClient(mc -> set(mc.screen, "tab", 9));
            ctx.runOnClient(mc -> set(((RoadmapScreen) mc.screen).tree, "treeScroll", 100000));
            ctx.waitTicks(3);
            shot(ctx, out, "jmenu-tree-end");
            // the settings' rules, with the typed emerald value
            ctx.runOnClient(mc -> set(mc.screen, "tab", 5));
            ctx.runOnClient(mc -> {
                try {
                    Field f = SettingsTab.class.getDeclaredField("SETTING_CATS");
                    f.setAccessible(true);
                    int i = java.util.Arrays.asList((String[]) f.get(null)).indexOf("rules");
                    set(((RoadmapScreen) mc.screen).settings, "settingsCat", Math.max(0, i));
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            });
            ctx.waitTicks(3);
            shot(ctx, out, "jmenu-settings-rules");
            // the glow settings: more rows than two columns hold, so the list scrolls
            for (int scroll : new int[] {0, 100000}) {
                ctx.runOnClient(mc -> {
                    try {
                        Field f = SettingsTab.class.getDeclaredField("SETTING_CATS");
                        f.setAccessible(true);
                        int i = java.util.Arrays.asList((String[]) f.get(null)).indexOf("glow");
                        set(((RoadmapScreen) mc.screen).settings, "settingsCat", Math.max(0, i));
                        set(((RoadmapScreen) mc.screen).settings, "settingsScroll", scroll);
                    } catch (ReflectiveOperationException e) {
                        throw new RuntimeException(e);
                    }
                });
                ctx.waitTicks(3);
                shot(ctx, out, scroll == 0 ? "jmenu-settings-glow" : "jmenu-settings-glow-end");
            }
            craftShots(ctx, out);
            // the bag's advice words and where-to-sell icons, on the recorded inventory
            ctx.setScreen(BagPreview::new);
            ctx.waitTicks(5);
            shot(ctx, out, "bag-badges");
            ctx.setScreen(RoadmapScreen::new);
            ctx.waitForScreen(RoadmapScreen.class);
            ctx.runOnClient(mc -> set(mc.screen, "tab", 0));
            ctx.waitTicks(3);
            int steps = ctx.computeOnClient(mc -> Roadmap.cached().size());
            for (int i = 0; i < steps; i++) {
                int sel = i;
                ctx.runOnClient(mc -> set(mc.screen, "selected", sel));
                ctx.waitTicks(3);
                shot(ctx, out, "jmenu-" + (i == 0 ? "now" : "step" + i));
            }
            craftScales(ctx, out);
            questGuides(ctx, out);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            FixtureState.uninstall();
        }
    }

    /** The crafting tab with a sample of real ingredients: a weapon, the picker, results, an accessory, a potion. */
    private static void craftShots(ClientGameTestContext ctx, Path out) throws Exception {
        ctx.runOnClient(mc -> {
            CraftingTab.previewIngredients = CraftPreview.ingredients();
            CraftPreview.levels();
            set(mc.screen, "tab", 10);
        });
        ctx.getInput().setCursorPos(2, 2);
        ctx.runOnClient(mc -> CraftPreview.recipe("relik", 103, 3, 2, "Foul Fairy Dust", "Obelisk Core", "Dizzying Void",
                "Depurinated Genome", "Exquisite Marsh Scale", "Gunkfueled Core"));
        ctx.waitTicks(5);
        shot(ctx, out, "craft-weapon");
        // the picker on slot 3, a meta ingredient hovered: the grid previews it
        ctx.runOnClient(mc -> CraftPreview.openSlot(2));
        ctx.waitTicks(3);
        List<int[]> rows = ctx.computeOnClient(mc -> {
            try {
                return CraftPreview.rects("overlay");
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        CraftPreview.hover(ctx, rows.get(Math.min(1, rows.size() - 1)));
        ctx.waitTicks(3);
        shot(ctx, out, "craft-picker");
        ctx.getInput().setCursorPos(2, 2);
        ctx.runOnClient(mc -> CraftPreview.set(CraftingTab.INSTANCE, "open",
                CraftPreview.get(CraftingTab.INSTANCE, "open").getClass().getEnumConstants()[0]));
        // the optimiser's three layouts
        ctx.runOnClient(mc -> CraftPreview.call("startSearch"));
        ctx.waitFor(mc -> {
            try {
                return !((List<?>) CraftPreview.get(CraftingTab.INSTANCE, "results")).isEmpty();
            } catch (ReflectiveOperationException e) {
                return false;
            }
        }, 20 * 30);
        ctx.waitTicks(3);
        shot(ctx, out, "craft-results");
        // many goal chips: they wrap instead of running under the search button
        ctx.runOnClient(mc -> {
            @SuppressWarnings("unchecked")
            var w = (java.util.Map<String, Double>) CraftPreview.get(CraftingTab.INSTANCE, "weights");
            w.put("healthRegen", 0.5);
            w.put("healthRegenRaw", 0.05);
            w.put("rawHealth", 0.02);
            w.put("manaRegen", 2.0);
        });
        ctx.waitTicks(3);
        shot(ctx, out, "craft-goals-wrap");
        ctx.runOnClient(mc -> {
            @SuppressWarnings("unchecked")
            var w = (java.util.Map<String, Double>) CraftPreview.get(CraftingTab.INSTANCE, "weights");
            w.clear();
            w.put("spellDamage", 1.0);
        });
        // the bottom: roll chances, shopping, what can I craft
        ctx.runOnClient(mc -> {
            CraftPreview.set(CraftingTab.INSTANCE, "foldWhat", true);
            CraftPreview.call("startWhat");
        });
        ctx.waitTicks(60);
        ctx.runOnClient(mc -> CraftPreview.set(CraftingTab.INSTANCE, "scroll", 100000));
        ctx.waitTicks(3);
        shot(ctx, out, "craft-bottom");
        ctx.runOnClient(mc -> CraftPreview.recipe("ring", 80, 2, 3, "Major's Badge", "Ancient Currency", null,
                null, null, null));
        ctx.waitTicks(3);
        shot(ctx, out, "craft-accessory");
        ctx.runOnClient(mc -> CraftPreview.recipe("potion", 43, 1, 3, "Bat Heart", "Black Sulphur"));
        ctx.waitTicks(3);
        shot(ctx, out, "craft-consumable");
        ctx.runOnClient(mc -> CraftPreview.recipe("potion", 43, 1, 3));
        ctx.waitTicks(3);
        shot(ctx, out, "craft-potion-heal");
        // our suggestions: the second page of the tab, filled in the background
        ctx.runOnClient(mc -> {
            CraftPreview.set(CraftingTab.INSTANCE, "view", 1);
            CraftPreview.call("startSuggestionsNow");
        });
        ctx.runOnClient(mc -> CraftPreview.set(CraftingTab.INSTANCE, "suggestMode", 1));
        ctx.waitTicks(20 * 12);
        shot(ctx, out, "craft-suggestions");
        // the same page as whole sets, four to a page, and one set's pieces
        ctx.runOnClient(mc -> {
            CraftPreview.set(CraftingTab.INSTANCE, "suggestMode", 0);
            CraftPreview.call("startSetsNow");
        });
        ctx.waitTicks(20 * 25);
        shot(ctx, out, "craft-sets");
        ctx.runOnClient(mc -> {
            var sets = (java.util.List<?>) CraftPreview.get(CraftingTab.INSTANCE, "sets");
            if (!sets.isEmpty()) {
                CraftPreview.set(CraftingTab.INSTANCE, "setDetail", sets.get(0));
            }
        });
        ctx.waitTicks(3);
        shot(ctx, out, "craft-set-pieces");
        // a piece's "Aç" opens it in the calculator: a click on the button, not on the row's tip area
        ctx.runOnClient(mc -> {
            if (CraftPreview.get(CraftingTab.INSTANCE, "setDetail") == null) {
                return;
            }
            String tipText = L.t("wynnlabs.craft.suggest_open_tip");
            try {
                for (Object a : (java.util.List<?>) CraftPreview.get(CraftingTab.INSTANCE, "overlay")) {
                    var tip = a.getClass().getDeclaredMethod("tip");
                    tip.setAccessible(true);
                    Object t = tip.invoke(a);
                    if (t instanceof java.util.List<?> l && !l.isEmpty() && l.get(0) instanceof net.minecraft.network.chat.Component c && c.getString().contains(tipText)) {
                        int[] r = new int[4];
                        String[] names = {"x", "y", "w", "h"};
                        for (int k = 0; k < 4; k++) {
                            var m = a.getClass().getDeclaredMethod(names[k]);
                            m.setAccessible(true);
                            r[k] = (int) m.invoke(a);
                        }
                        CraftingTab.INSTANCE.mouseClicked(r[0] + r[2] / 2.0, r[1] + r[3] / 2.0, 0);
                        break;
                    }
                }
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
            if (CraftPreview.get(CraftingTab.INSTANCE, "setDetail") != null || (int) CraftPreview.get(CraftingTab.INSTANCE, "view") != 0) {
                throw new AssertionError("the set piece's \"Aç\" did not open it");
            }
        });
        ctx.waitTicks(3);
        shot(ctx, out, "craft-set-piece-opened");
        ctx.runOnClient(mc -> CraftPreview.set(CraftingTab.INSTANCE, "setDetail", null));
        ctx.runOnClient(mc -> CraftPreview.set(CraftingTab.INSTANCE, "view", 0));
    }

    /** The saved recipes beside the menu (GUI 2), as a drawer (GUI 3) and the one-column page (GUI 4). */
    private static void craftScales(ClientGameTestContext ctx, Path out) throws Exception {
        ctx.runOnClient(mc -> {
            var saved = Crafting.saved();
            var goal = io.github.anilken.wynnlabs.crafting.SavedRecipes.SavedGoal.of(
                    io.github.anilken.wynnlabs.crafting.CraftSearch.Goal.of("spellDamage"));
            saved.save(new io.github.anilken.wynnlabs.crafting.SavedRecipes.Saved("Relik büyü", "relik", 103, 3, 2,
                    "NORMAL", java.util.Arrays.asList("Foul Fairy Dust", "Obelisk Core", "Dizzying Void",
                            "Depurinated Genome", null, null), goal, 1L), true);
            saved.save(new io.github.anilken.wynnlabs.crafting.SavedRecipes.Saved("Yüzük mana", "ring", 80, 2, 3,
                    "NORMAL", java.util.Arrays.asList("Major's Badge", null, null, null, null, null), goal, 2L), true);
            set(mc.screen, "tab", 10);
            CraftPreview.recipe("relik", 103, 3, 2, "Foul Fairy Dust", "Obelisk Core", "Dizzying Void",
                    "Depurinated Genome", "Exquisite Marsh Scale", "Gunkfueled Core");
        });
        ctx.getInput().setCursorPos(2, 2);
        for (int scale : new int[] {2, 4}) {
            ctx.runOnClient(mc -> {
                mc.options.guiScale().set(scale);
                mc.resizeDisplay();
            });
            ctx.waitTicks(5);
            shot(ctx, out, "craft-gui" + scale);
        }
        ctx.runOnClient(mc -> {
            mc.options.guiScale().set(3);
            mc.resizeDisplay();
            CraftPreview.set(CraftingTab.INSTANCE, "drawerOpen", true);
        });
        ctx.waitTicks(5);
        shot(ctx, out, "craft-drawer");
        ctx.runOnClient(mc -> CraftPreview.set(CraftingTab.INSTANCE, "drawerOpen", false));
    }

    /** The recorded bag as a 9 x 4 grid, each slot with its advice word and sell icon. */
    static final class BagPreview extends net.minecraft.client.gui.screens.Screen {
        BagPreview() {
            super(net.minecraft.network.chat.Component.literal("bag"));
        }

        @Override
        public void render(net.minecraft.client.gui.GuiGraphics g, int mx, int my, float pt) {
            g.fill(0, 0, width, height, 0xFF2A2A2A);
            var bag = GameState.current().inventory();
            int x0 = (width - 9 * 18) / 2;
            int y0 = (height - 4 * 18) / 2;
            for (int i = 0; i < Math.min(36, bag.size()); i++) {
                int x = x0 + (i % 9) * 18;
                int y = y0 + (i / 9) * 18;
                g.fill(x - 1, y - 1, x + 17, y + 17, 0xFF8B8B8B);
                var stack = bag.get(i);
                if (!stack.isEmpty()) {
                    g.renderItem(stack, x, y);
                    TooltipListener.drawBadge(g, x, y, stack);
                }
            }
        }
    }

    /**
     * The quests tab on "Jungle Fever" in the preview's language: the shipped guide while the wiki
     * page is unchanged, then the live English stages with the note when the page changed since.
     */
    private static void questGuides(ClientGameTestContext ctx, Path out) throws ReflectiveOperationException {
        ctx.setScreen(RoadmapScreen::new);
        ctx.waitForScreen(RoadmapScreen.class);
        ctx.runOnClient(mc -> QuestBook.put(new QuestBook.Entry("Jungle Fever", "STARTED", 48, "Medium", 0, true,
                "Infiltrate Almuj's Secret Emerald Labyrinth at [1019, 84, -1976] underground and find the Banker",
                1019, 84, -1976, null, System.currentTimeMillis(), "QUEST", "Medium")));
        QuestGuides.Guide g = ctx.computeOnClient(mc -> QuestGuides.find("Jungle Fever"));
        QuestWiki.Info same = new QuestWiki.Info("Medium", "", "", 48, 0, 0, java.util.List.of(), g.english(), java.util.List.of());
        java.util.List<QuestWiki.Stage> changed = new java.util.ArrayList<>(g.english());
        QuestWiki.Stage s3 = changed.get(3);
        changed.set(3, new QuestWiki.Stage(s3.title(), s3.task() + " in the jungle", s3.hints(), s3.at(), s3.points(), s3.names()));
        QuestWiki.Info newer = new QuestWiki.Info("Medium", "", "", 48, 0, 0, java.util.List.of(), changed, java.util.List.of());
        for (QuestWiki.Info live : new QuestWiki.Info[] {same, newer}) {
            ctx.runOnClient(mc -> {
                QuestWiki.putLive("Jungle Fever", live);
                try {
                    set(mc.screen, "tab", 2);
                    Field f = QuestsTab.class.getDeclaredField("questSelected");
                    f.setAccessible(true);
                    f.set(((RoadmapScreen) mc.screen).questsTab, "Jungle Fever");
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            });
            ctx.waitTicks(3);
            shot(ctx, out, live == same ? "quests-jungle-fever" : "quests-newer");
        }
        // the points tab: every place numbered by stage, the passed ones struck through
        ctx.runOnClient(mc -> {
            QuestWiki.putLive("Jungle Fever", same);
            try {
                set(((RoadmapScreen) mc.screen).questsTab, "questDetailTab", 3);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        ctx.waitTicks(3);
        shot(ctx, out, "quests-points");
        // the full guide (Tam çözüm) from the shipped stages, on the stage you are on
        ctx.runOnClient(mc -> QuestWiki.putLive("Jungle Fever", same));
        ctx.setScreen(() -> new GuideScreen("Jungle Fever",
                "Infiltrate Almuj's Secret Emerald Labyrinth at [1019, 84, -1976] underground and find the Banker"));
        ctx.waitTicks(10);
        shot(ctx, out, "quests-full-guide");
        // a quest with a part under its last stage ("▸ İpuçları": the door puzzle's answer)
        ctx.setScreen(() -> new GuideScreen("A Fighting Species", "Return to Darnes"));
        ctx.waitTicks(10);
        shot(ctx, out, "quests-full-guide-parts");
    }

    private static void shot(ClientGameTestContext ctx, Path out, String name) {
        ctx.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(out));
    }

    private static void flag(Object screen, String field, boolean value) throws ReflectiveOperationException {
        Field f = screen.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.setBoolean(screen, value);
    }

    private static void set(Object screen, String field, int value) throws ReflectiveOperationException {
        Field f = screen.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.setInt(screen, value);
    }
}
