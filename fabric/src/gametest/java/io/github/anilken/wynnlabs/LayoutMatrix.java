package io.github.anilken.wynnlabs;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The mod's screens at the common window sizes and GUI scales: every J menu tab, the crafting
 * views, the full guide and the plan screen are drawn, and whatever leaves the window or any two
 * click areas that overlap are listed in build/preview/layout-report.txt. With
 * -Dwynnlabs.layoutStrict=true a problem fails the run. A few shots per size go to
 * build/preview/layout/.
 */
public class LayoutMatrix implements FabricClientGameTest {

    /** Window sizes and the GUI scales tried at each (0 = Auto). */
    static final int[][] SIZES = {
        {1280, 720, 0, 2}, {1366, 768, 0, 2}, {1600, 900, 0, 2}, {1920, 1080, 0, 2, 3, 4},
        {2560, 1440, 0, 2, 3, 4}, {3840, 2160, 0, 2, 3, 4},
    };

    private static final String[] TAB_NAMES = {"home", "today", "quests", "deaths", "bank", "settings", "ai", "damage",
        "discoveries", "tree", "craft", "endgame"};
    private static final int ENDGAME = 11;
    private static final int SETTINGS = 5;
    /** The settings tab's categories, in its order: each one is measured and shot. */
    private static final String[] SETTING_CATS = {"about", "look", "tips", "glow", "coach", "quests", "build", "rules", "data"};
    /**
     * The settings tab's other views, after its categories: the About page's three update states,
     * a rule picked (changed from its default) and a switch picked; compact shows each picked
     * row's own page.
     */
    private static final String[] SETTING_EXTRAS = {"about-uptodate", "about-new", "about-failed", "rules-picked", "tips-picked"};
    /** The regions of decision 11's drawings, lit in the Havuzlar shots. */
    private static final String TCC = "The Canyon Colossus";
    private static final String COTL = "Canyon of the Lost";

