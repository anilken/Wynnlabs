package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.build.roadmap.Roadmap;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.market.Vault;
import io.github.anilken.wynnlabs.quests.QuestAdvisor;
import io.github.anilken.wynnlabs.quests.QuestBook;
import io.github.anilken.wynnlabs.quests.QuestGuides;
import io.github.anilken.wynnlabs.ui.HomeTab.Host;
import io.github.anilken.wynnlabs.ui.HomeTab.In;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The home's "Next" line on top: the one thing to do now, a slim row with a compass button.
 * It scores nothing itself: it takes the first of, in the Today list's order, a piece the
 * build plan wears from your bag, a better piece in your bank, the quest advisor's first
 * quest and the safest place to level; with none of them (the endgame) it is not drawn.
 */
final class HomeNext extends HomePart {

    /**
     * The row's content: its icon, the name and its colour, the line after it, the row's click
     * (a menu action, or null), the quest it tracks (null: none), the compass command (null: no
     * button) and the tooltip.
     */
    record Pick(String icon, String title, int colour, String sub, String action, QuestBook.Entry quest, String compass,
            List<Component> tip) {}

    private List<Vault.Find> bank = List.of();
    private long bankAt;

    /** The row's height in this menu size. */
    int height(boolean compact) {
        return compact ? 14 : large ? 18 : 16;
    }

    /** The first thing to do, or null. */
    Pick pick(In in) {
        long now = System.currentTimeMillis();
        if (now - bankAt > 3000) {             // the bank's pieces are tried against the build: not every frame
            bankAt = now;
            try {
                bank = Vault.forMe();
            } catch (Throwable t) {
                bank = List.of();
            }
        }
        Roadmap.Gear bag = null;
        try {
            for (Roadmap.Gear g : Roadmap.bagRows().values()) {
                if (g.info() != null && !Double.isNaN(g.gain()) && g.gain() > 0 && (bag == null || g.gain() > bag.gain())) {
                    bag = g;
                }
            }
        } catch (Throwable ignored) {
            // no plan yet
        }
        return choose(bag, bank.isEmpty() ? null : bank.get(0), in.quest(), safest(in.places()));
    }

