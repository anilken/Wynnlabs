package io.github.anilken.wynnlabs;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * {@code /wynnlabs shots [en|tr|current] [all|<tab>] [x2|x3|x4] [hidename]}: pictures of the mod's own menu
 * for the mod's pages, taken in the real game with the real resource pack. Only while the Debug
 * setting is on, and only when the player types it.
 *
 * <p>It opens the mod's menu (nothing else), selects each tab in rail order and a few views inside
 * them, and grabs each view with Minecraft's own screenshot path (as F2 does) twice, over a black
 * and a white backdrop instead of the world. The picture saved to {@code screenshots/wynnlabs/<time>/}
 * is the mod's window alone (frame, shadow, close seal, side panels) on transparency, cropped to its
 * box with a small margin ({@link ShotCrop}). {@code x2}/{@code x3}/{@code x4} draw it at that menu
 * scale for the run. It never opens a game menu, clicks a slot or sends
 * anything to the server, and it never starts a road map build or a build search: it captures
 * what is there. The sequence itself is {@link ShotRun}; this is its side in the game.
 */
public final class Shots {

    private Shots() {}

    /** The run in progress, driven by {@link #onTick}; null when none. */
    private static ShotRun run;
    /** While capturing: the menu draws without the mouse (no hover, no tooltip) and ignores input but Esc. */
    private static volatile boolean capturing;
    /** The player's own name, masked as "Player" in every string drawn while capturing; null when not hidden. */
    private static volatile String hidden;

    static final String MASK = "Player";

    /** The colour behind the menu while capturing (black, then white for the same view: ShotCrop). */
    private static volatile int backdrop = 0xFF000000;
    /** The mod's window boxes drawn in the frame being drawn, and in the last one finished (GUI pixels {x0, y0, x1, y1}). */
    private static float[] frameNow;
    private static volatile float[] frameLast;

    static boolean capturing() {
        return capturing;
    }

    static int backdrop() {
        return backdrop;
    }

    /** RoadmapScreen, before it draws a frame while capturing. */
    static void beginFrame() {
        frameNow = null;
    }

    /** RoadmapScreen, after it drew a frame while capturing: its window boxes are the ones to crop to. */
    static void endFrame() {
        frameLast = frameNow;
    }

    /**
     * A box of the mod's window drawn under {@code pose} (the frame with its shadow, the close seal,
     * a side panel): the picture keeps all of them. Only while capturing.
     */
    static void frame(org.joml.Matrix3x2f pose, int x, int y, int w, int h) {
        if (!capturing) {
            return;
        }
        var a = pose.transformPosition(x, y, new org.joml.Vector2f());
        var b = pose.transformPosition(x + w, y + h, new org.joml.Vector2f());
        frameNow = ShotCrop.union(frameNow, new float[] {Math.min(a.x, b.x), Math.min(a.y, b.y), Math.max(a.x, b.x), Math.max(a.y, b.y)});
    }

    static boolean running() {
        return run != null;
    }

    /** Esc in the menu while capturing: the run stops and puts everything back. */
    static void escape() {
        ShotRun r = run;
        if (r != null) {
            r.escape();
        }
    }

    /** At the end of each client tick: one step of the run. */
    static void onTick(Minecraft mc) {
        ShotRun r = run;
        if (r != null && !r.tick() && run == r) {
            run = null;
        }
    }

    // ---- the command ----

    /** The command tree, built once for any loader's command source. */
    static <S> LiteralArgumentBuilder<S> command(BiConsumer<S, Component> feedback) {
        return LiteralArgumentBuilder.<S>literal("wynnlabs")
                .then(LiteralArgumentBuilder.<S>literal("shots")
                        .executes(c -> {
                            feedback.accept(c.getSource(), start(""));
                            return 1;
                        })
                        .then(RequiredArgumentBuilder.<S, String>argument("options", StringArgumentType.greedyString())
                                .suggests((c, b) -> suggest(b))
                                .executes(c -> {
                                    feedback.accept(c.getSource(), start(StringArgumentType.getString(c, "options")));
                                    return 1;
                                })));
    }

    /** The words the options take: the languages, all and the tab names, hidename. */
    static List<String> words() {
        List<String> out = new ArrayList<>(List.of("en", "tr", "current", "all"));
        for (int t : RoadmapScreen.railOrder()) {
            out.add(RoadmapScreen.tabName(t));
        }
        out.addAll(List.of("x2", "x3", "x4", "hidename"));
        return out;
    }

