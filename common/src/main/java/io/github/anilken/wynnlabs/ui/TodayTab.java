package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.advice.SellSummary;
import io.github.anilken.wynnlabs.coach.Coach;
import io.github.anilken.wynnlabs.coach.Today;
import io.github.anilken.wynnlabs.coach.XpMeter;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Rules;
import io.github.anilken.wynnlabs.game.Professions;
import io.github.anilken.wynnlabs.game.Wyn;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.market.Vault;
import io.github.anilken.wynnlabs.quests.QuestAdvisor;
import io.github.anilken.wynnlabs.quests.QuestBook;
import io.github.anilken.wynnlabs.quests.QuestGuides;
import io.github.anilken.wynnlabs.quests.QuestWiki;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The Today tab: the session, the daily to-do list and the day's numbers. */
final class TodayTab extends MenuTab {

    TodayTab(RoadmapScreen menu) {
        super(menu);
    }

    // ---- the tab ----

    @Override
    void draw(GuiGraphics g, int x, int y, int w, int h, int mx, int my, float pt) {
        drawToday(g, x, y, w, h, mx, my);
    }

    // ---- today tab (Bugün) ----

    /**
     * Today: the reset countdown and this session's XP in the header, a card per character
     * (level, when played, the daily tasks, the daily reward), a framed to-do list with item icons
     * and one-click actions, and the last session in four tiles.
     */
    private void drawToday(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        // header chips, on the title line
        long m = Today.minutesToReset();
        double sr = XpMeter.sessionRate();
        String session = L.t("wynnlabs.today.session_chip", QuestAdvisor.fmt(XpMeter.sessionXp()))
                + (sr > 0 ? L.t("wynnlabs.today.session_rate", QuestAdvisor.fmt(Math.round(sr))) : "");
        String reset = L.t("wynnlabs.today.reset_chip", m / 60, m % 60);
        // the pills' own widths (pillText's), so the last one ends inside the page like the title's rule
        int sw = font().width(session) + 12;
        int rw = font().width(reset) + 22;
        int hy = y - 21;
        if (System.currentTimeMillis() - daysAt > 2000) {     // files: not every frame
            days = Today.all(Minecraft.getInstance());
            daysAt = System.currentTimeMillis();
        }
        String today = Today.utcDate();
        // the daily reward is once per account per day: the newest reading from any character today
        Boolean reward = null;
        long readAt = 0;
        for (Today.Day d : days) {
            if (today.equals(d.date()) && d.reward() != null && d.seen() >= readAt) {
                reward = d.reward();
                readAt = d.seen();
            }
        }
        int px = x + w - 3 - sw;
        pillText(g, px, hy, session, 0xFF97C459, 0xFF1C2A1E);
        px -= 6 + rw;
        pillText(g, px, hy, "clock", reset, 0xFFB4B2A9, 0xFF1A1A23);
        if (reward != null) {
            String rt = reward ? L.t("wynnlabs.today.reward_ready_chip") : L.t("wynnlabs.today.reward_claimed_chip");
            int tw = font().width(rt) + 22;
            px -= 6 + tw;
            pillText(g, px, hy, reward ? "sparkles" : "check", rt, reward ? 0xFF7EE0BC : 0xFF9C9A92, reward ? 0xFF173A2C : 0xFF1A1A23);
        }
        int ry = y;

        // one card per character, the one you play first
        int perRow = Math.max(1, Math.min(4, days.size()));
        int gap = 6;
        int cw = (w - (perRow - 1) * gap) / perRow;
        // compact: a shorter card (name and daily, no "played" line) so the rest of the page fits
        boolean small = menu.layout.compact();
        int ch = small ? 30 : 44;
        int iconS = small ? 20 : 28;
        for (int i = 0; i < days.size(); i++) {
            Today.Day d = days.get(i);
            boolean me = i == 0;
            boolean stale = !today.equals(d.date());
            int cx = x + (i % perRow) * (cw + gap);
            int cy = ry + (i / perRow) * (ch + gap);
            Kit.card(g, cx, cy, cw, ch, 0, me);
            // the class's portrait from the game's character select, the text beside it (none without the pack)
            int tx = cx + 6;
            if (d.cls() != null && WynnTex.classIcon(g, d.cls(), cx + 5, cy + 5, iconS)) {
                tx = cx + 9 + iconS;
            }
            int tw = cx + cw - 6 - tx;
            String lv = "Lv. " + d.level();
            ink(g, Component.literal(fit(nice(d.cls()), tw - 4 - font().width(lv))).withStyle(UiFont::bold),
                    tx, cy + 5, Theme.INK);
            ink(g, lv, cx + cw - 6 - font().width(lv), cy + 5, MUTED);
            if (!small) {
                String when = me ? L.t("wynnlabs.today.card_now") : L.t("wynnlabs.today.card_played", ago(d.seen()));
                ink(g, fit(when, tw), tx, cy + 15, FAINT);
            }
            long done = d.objectives().stream().filter(o -> o.max() > 0 && o.score() >= o.max()).count();
            String dv = stale || d.objectives().isEmpty() ? "—" : done + "/" + d.objectives().size();
            int dy = small ? cy + 15 : cy + 26;
            ink(g, fit(L.t("wynnlabs.today.card_daily"), tw - 18), tx, dy, stale ? FAINT : 0xFFDDDDDD);
            ink(g, dv, cx + cw - 6 - font().width(dv), dy, stale ? FAINT : 0xFFDDDDDD);
            Art.pill(g, tx, dy + 10, cx + cw - 6 - tx, 3, Theme.WELL, 0);
            if (!stale && !d.objectives().isEmpty() && done > 0) {
                Art.pill(g, tx, dy + 10, Math.max(3, (int) ((cx + cw - 6 - tx) * done / d.objectives().size())), 3, Theme.GREEN, 0);
            }
        }
        int cardRows = (days.size() + perRow - 1) / perRow;
        ry += cardRows * (ch + gap) + 4;

        // to do: a framed list, an item icon per line, the action in blue when it is a click
        section(g, L.t("wynnlabs.today.todo"), x, ry, w);
        ry += 11;
        List<Object[]> todo = new ArrayList<>();   // {icon, text, action, command, quest, objective}
        Today.Day mine = days.isEmpty() ? null : days.get(0);
        if (reward == null) {
            todo.add(new Object[] {icon("chest"), L.t("wynnlabs.today.todo_reward_unknown"), null, null, null, null});
        } else if (reward) {
            todo.add(new Object[] {icon("chest"), L.t("wynnlabs.today.todo_reward_ready"), null, null, null, null});
        }
        if (mine != null && today.equals(mine.date())) {
            for (Today.Objective o : mine.objectives()) {
                todo.add(new Object[] {icon("pick"), o.goal(), null, null, null, o});
            }
            if (mine.tickets() > 0) {
                todo.add(new Object[] {icon("paper"), L.n("wynnlabs.today.tickets", mine.tickets()), L.t("wynnlabs.today.go_compass"),
                        "/compass at 437 70 -1613", null, null});
            }
        }
        if (System.currentTimeMillis() - vaultAt > 3000) {
            vaultFinds = Vault.forMe();
            vaultAt = System.currentTimeMillis();
        }
        List<Vault.Group> bankJunk = Vault.junk();
        if (!bankJunk.isEmpty()) {
            long tm = bankJunk.stream().filter(j -> "Trade Market".equals(Vault.place(j))).count();
            todo.add(new Object[] {icon("emerald"), L.n("wynnlabs.today.todo_bank", bankJunk.size())
                    + (tm > 0 ? " (" + tm + " Trade Market)" : ""), L.t("wynnlabs.today.go_bank"), "#qgo:bank", null, null});
        }
        // a bank never opened is one of the home's first steps, not a daily task
        if (!vaultFinds.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Vault.Find f : vaultFinds) {
                names.add(f.item().name() + " +" + RoadmapScreen.pct(Math.round(f.gain())));
            }
            todo.add(new Object[] {icon("diamond"), L.t("wynnlabs.today.todo_vault", String.join(", ", names)),
                    L.t("wynnlabs.today.go_bank"), "#qgo:bank", null, null});
        }
        try {
            SellSummary.Summary ss = SellSummary.compute(Minecraft.getInstance());
            if (ss.blacksmith().size() + ss.market().size() > 0) {
                todo.add(new Object[] {icon("gold"), L.t("wynnlabs.today.todo_bag", ss.blacksmith().size(), ss.market().size())
                        + (ss.marketValue() > 0 ? " (~" + Prices.fmt(ss.marketValue()) + ")" : ""), null, null, null, null});
            }
        } catch (Throwable ignored) {
        }
        if (!menu.quests.isEmpty()) {
            QuestAdvisor.Suggestion q = menu.quests.get(0);
            todo.add(new Object[] {icon("compass"), L.t("wynnlabs.today.todo_quest", QuestGuides.title(q.quest().name())),
                    L.t("wynnlabs.today.track"), null, q.quest(), null});
        }
        int mf = Coach.manaFailsLast5Min();
        if (mf > 0) {
            todo.add(new Object[] {icon("lapis"), L.n("wynnlabs.today.mana_fails", mf), null, null, null, null});
        }
        int rowH = 22;
        int listH = Math.max(1, todo.size()) * rowH;
        Kit.card(g, x, ry, w, listH, 0);
        if (todo.isEmpty()) {
            ink(g, L.t("wynnlabs.today.nothing_todo"), x + 8, ry + 5, FAINT);
        }
        for (int i = 0; i < todo.size(); i++) {
            Object[] t = todo.get(i);
            int ty = ry + i * rowH;
            if (i > 0) {
                g.fill(x + 1, ty, x + w - 1, ty + 1, Theme.LINE);
            }
            boolean click = t[3] != null || t[4] != null;
            boolean hover = click && mx >= x && mx < x + w && my >= ty && my < ty + rowH;
            if (hover) {
                Kit.row(g, x + 2, ty + 1, w - 4, rowH - 2, 0, true);
            }
            String[] ic = (String[]) t[0];
            Art.iconTile(g, ic[0], x + 8, ty + 4, 14, (int) Long.parseLong(ic[1], 16), (int) Long.parseLong(ic[2], 16));
            String action = (String) t[2];
            Today.Objective o = (Today.Objective) t[5];
            int reserve = o != null ? 120 : action == null ? 8 : Kit.Ui.btnW(font(), action) + 12;
            ink(g, fit((String) t[1], w - 24 - reserve), x + 28, ty + 7, 0xFFE8E6DF);
            if (o != null && o.max() > 0) {
                boolean ok = o.score() >= o.max();
                int bx = x + w - 110;
                Art.pill(g, bx, ty + 10, 70, 3, Theme.WELL, 0);
                if (o.score() > 0) {
                    Art.pill(g, bx, ty + 10, Math.max(3, 70 * Math.min(o.score(), o.max()) / o.max()), 3, ok ? Theme.GREEN : Theme.AMBER, 0);
                }
                String sc = o.score() + "/" + o.max();
                ink(g, sc, x + w - 8 - font().width(sc), ty + 7, ok ? Theme.GOOD : MUTED);
            } else if (action != null && click) {
                // a click: a small wooden button, gold under the mouse
                int aw = Kit.Ui.btnW(font(), action);
                int state = hover ? Kit.ON : Kit.REST;
                Kit.button(g, x + w - 6 - aw, ty + 4, aw, Kit.Ui.BTN_H, state);
                onWood(g, action, x + w - 6 - aw + Kit.Ui.BTN_PAD, ty + 7, state);
            } else if (action != null) {
                ink(g, action, x + w - 8 - font().width(action), ty + 7, FAINT);
            }
            if (t[3] != null) {
                menu.hits.add(new MenuHit(x, ty, w, rowH, null, null, (String) t[3]));
            } else if (t[4] != null) {
                QuestBook.Entry q = (QuestBook.Entry) t[4];
                menu.hits.add(new MenuHit(x, ty, w, rowH, null, List.of(Component.literal(QuestGuides.title(q.name())).withStyle(ChatFormatting.AQUA),
                        L.c("wynnlabs.today.track_tip").withStyle(ChatFormatting.DARK_GRAY)), null, q));
            }
        }
        ry += listH + 8;

