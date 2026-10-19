package io.github.anilken.wynnlabs;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
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
import org.joml.Matrix3x2f;
import org.joml.Vector2f;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/**
 * Dev builds only. {@code /wynnlabs shots [en|tr|current] [all|<tab>] [x2|x3|x4] [hidename]}:
 * pictures of the mod's own menu for the mod's pages, taken in the real game with the real
 * resource pack. Only while the Debug setting is on, and only when the player types it.
 *
 * <p>It opens the mod's menu (nothing else) and takes every view of every tab in rail order
 * ({@link ShotRun#views}: sub-pages, filters, windows, tooltips), each grabbed with Minecraft's own
 * screenshot path (as F2 does) twice, over a black and a white backdrop instead of the world. The
 * picture is the mod's window alone (frame, shadow, close seal, side panels, and a tooltip past the
 * frame) on transparency, cropped with a small margin ({@link ShotCrop}); key panels are saved as
 * close-ups beside it, and an index.html lists them all. It never opens a game menu, clicks a slot
 * or sends anything to the server, and never starts a road map build or a build search.
 */
public final class Shots {

    private Shots() {}

    /** The run in progress, driven by {@link #onTick}; null when none. */
    private static ShotRun run;
    /** While capturing: the menu draws with the tool's mouse and ignores input but Esc. */
    private static volatile boolean capturing;
    /** The player's own name, masked as "Player" in every string drawn while capturing; null when not hidden. */
    private static volatile String hidden;

    static final String MASK = "Player";
    /** The mouse while no tooltip is wanted: far off the window. */
    private static final int AWAY = -10_000;

    /** The colour behind the menu while capturing (black, then white for the same view: ShotCrop). */
    private static volatile int backdrop = 0xFF000000;
    /** The tool's mouse, in GUI pixels. */
    private static int mouseX = AWAY;
    private static int mouseY = AWAY;
    /** The frame being drawn: the window's boxes, the tooltip's, and the named parts (GUI pixels {x0, y0, x1, y1}). */
    private static float[] frameNow;
    private static float[] tipNow;
    private static final Map<String, float[]> PARTS = new HashMap<>();

    static boolean capturing() {
        return capturing;
    }

    static int backdrop() {
        return backdrop;
    }

    static int mouseX() {
        return mouseX;
    }

    static int mouseY() {
        return mouseY;
    }

    static boolean running() {
        return run != null;
    }

    /** The last run, for the gametest: pictures taken, views skipped, how long it took. */
    static volatile int lastCount;
    static volatile int lastSkipped;
    static volatile long lastMillis;
    /** The last run's crops, for the gametest: file → {the picture's box, the window's alone} in framebuffer pixels {x, y, w, h}. */
    static final Map<String, int[][]> RECTS = new java.util.concurrent.ConcurrentHashMap<>();

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

    // ---- what the frame drew (DevTools, the tooltip mixin) ----

    /** The driven menu starts a frame: its boxes are noted afresh (the tick after it reads them). */
    static void beginFrame() {
        frameNow = null;
        tipNow = null;
        PARTS.clear();
    }

    private static float[] box(Matrix3x2f pose, int x, int y, int w, int h) {
        Vector2f a = pose.transformPosition(x, y, new Vector2f());
        Vector2f b = pose.transformPosition(x + w, y + h, new Vector2f());
        return new float[] {Math.min(a.x, b.x), Math.min(a.y, b.y), Math.max(a.x, b.x), Math.max(a.y, b.y)};
    }

    /** A box of the mod's window (the frame with its shadow, the close seal, a side panel): the picture keeps all of them. */
    static void frame(Matrix3x2f pose, int x, int y, int w, int h) {
        if (capturing) {
            frameNow = ShotCrop.union(frameNow, box(pose, x, y, w, h));
        }
    }

    /** A tooltip's background: a tooltip shot's crop takes it in, past the frame too. */
    public static void tooltip(Matrix3x2f pose, int x, int y, int w, int h) {
        if (capturing) {
            tipNow = ShotCrop.union(tipNow, box(pose, x, y, w, h));
        }
    }