    private static CompletableFuture<Suggestions> suggest(SuggestionsBuilder b) {
        String typed = b.getRemaining().toLowerCase(Locale.ROOT);
        int space = typed.lastIndexOf(' ');
        String last = typed.substring(space + 1);
        SuggestionsBuilder at = b.createOffset(b.getStart() + space + 1);
        for (String w : words()) {
            if (w.startsWith(last)) {
                at.suggest(w);
            }
        }
        return at.buildFuture();
    }

    /**
     * The options, read: the language (as the rail's picker numbers it, -1 to keep), the tab
     * ("all"), the menu scale to draw at (2 to 4, -1 to keep), whether the name is hidden.
     */
    record Options(int lang, String only, int scale, boolean hideName) {}

    /** {@code options} read, or null with the word that is not one ({@code bad[0]}). */
    static Options parse(String options, String[] bad) {
        int lang = 1;
        String only = "all";
        int scale = -1;
        boolean hide = false;
        List<String> tabs = new ArrayList<>();
        for (int t : RoadmapScreen.railOrder()) {
            tabs.add(RoadmapScreen.tabName(t));
        }
        for (String w : options.strip().toLowerCase(Locale.ROOT).split(" ")) {
            switch (w) {
                case "" -> { }
                case "en" -> lang = 1;
                case "tr" -> lang = 2;
                case "current" -> lang = -1;
                case "all" -> only = "all";
                case "hidename" -> hide = true;
                case "x2" -> scale = 2;
                case "x3" -> scale = 3;
                case "x4" -> scale = 4;
                default -> {
                    if (!tabs.contains(w)) {
                        bad[0] = w;
                        return null;
                    }
                    only = w;
                }
            }
        }
        return new Options(lang, only, scale, hide);
    }