        // the professions: gathering ones with a bar (quests ask for those), crafting as numbers
        // the professions while their card fits under the rest (a short window drops them)
        int profRows = (Professions.ALL.size() + (menu.layout.compact() ? 3 : menu.layout.large() ? 6 : 4) - 1)
                / (menu.layout.compact() ? 3 : menu.layout.large() ? 6 : 4);
        if (ry + 11 + profRows * 14 + 6 <= y + h) {
            ry = drawProfessions(g, x, ry, w, mx, my);
        }

        // the last session, in four tiles with big numbers
        try {
            com.google.gson.JsonObject last = Coach.lastSession();
            com.google.gson.JsonObject meas = Coach.measured();
            if (last != null && ry + 11 + 38 <= y + h) {
                section(g, L.t("wynnlabs.today.last_head", ago(last.get("end").getAsLong())), x, ry, w);
                ry += 11;
                long mins = (last.get("end").getAsLong() - last.get("start").getAsLong()) / 60000;
                double lvs = last.get("levels").getAsDouble();
                int em = last.get("emeralds").getAsInt();
                int de = last.get("deaths").getAsInt();
                int tw = (w - 3 * gap) / 4;
                int th = 38;
                bigTile(g, x, ry, tw, th, L.t("wynnlabs.today.tile_time"), L.t("wynnlabs.today.tile_minutes", mins), null, 0xFFFFFFFF);
                bigTile(g, x + (tw + gap), ry, tw, th, L.t("wynnlabs.today.tile_levels"),
                        (lvs >= 0 ? "+" : "") + String.format(Locale.ROOT, "%.2f", lvs), null, 0xFF97C459);
                bigTile(g, x + 2 * (tw + gap), ry, tw, th, L.t("wynnlabs.today.tile_money"),
                        (em >= 0 ? "+" : "") + Prices.fmt(em) + " · " + de, null, de >= 2 ? 0xFFE24B4A : 0xFFFFFFFF);
                if (meas.has("casts") && meas.get("casts").getAsInt() >= 5 && meas.has("modelPerCast")) {
                    bigTile(g, x + 3 * (tw + gap), ry, tw, th, L.t("wynnlabs.today.tile_damage", meas.get("casts").getAsInt()),
                            "~" + Math.round(meas.get("avgPerCast").getAsDouble()),
                            L.t("wynnlabs.today.tile_damage_sub", Math.round(meas.get("modelPerCast").getAsDouble()),
                                    String.format(Locale.ROOT, "%.2f", Rules.sub("model", "spellCalibration", 1.0))), 0xFFFFFFFF);
                } else {
                    bigTile(g, x + 3 * (tw + gap), ry, tw, th, L.t("wynnlabs.today.tile_damage", 0), "—", null, 0xFF6E6C78);
                }
                ry += th + 4;
                List<String> tipsOut = Coach.sessionTips(last);
                if (!tipsOut.isEmpty() && ry < y + h - 10) {
                    ink(g, fit("→ " + String.join(" · ", tipsOut), w), x, ry + 2, Theme.ACCENT);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** A list icon: {Tabler name, tile ARGB, icon ARGB} as hex strings. */
    private static String[] icon(String what) {
        return switch (what) {
            case "chest" -> new String[] {"gift", "FF173A2C", "FF7EE0BC"};
            case "pick" -> new String[] {"pick", "FF3A2A12", "FFFAC775"};
            case "paper" -> new String[] {"ticket", "FF12303A", "FF55FFFF"};
            case "emerald" -> new String[] {"coin", "FF3A3212", "FFFAC775"};
            case "diamond" -> new String[] {"diamond", "FF0F2A22", "FF5DCAA5"};
            case "gold" -> new String[] {"shopping-cart", "FF3A3212", "FFFAC775"};
            case "compass" -> new String[] {"map-pin", "FF12273A", "FF85B7EB"};
            default -> new String[] {"droplet", "FF12273A", "FF85B7EB"};
        };
    }

    /** A number tile: a dim label, a big bold value, and an optional small note under it. */
    private void bigTile(GuiGraphics g, int x, int y, int w, int h, String label, String value, String note, int colour) {
        Kit.plaque(g, x, y, w, h);
        ink(g, fit(label, w - 10), x + 6, y + 5, FAINT);
        scaledText(g, Component.literal(fit(value, (int) ((w - 10) / 1.3f))).withStyle(UiFont::bold), x + 6, y + 16, 1.3f, colour);
        if (note != null) {
            int vw = (int) (font().width(Component.literal(value).withStyle(UiFont::bold)) * 1.3f);
            scaledText(g, Component.literal(fit(note, (int) ((w - vw - 16) / 0.75f))), x + 10 + vw, y + 22, 0.75f, 0xFF6E6C78);
        }
    }

    /** A pill label; returns where the next one starts. */
    private long blockedAt;
    private String blockedLine = "";

    /**
     * The professions card: in columns, the gathering professions first with a bar to 100 under
     * the name, the crafting ones after them as numbers; then, when a quest you could do by level
     * is held back by a profession, what it still needs. Returns the y below it.
     */
    private int drawProfessions(GuiGraphics g, int x, int y, int w, int mx, int my) {
        Map<String, Integer> lv = Professions.levels();
        if (lv.values().stream().allMatch(v -> v < 0)) {
            return y;                                   // not known (no Wynntils): nothing to show
        }
        section(g, L.t("wynnlabs.prof.title"), x, y, w);
        y += 11;
        // four columns; three on a compact page so every name fits, six on a large one so a name
        // stays near its level
        int cols = menu.layout.compact() ? 3 : menu.layout.large() ? 6 : 4;
        int colW = (w - 12) / cols;
        int rowH = 11;
        int rows = (Professions.ALL.size() + cols - 1) / cols;
        // a row with a gathering profession is taller: its bar runs under the names
        int[] rowY = new int[rows + 1];
        for (int r = 0; r < rows; r++) {
            boolean bar = false;
            for (int i = r * cols; i < Math.min(Professions.ALL.size(), (r + 1) * cols); i++) {
                bar |= Professions.gathering(Professions.ALL.get(i)) && lv.get(Professions.ALL.get(i)) >= 0;
            }
            rowY[r + 1] = rowY[r] + rowH + (bar ? 3 : 0);
        }
        // the quest a profession holds back: worked out every few seconds, not every frame
        if (System.currentTimeMillis() - blockedAt > 3000) {
            blockedAt = System.currentTimeMillis();
            blockedLine = "";
            int level = Wyn.level();
            int asked = 0;
            for (QuestBook.Entry e : QuestBook.all()) {
                if (!"UNAVAILABLE".equals(e.status()) || e.level() > level || asked >= 6) {
                    continue;
                }
                asked++;                                // a few wiki pages at a time
                QuestWiki.Info qi = QuestWiki.get(e.name());
                Map<String, int[]> miss = qi == null ? Map.of() : Professions.missing(qi.professions());
                if (!miss.isEmpty()) {
                    List<String> parts = new ArrayList<>();
                    miss.forEach((p, hn) -> parts.add(Professions.name(p) + " +" + (hn[1] - hn[0])));
                    blockedLine = L.t("wynnlabs.prof.blocked", String.join(", ", parts), e.name());
                    break;
                }
            }
        }
        int ch = rowY[rows] + 6 + (blockedLine.isEmpty() ? 0 : rowH + 2);
        Kit.card(g, x, y, w, ch, 0);
        for (int i = 0; i < Professions.ALL.size(); i++) {
            String p = Professions.ALL.get(i);
            int v = lv.get(p);
            int cx = x + 6 + (i % cols) * colW;
            int cy = y + 4 + rowY[i / cols];
            String val = v < 0 ? "?" : String.valueOf(v);
            int vw = font().width(val);
            // the profession's icon from the game's pack before its name (the name alone without the pack)
            int nx = WynnTex.profession(g, p, cx, cy - 1, 9) ? cx + 11 : cx;
            ink(g, fit(Professions.name(p), colW - vw - 10 - (nx - cx)), nx, cy, MUTED);
            if (Professions.gathering(p) && v >= 0) {
                int bw = colW - 12;
                Art.pill(g, cx, cy + 10, bw, 2, Theme.WELL, 0);
                Art.pill(g, cx, cy + 10, Math.max(2, bw * Math.min(v, 100) / 100), 2, Theme.TEAL, 0);
            }
            ink(g, val, cx + colW - vw - 6, cy, Theme.INK);
        }
        if (!blockedLine.isEmpty()) {
            ink(g, fit(blockedLine, w - 12), x + 6, y + 4 + rowY[rows] + 1, Theme.BAD);
        }
        return y + ch + 8;
    }

    private int pillText(GuiGraphics g, int x, int y, String text, int colour, int bg) {
        int w = font().width(text) + 12;
        Kit.pillLine(g, x, y, w, 13, Theme.paperRgb(colour));
        ink(g, text, x + 6, y + 3, colour, false);
        return x + w + 6;
    }

    /** A pill with an 8 px icon before its text. */
    private int pillText(GuiGraphics g, int x, int y, String icon, String text, int colour, int bg) {
        int w = font().width(text) + 22;
        Kit.pillLine(g, x, y, w, 13, Theme.paperRgb(colour));
        inkIconText(g, icon, text, x + 6, y + 3, colour);
        return x + w + 6;
    }

    private List<Today.Day> days = List.of();
    private List<Vault.Find> vaultFinds = List.of();
    private long vaultAt;
    private long daysAt;
}
