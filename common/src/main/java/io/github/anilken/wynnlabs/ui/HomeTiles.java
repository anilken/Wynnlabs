package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.core.DevHooks;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.crafting.Crafting;
import io.github.anilken.wynnlabs.endgame.ItemIcons;
import io.github.anilken.wynnlabs.game.Professions;
import io.github.anilken.wynnlabs.game.Wyn;
import io.github.anilken.wynnlabs.market.Prices;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.PoolItem;
import io.github.anilken.wynnlabs.model.RankedAspect;
import io.github.anilken.wynnlabs.model.Tier;
import io.github.anilken.wynnlabs.quests.Discoveries;
import io.github.anilken.wynnlabs.quests.QuestAdvisor;
import io.github.anilken.wynnlabs.quests.QuestGuides;
import io.github.anilken.wynnlabs.ui.HomeTab.Host;
import io.github.anilken.wynnlabs.ui.HomeTab.In;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The home tab's tiles: this week's raid pool, the aspects and the professions on the left;
 * the next quest, where to level, the bank and the nearest secret discovery on the right; and
 * the compact page's rows. They read {@link HomeData}, refreshed twice a second.
 */
final class HomeTiles extends HomePart {

    /** Wynncraft's highest profession level: the professions' bars without Wynntils' XP run to it. */
    private static final int PROF_CAP = 132;

    private HomeData d;
    private long dataAt;

    /** Reads the tiles' data again when it is older than half a second. */
    void refresh(long now) {
        if (d == null || now - dataAt > 500) {
            d = HomeData.read();
            dataAt = now;
        }
    }

    // ---- the tiles ----