    /** Reads the options and starts a run; the line shown to the player. */
    static Component start(String options) {
        if (!Settings.on("debug")) {
            return L.c("wynnlabs.shots.debug_off", L.t("wynnlabs.setting.debug"), L.t("wynnlabs.screen.tab_settings"),
                    L.t("wynnlabs.settings.cat_data")).withStyle(ChatFormatting.YELLOW);
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return L.c("wynnlabs.shots.no_world").withStyle(ChatFormatting.YELLOW);
        }
        if (run != null) {
            return L.c("wynnlabs.shots.busy").withStyle(ChatFormatting.YELLOW);
        }
        String[] bad = new String[1];
        Options o = parse(options == null ? "" : options, bad);
        if (o == null) {
            return L.c("wynnlabs.shots.unknown", bad[0], String.join(", ", words())).withStyle(ChatFormatting.YELLOW);
        }
        String[] names = new String[RoadmapScreen.tabCount()];
        for (int t = 0; t < names.length; t++) {
            names[t] = RoadmapScreen.tabName(t);
        }
        List<ShotRun.Shot> shots = ShotRun.plan(RoadmapScreen.railOrder(), names, o.only());
        Path base = mc.gameDirectory.toPath().resolve("screenshots").resolve("wynnlabs");
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT));
        Path dir = base.resolve(stamp);
        for (int k = 2; Files.exists(dir); k++) {
            dir = base.resolve(stamp + "_" + k);         // two runs in one second: one folder each
        }
        run = new ShotRun(new Live(mc, dir), shots, o.lang(), o.scale(), o.hideName());
        boolean plan = Plans.current() != null;
        long n = shots.stream().filter(s -> plan || !"plan".equals(s.state())).count();
        return L.cn("wynnlabs.shots.started", n).withStyle(ChatFormatting.GRAY);
    }

    // ---- the name mask (GuiGraphicsShotsMixin) ----

    /**
     * Every string drawn while the name is hidden: the player's own name in it as "Player", in the
     * style of the name's first letter. Anything else, and everything when not hidden, as it is.
     */
    public static FormattedCharSequence mask(FormattedCharSequence s) {
        String name = hidden;
        if (name == null || s == null) {
            return s;
        }
        StringBuilder text = new StringBuilder();
        List<Style> styles = new ArrayList<>();
        s.accept((i, style, cp) -> {
            text.appendCodePoint(cp);
            for (int k = Character.charCount(cp); k > 0; k--) {
                styles.add(style);
            }
            return true;
        });
        String str = text.toString();
        if (maskedAt(str, name, 0) < 0) {
            return s;
        }
        return sink -> {
            int idx = 0;
            int pos = 0;
            while (pos < str.length()) {
                if (str.regionMatches(true, pos, name, 0, name.length())) {
                    Style st = styles.get(pos);
                    for (int k = 0; k < MASK.length(); k++) {
                        if (!sink.accept(idx++, st, MASK.charAt(k))) {
                            return false;
                        }
                    }
                    pos += name.length();
                } else {
                    int cp = str.codePointAt(pos);
                    if (!sink.accept(idx++, styles.get(pos), cp)) {
                        return false;
                    }
                    pos += Character.charCount(cp);
                }
            }
            return true;
        };
    }

    /** Where {@code name} first stands in {@code text} at or after {@code from}, case ignored; -1 when not. */
    static int maskedAt(String text, String name, int from) {
        if (name == null || name.isEmpty()) {
            return -1;
        }
        for (int i = from; i + name.length() <= text.length(); i++) {
            if (text.regionMatches(true, i, name, 0, name.length())) {
                return i;
            }
        }
        return -1;
    }

    // ---- the run's side in the game ----

    /** {@link ShotRun.Stage} in the game: the mod's menu, Minecraft's screenshot, the settings. */
    private static final class Live implements ShotRun.Stage {
        private final Minecraft mc;
        private final Path dir;
        private final AtomicInteger pending = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        /** Whether settings.json had no language pick: put back as absent, not as "follow the game". */
        private final boolean langUnset;

        private RoadmapScreen screen;
        private Screen prev;
        private boolean hudWas;
        private boolean poolsWas;
        private BuildWindow.Saved windowWas;
        private boolean planPickedWas;

        Live(Minecraft mc, Path dir) {
            this.mc = mc;
            this.dir = dir;
            this.langUnset = Settings.number("langOverride", -1) == -1;
        }

        @Override
        public int lang() {
            return Settings.number("langOverride", 0);
        }

        /**
         * The rail's language picker sets this same number. The picker also rebuilds the road map
         * in the new language; the screenshots never start a build, so the map stays as it is.
         */
        @Override
        public void lang(int pick) {
            if (langUnset && pick == 0) {
                Settings.resetNumber("langOverride");
            } else {
                Settings.setNumber("langOverride", pick);
            }
        }

        @Override
        public String code() {
            String c = L.code();
            return c.length() >= 2 ? c.substring(0, 2) : "en";
        }

        @Override
        public void flags(boolean on, boolean hideName) {
            if (on) {
                hudWas = mc.options.hideGui;
                mc.options.hideGui = true;          // the HUD under the menu (F3, overlays, chat) stays out of the pictures
                hidden = hideName ? mc.getUser().getName() : null;
                capturing = true;
            } else {
                capturing = false;
                hidden = null;
                backdrop = 0xFF000000;
                frameLast = null;
                mc.options.hideGui = hudWas;
            }
        }

        @Override
        public void open() {
            Screen cur = mc.screen;
            prev = cur instanceof ChatScreen ? null : cur;   // the chat the command was typed in closes anyway
            poolsWas = EndgameTab.INSTANCE.shotPools();
            windowWas = BuildWindow.INSTANCE.save();
            planPickedWas = RoadmapScreen.shotPlanPicked();
            screen = new RoadmapScreen();
            mc.setScreen(screen);
        }

        @Override
        public boolean showing() {
            return mc.player != null && screen != null && mc.screen == screen;
        }

        @Override
        public boolean has(String state) {
            return !"plan".equals(state) || Plans.current() != null;
        }

        @Override
        public void select(int tab, String state) {
            screen.shotSelect(tab, state);
        }

        @Override
        public int passes() {
            return 2;
        }

        @Override
        public void backdrop(int pass) {
            backdrop = pass == 0 ? 0xFF000000 : 0xFFFFFFFF;
        }

        /** The picture being taken: its window box in framebuffer pixels, and its frame over black. */
        private int[] rect;
        private CompletableFuture<NativeImage> overBlack;

        @Override
        public void capture(String file, int pass) {
            if (pass == 0) {
                float[] gui = frameLast;
                if (gui == null) {
                    throw new IllegalStateException("the menu drew no window");
                }
                rect = ShotCrop.pixels(gui, mc.getWindow().getGuiScale());
                overBlack = grab();
                return;
            }
            Path p = dir.resolve(file);
            try {
                Files.createDirectories(dir);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("cannot create " + dir, e);
            }
            int[] box = rect;
            CompletableFuture<NativeImage> black = overBlack;
            CompletableFuture<NativeImage> white = grab();
            pending.incrementAndGet();
            // both frames read back: the window alone, cropped and on transparency, written off the client thread
            black.thenAcceptBothAsync(white, (b, w) -> {
                try (b; w; NativeImage out = window(b, w, box)) {
                    out.writeToFile(p);
                } catch (Exception e) {
                    failed.incrementAndGet();
                    System.out.println("[WynnLabs] screenshots: could not write " + p + ": " + e);
                } finally {
                    pending.decrementAndGet();
                }
            }, Util.ioPool());
            overBlack = null;
        }

        /** The frame drawn last (this tick runs before the next one is drawn), as F2 takes it. */
        private CompletableFuture<NativeImage> grab() {
            CompletableFuture<NativeImage> f = new CompletableFuture<>();
            Screenshot.takeScreenshot(mc.getMainRenderTarget(), f::complete);
            return f;
        }

        /** The window's box of the two frames with a transparent margin, each pixel with its own alpha (ShotCrop). */
        private static NativeImage window(NativeImage black, NativeImage white, int[] box) {
            int m = ShotCrop.MARGIN;
            int[] px = ShotCrop.compose(black::getPixel, white::getPixel, black.getWidth(), black.getHeight(), box, m);
            int ow = box[2] + 2 * m;
            int oh = box[3] + 2 * m;
            NativeImage out = new NativeImage(ow, oh, true);
            for (int y = 0; y < oh; y++) {
                for (int x = 0; x < ow; x++) {
                    out.setPixel(x, y, px[y * ow + x]);
                }
            }
            return out;
        }

        /** Whether settings.json had no menu scale: put back as absent. */
        private final boolean scaleUnset = Settings.number("menuScale", -1) == -1;

        @Override
        public int scale() {
            return Settings.number("menuScale", 0);
        }

        /** The settings tab's Menu scale buttons set this same number; the next frame draws at it. */
        @Override
        public void scale(int scale) {
            if (scaleUnset && scale == 0) {
                Settings.resetNumber("menuScale");
            } else {
                Settings.setNumber("menuScale", scale);
            }
        }

        @Override
        public int pending() {
            return pending.get();
        }

        @Override
        public void restore() {
            if (overBlack != null) {
                overBlack.thenAccept(NativeImage::close);   // stopped between a picture's two frames
                overBlack = null;
            }
            // only our menu is replaced: a screen something else opened since stays
            if (screen != null && mc.screen == screen) {
                mc.setScreen(prev);
            }
            EndgameTab.INSTANCE.shotPools(poolsWas);
            if (windowWas != null) {
                BuildWindow.INSTANCE.restore(windowWas);
            }
            RoadmapScreen.shotPlanPicked(planPickedWas);
        }

        @Override
        public void done(int count, ShotRun.Stop stop, String detail) {
            int saved = Math.max(0, count - failed.get());
            MutableComponent line;
            if (stop == ShotRun.Stop.ERROR) {
                line = L.c("wynnlabs.shots.failed", detail == null ? "?" : detail).withStyle(ChatFormatting.RED);
            } else if (stop != null) {
                line = L.c("wynnlabs.shots.stopped", saved).withStyle(ChatFormatting.YELLOW);
            } else {
                line = L.cn("wynnlabs.shots.saved", saved).withStyle(ChatFormatting.GRAY);
            }
            if (saved > 0) {
                // as the game's own screenshot line: the folder's name, underlined, a click opens it
                line.append(" ").append(Component.literal(dir.getFileName().toString()).withStyle(ChatFormatting.UNDERLINE)
                        .withStyle(st -> st.withClickEvent(new ClickEvent.OpenFile(dir.toAbsolutePath()))));
            }
            if (mc.player != null) {
                mc.player.displayClientMessage(line, false);
            }
            System.out.println("[WynnLabs] screenshots: " + saved + " in " + dir.toAbsolutePath()
                    + (stop == null ? "" : " (stopped: " + stop + (detail == null ? "" : ", " + detail) + ")"));
        }
    }
}
