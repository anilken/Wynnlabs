package io.github.anilken.wynnlabs.ui;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Platform;
import io.github.anilken.wynnlabs.core.ReleaseNotes;
import io.github.anilken.wynnlabs.core.UpdateCheck;
import io.github.anilken.wynnlabs.core.UpdateDownload;
import io.github.anilken.wynnlabs.core.UpdatePrefs;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * The update window, opened from the chat line's [Update] or the About page: the new and the
 * current version, a short summary or the release notes (scrollable), the download's progress
 * and the actions Update, Update &amp; exit, Ignore now, Ignore this version, Changelog and Back.
 * Nothing here talks to the game server; the download is GitHub only ({@link UpdateDownload}).
 */
public final class UpdateScreen extends Screen {

    private final Screen parent;
    private boolean changelog;
    private int scroll;
    /** Update &amp; exit was pressed: the game closes once the jar is ready. */
    private boolean exitWhenReady;
    private List<ReleaseNotes.Line> notes;
    private String notesFor;

    /** This frame's click areas and what a click does. */
    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private final List<Hit> hits = new ArrayList<>();
    private int[] closeRect;
    private int bodyTop;
    private int bodyH;

    public UpdateScreen(Screen parent, boolean changelog) {
        super(L.c("wynnlabs.update.screen_title"));
        this.parent = parent;
        this.changelog = changelog;
    }

    /**
     * On this client only: {@code /wynnlabs-update} (the chat line's [Update]) downloads at once
     * and says how it went in chat; {@code /wynnlabs-update details} ([Details]) opens this window.
     * With no newer release known, both only say so.
     */
    public static <S> LiteralArgumentBuilder<S> command(BiConsumer<S, Component> feedback) {
        return LiteralArgumentBuilder.<S>literal("wynnlabs-update")
                .executes(c -> {
                    UpdateCheck.updateFromChat(line -> feedback.accept(c.getSource(), line));
                    return 1;
                })
                .then(LiteralArgumentBuilder.<S>literal("details").executes(c -> {
                    if (UpdateCheck.state().status() != UpdateCheck.Status.NEWER) {
                        feedback.accept(c.getSource(), L.c("wynnlabs.update.none_known").withStyle(ChatFormatting.GRAY));
                        return 0;
                    }
                    Minecraft mc = Minecraft.getInstance();
                    mc.schedule(() -> mc.setScreen(new UpdateScreen(mc.screen, false)));   // after the chat box closed
                    return 1;
                }));
    }

    @Override
    protected void init() {
        UiFont.apply(this);
        super.init();
    }