    /** A tile's card and its title line (an icon, the name); lit under the mouse. */
    private void tile(GuiGraphics g, int x, int y, int w, int h, String icon, String title, int accent, int mx, int my) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        card(g, x, y, w, h, accent, hover);
        Art.icon(g, icon, x + 5, y + 6, 8, Theme.INK_2);
        ink(g, fit(title, w - 22), x + 16, y + 6, Theme.INK_2);
    }

    /** "Click: open the X tab", the last line of a tile's tooltip. */
    private static Component opens(String tabKey) {
        return L.c("wynnlabs.homev.click_open", L.t(tabKey)).withStyle(ChatFormatting.DARK_GRAY);
    }

    /**
     * This week's raid pool: the class's aspects, mythic first, each on a square of its rarity's
     * colour with a 2px frame of it (the icon is the class's aspect, the same for every rarity),
     * and the mythic dry count.
     */
    void weekTile(GuiGraphics g, Host host, int x, int y, int w, int h, int mx, int my) {
        tile(g, x, y, w, h, "trophy", L.t("wynnlabs.homev.week"), Theme.TIER_MYTHIC, mx, my);
        if (DevHooks.ON) {
            DevHooks.part("week", g.pose(), x, y, w, h);
        }
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(L.t("wynnlabs.homev.week_tip")).withStyle(ChatFormatting.LIGHT_PURPLE));
        boolean dry = d.raidsUp() && d.dryPulls() >= 0;
        int iy = y + 18;
        if (d.pool().isEmpty()) {
            ink(g, fit(L.t("wynnlabs.homev.week_none"), w - 12), x + 6, iy + 2, Theme.INK_3);
            tip.add(Component.literal(L.t("wynnlabs.endgame.pool_never_tip")).withStyle(ChatFormatting.GRAY));
        } else {
            int is = large ? 24 : 16;
            int s = is + 4;
            int per = Math.max(1, (w - 12 + 2) / (s + 2));
            int rows = Math.max(1, (y + h - (dry ? 16 : 6) - iy + 2) / (s + 2));
            int max = per * rows;
            int n = d.pool().size() > max ? max - 1 : d.pool().size();
            for (int i = 0; i < n; i++) {
                PoolItem a = d.pool().get(i);
                int sx = x + 6 + (i % per) * (s + 2);
                int sy = iy + (i / per) * (s + 2);
                int c = tierColour(a.rarity());
                g.fill(sx, sy, sx + s, sy + s, c);
                g.fill(sx + 2, sy + 2, sx + s - 2, sy + s - 2, wash(c));
                ItemIcons.draw(g, a, a.aspectClass() == Klass.NONE ? d.klass() : a.aspectClass(), sx + 2, sy + 2, is, false);
            }
            if (n < d.pool().size()) {
                String more = "+" + (d.pool().size() - n);
                int sx = x + 6 + (n % per) * (s + 2);
                int sy = iy + (n / per) * (s + 2);
                ink(g, more, sx + (s - font.width(more)) / 2, sy + (s - 8) / 2, Theme.INK_2);
            }
            // the tooltip by rarity, as the pool view groups them
            Tier at = null;
            for (PoolItem a : d.pool()) {
                if (a.rarity() != at) {
                    at = a.rarity();
                    tip.add(Component.literal(rarityName(at)).withStyle(tierChat(at)).withStyle(UiFont::bold));
                }
                tip.add(Component.literal("  ◆ " + a.name()).withStyle(tierChat(a.rarity())));
            }
        }
        if (dry) {
            ink(g, fit(L.n("wynnlabs.homev.week_dry", d.dryPulls()), w - 12), x + 6, y + h - 13, Theme.INK_3);
        }
        tip.add(opens("wynnlabs.screen.tab_endgame"));
        host.area(x, y, w, h, null, tip, "#qopen:" + RoadmapScreen.TAB_ENDGAME);
    }

    /** A rarity's colour mixed 30% into the card: the square under an aspect's icon. */
    private static int wash(int c) {
        int r = ((c >> 16 & 0xFF) * 3 + (Theme.CARD >> 16 & 0xFF) * 7) / 10;
        int gr = ((c >> 8 & 0xFF) * 3 + (Theme.CARD >> 8 & 0xFF) * 7) / 10;
        int b = ((c & 0xFF) * 3 + (Theme.CARD & 0xFF) * 7) / 10;
        return 0xFF000000 | r << 16 | gr << 8 | b;
    }

    /** "Mythic": a rarity's name as the game writes it. */
    private static String rarityName(Tier t) {
        if (t == null) {
            return "";
        }
        String n = t.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    /** The class's aspects: owned of all on a half ring, and the next one for the build. */
    void aspectTile(GuiGraphics g, Host host, int x, int y, int w, int h, int mx, int my) {
        tile(g, x, y, w, h, "sparkles", L.t("wynnlabs.homev.aspects"), Theme.GOLD_DEEP, mx, my);
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(L.t("wynnlabs.homev.aspects")).withStyle(ChatFormatting.GOLD));
        if (!d.aspectsUp() || d.total() == 0) {
            ink(g, fit(L.t(d.aspectsUp() ? "wynnlabs.endgame.aspects_no_data" : "wynnlabs.endgame.aspects_off"), w - 12),
                    x + 6, y + 20, Theme.INK_3);
        } else {
            int owned = d.owned();
            String count = (owned < 0 ? "?" : String.valueOf(owned)) + "/" + d.total();
            int tx;
            if (h >= 52) {
                int gcx = x + 6 + 29;
                int gcy = y + 18 + 29;
                Kit.gauge(g, gcx, gcy, 29, 5, owned < 0 ? 0 : owned / (double) d.total(), Theme.PAPER_DEEP, Theme.GOLD_DEEP, Theme.GOLD_EDGE);
                Component c = Component.literal(count).withStyle(UiFont::bold);
                Kit.text(g, font, c, gcx - font.width(c) / 2, gcy - 11, Theme.INK);
                tx = x + 70;
            } else {
                Kit.text(g, font, Component.literal(count).withStyle(UiFont::bold), x + 6, y + 19, Theme.INK);
                tx = x + 10 + font.width(count) + 6;
            }
            int tw = x + w - 6 - tx;
            if (owned < 0) {
                ink(g, fit(L.t("wynnlabs.endgame.aspects_unread"), tw), tx, y + 20, Theme.INK_3);
                tip.add(Component.literal(L.t("wynnlabs.endgame.aspects_unread_tip")).withStyle(ChatFormatting.GRAY));
            } else {
                tip.add(L.c("wynnlabs.homev.aspects_tip", owned, d.total()).withStyle(ChatFormatting.GRAY));
                RankedAspect a = d.next();
                if (a == null) {
                    ink(g, fit(L.t("wynnlabs.homev.aspects_done"), tw), tx, y + 20, Theme.GOOD);
                } else {
                    String shortName = shortAspect(a.aspect().name());
                    List<String> name = wrap2(shortName, tw, true);
                    int by;
                    if (!String.join(" ", name).contains("…")) {
                        // beside the ring: "Next" over the name in up to two lines
                        ink(g, fit(L.t("wynnlabs.homev.aspects_next"), tw), tx, y + 20, Theme.INK_3);
                        for (int i = 0; i < name.size(); i++) {
                            Kit.text(g, font, Component.literal(name.get(i)).withStyle(UiFont::bold), tx, y + 31 + i * 10,
                                    tierColour(a.aspect().rarity()));
                        }
                        by = y + 31 + Math.max(2, name.size()) * 10 + 4;
                    } else {
                        // too narrow beside the ring: "Next: name" on a line under it
                        Component next = Component.literal(L.t("wynnlabs.homev.aspects_next") + ": ").withColor(Theme.INK_3 & 0xFFFFFF)
                                .append(Component.literal(shortName).withStyle(UiFont::bold).withColor(tierColour(a.aspect().rarity()) & 0xFFFFFF));
                        if (font.width(next) > w - 12) {
                            // still too wide: the name alone, in its colour
                            next = Component.literal(fit(shortName, w - 12, true)).withStyle(UiFont::bold)
                                    .withColor(tierColour(a.aspect().rarity()) & 0xFFFFFF);
                        }
                        Kit.text(g, font, next, x + 6, y + 51, Theme.INK);
                        by = y + 62;
                    }
                    tip.add(Component.literal(L.t("wynnlabs.homev.aspects_next") + ": " + a.aspect().name())
                            .withStyle(tierChat(a.aspect().rarity())));
                    if (!a.reason().isEmpty()) {
                        tip.add(Component.literal(a.reason()).withStyle(ChatFormatting.GRAY));
                    }
                    // what it boosts, one line under the ring: the abilities' names only (the tooltip has the rest)
                    List<String> abilities = new ArrayList<>();
                    a.matches().forEach(m -> abilities.add(m.ability()));
                    String boosts = abilities.isEmpty() ? a.reason() : L.t("wynnlabs.homev.aspects_boosts", String.join(", ", abilities));
                    if (!boosts.isEmpty() && by + 9 <= y + h - 5) {
                        ink(g, fit(boosts, w - 12), x + 6, by, Theme.INK_3);
                    }
                }
            }
        }
        tip.add(opens("wynnlabs.screen.tab_endgame"));
        host.area(x, y, w, h, null, tip, "#qopen:" + RoadmapScreen.TAB_ENDGAME);
    }

    /**
     * The professions: up to three rows (the crafting tab's profession first, then the highest),
     * each its name, a bar and its level. The bar is the XP into the level where Wynntils gives it,
     * else the level against the cap ({@link #PROF_CAP}).
     */
    void professionTile(GuiGraphics g, Host host, int x, int y, int w, int h, int mx, int my) {
        tile(g, x, y, w, h, "hammer", L.t("wynnlabs.homev.profs"), Theme.AMBER, mx, my);
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(L.t("wynnlabs.homev.profs")).withStyle(ChatFormatting.GOLD));
        if (d.profs().isEmpty()) {
            ink(g, fit(L.t("wynnlabs.homev.profs_none"), w - 12), x + 6, y + 20, Theme.INK_3);
            tip.add(Component.literal(L.t("wynnlabs.homev.profs_none_tip")).withStyle(ChatFormatting.GRAY));
        } else {
            int pitch = Math.max(11, Math.min(18, (h - 24) / 3));
            int ry = y + 19;
            int lw = 0;                                         // the widest level: every bar ends at one edge
            int names = 0;                                      // the widest name (and its icon): the bars start after it
            for (String pr : d.profs()) {
                lw = Math.max(lw, font.width(String.valueOf(d.levels().get(pr))));
                names = Math.max(names, font.width(Professions.name(pr)) + (WynnTex.has("font/profession") ? 11 : 0));
            }
            int nw = Math.min(names + 4, w - 12 - lw - 4 - 16);    // a bar keeps 16px at least
            for (String pr : d.profs()) {
                if (ry + 9 > y + h - 5) {
                    break;
                }
                int lv = d.levels().get(pr);
                int[] xp = Crafting.professionXp(pr);
                boolean exact = xp != null && xp.length == 2 && xp[1] > 0;
                double frac = exact ? xp[0] / (double) xp[1] : lv / (double) PROF_CAP;
                int nx = WynnTex.profession(g, pr, x + 6, ry - 1, 9) ? x + 17 : x + 6;
                ink(g, fit(Professions.name(pr), x + 6 + nw - nx - 3), nx, ry, Theme.INK_2);
                String n = String.valueOf(lv);
                int bx = x + 6 + nw;
                int bw = x + w - 6 - lw - 4 - bx;
                int colour = Professions.gathering(pr) ? Theme.TEAL_LO : Theme.AMBER_LO;
                Art.pill(g, bx, ry + 3, bw, 3, Theme.WELL, 0);
                Art.pill(g, bx, ry + 3, Math.max(3, (int) Math.round(bw * Math.max(0, Math.min(1, frac)))), 3, colour, 0);
                ink(g, n, x + w - 6 - font.width(n), ry, Theme.INK);
                tip.add(Component.literal(Professions.name(pr) + " · Lv " + lv
                        + (exact ? " · " + L.t("wynnlabs.homev.profs_xp", RoadmapScreen.pct(Math.round(frac * 100))) : ""))
                        .withStyle(ChatFormatting.GRAY));
                ry += pitch;
            }
            if (d.profs().stream().noneMatch(pr -> {
                int[] xp = Crafting.professionXp(pr);
                return xp != null && xp.length == 2 && xp[1] > 0;
            })) {
                tip.add(L.c("wynnlabs.homev.profs_cap", PROF_CAP).withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        tip.add(opens("wynnlabs.screen.tab_craft"));
        host.area(x, y, w, h, null, tip, "#qopen:" + RoadmapScreen.TAB_CRAFT);
    }

    /** The best quest: a small compass pointing to it from where you look, its name, distance and level; a click tracks it. */
    void questTile(GuiGraphics g, In in, Host host, int x, int y, int w, int h, int mx, int my) {
        tile(g, x, y, w, h, "map-2", L.t("wynnlabs.homev.quest"), 0xFF378ADD, mx, my);
        QuestAdvisor.Suggestion q = in.quest();
        if (q == null) {
            ink(g, fit(L.t(QuestAdvisor.known() == 0 ? "wynnlabs.home.open_book" : "wynnlabs.home.no_quest"), w - 12), x + 6, y + 20,
                    Theme.INK_3);
            host.area(x, y, w, h, null, List.of(opens("wynnlabs.screen.tab_quests")), "#qopen:2");
            return;
        }
        int px = large && h >= 52 ? 3 : 2;                  // the arrow's pixel: 7 of them across
        int ds = 7 * px + 8;
        int tx = x + 6;
        if (HomeDial.draw(g, q.quest(), x + 6, y + 18, ds)) {
            tx = x + 6 + ds + 5;
        }
        int tw = x + w - 6 - tx;
        ink(g, Component.literal(fit(QuestGuides.title(q.quest().name()), tw)).withStyle(ChatFormatting.AQUA), tx, y + 19, Theme.LINK);
        String sub = (q.distance() >= 0 ? RoadmapScreen.dist(q.distance()) + " · " : "") + "Lv. " + q.quest().level();
        ink(g, fit(sub, tw), tx, y + 30, Theme.INK_3);
        // why this one, where the tile has room under the dial
        int ry = y + 22 + ds;
        for (String r : q.reasons()) {
            ry = lines(g, "• " + L.cap(r), x + 6, ry, w - 12, y + h - 5, Theme.INK_3);
        }
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(QuestGuides.title(q.quest().name())).withStyle(ChatFormatting.AQUA).withStyle(UiFont::bold));
        for (String r : q.reasons()) {
            tip.add(Component.literal("• " + L.cap(r)).withStyle(ChatFormatting.GRAY));
        }
        tip.add(L.c("wynnlabs.home.click_track").withStyle(ChatFormatting.GREEN));
        host.quest(x, y, w, h, tip, q.quest());
    }

    /** Where to level: the safest place for now with its safety dot, and the experience to the next level. */
    void grindTile(GuiGraphics g, In in, Host host, int x, int y, int w, int h, int mx, int my) {
        String raw = HomeNext.safest(in.places());
        int risk = raw == null ? 1 : RoadmapScreen.placeRisk(raw);
        tile(g, x, y, w, h, "sword", L.t("wynnlabs.home.grind"), RoadmapScreen.RISK[risk], mx, my);
        int xp;
        try {
            xp = Wyn.xp().percentInt();
        } catch (Throwable t) {
            xp = -1;
        }
        if (raw == null) {
            ink(g, fit(L.t("wynnlabs.home.no_place"), w - 12), x + 6, y + 20, Theme.INK_3);
        } else {
            String place = RoadmapScreen.shortPlace(raw);
            String name = place.contains(" (") ? place.substring(0, place.indexOf(" (")) : place;
            String lv = place.contains(" (") ? place.substring(place.indexOf(" (") + 2, place.length() - 1) : "";
            int rc = Theme.paperRgb(RoadmapScreen.RISK[risk]);
            Art.box(g, x + 6, y + 20, 6, 6, 3, rc, 0);
            ink(g, fit(name, w - 22), x + 16, y + 19, Theme.INK);
            String safe = L.t(risk == 0 ? "wynnlabs.home.risk_safe" : risk == 1 ? "wynnlabs.home.risk_careful" : "wynnlabs.home.risk_risky");
            ink(g, fit(safe + (lv.isEmpty() ? "" : " · " + lv), w - 22), x + 16, y + 30, Theme.INK_3);
            String cmd = RoadmapScreen.compassFor(raw);
            host.area(x, y, w, h, null, List.of(Component.literal(place).withStyle(ChatFormatting.WHITE),
                    Component.literal(L.t(cmd != null ? "wynnlabs.screen.click_compass" : "wynnlabs.screen.click_copy_name"))
                            .withStyle(ChatFormatting.DARK_GRAY)), cmd != null ? cmd : name);
        }
        // the experience to the next level, as the band's bar
        if (xp >= 0 && h >= 50) {
            String p = RoadmapScreen.pct(xp);
            int bw = w - 12 - font.width(p) - 4;
            Kit.tealBar(g, x + 6, y + h - 11, bw, 4, Math.max(0, Math.min(100, xp)) / 100.0, true);
            ink(g, p, x + w - 6 - font.width(p), y + h - 13, Theme.INK_3);
        }
    }

    /** The bank: a strip of what is in it by kind (sell, quest, profession, gear, other), and what selling would bring. */
    void bankTile(GuiGraphics g, Host host, int x, int y, int w, int h, int mx, int my) {
        tile(g, x, y, w, h, "building-bank", L.t("wynnlabs.homev.bank"), 0xFF5DCAA5, mx, my);
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(L.t("wynnlabs.homev.bank")).withStyle(ChatFormatting.DARK_AQUA));
        int[] b = d.bank();
        if (b == null) {
            ink(g, fit(L.t("wynnlabs.homev.bank_none"), w - 12), x + 6, y + 20, Theme.INK_3);
        } else {
            int[] colours = {Theme.MARK_SELL, Theme.MARK_QUEST, Theme.MARK_IDENTIFY, Theme.MARK_USE, 0xFFBCAE8E};
            String[] names = {L.t("wynnlabs.bank.filter_sell"), L.t("wynnlabs.bank.filter_quest"), L.t("wynnlabs.bank.filter_prof"),
                L.t("wynnlabs.bank.filter_gear"), L.t("wynnlabs.homev.bank_other")};
            int total = 0;
            for (int n : b) {
                total += n;
            }
            int sx = x + 6;
            int sw = w - 12;
            int at = sx;
            int run = 0;
            for (int i = 0; i < b.length; i++) {
                run += b[i];
                int end = total == 0 ? sx : sx + sw * run / total;
                g.fill(at, y + 19, end, y + 25, colours[i]);
                at = end;
                tip.add(Component.literal(names[i] + ": " + b[i]).withColor(colours[i] & 0xFFFFFF));
            }
            Kit.outline(g, sx - 1, y + 18, sw + 2, 8, Theme.LINE);
            String sell = L.t("wynnlabs.homev.bank_sell", b[0]) + (d.sellWorth() > 0 ? " · ≈" + Prices.fmt(d.sellWorth()) : "");
            ink(g, fit(sell, w - 12), x + 6, y + 30, b[0] > 0 ? Theme.BAD : Theme.INK_3);
            if (d.sellWorth() > 0) {
                tip.add(L.cn("wynnlabs.homev.bank_worth_tip", d.priced(), Prices.fmt(d.sellWorth()), d.priced()).withStyle(ChatFormatting.GOLD));
            }
            // a key under it when there is room: a colour square and its count, quest to gear
            if (h >= 52) {
                int kx = x + 6;
                for (int i = 1; i < 4; i++) {
                    String n = String.valueOf(b[i]);
                    if (kx + 9 + font.width(n) > x + w - 6) {
                        break;
                    }
                    g.fill(kx, y + 43, kx + 6, y + 49, colours[i]);
                    ink(g, n, kx + 8, y + 42, Theme.INK_3);
                    kx += 8 + font.width(n) + 8;
                }
            }
        }
        tip.add(opens("wynnlabs.screen.tab_bank"));
        host.area(x, y, w, h, null, tip, "#qopen:" + RoadmapScreen.TAB_BANK);
    }

    /** The nearest secret discovery not done yet at your level: its name, distance, level and region; a click opens it. */
    void discoveryTile(GuiGraphics g, Host host, int x, int y, int w, int h, int mx, int my) {
        tile(g, x, y, w, h, "compass", L.t("wynnlabs.homev.discovery"), 0xFFC9A7F0, mx, my);
        Discoveries.Entry e = d.near();
        if (e == null) {
            ink(g, fit(L.t("wynnlabs.homev.discovery_none"), w - 12), x + 6, y + 20, Theme.INK_3);
            host.area(x, y, w, h, null, List.of(opens("wynnlabs.screen.tab_discoveries")), "#qopen:" + RoadmapScreen.TAB_DISCOVERIES);
            return;
        }
        ink(g, Component.literal(fit(e.name(), w - 12, true)).withStyle(UiFont::bold), x + 6, y + 19, Theme.ACCENT);
        ink(g, fit(RoadmapScreen.dist(d.nearDist()) + " · Lv. " + e.minLevel(), w - 12), x + 6, y + 30, Theme.INK_3);
        if (h >= 52 && e.region() != null) {
            ink(g, fit(e.region(), w - 12), x + 6, y + 41, Theme.INK_3);
        }
        host.area(x, y, w, h, null, List.of(Component.literal(e.name()).withStyle(ChatFormatting.LIGHT_PURPLE),
                Component.literal(e.region() == null ? "" : e.region()).withStyle(ChatFormatting.GRAY),
                opens("wynnlabs.screen.tab_discoveries")), "#qdisc:" + e.id());
    }

    // ---- compact: the tiles as rows ----

    /**
     * The compact page's tiles as rows beside the stand, in order of use (the first steps while
     * any is open, quest, where to level, this week, aspects, bank (not beside the first steps),
     * professions, discovery); the ones that do not fit are left out. A row
     * is a label over its answer when tall enough, else one line.
     */
    void rows(GuiGraphics g, In in, Host host, int x, int y, int w, int h, int mx, int my, HomeSteps steps) {
        List<String[]> rows = new ArrayList<>();       // {icon, label, value, action}
        List<List<Component>> tips = new ArrayList<>();
        if (steps != null) {
            rows.add(steps.row());                     // the menus still to open: first, in the bank's place
            tips.add(steps.tip(FirstSteps.done()).subList(1, FirstSteps.STEPS.size() + 2));
        }
        QuestAdvisor.Suggestion q = in.quest();
        rows.add(new String[] {"map-2", L.t("wynnlabs.homev.quest"), q == null ? L.t("wynnlabs.home.no_quest")
            : QuestGuides.title(q.quest().name()) + (q.distance() >= 0 ? " · " + RoadmapScreen.dist(q.distance()) : ""), q == null ? "#qopen:2" : null});
        tips.add(q == null ? List.of() : List.of(Component.literal(QuestGuides.title(q.quest().name())).withStyle(ChatFormatting.AQUA),
                L.c("wynnlabs.home.click_track").withStyle(ChatFormatting.GREEN)));
        String raw = HomeNext.safest(in.places());
        String place = raw == null ? null : RoadmapScreen.shortPlace(raw);
        String cmd = raw == null ? null : RoadmapScreen.compassFor(raw);
        rows.add(new String[] {"sword", L.t("wynnlabs.home.grind"), place == null ? L.t("wynnlabs.home.no_place") : place,
            place == null ? null : cmd != null ? cmd : place});
        tips.add(place == null ? List.of() : List.of(Component.literal(place).withStyle(ChatFormatting.WHITE),
                Component.literal(L.t(cmd != null ? "wynnlabs.screen.click_compass" : "wynnlabs.screen.click_copy_name"))
                        .withStyle(ChatFormatting.DARK_GRAY)));
        rows.add(new String[] {"trophy", L.t("wynnlabs.homev.week"), d.pool().isEmpty() ? L.t("wynnlabs.homev.week_none")
            : L.n("wynnlabs.homev.week_count", d.pool().size()) + (d.raidsUp() && d.dryPulls() >= 0 ? " · " + L.n("wynnlabs.homev.week_dry", d.dryPulls()) : ""),
            "#qopen:" + RoadmapScreen.TAB_ENDGAME});
        tips.add(List.of(opens("wynnlabs.screen.tab_endgame")));
        RankedAspect a = d.next();
        rows.add(new String[] {"sparkles", L.t("wynnlabs.homev.aspects"), !d.aspectsUp() || d.total() == 0 ? L.t("wynnlabs.endgame.aspects_no_data")
            : (d.owned() < 0 ? "?" : String.valueOf(d.owned())) + "/" + d.total() + (a != null && d.owned() >= 0 ? " · " + shortAspect(a.aspect().name()) : ""),
            "#qopen:" + RoadmapScreen.TAB_ENDGAME});
        tips.add(List.of(opens("wynnlabs.screen.tab_endgame")));
        int[] b = d.bank();
        if (steps == null) {
            rows.add(new String[] {"building-bank", L.t("wynnlabs.homev.bank"), b == null ? L.t("wynnlabs.homev.bank_none")
                : L.t("wynnlabs.homev.bank_sell", b[0]) + (d.sellWorth() > 0 ? " · ≈" + Prices.fmt(d.sellWorth()) : ""), "#qopen:" + RoadmapScreen.TAB_BANK});
            tips.add(List.of(opens("wynnlabs.screen.tab_bank")));
        }
        String top = d.profs().isEmpty() ? null : d.profs().get(0);
        rows.add(new String[] {"hammer", L.t("wynnlabs.homev.profs"), top == null ? L.t("wynnlabs.homev.profs_none")
            : Professions.name(top) + " " + d.levels().get(top), "#qopen:" + RoadmapScreen.TAB_CRAFT});
        tips.add(List.of(opens("wynnlabs.screen.tab_craft")));
        Discoveries.Entry e = d.near();
        rows.add(new String[] {"compass", L.t("wynnlabs.homev.discovery"), e == null ? L.t("wynnlabs.homev.discovery_none")
            : e.name() + " · " + RoadmapScreen.dist(d.nearDist()), e == null ? "#qopen:" + RoadmapScreen.TAB_DISCOVERIES : "#qdisc:" + e.id()});
        tips.add(List.of(opens("wynnlabs.screen.tab_discoveries")));

        int gap = 3;
        int n = Math.min(rows.size(), Math.max(1, (h + gap) / (14 + gap)));
        int rh = Math.min(26, (h - (n - 1) * gap) / n);
        boolean two = rh >= 22;
        for (int i = 0; i < n; i++) {
            String[] r = rows.get(i);
            int ry = y + i * (rh + gap);
            boolean hover = mx >= x && mx < x + w && my >= ry && my < ry + rh;
            card(g, x, ry, w, rh, 0, hover);
            Art.icon(g, r[0], x + 4, ry + (rh - 8) / 2, 8, Theme.INK_2);
            int tx = x + 15;
            int tw = x + w - 4 - tx;
            if (two) {
                ink(g, fit(r[1], tw), tx, ry + 3, Theme.INK_3);
                ink(g, fit(r[2], tw), tx, ry + 13, Theme.INK);
            } else {
                ink(g, fit(r[2], tw), tx, ry + (rh - 8) / 2, Theme.INK);
            }
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(r[1] + ": " + r[2]).withStyle(ChatFormatting.WHITE));
            tip.addAll(tips.get(i));
            if (r[0].equals("map-2") && q != null) {
                host.quest(x, ry, w, rh, tip, q.quest());
            } else {
                host.area(x, ry, w, rh, null, tip, r[3]);
            }
        }
    }
}
