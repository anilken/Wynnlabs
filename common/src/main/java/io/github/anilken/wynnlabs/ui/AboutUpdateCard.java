package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.UpdateCheck;
import io.github.anilken.wynnlabs.core.UpdateDownload;
import io.github.anilken.wynnlabs.core.UpdateInstall;
import io.github.anilken.wynnlabs.core.UpdatePrefs;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The About page's update card: up to date, a newer release (Update, Changelog, Ignore this
 * version), downloading with its bar, ready to install on exit (Quit now), a failed install (Open
 * updates folder), ignored, a development build. Its buttons click through {@code #upd:} ids
 * that {@link SettingsTab} hands to {@link #click}.
 */
final class AboutUpdateCard extends MenuPaint {

    AboutUpdateCard(RoadmapScreen menu) {
        super(menu);
    }

    /** Draws the card at {@code x, y}, {@code w} wide; returns its height. */
    int draw(GuiGraphics g, int x, int y, int w, int mx, int my) {
        UpdateCheck.State s = UpdateCheck.state();
        String mine = UpdateScreen.vName(s.own().isEmpty() ? UpdateCheck.version.get() : s.own());
        String loader = UpdateScreen.loaderLabel();
        boolean newer = s.status() == UpdateCheck.Status.NEWER;
        boolean mineDl = newer && s.latest() != null && s.latest().equals(UpdateDownload.tag());
        UpdateDownload.Phase phase = mineDl ? UpdateDownload.phase() : UpdateDownload.Phase.IDLE;
        String failedInstall = UpdateInstall.failedTag();
        String title;
        String sub;
        String icon;
        int iconColour;
        int subColour = Theme.INK_2;
        List<String[]> buttons = new ArrayList<>();     // {label, action}; the first is the main one
        boolean bar = false;
        if (failedInstall != null && phase != UpdateDownload.Phase.DOWNLOADING && phase != UpdateDownload.Phase.READY) {
            title = L.t("wynnlabs.about.install_failed_title", failedInstall);
            sub = L.t("wynnlabs.about.install_failed_sub");
            icon = "alert-triangle";
            iconColour = Theme.BAD;
            buttons.add(new String[] {L.t("wynnlabs.about.open_folder"), "#upd:folder"});
            if (newer && s.asset() != null) {
                buttons.add(new String[] {L.t("wynnlabs.update.update"), "#upd:get"});
            }
        } else if (phase == UpdateDownload.Phase.DOWNLOADING) {
            title = L.t("wynnlabs.about.downloading_title", s.latest());
            sub = "";
            icon = "refresh";
            iconColour = Theme.INK_3;
            bar = true;
        } else if (phase == UpdateDownload.Phase.READY) {
            title = L.t("wynnlabs.about.ready_title", s.latest());
            sub = L.t("wynnlabs.about.ready_sub", mine);
            icon = "check";
            iconColour = Theme.GOOD;
            buttons.add(new String[] {L.t("wynnlabs.about.quit_now"), "#upd:quit"});
        } else if (newer) {
            title = L.t("wynnlabs.about.newer_title", s.latest());
            boolean ignored = UpdatePrefs.ignoredVersion(s.latest());
            sub = phase == UpdateDownload.Phase.FAILED ? UpdateCheck.failText(UpdateDownload.failure())
                    : ignored ? L.t("wynnlabs.about.ignored_sub", mine)
                    : s.asset() == null ? L.t("wynnlabs.about.no_fit_sub", mine)
                    : s.published() > 0 ? L.t("wynnlabs.about.newer_sub", mine, ago(s.published()))
                    : L.t("wynnlabs.about.newer_sub_nodate", mine);
            if (phase == UpdateDownload.Phase.FAILED) {
                subColour = Theme.BAD;
            }
            icon = "arrow-up";
            iconColour = Theme.WARN;
            buttons.add(s.asset() != null ? new String[] {L.t("wynnlabs.update.update"), "#upd:get"}
                    : new String[] {L.t("wynnlabs.about.open_github"), "#upd:open"});
            buttons.add(new String[] {L.t("wynnlabs.update.changelog"), "#upd:notes"});
            if (!ignored) {
                buttons.add(new String[] {L.t("wynnlabs.update.ignore_version"), "#upd:ignore"});
            }
            if (s.asset() != null) {
                buttons.add(new String[] {L.t("wynnlabs.about.open_github"), "#upd:open"});
            }
        } else {
            switch (s.status()) {
                case UP_TO_DATE -> {
                    title = L.t("wynnlabs.about.uptodate_title");
                    sub = L.t("wynnlabs.about.uptodate_sub", mine, loader, ago(s.checkedAt()));
                    icon = "check";
                    iconColour = Theme.GOOD;
                    buttons.add(new String[] {L.t("wynnlabs.about.check_again"), "#upd:check"});
                }
                case FAILED -> {
                    title = L.t("wynnlabs.about.failed_title");
                    sub = L.t(switch (s.reason()) {
                        case OFFLINE -> "wynnlabs.about.reason_offline";
                        case OFF -> "wynnlabs.about.reason_off";
                        case DEV -> "wynnlabs.about.reason_dev";
                        default -> "wynnlabs.about.reason_no_answer";
                    });
                    icon = "alert-triangle";
                    iconColour = Theme.BAD;
                    if (s.reason() == UpdateCheck.Reason.OFF) {
                        buttons.add(new String[] {L.t("wynnlabs.about.go_setting"), "#upd:setting"});
                    } else if (s.reason() != UpdateCheck.Reason.DEV) {
                        buttons.add(new String[] {L.t("wynnlabs.about.try_again"), "#upd:check"});
                    }
                }
                case CHECKING -> {
                    title = L.t("wynnlabs.about.checking_title");
                    sub = L.t("wynnlabs.about.idle_sub", mine, loader);
                    icon = "refresh";
                    iconColour = Theme.INK_3;
                }
                default -> {
                    title = L.t("wynnlabs.about.idle_title");
                    sub = L.t("wynnlabs.about.idle_sub", mine, loader);
                    icon = "clock";
                    iconColour = Theme.INK_3;
                    buttons.add(new String[] {L.t("wynnlabs.about.check_now"), "#upd:check"});
                }
            }
        }
        // one button: on the right, as the card has always had; several (or the bar): a row under the text
        boolean row = buttons.size() > 1 || bar;
        int tx = x + 26;
        int bx = x + w - 8;
        int cardH = row ? 58 : 40;
        boolean lit = newer || phase == UpdateDownload.Phase.READY;
        Kit.card(g, x, y, w, cardH, lit ? Theme.GOLD_DEEP : 0, lit);
        if (lit) {
            Kit.outline(g, x - 1, y - 1, w + 2, cardH + 2, Theme.GOLD_DEEP);
        }
        Art.icon(g, icon, x + 8, y + 9, 12, Theme.paperRgb(iconColour));
        if (!row && !buttons.isEmpty()) {
            bx -= cardButton(g, buttons.get(0), true, newer, bx, y + (cardH - 14) / 2, mx, my, true, s.url());
        }
        int room = bx - tx - 8;
        ink(g, Component.literal(fit(title, room - 6)).withStyle(UiFont::bold), tx, y + 8, Theme.INK);
        List<String> subLines = sub.isEmpty() ? List.of() : wrapText(sub, room, row ? 1 : 2);
        for (int i = 0; i < subLines.size(); i++) {
            ink(g, subLines.get(i), tx, y + 20 + i * 9, subColour);
        }
        if (bar) {
            double f = UpdateDownload.progress();
            String pct = L.t("wynnlabs.box.pct", (int) Math.round(f * 100));
            int pw = font().width(pct) + 6;
            Kit.tealBar(g, tx, y + 38, room - pw, 7, f, true);
            ink(g, pct, tx + room - pw + 6, y + 37, Theme.INK_2);
        } else if (row) {
            int rx = tx;
            for (int i = 0; i < buttons.size(); i++) {
                String[] b = buttons.get(i);
                int bw = font().width(b[0]) + 14 + ("#upd:open".equals(b[1]) ? 11 : 0);
                if (rx + bw > x + w - 8) {
                    break;                      // no room: the rest stay in the update window
                }
                rx += cardButton(g, b, i == 0, true, rx, y + 36, mx, my, false, s.url()) + 4;
            }
        }
        return cardH;
    }

    /** One button; {@code alignRight}: {@code x} is its right edge. Returns its width. */
    private int cardButton(GuiGraphics g, String[] b, boolean main, boolean litCard, int x, int y, int mx, int my,
                           boolean alignRight, String url) {
        boolean linkOut = "#upd:open".equals(b[1]);
        int bw = font().width(b[0]) + 14 + (linkOut ? 11 : 0);
        int bx = alignRight ? x - bw : x;
        boolean bh = mx >= bx && mx < bx + bw && my >= y && my < y + 14;
        int st = main && litCard ? Kit.ON : bh ? Kit.HOVER : Kit.REST;
        Kit.button(g, bx, y, bw, 14, st);
        if (linkOut) {
            Art.icon(g, "arrow-up-right", bx + 6, y + 3, 8, Kit.buttonText(st));
        }
        g.drawString(font(), b[0], bx + 7 + (linkOut ? 11 : 0), y + 3, Kit.buttonText(st), st != Kit.ON);
        menu.toggles.add(new Object[] {new int[] {bx, y, bw, 14}, b[1]});
        if (linkOut && url != null) {
            menu.hits.add(new MenuHit(bx, y, bw, 14, null, List.of(L.c("wynnlabs.about.link_tip", url)), null));
        }
        return bw;
    }

    /** A click on one of the card's buttons; false for ids it does not own ({@code #upd:setting}). */
    boolean click(String id) {
        UpdateCheck.State s = UpdateCheck.state();
        switch (id) {
            case "#upd:check" -> {
                if (!UpdateCheck.checkNow() && UpdateCheck.state().status() != UpdateCheck.Status.FAILED) {
                    menu.flash(L.t("wynnlabs.about.wait"));
                }
            }
            case "#upd:open" -> openLink(s.url());
            case "#upd:get" -> {
                if (s.asset() != null && !UpdateDownload.start(s.asset(), s.latest(), false)
                        && UpdateDownload.phase() == UpdateDownload.Phase.FAILED) {
                    menu.flash(UpdateCheck.failText(UpdateDownload.failure()));
                }
            }
            case "#upd:notes" -> net.minecraft.client.Minecraft.getInstance().setScreen(new UpdateScreen(menu, true));
            case "#upd:ignore" -> UpdatePrefs.ignore(s.latest());
            case "#upd:quit" -> net.minecraft.client.Minecraft.getInstance().stop();
            case "#upd:folder" -> UpdateInstall.openFolder();
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Opens a GitHub page in the browser after the game's "open this link?" prompt; anything else is refused. */
    void openLink(String url) {
        if (url == null || !url.startsWith("https://github.com/")) {
            return;
        }
        net.minecraft.client.gui.screens.ConfirmLinkScreen.confirmLinkNow(menu, java.net.URI.create(url), true);
    }
}
