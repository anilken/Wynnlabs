package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.api.AiLink;
import io.github.anilken.wynnlabs.api.WynnLabs;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Settings;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** The AI assistant tab: the local API's switches, its state, address, skill and endpoints. */
final class AiTab extends MenuTab {

    AiTab(RoadmapScreen menu) {
        super(menu);
    }

    // ---- the tab ----

    @Override
    void draw(GuiGraphics g, int x, int y, int w, int h, int mx, int my, float pt) {
        drawAiTab(g, x, y, w, h, mx, my);
    }

    /** Its switches and buttons are the settings' kind of click ({@link SettingsTab#toggleClicked}). */
    @Override
    boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return menu.settings.toggleClicked(event, doubleClick);
    }

    @Override
    boolean sharedClicks() {
        return false;
    }

    private int rowPitch = SettingsTab.SET_ROW;

    /** A switch with its description (the AI tab's rows); the whole row is clickable. */
    private void drawSettingRow(GuiGraphics g, Object[] row, int x, int y, int w, int mx, int my) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + rowPitch - 2;
        if (hover) {
            g.fill(x - 3, y - 1, x + w, y + rowPitch - 3, Theme.CARD_HOVER);
        }
        String k = (String) row[1];
        boolean on = Settings.on(k);
        pill(g, x, y + 9, on);
        String title = L.t(Settings.LABELS.get(k));
        String desc = L.t(Settings.DESC.get(k));
        ink(g, fit(title, w - 26), x + 26, y + 5, on ? 0xFFE8E6DF : MUTED);
        // a taller row (the AI tab's) has room for the description's second line
        descWrap(g, desc, x + 26, y + 17, w - 26, rowPitch > SettingsTab.SET_ROW ? 2 : 1);
        menu.toggles.add(new Object[] {new int[] {x, y, w, rowPitch - 2}, k});
        if (hover) {
            rowTip(x, y, w, rowPitch - 2, title, desc);
        }
    }

    /**
     * The AI assistant panel: the API's state (a dot, the last request, today's count), its
     * address with copy and the port with −/+, the skill buttons, and the endpoint list.
     */
    private void drawAiPanel(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        // status card
        String st = AiLink.state;
        int dot = st.equals("listening") ? 0xFF5DCAA5 : st.equals("busy") ? 0xFFE24B4A : 0xFF6E6C78;
        // compact: one line, the state and then its note, to leave the endpoints some room
        boolean slim = menu.layout.compact();
        Kit.card(g, x, y, w, slim ? 18 : 34, 0);
        Art.box(g, x + 8, y + 6, 7, 7, 3, dot, 0);
        String head = st.equals("listening") ? L.t("wynnlabs.ai.listening")
                : st.equals("busy") ? L.t("wynnlabs.ai.busy", AiLink.port) : L.t("wynnlabs.ai.off");
        String headCut = fit(head, w - 30);
        ink(g, Component.literal(headCut).withStyle(UiFont::bold), x + 20, y + 5, 0xFFFFFFFF, false);
        String sub;
        if (st.equals("listening")) {
            sub = AiLink.lastAt() == 0 ? L.t("wynnlabs.ai.no_requests")
                    : L.t("wynnlabs.ai.last_request", ago(AiLink.lastAt()), AiLink.lastPath(), AiLink.today());
        } else if (st.equals("busy")) {
            sub = L.t("wynnlabs.ai.busy_hint");
        } else {
            sub = L.t("wynnlabs.ai.off_hint");
        }
        int ry;
        if (slim) {
            int hw = font().width(Component.literal(headCut).withStyle(UiFont::bold)) + 8;
            desc(g, sub, x + 20 + hw, y + 6, w - 28 - hw);
            ry = y + 23;
        } else {
            desc(g, sub, x + 20, y + 19, w - 28);
            ry = y + 40;
        }
        // address and port
        String url = AiLink.url();
        ink(g, url, x + 2, ry + 3, 0xFF85B7EB, false);
        int bx = x + 8 + font().width(url);
        String copy = L.t("wynnlabs.ai.copy");
        int cw = font().width(copy) + 14;
        boolean ch = mx >= bx && mx < bx + cw && my >= ry && my < ry + 13;
        Kit.button(g, bx, ry, cw, 13, ch ? Kit.HOVER : Kit.REST);
        onWood(g, copy, bx + 7, ry + 3, ch ? Kit.HOVER : Kit.REST);
        menu.toggles.add(new Object[] {new int[] {bx, ry, cw, 13}, "#aicopy"});
        String portLabel = L.t("wynnlabs.ai.port", AiLink.port);
        int pw = font().width(portLabel);
        int px = x + w - (pw + 40);
        stepButton(g, px, ry, "−", mx, my, "#aiport:-1");
        ink(g, portLabel, px + 18, ry + 3, 0xFFFAC775, false);
        stepButton(g, px + 22 + pw, ry, "+", mx, my, "#aiport:1");
        ry += 20;
        // the skill
        String[][] btns = {{L.t("wynnlabs.ai.export"), "#aiexport"}, {L.t("wynnlabs.ai.install"), "#aiinstall"},
            {L.t("wynnlabs.ai.prompt"), "#aiprompt"}};
        int sx = x;
        for (String[] b : btns) {
            int bw = font().width(b[0]) + 14;
            boolean hov = mx >= sx && mx < sx + bw && my >= ry && my < ry + 15;
            Kit.button(g, sx, ry, bw, 15, hov ? Kit.ON : Kit.REST);
            onWood(g, b[0], sx + 7, ry + 4, hov ? Kit.ON : Kit.REST);
            menu.toggles.add(new Object[] {new int[] {sx, ry, bw, 15}, b[1]});
            sx += bw + 6;
        }
        if (aiNote != null) {
            desc(g, aiNote, x, ry + 19, w);
        }
        ry += slim && aiNote == null ? 21 : 32;
        // endpoints, two columns when they do not fit
        ink(g, L.t("wynnlabs.ai.endpoints"), x, ry, FAINT, false);
        ry += Kit.Ui.LINE;
        int rowH = Kit.Ui.LINE_DENSE;
        int fitRows = Math.max(1, (y + h - ry) / rowH);
        int all = AiLink.ENDPOINTS.size();
        // as many columns of at least 240px as the width holds (up to 3), two when one would not fit
        int cols = Math.max(all > fitRows ? 2 : 1, Math.min(3, w / 240));
        int per = (all + cols - 1) / cols;
        // what still does not fit: the last column's last row says how many are left out
        int shown = all;
        if (per > fitRows) {
            per = fitRows;
            shown = per * cols - 1;
        }
        int colW = (w - (cols - 1) * 12) / cols;
        for (int i = 0; i < shown; i++) {
            AiLink.Endpoint e = AiLink.ENDPOINTS.get(i);
            int ex = x + (i / per) * (colW + 12);
            int ey = ry + (i % per) * rowH;
            ink(g, e.path(), ex, ey, 0xFF85B7EB, false);
            int pwid = font().width(e.path()) + 6;
            desc(g, L.t(e.key()), ex + pwid, ey + 1, colW - pwid);
        }
        if (shown < all) {
            int ex = x + (cols - 1) * (colW + 12);
            int ey = ry + (per - 1) * rowH;
            String more = L.t("wynnlabs.ai.more", all - shown);
            ink(g, more, ex, ey, FAINT, false);
            List<Component> tip = new ArrayList<>();
            for (int i = shown; i < all; i++) {
                AiLink.Endpoint e = AiLink.ENDPOINTS.get(i);
                tip.add(Component.literal(e.path() + "  ").withStyle(ChatFormatting.AQUA)
                        .append(Component.literal(L.t(e.key())).withStyle(ChatFormatting.GRAY)));
            }
            menu.hits.add(new MenuHit(ex, ey - 1, font().width(more), rowH, null, tip, null));
        }
    }

    private String aiNote;

    /** The AI assistant tab: the API and write switches, then status, address, skill and endpoints. */
    private void drawAiTab(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        // two switches side by side, their descriptions on two lines rather than cut
        rowPitch = SettingsTab.SET_ROW + 9;
        int colW = (w - 18) / 2;
        drawSettingRow(g, new Object[] {"switch", "localApi"}, x, y, colW, mx, my);
        drawSettingRow(g, new Object[] {"switch", "apiWrite"}, x + colW + 18, y, colW, mx, my);
        int top = rowPitch + (menu.layout.compact() ? 0 : 6);
        rowPitch = SettingsTab.SET_ROW;
        drawAiPanel(g, x, y + top, w, h - top, mx, my);
    }

    void aiClick(String id) {
        try {
            switch (id) {
                case "#aicopy" -> {
                    Minecraft.getInstance().keyboardHandler.setClipboard(AiLink.url());
                    aiNote = L.t("wynnlabs.ai.copied_url");
                }
                case "#aiexport" -> aiNote = L.t("wynnlabs.ai.exported", AiLink.export());
                case "#aiinstall" -> aiNote = L.t("wynnlabs.ai.installed", AiLink.installClaude());
                case "#aiprompt" -> {
                    Minecraft.getInstance().keyboardHandler.setClipboard(AiLink.prompt());
                    aiNote = L.t("wynnlabs.ai.copied_prompt");
                }
                default -> {
                    if (id.startsWith("#aiport:")) {
                        int p = Math.max(1024, Math.min(65535, Settings.number("localApiPort", 47591)
                                + Integer.parseInt(id.substring(8))));
                        Settings.setNumber("localApiPort", p);
                        WynnLabs.syncServer();
                        aiNote = null;
                    }
                }
            }
        } catch (Exception e) {
            aiNote = L.t("wynnlabs.ai.failed", e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }
}