    /** The first that applies: the bag's piece, the bank's, the quest, the place to level. */
    static Pick choose(Roadmap.Gear bag, Vault.Find bank, QuestAdvisor.Suggestion quest, String place) {
        if (bag != null) {
            String gain = "+" + RoadmapScreen.pct(Math.round(bag.gain()));
            return new Pick("shirt", bag.info().name(), Theme.GOOD, gain + " · " + L.t("wynnlabs.next.bag"), null, null, null,
                    List.of(Component.literal(bag.info().name()).withStyle(ChatFormatting.GREEN),
                            L.c("wynnlabs.next.gear_tip", gain).withStyle(ChatFormatting.GRAY)));
        }
        if (bank != null) {
            String gain = "+" + RoadmapScreen.pct(Math.round(bank.gain()));
            return new Pick("building-bank", bank.item().name(), Theme.GOOD, gain + " · " + L.t("wynnlabs.next.bank"),
                    "#qgo:bank", null, null, List.of(Component.literal(bank.item().name()).withStyle(ChatFormatting.GREEN),
                            L.c("wynnlabs.next.gear_tip", gain).withStyle(ChatFormatting.GRAY),
                            L.c("wynnlabs.homev.click_open", L.t("wynnlabs.screen.tab_bank")).withStyle(ChatFormatting.DARK_GRAY)));
        }
        if (quest != null) {
            QuestBook.Entry q = quest.quest();
            String name = QuestGuides.title(q.name());
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(name).withStyle(ChatFormatting.AQUA).withStyle(UiFont::bold));
            for (String r : quest.reasons()) {
                tip.add(Component.literal("• " + L.cap(r)).withStyle(ChatFormatting.GRAY));
            }
            tip.add(L.c("wynnlabs.home.click_track").withStyle(ChatFormatting.GREEN));
            String at = q.x() == null || q.z() == null ? null
                    : "/compass at " + q.x() + " " + (q.y() == null ? 64 : q.y()) + " " + q.z();
            return new Pick("map-2", name, Theme.LINK, (quest.distance() >= 0 ? RoadmapScreen.dist(quest.distance()) + " · " : "")
                    + "Lv. " + q.level(), null, q, at, tip);
        }
        if (place != null) {
            String shown = RoadmapScreen.shortPlace(place);
            String name = shown.contains(" (") ? shown.substring(0, shown.indexOf(" (")) : shown;
            String lv = shown.contains(" (") ? shown.substring(shown.indexOf(" (") + 2, shown.length() - 1) : "";
            int risk = RoadmapScreen.placeRisk(place);
            String safe = L.t(risk == 0 ? "wynnlabs.home.risk_safe" : risk == 1 ? "wynnlabs.home.risk_careful" : "wynnlabs.home.risk_risky");
            String cmd = RoadmapScreen.compassFor(place);
            return new Pick("sword", name, Theme.INK, safe + (lv.isEmpty() ? "" : " · " + lv), cmd == null ? name : null, null, cmd,
                    List.of(Component.literal(shown).withStyle(ChatFormatting.WHITE), Component.literal(L.t(cmd != null
                            ? "wynnlabs.screen.click_compass" : "wynnlabs.screen.click_copy_name")).withStyle(ChatFormatting.DARK_GRAY)));
        }
        return null;
    }

    /** The safest of the places to level (the first of the safest), as the grind tile shows it; null for none. */
    static String safest(List<String> places) {
        String best = null;
        for (String p : places) {
            if (best == null || RoadmapScreen.placeRisk(p) < RoadmapScreen.placeRisk(best)) {
                best = p;
            }
        }
        return best;
    }

    /** The row: "Next" in front, the icon, the name and its line, and the compass button on the right. */
    void draw(GuiGraphics g, Pick p, Host host, int x, int y, int w, int h, boolean compact, int mx, int my) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        card(g, x, y, w, h, 0, hover);
        int ty = y + (h - 8) / 2;
        int right = x + w - 3;
        if (p.compass() != null) {
            // the compass button first: its area is added before the row's, so its click wins
            String word = compact ? "" : L.t("wynnlabs.next.compass");
            int bh = h - 4;
            int bw = (word.isEmpty() ? 0 : font.width(word) + 3) + 8 + 8;
            int bx = right - bw;
            boolean on = mx >= bx && mx < bx + bw && my >= y + 2 && my < y + 2 + bh;
            int state = on ? Kit.HOVER : Kit.REST;
            Kit.button(g, bx, y + 2, bw, bh, state);
            Art.icon(g, "compass", bx + 4, y + 2 + (bh - 8) / 2, 8, Kit.buttonText(state));
            if (!word.isEmpty()) {
                g.drawString(font, word, bx + 15, y + 2 + (bh - 8) / 2, Kit.buttonText(state), true);
            }
            host.area(bx, y + 2, bw, bh, null, List.of(Component.literal(p.title()).withStyle(ChatFormatting.WHITE),
                    L.c("wynnlabs.screen.click_compass").withStyle(ChatFormatting.DARK_GRAY)), p.compass());
            right = bx - 4;
        }
        String label = L.t("wynnlabs.next.label");
        Component lc = Component.literal(label).withStyle(UiFont::bold);
        int tx = x + 6;
        ink(g, lc, tx, ty, Theme.INK_2);
        tx += font.width(lc) + 6;
        Art.icon(g, p.icon(), tx, y + (h - 8) / 2, 8, Theme.INK_2);
        tx += 11;
        int room = right - tx;
        String title = fit(p.title(), Math.max(20, room * 3 / 5), true);
        Component tc = Component.literal(title).withStyle(UiFont::bold);
        ink(g, tc, tx, ty, p.colour());
        int sx = tx + font.width(tc);
        if (right - sx > 20) {
            ink(g, fit(" · " + p.sub(), right - sx), sx, ty, Theme.INK_3);
        }
        if (p.quest() != null) {
            host.quest(x, y, w, h, p.tip(), p.quest());
        } else {
            host.area(x, y, w, h, null, p.tip(), p.action());
        }
    }
}