    /** A named panel or element: the first of a name in a frame counts (close-ups, hover targets). */
    static void part(String name, Matrix3x2f pose, int x, int y, int w, int h) {
        if (capturing && !PARTS.containsKey(name)) {
            PARTS.put(name, box(pose, x, y, w, h));
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

    /** The words the options take: the languages, all and the tab names, the sizes, hidename. */
    static List<String> words() {
        List<String> out = new ArrayList<>(List.of("en", "tr", "current", "all"));
        for (int t : ShotDriver.railOrder()) {
            out.add(ShotDriver.tabName(t));
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
        for (int t : ShotDriver.railOrder()) {
            tabs.add(ShotDriver.tabName(t));
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
        List<ShotRun.Shot> shots = ShotRun.plan(ShotDriver.railOrder(), ShotDriver.tabNames(), o.only());
        Path base = mc.gameDirectory.toPath().resolve("screenshots").resolve("wynnlabs");
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT));
        Path dir = base.resolve(stamp);
        for (int k = 2; Files.exists(dir); k++) {
            dir = base.resolve(stamp + "_" + k);         // two runs in one second: one folder each
        }
        RECTS.clear();
        Live live = new Live(mc, dir);
        run = new ShotRun(live, shots, o.lang(), o.scale(), o.hideName());
        live.run = run;
        return L.cn("wynnlabs.shots.started", shots.size()).withStyle(ChatFormatting.GRAY);
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

    // ---- the index ----

    /** A page listing every picture of the run with its name, the close-ups under them, to pick from. */
    static String index(String title, List<String> files, List<String> closes) {
        StringBuilder h = new StringBuilder();
        h.append("<!doctype html>\n<meta charset=\"utf-8\">\n<title>").append(title).append("</title>\n<style>\n")
                .append("body{font:14px system-ui,sans-serif;background:#2b2b2b;color:#eee;margin:16px}\n")
                .append("h1{font-size:18px}h2{font-size:15px;margin-top:28px}\n")
                .append(".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:14px}\n")
                .append("figure{margin:0;background:repeating-conic-gradient(#3a3a3a 0 25%,#444 0 50%) 0 0/16px 16px;padding:6px}\n")
                .append("img{width:100%;height:auto;display:block}figcaption{font-size:12px;margin-top:4px;word-break:break-all}\n")
                .append("a{color:#9cf}\n</style>\n<h1>").append(title).append(" (").append(files.size()).append(" + ")
                .append(closes.size()).append(" close-ups)</h1>\n<div class=\"grid\">\n");
        for (String f : files) {
            h.append("<figure><a href=\"").append(f).append("\"><img src=\"").append(f).append("\" loading=\"lazy\"></a><figcaption>")
                    .append(f).append("</figcaption></figure>\n");
        }
        h.append("</div>\n");
        if (!closes.isEmpty()) {
            h.append("<h2>Close-ups</h2>\n<div class=\"grid\">\n");
            for (String f : closes) {
                h.append("<figure><a href=\"").append(f).append("\"><img src=\"").append(f).append("\" loading=\"lazy\"></a><figcaption>")
                        .append(f).append("</figcaption></figure>\n");
            }
            h.append("</div>\n");
        }
        return h.toString();
    }

    // ---- the run's side in the game ----

    /** {@link ShotRun.Stage} in the game: the mod's menu, Minecraft's screenshot, the settings. */
    private static final class Live implements ShotRun.Stage {
        private final Minecraft mc;
        private final Path dir;
        private final long started = System.currentTimeMillis();
        private final AtomicInteger pending = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        /** Whether settings.json had no language pick / menu scale: put back as absent. */
        private final boolean langUnset;
        private final boolean scaleUnset;

        private ShotRun run;
        private RoadmapScreen screen;
        private ShotDriver driver;
        private Screen prev;
        private boolean hudWas;

        /** The picture being taken: its crop boxes in framebuffer pixels, and its frame over black. */
        private int[] rect;
        private Map<String, int[]> closeRects = Map.of();
        private CompletableFuture<NativeImage> overBlack;

        Live(Minecraft mc, Path dir) {
            this.mc = mc;
            this.dir = dir;
            this.langUnset = Settings.number("langOverride", -1) == -1;
            this.scaleUnset = Settings.number("menuScale", -1) == -1;
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
                mouseX = AWAY;
                mouseY = AWAY;
                capturing = true;
            } else {
                capturing = false;
                hidden = null;
                backdrop = 0xFF000000;
                mouseX = AWAY;
                mouseY = AWAY;
                beginFrame();
                mc.options.hideGui = hudWas;
            }
        }

        @Override
        public void open() {
            Screen cur = mc.screen;
            prev = cur instanceof ChatScreen ? null : cur;   // the chat the command was typed in closes anyway
            screen = new RoadmapScreen();
            driver = new ShotDriver(screen);
            mc.setScreen(screen);
        }

        @Override
        public boolean showing() {
            return mc.player != null && screen != null && mc.screen == screen;
        }

        @Override
        public void select(int tab) {
            mouseX = AWAY;
            mouseY = AWAY;
            driver.select(tab);
        }

        @Override
        public boolean act(String step) {
            return driver.act(step);
        }

        @Override
        public boolean hover(String part) {
            if (part == null) {
                mouseX = AWAY;
                mouseY = AWAY;
                return true;
            }
            float[] r = driver.target(part, PARTS);
            if (r == null || r[2] - r[0] < 1 || r[3] - r[1] < 1) {
                return false;
            }
            mouseX = (int) Math.floor((r[0] + r[2]) / 2);
            mouseY = (int) Math.floor((r[1] + r[3]) / 2);
            return true;
        }

        @Override
        public int passes() {
            return 2;
        }

        @Override
        public void backdrop(int pass) {
            backdrop = pass == 0 ? 0xFF000000 : 0xFFFFFFFF;
        }

        @Override
        public void capture(String file, int pass, Map<String, String> closes) {
            double scale = mc.getWindow().getGuiScale();
            if (pass == 0) {
                float[] gui = frameNow;
                if (gui == null) {
                    throw new IllegalStateException("the menu drew no window");
                }
                // a tooltip past the frame is part of the picture
                rect = ShotCrop.pixels(ShotCrop.union(gui, tipNow), scale);
                RECTS.put(file, new int[][] {rect, ShotCrop.pixels(gui, scale)});
                Map<String, int[]> parts = new LinkedHashMap<>();
                for (Map.Entry<String, String> c : closes.entrySet()) {
                    float[] p = PARTS.get(c.getKey());
                    if (p != null) {
                        parts.put(c.getValue(), ShotCrop.pixels(p, scale));
                    }
                }
                closeRects = parts;
                overBlack = grab();
                return;
            }
            Path p = dir.resolve(file);
            try {
                Files.createDirectories(dir.resolve("close"));
            } catch (java.io.IOException e) {
                throw new IllegalStateException("cannot create " + dir, e);
            }
            int[] box = rect;
            Map<String, int[]> parts = closeRects;
            CompletableFuture<NativeImage> black = overBlack;
            CompletableFuture<NativeImage> white = grab();
            pending.incrementAndGet();
            // both frames read back: the window alone and its close-ups, on transparency, written off the client thread
            black.thenAcceptBothAsync(white, (b, w) -> {
                try (b; w) {
                    write(b, w, box, p);
                    for (Map.Entry<String, int[]> c : parts.entrySet()) {
                        write(b, w, c.getValue(), dir.resolve(c.getKey()));
                    }
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

        /** The box of the two frames with a transparent margin, each pixel with its own alpha (ShotCrop), as a PNG. */
        private static void write(NativeImage black, NativeImage white, int[] box, Path to) throws java.io.IOException {
            int m = ShotCrop.MARGIN;
            int[] px = ShotCrop.compose(black::getPixel, white::getPixel, black.getWidth(), black.getHeight(), box, m);
            int ow = box[2] + 2 * m;
            int oh = box[3] + 2 * m;
            try (NativeImage out = new NativeImage(ow, oh, true)) {
                for (int y = 0; y < oh; y++) {
                    for (int x = 0; x < ow; x++) {
                        out.setPixel(x, y, px[y * ow + x]);
                    }
                }
                out.writeToFile(to);
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
            if (driver != null) {
                driver.restore();
            }
        }

        @Override
        public void done(int count, ShotRun.Stop stop, String detail) {
            int saved = Math.max(0, count - failed.get());
            writeIndex();
            lastCount = saved;
            lastSkipped = run == null ? 0 : run.skipped();
            lastMillis = System.currentTimeMillis() - started;
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
            System.out.println("[WynnLabs] screenshots: " + saved + " in " + dir.toAbsolutePath() + " in "
                    + (System.currentTimeMillis() - started) / 1000 + " s"
                    + (stop == null ? "" : " (stopped: " + stop + (detail == null ? "" : ", " + detail) + ")"));
        }

        /** index.html in the run's folder: every picture written so far, then the close-ups. */
        private void writeIndex() {
            if (!Files.isDirectory(dir)) {
                return;
            }
            try {
                List<String> files = list(dir, "");
                List<String> closes = list(dir.resolve("close"), "close/");
                Files.writeString(dir.resolve("index.html"), index("Wynn Labs " + dir.getFileName(), files, closes),
                        StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                System.out.println("[WynnLabs] screenshots: could not write the index: " + e);
            }
        }

        private static List<String> list(Path d, String prefix) throws java.io.IOException {
            if (!Files.isDirectory(d)) {
                return List.of();
            }
            try (Stream<Path> s = Files.list(d)) {
                return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".png")).sorted().map(n -> prefix + n).toList();
            }
        }
    }
}
