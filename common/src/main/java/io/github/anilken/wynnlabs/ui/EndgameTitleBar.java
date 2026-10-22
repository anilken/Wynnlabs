package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.core.L;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/** The Endgame tab's title line: the Summary / Pools switch and the session and streak pills. */
final class EndgameTitleBar extends EndgamePanel {

    EndgameTitleBar(EndgameTab tab) {
        super(tab);
    }

    /**
     * The title line's right: the session and streak pills, as on Today, and before them the
     * Summary / Pools switch (decision 11). The streak pill gives way when the switch would reach
     * the title.
     */
    void pills(GuiGraphics g, int x, int titleY, int w, boolean compact, int mx, int my) {
        int py = titleY - 2;
        String[] chips = {L.t("wynnlabs.endgame.view_summary"), L.t("wynnlabs.endgame.view_pools")};
        int chipsW = Kit.Ui.btnW(tab.font, chips[0]) + 2 + Kit.Ui.btnW(tab.font, chips[1]);
        int titleEnd = x + tab.font.width(L.c("wynnlabs.screen.tab_endgame").withStyle(UiFont::bold)) + 8;
        String st = L.t("wynnlabs.endgame.pill_streak", tab.d.streak());
        int tw = tab.font.width(st) + 22;
        String session = L.t(compact ? "wynnlabs.endgame.pill_session_short" : "wynnlabs.endgame.pill_session",
                tab.d.sessionDone(), tab.d.sessionFailed());
        int sw = tab.font.width(session) + 22;
        boolean streak = tab.d.raidsUp() && tab.d.streak() > 0;
        // from the right: the streak, then the session before it, then the switch
        int px = x + w - 3 + 6;
        if (streak && px - 6 - tw - 6 - sw - 6 - chipsW < titleEnd) {
            streak = false;
        }
        if (streak) {
            px -= 6 + tw;
            pill(g, px, py, "flame", st, Theme.WARN);
            tab.tips.add(new Tip(px, py, tw, 13, List.of(title(L.n("wynnlabs.endgame.tip_streak_head", tab.d.streak()), ChatFormatting.GOLD),
                    grey(L.t("wynnlabs.endgame.tip_streak")))));
        }
        if (tab.d.raidsUp()) {
            px -= 6 + sw;
            pill(g, px, py, "clock", session, Theme.GOOD);
            tab.tips.add(new Tip(px, py, sw, 13, List.of(title(L.t("wynnlabs.endgame.tip_session_head"), ChatFormatting.GREEN),
                    grey(L.t("wynnlabs.endgame.tip_session", tab.d.sessionDone(), tab.d.sessionFailed())),
                    grey(L.t("wynnlabs.endgame.tip_session_since")))));
            px -= 4;
        } else {
            px -= 9;
        }
        int cx = px - chipsW;
        for (int i = 0; i < 2; i++) {
            int bw = Kit.Ui.btnW(tab.font, chips[i]);
            boolean on = tab.view == i;
            boolean hover = mx >= cx && mx < cx + bw && my >= py && my < py + 13;
            int state = on ? Kit.ON : hover ? Kit.HOVER : Kit.REST;
            Kit.button(g, cx, py, bw, 13, state);
            g.drawString(tab.font, chips[i], cx + Kit.Ui.BTN_PAD, py + 3, Kit.buttonText(state), state != Kit.ON);
            int v = i;
            tab.acts.add(new Act(cx, py, bw, 13, () -> tab.view = v, "endgame:view" + i));
            cx += bw + 2;
        }
    }

    private void pill(GuiGraphics g, int x, int y, String icon, String text, int colour) {
        int w = tab.font.width(text) + 22;
        Kit.pillLine(g, x, y, w, 13, colour);
        Art.icon(g, icon, x + 6, y + 3, 8, colour);
        Kit.text(g, tab.font, text, x + 16, y + 3, colour);
    }
}