    @Override
    public void tick() {
        UpdateDownload.Phase p = UpdateDownload.phase();
        if (exitWhenReady && p == UpdateDownload.Phase.READY) {
            exitWhenReady = false;
            minecraft.stop();                   // the install runs while the game closes
        } else if (exitWhenReady && p == UpdateDownload.Phase.FAILED) {
            exitWhenReady = false;
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        MenuScale.render(this, g, mx, my, (x, y) -> draw(g, x, y, pt));
    }

    private void draw(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        hits.clear();
        UpdateCheck.State s = UpdateCheck.state();
        int w = Math.min(width - 20, 340);
        int h = Math.min(height - 20, 210);
        int x = (width - w) / 2;
        int y = (height - h) / 2;
        Kit.window(g, x - 5, y - 5, w + 10, h + 10);
        Kit.paper(g, x, y, w, h);
        int[] cr = closeRect;
        closeRect = Kit.closeOutside(g, width, x, y, w, cr != null && in(mx, my, cr));
        int in = x + 10;
        int iw = w - 20;
        boolean newer = s.status() == UpdateCheck.Status.NEWER;
        String mine = vName(s.own());
        String title = newer ? L.t("wynnlabs.update.screen_newer", s.latest()) : L.t("wynnlabs.about.uptodate_title");
        Kit.text(g, font, Component.literal(fit(title, iw)).withStyle(UiFont::bold), in, y + 12, Theme.paperRgb(Theme.INK));
        String sub = s.published() > 0 ? L.t("wynnlabs.about.newer_sub", mine, MenuPaint.ago(s.published()))
                : L.t("wynnlabs.about.newer_sub_nodate", mine);
        ink(g, fit(sub, iw), in, y + 24, Theme.INK_2);
        g.fill(in, y + 36, in + iw, y + 37, Theme.LINE);

        // the buttons first (they may wrap), so the body knows how much room is left
        List<Object[]> acts = actions(s, newer);
        int rows = rowsNeeded(acts, iw);
        int buttonsY = y + h - 10 - rows * 18 + 4;
        int statusY = buttonsY - 16;
        bodyTop = y + 42;
        bodyH = statusY - 4 - bodyTop;
        if (changelog && newer) {
            drawNotes(g, s, in, bodyTop, iw, bodyH);
        } else {
            drawSummary(g, s, newer, in, bodyTop, iw);
        }
        drawStatus(g, s, in, statusY, iw);
        drawButtons(g, acts, in, buttonsY, iw, mx, my);
    }

    /** What pressing Update will do, or why no download fits. */
    private void drawSummary(GuiGraphics g, UpdateCheck.State s, boolean newer, int x, int y, int w) {
        String text;
        if (!newer) {
            text = L.t("wynnlabs.update.nothing_new");
        } else if (s.asset() == null) {
            text = L.t("wynnlabs.update.no_fit", loaderLabel());
        } else {
            text = L.t("wynnlabs.update.summary", loaderLabel(), String.format(Locale.ROOT, "%.1f", s.asset().size() / 1048576.0));
        }
        int ly = y;
        for (String line : wrap(text, w, 6)) {
            ink(g, line, x, ly, Theme.INK);
            ly += 10;
        }
        if (newer && UpdatePrefs.ignoredVersion(s.latest())) {
            ink(g, fit(L.t("wynnlabs.update.ignored_note"), w), x, ly + 4, Theme.INK_3);
        }
    }

    /** The release notes as plain lines, scrolled by the wheel. */
    private void drawNotes(GuiGraphics g, UpdateCheck.State s, int x, int y, int w, int h) {
        if (notes == null || !s.latest().equals(notesFor)) {
            notes = ReleaseNotes.lines(s.notes());
            notesFor = s.latest();
            scroll = 0;
        }
        List<Component> lines = new ArrayList<>();
        for (ReleaseNotes.Line l : notes) {
            String prefix = l.bullet() ? "• " : "";
            for (String part : wrap(prefix + l.text(), w - 6, 4)) {
                lines.add(l.heading() ? Component.literal(part).withStyle(UiFont::bold) : Component.literal(part));
            }
        }
        if (lines.isEmpty()) {
            ink(g, L.t("wynnlabs.update.no_notes"), x, y, Theme.INK_3);
            return;
        }
        int visible = Math.max(1, h / 10);
        scroll = Math.max(0, Math.min(scroll, lines.size() - visible));
        g.enableScissor(x, y, x + w, y + h);
        for (int i = 0; i < visible && scroll + i < lines.size(); i++) {
            Kit.text(g, font, lines.get(scroll + i), x, y + i * 10, Theme.paperRgb(Theme.INK));
        }
        g.disableScissor();
        if (lines.size() > visible) {
            // a thin scroll mark on the right
            int track = h;
            int thumb = Math.max(8, track * visible / lines.size());
            int ty = y + (track - thumb) * scroll / Math.max(1, lines.size() - visible);
            g.fill(x + w - 2, ty, x + w, ty + thumb, Theme.LINE);
        }
    }

    /** The download's progress, the ready note or why it failed. */
    private void drawStatus(GuiGraphics g, UpdateCheck.State s, int x, int y, int w) {
        if (s.latest() == null || !s.latest().equals(UpdateDownload.tag())) {
            return;
        }
        switch (UpdateDownload.phase()) {
            case DOWNLOADING -> {
                double f = UpdateDownload.progress();
                String pct = L.t("wynnlabs.box.pct", (int) Math.round(f * 100));
                int pw = font.width(pct) + 6;
                Kit.tealBar(g, x, y + 2, w - pw, 7, f, true);
                ink(g, pct, x + w - pw + 6, y + 1, Theme.INK_2);
            }
            case READY -> ink(g, fit(L.t("wynnlabs.update.ready", s.latest()), w), x, y + 1, Theme.GOOD);
            case FAILED -> ink(g, fit(UpdateCheck.failText(UpdateDownload.failure()), w), x, y + 1, Theme.BAD);
            default -> { }
        }
    }

    /** {label, action, primary, enabled} for each button, in order. */
    private List<Object[]> actions(UpdateCheck.State s, boolean newer) {
        List<Object[]> out = new ArrayList<>();
        boolean busy = UpdateDownload.phase() == UpdateDownload.Phase.DOWNLOADING;
        boolean ready = UpdateDownload.phase() == UpdateDownload.Phase.READY && s.latest() != null
                && s.latest().equals(UpdateDownload.tag());
        if (newer && s.asset() != null) {
            out.add(new Object[] {L.t("wynnlabs.update.update"), (Runnable) () -> UpdateDownload.start(s.asset(), s.latest(), false),
                    true, !busy && !ready});
            out.add(new Object[] {L.t("wynnlabs.update.update_exit"), (Runnable) () -> {
                exitWhenReady = true;
                UpdateDownload.start(s.asset(), s.latest(), false);
            }, false, !busy && !exitWhenReady});
        } else if (newer) {
            out.add(new Object[] {L.t("wynnlabs.about.open_github"), (Runnable) () -> openPage(s.url()), true, true});
        }
        if (newer) {
            out.add(new Object[] {L.t("wynnlabs.update.ignore_now"), (Runnable) () -> {
                UpdatePrefs.ignoreNow();
                onClose();
            }, false, true});
            if (!UpdatePrefs.ignoredVersion(s.latest())) {
                out.add(new Object[] {L.t("wynnlabs.update.ignore_version"), (Runnable) () -> {
                    UpdatePrefs.ignore(s.latest());
                    onClose();
                }, false, true});
            }
            out.add(new Object[] {L.t(changelog ? "wynnlabs.update.summary_button" : "wynnlabs.update.changelog"),
                    (Runnable) () -> changelog = !changelog, false, true});
        }
        out.add(new Object[] {L.t("wynnlabs.update.back"), (Runnable) this::onClose, false, true});
        return out;
    }

    private int rowsNeeded(List<Object[]> acts, int w) {
        int rows = 1;
        int bx = 0;
        for (Object[] a : acts) {
            int bw = font.width((String) a[0]) + 14;
            if (bx > 0 && bx + bw > w) {
                rows++;
                bx = 0;
            }
            bx += bw + 4;
        }
        return rows;
    }

    private void drawButtons(GuiGraphics g, List<Object[]> acts, int x, int y, int w, int mx, int my) {
        int bx = x;
        int by = y;
        for (Object[] a : acts) {
            String label = (String) a[0];
            boolean primary = (Boolean) a[2];
            boolean enabled = (Boolean) a[3];
            int bw = font.width(label) + 14;
            if (bx > x && bx + bw > x + w) {
                bx = x;
                by += 18;
            }
            boolean hover = enabled && mx >= bx && mx < bx + bw && my >= by && my < by + 14;
            int st = primary && enabled ? Kit.ON : hover ? Kit.HOVER : Kit.REST;
            Kit.button(g, bx, by, bw, 14, st);
            g.drawString(font, label, bx + 7, by + 3, enabled ? Kit.buttonText(st) : Theme.CREAM_DIM, enabled && st != Kit.ON);
            if (enabled) {
                hits.add(new Hit(bx, by, bw, 14, (Runnable) a[1]));
            }
            bx += bw + 4;
        }
    }

    /** A GitHub page in the browser, after the game's "open this link?" prompt. */
    private void openPage(String url) {
        if (url != null && url.startsWith("https://github.com/")) {
            net.minecraft.client.gui.screens.ConfirmLinkScreen.confirmLinkNow(this, java.net.URI.create(url), true);
        }
    }

    static String vName(String v) {
        if (v == null || v.isBlank()) {
            return "?";
        }
        return Character.isDigit(v.charAt(0)) ? "v" + v : v;
    }

    static String loaderLabel() {
        try {
            String l = Platform.get().loaderName();
            return l.equals("neoforge") ? "NeoForge" : l.equals("fabric") ? "Fabric" : l;
        } catch (Throwable e) {
            return "?";
        }
    }

    // ---- text on paper ----

    private void ink(GuiGraphics g, String s, int x, int y, int colour) {
        Kit.text(g, font, s, x, y, Theme.paperRgb(colour));
    }

    private String fit(String s, int w) {
        if (font.width(s) <= w) {
            return s;
        }
        return font.plainSubstrByWidth(s, Math.max(0, w - font.width("…"))) + "…";
    }

    /** {@code text} in lines of at most {@code w} px, at most {@code max} of them (the last cut with "…"). */
    private List<String> wrap(String text, int w, int max) {
        List<String> out = new ArrayList<>();
        for (var seq : font.getSplitter().splitLines(text, w, net.minecraft.network.chat.Style.EMPTY)) {
            if (out.size() == max) {
                out.set(max - 1, fit(out.get(max - 1) + "…", w));
                break;
            }
            out.add(seq.getString());
        }
        return out;
    }

    private static boolean in(double x, double y, int[] r) {
        return x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
    }

    // ---- input ----

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        event = MenuScale.event(event);
        double ex = event.x();
        double ey = event.y();
        if (closeRect != null && in(ex, ey, closeRect)) {
            onClose();
            return true;
        }
        for (Hit h : List.copyOf(hits)) {
            if (ex >= h.x() && ex < h.x() + h.w() && ey >= h.y() && ey < h.y() + h.h()) {
                h.action().run();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double h, double v) {
        if (changelog) {
            scroll -= (int) Math.signum(v) * 3;
        }
        return true;
    }
}