    /** -PpreviewTab=today: only that tab (a one-tab change is checked on its tab alone). */
    private static final String ONLY = System.getProperty("wynnlabs.previewTab", "");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if ("jmenu".equals(System.getProperty("wynnlabs.previewOnly"))) {
            return;
        }
        Path out = Path.of(System.getProperty("wynnlabs.preview", "preview"));
        Path shots = out.resolve("layout");
        Path fixture = Fixtures.root().resolve(System.getProperty("wynnlabs.previewFixture", "shaman-44"));
        Map<String, List<String>> report = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
            world.getClientWorld().waitForChunksRender();
            Files.createDirectories(shots);
            FixtureState fx = FixtureState.load(fixture);
            ctx.runOnClient(mc -> {
                fx.install(System.getProperty("wynnlabs.previewLang", "tr"));
                Wyn.clearDown();
            });
            ctx.setScreen(RoadmapScreen::new);
            ctx.waitForScreen(RoadmapScreen.class);
            ctx.getInput().setCursorPos(1, 1);
            ctx.waitFor(mc -> !Roadmap.cached().isEmpty() && !Roadmap.isBuilding(), 20 * 60);
            // something in every tab: the recorded quest book and bank, professions, deaths, found
            // discoveries; one quest started, a crafted relik, suggestions and sets
            ctx.runOnClient(mc -> {
                long now = System.currentTimeMillis();
                fx.quests().forEach(QuestBook::put);
                Vault.preview(fx.bankStacks());
                java.util.Map<String, Integer> prof = fx.data.state().professions();
                String[] names = {"WOODWORKING", "WOODCUTTING", "FARMING", "MINING", "FISHING", "ALCHEMISM", "COOKING",
                    "JEWELING", "SCRIBING", "TAILORING", "ARMOURING", "WEAPONSMITHING"};
                int[] levels = {105, 74, 52, 61, 33, 40, 28, 47, 19, 36, 44, 51};
                for (int i = 0; i < names.length; i++) {
                    prof.put(names[i], levels[i]);
                }
                DeathCoach.preview(List.of(
                        new DeathCoach.Death(now - 3_600_000L, 1019, 21, -1991, 44, "Almuj Emerald Labyrinth", false, 48, 2900, 1927,
                                3.4, 2, List.of("Guard Golem", "Emerald Sentry"), List.of(Words.literal("Bring 3 more potions"), Words.literal("Health is 30% under the area"))),
                        new DeathCoach.Death(now - 86_400_000L, -1297, 71, -4776, 43, "Leadin Fortress", false, 45, 3100, 1880,
                                6.1, 0, List.of("Orc Warrior"), List.of(Words.literal("No potions left: buy them before going in"))),
                        new DeathCoach.Death(now - 3 * 86_400_000L, -760, 79, -666, 41, "Jungle", false, 42, 3300, 1800,
                                9.0, 4, List.of("Jungle Spider", "Cobra"), List.<Words>of())));
                List<Discoveries.Entry> all = Discoveries.all();
                java.util.Set<String> found = new java.util.HashSet<>();
                for (int i = 0; i < Math.min(18, all.size()); i += 2) {
                    found.add(Discoveries.key(all.get(i).name()));
                }
                fx.discoveries = found;
                DiscoveryBook.previewSeen();
                // the other characters' saved days, for the today tab's cards
                String today = Today.utcDate();
                String[][] alts = {{"char0002", "WARRIOR", "42"}, {"char0003", "MAGE", "107"}, {"char0004", "ARCHER", "70"}};
                for (String[] a : alts) {
                    java.nio.file.Path dir = Dirs.config().resolve("characters").resolve(a[0]);
                    try {
                        java.nio.file.Files.createDirectories(dir);
                        java.nio.file.Files.writeString(dir.resolve("today.json"), "{\"class\":\"" + a[1] + "\",\"level\":" + a[2]
                                + ",\"date\":\"" + today + "\",\"objectives\":[{\"goal\":\"Kill 50 mobs\",\"score\":30,\"max\":50}],"
                                + "\"tickets\":0,\"seen\":" + (now - 3_600_000L) + "}");
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }
                QuestBook.put(new QuestBook.Entry("Jungle Fever", "STARTED", 48, "Medium", 0, true,
                        "Infiltrate Almuj's Secret Emerald Labyrinth at [1019, 84, -1976] underground and find the Banker",
                        1019, 84, -1976, null, now, "QUEST", "Medium"));
                QuestBook.put(new QuestBook.Entry("Recipe For Disaster", "AVAILABLE", 45, "Long", 120000, true, null,
                        949, 129, -4475, null, now, "QUEST", "Hard"));
                QuestBook.put(new QuestBook.Entry("A Fighting Species", "AVAILABLE", 44, "Medium", 90000, true, null,
                        -560, 58, -1640, null, now, "QUEST", "Medium"));
                EndgameTab.preview();                      // raids, lootruns, the pool and aspects of design decision 9
                CraftingTab.previewIngredients = CraftPreview.ingredients();
                CraftPreview.levels();
                CraftPreview.recipe("relik", 103, 3, 2, "Foul Fairy Dust", "Obelisk Core", "Dizzying Void",
                        "Depurinated Genome", "Exquisite Marsh Scale", "Gunkfueled Core");
                set(mc.screen, "tab", 10);                 // the tab draws once: its ingredients load
            });
            ctx.waitTicks(10);
            ctx.runOnClient(mc -> {
                CraftPreview.set(CraftingTab.INSTANCE, "view", 1);
                CraftPreview.call("startSuggestionsNow");
                CraftPreview.set(CraftingTab.INSTANCE, "suggestMode", 1);
            });
            ctx.waitTicks(20 * 12);
            ctx.runOnClient(mc -> {
                CraftPreview.set(CraftingTab.INSTANCE, "suggestMode", 0);
                CraftPreview.call("startSetsNow");
            });
            ctx.waitTicks(20 * 25);
            ctx.runOnClient(mc -> CraftPreview.set(CraftingTab.INSTANCE, "view", 0));
            for (int[] size : SIZES) {
                ctx.getInput().resizeWindow(size[0], size[1]);
                ctx.waitTicks(2);
                int[] real = ctx.computeOnClient(mc -> new int[] {mc.getWindow().getWidth(), mc.getWindow().getHeight()});
                if (real[0] != size[0] || real[1] != size[1]) {
                    report.computeIfAbsent("skipped", k -> new ArrayList<>())
                            .add(size[0] + "×" + size[1] + ": the window opened at " + real[0] + "×" + real[1]);
                    continue;
                }
                for (int i = 2; i < size.length; i++) {
                    int scale = size[i];
                    ctx.runOnClient(mc -> {
                        mc.options.guiScale().set(scale);
                        mc.resizeDisplay();
                    });
                    ctx.waitTicks(2);
                    int[] gui = ctx.computeOnClient(mc -> new int[] {mc.getWindow().getGuiScaledWidth(),
                        mc.getWindow().getGuiScaledHeight(), (int) mc.getWindow().getGuiScale()});
                    String where = size[0] + "x" + size[1] + "@" + (scale == 0 ? "auto" : scale) + " (" + gui[0] + "x" + gui[1] + ")";
                    if (!seen.add(size[0] + "x" + size[1] + "@" + gui[2])) {
                        continue;                       // Auto chose a scale already tried
                    }
                    // shots on Auto, and of the large layout at 1920x1080 on scale 2
                    checkMenu(ctx, where, gui, report, scale == 0 || size[0] == 1920 && (scale == 2 || scale == 3) ? shots : null);
                    if (ONLY.isEmpty()) {
                        checkScreen(ctx, where + " guide", gui, report, () -> new GuideScreen("Jungle Fever", ""), null);
                    }
                    ctx.setScreen(RoadmapScreen::new);
                    ctx.waitForScreen(RoadmapScreen.class);
                }
            }
            menuScale(ctx, shots, report);
            if (ONLY.isEmpty() || "endgame".equals(ONLY)) {
                endgame(ctx, shots, report);
                pools(ctx, shots, report);
            }
            ctx.runOnClient(mc -> {
                mc.options.guiScale().set(3);
                mc.resizeDisplay();
            });
            ctx.getInput().resizeWindow(1920, 1080);
            writeReport(out.resolve("layout-report.txt"), report);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            FixtureState.uninstall();
        }
        if (Boolean.getBoolean("wynnlabs.layoutStrict")) {
            int n = report.entrySet().stream().filter(e -> !e.getKey().equals("skipped")).mapToInt(e -> e.getValue().size()).sum();
            if (n > 0) {
                throw new AssertionError(n + " layout problems: see build/preview/layout-report.txt");
            }
        }
    }

    /**
     * The menu scale: at 1920x1080 on scale 4 with the menu at 3 the quests tab (with
     * -PpreviewTab, that tab) looks as it does on scale 3 (both shots saved), nothing leaves the
     * 480x270 window, and a click on a rail tab, given in the game's own GUI pixels, lands on that tab.
     */
    private static void menuScale(ClientGameTestContext ctx, Path shots, Map<String, List<String>> report) throws Exception {
        ctx.getInput().resizeWindow(1920, 1080);
        int only = List.of(TAB_NAMES).indexOf(ONLY);
        int shown = only < 0 ? 2 : only;
        for (int[] run : new int[][] {{3, 0}, {4, 3}}) {
            ctx.runOnClient(mc -> {
                Settings.setNumber("menuScale", run[1]);
                mc.options.guiScale().set(run[0]);
                mc.resizeDisplay();
            });
            ctx.setScreen(RoadmapScreen::new);
            ctx.waitForScreen(RoadmapScreen.class);
            ctx.runOnClient(mc -> set(mc.screen, "tab", shown));
            int[] gui = ctx.computeOnClient(mc -> new int[] {mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()});
            measure(ctx, "menu scale " + run[1] + " on game scale " + run[0], gui, report, List::of);
            ctx.takeScreenshot(TestScreenshotOptions.of("menuscale-game" + run[0] + "-menu" + run[1])
                    .disableCounterPrefix().withDestinationDir(shots));
            // the same with the mouse on the first quest row: its tooltip at the menu's size
            double[] at = ctx.computeOnClient(mc -> {
                Field hitsField = RoadmapScreen.class.getDeclaredField("hits");
                hitsField.setAccessible(true);
                for (Object h : (List<?>) hitsField.get(mc.screen)) {
                    var rec = h.getClass();
                    var tip = rec.getDeclaredMethod("tip");
                    var copy = rec.getDeclaredMethod("copy");
                    tip.setAccessible(true);
                    copy.setAccessible(true);
                    if (copy.invoke(h) instanceof String c && c.startsWith("#qsel:") && tip.invoke(h) != null) {
                        int[] r = new int[4];
                        String[] n = {"x", "y", "w", "h"};
                        for (int k = 0; k < 4; k++) {
                            var m = rec.getDeclaredMethod(n[k]);
                            m.setAccessible(true);
                            r[k] = (int) m.invoke(h);
                        }
                        double f = MenuScale.factor();
                        double scale = mc.getWindow().getGuiScale();
                        return new double[] {(r[0] + r[2] / 2.0) / f * scale, (r[1] + r[3] / 2.0) / f * scale};
                    }
                }
                return null;
            });
            if (at != null) {
                ctx.getInput().setCursorPos(at[0], at[1]);
                ctx.waitTicks(3);
                ctx.takeScreenshot(TestScreenshotOptions.of("menuscale-game" + run[0] + "-menu" + run[1] + "-tooltip")
                        .disableCounterPrefix().withDestinationDir(shots));
                ctx.getInput().setCursorPos(1, 1);
            }
        }
        // the rail's tab 0 (home), clicked where it shows on screen at the game's scale 4
        ctx.runOnClient(mc -> {
            Field tabsField = RoadmapScreen.class.getDeclaredField("tabs");
            tabsField.setAccessible(true);
            int[] home = null;
            for (int[] t : (List<int[]>) tabsField.get(mc.screen)) {
                if (t[4] == 0) {
                    home = t;
                }
            }
            double f = MenuScale.factor();
            double sx = (home[0] + home[2] / 2.0) / f;
            double sy = (home[1] + home[3] / 2.0) / f;
            mc.screen.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(sx, sy,
                    new net.minecraft.client.input.MouseButtonInfo(0, 0)), false);
            Field tab = RoadmapScreen.class.getDeclaredField("tab");
            tab.setAccessible(true);
            if (tab.getInt(mc.screen) != 0) {
                report.computeIfAbsent("menu scale click", k -> new ArrayList<>())
                        .add("a click on the home tab at " + Math.round(sx) + "," + Math.round(sy) + " did not open it");
            }
            Settings.setNumber("menuScale", 0);
        });
    }

    /**
     * The endgame tab's extra states at the standard size (1920x1080 on scale 3) and compact (on
     * Auto): an aspect row's tooltip, the empty character char0002, and raid tracking marked down.
     */
    private static void endgame(ClientGameTestContext ctx, Path shots, Map<String, List<String>> report) throws Exception {
        ctx.getInput().resizeWindow(1920, 1080);
        for (int scale : new int[] {3, 0}) {
            ctx.runOnClient(mc -> {
                mc.options.guiScale().set(scale);
                mc.resizeDisplay();
            });
            ctx.setScreen(RoadmapScreen::new);
            ctx.waitForScreen(RoadmapScreen.class);
            ctx.runOnClient(mc -> set(mc.screen, "tab", ENDGAME));
            int[] gui = ctx.computeOnClient(mc -> new int[] {mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()});
            String where = "1920x1080@" + (scale == 0 ? "auto" : scale);
            String file = where.replaceAll("[^0-9a-z@x]", "");
            boolean compact = gui[0] < 560 || gui[1] < 300;
            Supplier<List<LayoutCheck.Area>> areas = () -> EndgameTab.INSTANCE.clickAreas();
            // the mouse on an aspect row (compact: in the aspect section)
            ctx.runOnClient(mc -> EndgameTab.INSTANCE.previewSection(compact ? EndgameTab.SEC_ASPECTS : EndgameTab.SEC_RAIDS));
            ctx.waitTicks(3);
            // Aspect of Damnation (in two raids of Wynnventory's pool) where it shows, else Aspect of the Amphibian
            double[] at = ctx.computeOnClient(mc -> {
                double[] found = null;
                for (LayoutCheck.Area a : EndgameTab.INSTANCE.clickAreas()) {
                    if (a.what().equals("Aspect of Damnation")) {
                        return onScreen(mc, new int[] {a.x(), a.y(), a.w(), a.h()});
                    }
                    if (a.what().equals("Aspect of the Amphibian")) {
                        found = onScreen(mc, new int[] {a.x(), a.y(), a.w(), a.h()});
                    }
                }
                return found;
            });
            if (at == null) {
                report.computeIfAbsent(where + " endgame tooltip", k -> new ArrayList<>()).add("no aspect row to hover");
            } else {
                hoverShot(ctx, at, shots, file + "-endgame-tooltip");
            }
            // group 7: the pool filter grouped by raid (compact: the aspect section), the pool line's
            // tooltip, the mythic camps line with its tooltip (compact: the lootrun section)
            // (Aspect of Damnation picked, as in the mockup: the card names its two raids)
            ctx.runOnClient(mc -> {
                EndgameTab.INSTANCE.previewSection(EndgameTab.SEC_ASPECTS);
                EndgameTab.INSTANCE.previewPoolFilter(true);
                EndgameTab.INSTANCE.previewPick("Aspect of Damnation");
            });
            measure(ctx, where + " endgame-pool", gui, report, areas);
            ctx.takeScreenshot(TestScreenshotOptions.of(file + "-endgame-pool").disableCounterPrefix().withDestinationDir(shots));
            ctx.runOnClient(mc -> {
                EndgameTab.INSTANCE.previewPick(null);
                EndgameTab.INSTANCE.previewSection(EndgameTab.SEC_RAIDS);
            });
            ctx.waitTicks(2);
            double[] pool = ctx.computeOnClient(mc -> {
                for (LayoutCheck.Area a : EndgameTab.INSTANCE.clickAreas()) {
                    if (a.what().equals("endgame:pool")) {
                        return onScreen(mc, new int[] {a.x(), a.y(), a.w(), a.h()});
                    }
                }
                return null;
            });
            if (pool == null) {
                report.computeIfAbsent(where + " endgame pool tooltip", k -> new ArrayList<>()).add("no pool line to hover");
            } else {
                hoverShot(ctx, pool, shots, file + "-endgame-pool-tip");
            }
            ctx.runOnClient(mc -> {
                EndgameTab.INSTANCE.previewPoolFilter(false);
                EndgameTab.INSTANCE.previewSection(EndgameTab.SEC_LOOTRUNS);
            });
            if (compact) {
                measure(ctx, where + " endgame-lootruns", gui, report, areas);
                ctx.takeScreenshot(TestScreenshotOptions.of(file + "-endgame-lootruns").disableCounterPrefix().withDestinationDir(shots));
            } else {
                ctx.waitTicks(2);
            }
            double[] mythic = ctx.computeOnClient(mc -> {
                int[] r = EndgameTab.INSTANCE.previewMythicLine();
                return r == null ? null : onScreen(mc, r);
            });
            if (mythic == null) {
                report.computeIfAbsent(where + " endgame mythic line", k -> new ArrayList<>()).add("no mythic camps line drawn");
            } else {
                hoverShot(ctx, mythic, shots, file + "-endgame-mythic-tip");
            }
            // the fallback: only the in-game pool of decision 9, no Wynnventory data (pool filter on)
            ctx.runOnClient(mc -> {
                EndgameTab.previewFallback();
                EndgameTab.INSTANCE.previewPoolFilter(true);
            });
            for (int view : compact ? new int[] {0, 1, 2} : new int[] {0}) {
                ctx.runOnClient(mc -> EndgameTab.INSTANCE.previewSection(view));
                String name = "endgame-fallback" + (compact ? "-" + view : "");
                measure(ctx, where + " " + name, gui, report, areas);
                ctx.takeScreenshot(TestScreenshotOptions.of(file + "-" + name).disableCounterPrefix().withDestinationDir(shots));
            }
            ctx.runOnClient(mc -> EndgameTab.INSTANCE.previewPoolFilter(false));
            // the empty character, each compact section
            ctx.runOnClient(mc -> EndgameTab.previewEmpty());
            for (int view : compact ? new int[] {0, 1, 2} : new int[] {0}) {
                ctx.runOnClient(mc -> EndgameTab.INSTANCE.previewSection(view));
                String name = "endgame-empty" + (compact ? "-" + view : "");
                measure(ctx, where + " " + name, gui, report, areas);
                ctx.takeScreenshot(TestScreenshotOptions.of(file + "-" + name).disableCounterPrefix().withDestinationDir(shots));
            }
            // raid tracking off with this Wynntils (the other sections keep working)
            ctx.runOnClient(mc -> {
                EndgameTab.preview();
                EndgameTab.INSTANCE.previewSection(EndgameTab.SEC_RAIDS);
                Wyn.markDown(Wyn.Feature.RAIDS, "preview");
            });
            measure(ctx, where + " endgame-raids-off", gui, report, areas);
            ctx.takeScreenshot(TestScreenshotOptions.of(file + "-endgame-raids-off").disableCounterPrefix().withDestinationDir(shots));
            ctx.runOnClient(mc -> Wyn.clearDown());
        }
    }

    /**
     * Group 8, the Havuzlar view of decision 11 at 1920x1080: standard (scale 3) Raidler at The
     * Canyon Colossus and Lootrunlar at Canyon of the Lost with an item row's tooltip each, compact
     * (Auto) both sub-tabs, large (scale 2) Lootrunlar, and the fallback (only the in-game pool)
     * at standard and compact; plus the class chip on and the empty states, measured only.
     */
    private static void pools(ClientGameTestContext ctx, Path shots, Map<String, List<String>> report) throws Exception {
        ctx.getInput().resizeWindow(1920, 1080);
        for (int scale : new int[] {3, 0, 2}) {
            ctx.runOnClient(mc -> {
                mc.options.guiScale().set(scale);
                mc.resizeDisplay();
            });
            ctx.setScreen(RoadmapScreen::new);
            ctx.waitForScreen(RoadmapScreen.class);
            ctx.runOnClient(mc -> {
                set(mc.screen, "tab", ENDGAME);
                EndgameTab.preview();
            });
            String where = "1920x1080@" + (scale == 0 ? "auto" : scale);
            String file = where.replaceAll("[^0-9a-z@x]", "");
            boolean large = scale == 2;
            boolean standard = scale == 3;
            Supplier<List<LayoutCheck.Area>> areas = () -> ((RoadmapScreen) net.minecraft.client.Minecraft.getInstance().screen).clickAreas();
            // Raidler at The Canyon Colossus, then with the class chip on
            ctx.runOnClient(mc -> EndgameTab.INSTANCE.previewPools(EndgameTab.TAB_RAIDS, TCC));
            measure(ctx, where + " endgame-pools-raids", gui(ctx), report, areas);
            if (!large) {
                ctx.takeScreenshot(TestScreenshotOptions.of(file + "-endgame-pools-raids").disableCounterPrefix().withDestinationDir(shots));
            }
            if (standard) {
                rowShot(ctx, "Aspect of the Monolith", shots, file + "-endgame-pools-raids-tip", report, where);
            }
            ctx.runOnClient(mc -> clickArea(report, where, "endgame:classonly"));
            measure(ctx, where + " endgame-pools-classonly", gui(ctx), report, areas);
            ctx.runOnClient(mc -> clickArea(report, where, "endgame:classonly"));
            // Lootrunlar at Canyon of the Lost (the shiny Halcyon on top)
            ctx.runOnClient(mc -> EndgameTab.INSTANCE.previewPools(EndgameTab.TAB_CAMPS, COTL));
            measure(ctx, where + " endgame-pools-camps", gui(ctx), report, areas);
            ctx.takeScreenshot(TestScreenshotOptions.of(file + "-endgame-pools-camps").disableCounterPrefix().withDestinationDir(shots));
            if (standard) {
                rowShot(ctx, "Halcyon*", shots, file + "-endgame-pools-camps-tip", report, where);
            }
            // the fallback: only the in-game pool, no Wynnventory data
            ctx.runOnClient(mc -> {
                EndgameTab.previewFallback();
                EndgameTab.INSTANCE.previewPools(EndgameTab.TAB_RAIDS, null);
            });
            measure(ctx, where + " endgame-pools-fallback", gui(ctx), report, areas);
            if (!large) {
                ctx.takeScreenshot(TestScreenshotOptions.of(file + "-endgame-pools-fallback").disableCounterPrefix().withDestinationDir(shots));
            }
            ctx.runOnClient(mc -> EndgameTab.INSTANCE.previewPools(EndgameTab.TAB_CAMPS, null));
            measure(ctx, where + " endgame-pools-fallback-camps", gui(ctx), report, areas);
            // nothing at all (the empty character)
            ctx.runOnClient(mc -> {
                EndgameTab.previewEmpty();
                EndgameTab.INSTANCE.previewPools(EndgameTab.TAB_RAIDS, null);
            });
            measure(ctx, where + " endgame-pools-empty", gui(ctx), report, areas);
            ctx.runOnClient(mc -> {
                EndgameTab.preview();
                EndgameTab.INSTANCE.previewPools(-1, null);
            });
        }
    }

    private static int[] gui(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> new int[] {mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()});
    }

    /** Clicks the endgame tab's click area named {@code what}, as the player would; reported when it is not drawn. */
    private static void clickArea(Map<String, List<String>> report, String where, String what) {
        for (LayoutCheck.Area a : EndgameTab.INSTANCE.clickAreas()) {
            if (a.what().equals(what)) {
                EndgameTab.INSTANCE.mouseClicked(a.x() + a.w() / 2.0, a.y() + a.h() / 2.0, 0);
                return;
            }
        }
        report.computeIfAbsent(where + " endgame pools", k -> new ArrayList<>()).add("no \"" + what + "\" to click");
    }

    /** The mouse on the Havuzlar row of this item, a shot with its tooltip; reported when the row is not drawn. */
    private static void rowShot(ClientGameTestContext ctx, String item, Path shots, String name, Map<String, List<String>> report,
            String where) {
        ctx.waitTicks(2);
        double[] at = ctx.computeOnClient(mc -> {
            int[] r = EndgameTab.INSTANCE.previewRow(item);
            return r == null ? null : onScreen(mc, r);
        });
        if (at == null) {
            report.computeIfAbsent(where + " endgame pools tooltip", k -> new ArrayList<>()).add("no row of " + item + " to hover");
        } else {
            hoverShot(ctx, at, shots, name);
        }
    }

    /** A point a third into this menu rectangle {x, y, w, h}, in window pixels for the mouse. */
    private static double[] onScreen(net.minecraft.client.Minecraft mc, int[] r) {
        double f = MenuScale.factor();
        double s = mc.getWindow().getGuiScale();
        return new double[] {(r[0] + r[2] / 3.0) / f * s, (r[1] + r[3] / 2.0) / f * s};
    }

    /** The mouse at {@code at}, a shot with its tooltip, then the mouse back in the corner. */
    private static void hoverShot(ClientGameTestContext ctx, double[] at, Path shots, String name) {
        ctx.getInput().setCursorPos(at[0], at[1]);
        ctx.waitTicks(3);
        ctx.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(shots));
        ctx.getInput().setCursorPos(1, 1);
    }

    /** Every rail tab, and the crafting tab's suggestions view, at the current size. */
    private static void checkMenu(ClientGameTestContext ctx, String where, int[] gui, Map<String, List<String>> report, Path shots)
            throws Exception {
        for (int tab = 0; tab < TAB_NAMES.length; tab++) {
            if (!ONLY.isEmpty() && !TAB_NAMES[tab].equals(ONLY)) {
                continue;
            }
            int t = tab;
            ctx.runOnClient(mc -> set(mc.screen, "tab", t));
            // the crafting tab's calculator and sets; the quests tab's steps and points; the
            // endgame tab's three compact sections; every settings category
            boolean compact = gui[0] < 560 || gui[1] < 300;
            int[] views = t == 10 || t == 2 ? new int[] {0, 1} : t == ENDGAME && compact ? new int[] {0, 1, 2}
                    : t == SETTINGS ? java.util.stream.IntStream.range(0, SETTING_CATS.length + SETTING_EXTRAS.length).toArray()
                    : new int[] {0};
            for (int view : views) {
                if (t == ENDGAME) {
                    ctx.runOnClient(mc -> EndgameTab.INSTANCE.previewSection(view));
                }
                if (t == 10) {
                    ctx.runOnClient(mc -> CraftPreview.set(CraftingTab.INSTANCE, "view", view));
                }
                if (t == 2) {
                    ctx.runOnClient(mc -> set(mc.screen, "questDetailTab", view == 0 ? 0 : 3));
                }
                if (t == SETTINGS) {
                    ctx.runOnClient(mc -> settingsView((RoadmapScreen) mc.screen, view, compact));
                }
                String name = TAB_NAMES[t] + (t == SETTINGS ? "-" + settingsName(view) : views.length > 1 ? "-" + view : "");
                measure(ctx, where + " " + name, gui, report, () -> ((RoadmapScreen) net.minecraft.client.Minecraft.getInstance().screen).clickAreas());
                if (shots != null) {
                    ctx.takeScreenshot(TestScreenshotOptions.of(where.replaceAll("[^0-9a-z@x]", "") + "-" + name)
                            .disableCounterPrefix().withDestinationDir(shots));
                }
                if (t == SETTINGS) {
                    ctx.runOnClient(mc -> settingsViewDone(view));
                }
            }
            if (t == 2) {
                questStates(ctx, where, gui, report, shots);
            }
            if (t == ENDGAME) {
                // group 8: the Havuzlar view, both sub-tabs, at every size (its shots are taken in pools())
                for (int sub : new int[] {EndgameTab.TAB_RAIDS, EndgameTab.TAB_CAMPS}) {
                    ctx.runOnClient(mc -> EndgameTab.INSTANCE.previewPools(sub, sub == EndgameTab.TAB_RAIDS ? TCC : COTL));
                    measure(ctx, where + " endgame-pools-" + (sub == EndgameTab.TAB_RAIDS ? "raids" : "camps"), gui, report,
                            () -> ((RoadmapScreen) net.minecraft.client.Minecraft.getInstance().screen).clickAreas());
                }
                ctx.runOnClient(mc -> EndgameTab.INSTANCE.previewPools(-1, null));
            }
        }
        ctx.runOnClient(mc -> {
            CraftPreview.set(CraftingTab.INSTANCE, "view", 0);
            ((RoadmapScreen) mc.screen).previewSettings(0, 0, false);
            UpdateCheck.reset();
        });
    }

    private static String settingsName(int view) {
        return view < SETTING_CATS.length ? SETTING_CATS[view] : SETTING_EXTRAS[view - SETTING_CATS.length];
    }

    /**
     * A settings view: a category with its first row picked, or one of {@link #SETTING_EXTRAS}
     * (an About state given by hand; Outdated gear at 6 levels instead of 4; a switch picked).
     */
    private static void settingsView(RoadmapScreen screen, int view, boolean compact) {
        String name = settingsName(view);
        long now = System.currentTimeMillis();
        String page = "https://github.com/anilken/wynnlabs/releases/tag/";
        switch (name) {
            case "about-uptodate" -> {
                screen.previewSettings(0, 0, false);
                UpdateCheck.preview(new UpdateCheck.State(UpdateCheck.Status.UP_TO_DATE, UpdateCheck.Reason.NONE, "0.3.0",
                        "v0.3.0", page + "v0.3.0", now - 5 * 86_400_000L, now - 4 * 60_000L));
            }
            case "about-new" -> {
                screen.previewSettings(0, 0, false);
                UpdateCheck.preview(new UpdateCheck.State(UpdateCheck.Status.NEWER, UpdateCheck.Reason.NONE, "0.3.0",
                        "v0.4.0", page + "v0.4.0", now - 3 * 86_400_000L, now - 2 * 60_000L));
            }
            case "about-failed" -> {
                screen.previewSettings(0, 0, false);
                UpdateCheck.preview(new UpdateCheck.State(UpdateCheck.Status.FAILED, UpdateCheck.Reason.OFFLINE, "0.3.0",
                        null, null, 0, now - 60_000L));
            }
            case "rules-picked" -> {
                RuleEditor.Rule tooLow = RuleEditor.RULES.get(0);
                RuleEditor.step(tooLow, 1);
                RuleEditor.step(tooLow, 1);
                screen.previewSettings(7, 1, compact);           // Rules > Selling old gear > Outdated, at 6 lv
            }
            case "tips-picked" -> screen.previewSettings(2, 1, compact);   // Tooltips > Keep items for your other characters
            default -> {
                UpdateCheck.reset();
                screen.previewSettings(view, 0, false);
            }
        }
    }

    /** After a settings view's shot: the rule it changed back to its default. */
    private static void settingsViewDone(int view) {
        if (settingsName(view).equals("rules-picked")) {
            RuleEditor.reset(RuleEditor.RULES.get(0));
        }
    }

    /**
     * The quests tab's "read quests with Wynntils" at this size: the title line's "Yenile" chip
     * with its tooltip, the chip while a rescan runs, and the empty card (its button, the note
     * under it) before and while one runs. Shots where {@code shots} is given.
     */
    private static void questStates(ClientGameTestContext ctx, String where, int[] gui, Map<String, List<String>> report, Path shots)
            throws Exception {
        String file = where.replaceAll("[^0-9a-z@x]", "");
        Supplier<List<LayoutCheck.Area>> areas = () -> ((RoadmapScreen) net.minecraft.client.Minecraft.getInstance().screen).clickAreas();
        ctx.runOnClient(mc -> set(mc.screen, "questDetailTab", 0));
        String[][] states = {{"quests-rescan", "0", "0"}, {"quests-rescanning", "0", "1"}, {"quests-empty", "1", "0"},
            {"quests-empty-rescanning", "1", "1"}};
        for (String[] s : states) {
            boolean hidden = s[1].equals("1");
            boolean running = s[2].equals("1");
            ctx.runOnClient(mc -> {
                QuestBook.previewHidden(hidden);
                QuestBook.previewRescanning(running);
            });
            measure(ctx, where + " " + s[0], gui, report, areas);
            if (shots == null) {
                continue;
            }
            if (!running) {
                // the mouse on the chip or the button: its tooltip
                double[] at = ctx.computeOnClient(mc -> {
                    for (LayoutCheck.Area a : areas.get()) {
                        if (a.what().equals("#qrescan:")) {
                            return onScreen(mc, new int[] {a.x(), a.y(), a.w(), a.h()});
                        }
                    }
                    return null;
                });
                if (at == null) {
                    report.computeIfAbsent(where + " " + s[0], k -> new ArrayList<>()).add("no rescan button to hover");
                } else {
                    hoverShot(ctx, at, shots, file + "-" + s[0] + "-tip");
                }
            }
            if (hidden || running) {
                ctx.takeScreenshot(TestScreenshotOptions.of(file + "-" + s[0]).disableCounterPrefix().withDestinationDir(shots));
            }
        }
        ctx.runOnClient(mc -> {
            QuestBook.previewHidden(false);
            QuestBook.previewRescanning(false);
        });
    }

    private static void checkScreen(ClientGameTestContext ctx, String where, int[] gui, Map<String, List<String>> report,
            Supplier<Screen> screen, Path shots) {
        ctx.setScreen(screen);
        ctx.waitTicks(5);
        measure(ctx, where, gui, report, List::of);
    }

    /** One frame's drawing and click areas at the current size. */
    private static void measure(ClientGameTestContext ctx, String where, int[] gui, Map<String, List<String>> report,
            Supplier<List<LayoutCheck.Area>> areas) {
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> LayoutCheck.start());
        ctx.waitTicks(1);
        List<int[]> frames = ctx.computeOnClient(mc -> LayoutCheck.frames());
        List<int[]> surfaces = ctx.computeOnClient(mc -> LayoutCheck.textAreas());
        List<LayoutCheck.Drawn> drawn = ctx.computeOnClient(mc -> LayoutCheck.stop());
        List<LayoutCheck.Area> clicks = ctx.computeOnClient(mc -> areas.get());
        List<String> found = new ArrayList<>(LayoutCheck.outside(drawn, gui[0], gui[1]));
        found.addAll(LayoutCheck.outsideFrames(drawn, frames));
        found.addAll(LayoutCheck.textOverlaps(drawn));
        found.addAll(LayoutCheck.textOffSurface(drawn, surfaces));
        found.addAll(LayoutCheck.overlaps(clicks));
        if (drawn.isEmpty()) {
            found.add("nothing was drawn");
        }
        if (!found.isEmpty()) {
            report.put(where, found);
        }
    }

    private static void writeReport(Path file, Map<String, List<String>> report) throws Exception {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (var e : report.entrySet()) {
            sb.append(e.getKey()).append('\n');
            for (String p : new java.util.LinkedHashSet<>(e.getValue())) {
                sb.append("  ").append(p).append('\n');
                n += e.getKey().equals("skipped") ? 0 : 1;
            }
        }
        sb.insert(0, n + " problems\n\n");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("[WynnLabs] layout check: " + n + " problems (" + file + ")");
    }

    private static void set(Object screen, String field, int value) throws ReflectiveOperationException {
        Field f = screen.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.setInt(screen, value);
    }
}
