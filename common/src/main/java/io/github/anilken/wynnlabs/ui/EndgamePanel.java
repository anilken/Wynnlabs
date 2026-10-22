package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.endgame.ItemIcons;
import io.github.anilken.wynnlabs.endgame.RaidPool;
import io.github.anilken.wynnlabs.endgame.WynnventoryPools;
import io.github.anilken.wynnlabs.model.AspectEntry;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.PoolItem;
import io.github.anilken.wynnlabs.model.Tier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the Endgame tab's panels share: the click and hover areas they add, the pack glyphs, and the
 * small text and drawing helpers. Each panel draws one part of {@link EndgameTab} into the tab's
 * frame state; the view state itself stays on the tab.
 */
abstract class EndgamePanel {

    /** The tab whose frame state (font, clock, data, click and hover areas) the panel draws into. */
    final EndgameTab tab;

    EndgamePanel(EndgameTab tab) {
        this.tab = tab;
    }

    // pack glyphs (drawn when the Wynncraft pack has them, a plain mark in the same place otherwise)
    static final String TEX_RAID = "wynn/gui/content_book/raid_active";
    static final String TEX_CAMP = "wynn/gui/content_book/loot_camp_active";
    static final String TEX_LOOT = "wynn/gui/loot/";
    static final String TEX_ASPECTS_BUTTON = "wynn/gui/ability_tree/button/aspects";

    static final long MIN = 60_000L;
    static final long HOUR = 60 * MIN;
    static final long DAY = 24 * HOUR;

    /** A click area drawn this frame, and what a click there does. */
    record Act(int x, int y, int w, int h, Runnable run, String what) {
        boolean in(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    /** A hover area drawn this frame, and its tooltip lines. */
    record Tip(int x, int y, int w, int h, List<Component> lines) {
        boolean in(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    /** A card label's glyph (a 12 px line pitch holds it), drawn 2 px above its text line. */
    static final int ICON = 12;
    /** A list row's glyph (rows of 12), drawn 1 px above its text line: a little over the letters' height. */
    static final int ROW_ICON = 10;
    /** A pool from an earlier week: its header and source drawn greyed. */
    static final int GREYED = Theme.LINE;

    /** Words of {@code s} in lines no wider than {@code w}. */
    List<String> wrap(String s, int w) {
        List<String> out = new ArrayList<>();
        String cur = "";
        for (String word : s.split(" ")) {
            String next = cur.isEmpty() ? word : cur + " " + word;
            if (cur.isEmpty() || tab.font.width(next) <= w) {
                cur = next;
            } else {
                out.add(cur);
                cur = word;
            }
        }
        if (!cur.isEmpty()) {
            out.add(cur);
        }
        return out;
    }

    String nice(Klass k) {
        if (k == null || k == Klass.NONE) {
            return "";
        }
        String n = k.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    int pipsW(int tiers) {
        return Math.max(0, tiers * 7 - 2);
    }

    /** One pip per tier: filled in the tier colour up to the owned tier, empty ones in the well colour. */
    void pips(GuiGraphics g, int x, int y, int tiers, int owned, int colour) {
        for (int i = 0; i < tiers; i++) {
            int px = x + i * 7;
            if (i < owned) {
                g.fill(px, y, px + 5, y + 5, colour);
            } else {
                g.fill(px, y, px + 5, y + 5, Theme.WELL);
                Kit.outline(g, px, y, 5, 5, Theme.LINE);
            }
        }
    }

    /** "NEW" or "+T2" (the tier the tag names, {@link RaidPool#tagTier}) for an aspect's mark in the pool shown; null for none. */
    String tagText(String aspect) {
        RaidPool.Mark m = RaidPool.markOf(aspect);
        if (m == RaidPool.Mark.NEW) {
            return L.t("wynnlabs.endgame.tag_new");
        }
        if (m == RaidPool.Mark.TIER_UP) {
            int tier = RaidPool.tagTier(aspect);
            return tier > 0 ? L.t("wynnlabs.endgame.tag_tier_up", tier) : null;
        }
        return null;
    }

    /** A small green tag with light text. */
    void tag(GuiGraphics g, String text, int x, int y) {
        int w = Kit.Ui.tagW(tab.font, text);
        Art.pill(g, x, y, w, 10, Theme.GOOD, 0);
        Kit.text(g, tab.font, text, x + Kit.Ui.TAG_PAD, y + 1, Theme.CARD);
    }

    /** A pack glyph without its empty margin (the content book's 32 px raid glyph is 19 px of it), else an icon. */
    void glyph(GuiGraphics g, String tex, String fallback, int x, int y, int size, int colour) {
        if (!WynnTex.drawTrimmed(g, tex, x, y, size)) {
            Art.icon(g, fallback, x, y, size, colour);
        }
    }

    void mythicGlyph(GuiGraphics g, int x, int y, int size) {
        glyph(g, TEX_LOOT + "mythic", "diamond", x, y, size, Theme.TIER_MYTHIC);
    }

    /**
     * An aspect's own picture, drawn the way the Pools view draws it (the game's aspect item for
     * its class, then the pack texture, then a glyph), so the list and the pools look alike.
     */
    void aspectGlyph(GuiGraphics g, AspectEntry aspect, int x, int y, int size) {
        Klass k = aspect.klass() == null ? Klass.NONE : aspect.klass();
        if (k == Klass.NONE) {
            rarityGlyph(g, aspect.rarity(), x, y, size);
            return;
        }
        String subtype = k.name().charAt(0) + k.name().substring(1).toLowerCase(Locale.ROOT) + "Aspect";
        ItemIcons.draw(g, new PoolItem(aspect.name(), "AspectItem", aspect.rarity(), subtype, 1), k, x, y, size, false);
    }

    void rarityGlyph(GuiGraphics g, Tier rarity, int x, int y, int size) {
        String r = rarity == null ? "legendary" : rarity.name().toLowerCase(Locale.ROOT);
        glyph(g, TEX_LOOT + r, "diamond", x, y, size, tierColour(rarity));
    }

    static int tierColour(Tier t) {
        if (t == null) {
            return Theme.INK;
        }
        return switch (t) {
            case MYTHIC -> Theme.TIER_MYTHIC;
            case FABLED -> Theme.TIER_FABLED;
            case LEGENDARY -> Theme.TIER_LEGENDARY;
            case RARE -> Theme.TIER_RARE;
            case UNIQUE -> Theme.TIER_UNIQUE;
            case SET -> Theme.TIER_SET;
            default -> Theme.INK;
        };
    }

    static ChatFormatting tierChat(Tier t) {
        if (t == null) {
            return ChatFormatting.WHITE;
        }
        return switch (t) {
            case MYTHIC -> ChatFormatting.DARK_PURPLE;
            case FABLED -> ChatFormatting.RED;
            case LEGENDARY -> ChatFormatting.AQUA;
            default -> t.colour();
        };
    }

    static String rarityName(Tier t) {
        if (t == null) {
            return "";
        }
        String n = t.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    /** A beacon colour's mark (English names as Wynntils gives them). */
    static int beaconColour(String name) {
        return switch (name == null ? "" : name.toLowerCase(Locale.ROOT)) {
            case "blue" -> 0xFF3A6FD0;
            case "purple" -> 0xFF8A4AC8;
            case "yellow" -> 0xFFE0C030;
            case "rainbow" -> 0xFFE070B0;
            case "red" -> 0xFFC83A30;
            case "green" -> 0xFF4AA040;
            case "orange" -> 0xFFE08A30;
            case "aqua" -> 0xFF40C0C8;
            case "gray", "grey" -> 0xFF9A9A9A;
            case "dark gray", "dark grey" -> 0xFF555555;
            case "white" -> 0xFFF0F0F0;
            case "crimson" -> 0xFF8A1A2A;
            default -> Theme.INK_3;
        };
    }

    void ink(GuiGraphics g, String s, int x, int y, int colour) {
        Kit.text(g, tab.font, s, x, y, colour);
    }

    /** Pieces of one line, each in its own colour, cut with "…" where the line runs out of {@code w}. */
    void segments(GuiGraphics g, int x, int y, int w, String[] parts, int[] colours) {
        int cx = x;
        int end = x + w;
        for (int i = 0; i < parts.length; i++) {
            String s = parts[i];
            if (s == null || s.isEmpty()) {
                continue;
            }
            int sw = tab.font.width(s);
            if (cx + sw <= end) {
                ink(g, s, cx, y, colours[i]);
                cx += sw;
                continue;
            }
            // the line runs out here: this piece is the last one, cut
            String f = fit(s, end - cx);
            if (!f.isEmpty()) {
                ink(g, f, cx, y, colours[i]);
            }
            return;
        }
    }

    /** Why the in-game pool is shown instead of Wynnventory's, or null when the gate is not the reason. */
    static String reason(WynnventoryPools.Status s) {
        if (s == null) {
            return null;
        }
        return switch (s) {
            case OFF -> L.t("wynnlabs.endgame.reason_off");
            case NO_MOD -> L.t("wynnlabs.endgame.reason_no_mod");
            case NO_KEY -> L.t("wynnlabs.endgame.reason_no_key");
            case KEY_REFUSED -> L.t("wynnlabs.endgame.reason_refused");
            case FAILED -> L.t("wynnlabs.endgame.reason_failed");
            default -> null;
        };
    }

    /** Text right-aligned to {@code right}. */
    void right(GuiGraphics g, String s, int right, int y, int colour) {
        Kit.text(g, tab.font, s, right - tab.font.width(s), y, colour);
    }

    static MutableComponent bold(String s) {
        return Component.literal(s).withStyle(UiFont::bold);
    }

    static Component title(String s, ChatFormatting f) {
        return Component.literal(s).withStyle(f);
    }

    static Component grey(String s) {
        return Component.literal(s).withStyle(ChatFormatting.GRAY);
    }

    static Component dark(String s) {
        return Component.literal(s).withStyle(ChatFormatting.DARK_GRAY);
    }

    static Component warn(String s) {
        return Component.literal(s).withStyle(ChatFormatting.GOLD);
    }

    static Component good(String s) {
        return Component.literal(s).withStyle(ChatFormatting.GREEN);
    }

    String fit(String s, int w) {
        if (s == null || w <= 0) {
            return "";
        }
        if (tab.font.width(s) <= w) {
            return s;
        }
        return tab.font.plainSubstrByWidth(s, Math.max(0, w - tab.font.width("…"))) + "…";
    }

    /**
     * {@code s} in bold, cut with "…" to {@code w} measured in bold: the game's bold is a pixel
     * wider per letter (the web font's bold face differs too), so a cut measured plain runs over.
     */
    MutableComponent boldFit(String s, int w) {
        if (s == null || w <= 0) {
            return Component.empty();
        }
        MutableComponent whole = bold(s);
        if (tab.font.width(whole) <= w) {
            return whole;
        }
        String cut = tab.font.substrByWidth(whole, Math.max(0, w - tab.font.width(bold("…")))).getString();
        return bold(cut.stripTrailing() + "…");
    }

    /**
     * {@code s} in bold over at most {@code maxLines} lines of {@code w}, broken between words and
     * measured in bold; the last line is cut with "…" when the rest does not fit.
     */
    List<MutableComponent> boldLines(String s, int w, int maxLines) {
        List<MutableComponent> out = new ArrayList<>();
        if (s == null || s.isEmpty() || w <= 0) {
            return out;
        }
        String rest = s;
        while (out.size() < maxLines - 1 && tab.font.width(bold(rest)) > w) {
            String[] words = rest.split(" ");
            String line = "";
            int used = 0;
            for (String word : words) {
                String next = line.isEmpty() ? word : line + " " + word;
                if (!line.isEmpty() && tab.font.width(bold(next)) > w) {
                    break;
                }
                line = next;
                used++;
            }
            if (used >= words.length || tab.font.width(bold(line)) > w) {
                break;                                  // one word wider than the line: cut it instead
            }
            out.add(bold(line));
            rest = String.join(" ", java.util.Arrays.copyOfRange(words, used, words.length));
        }
        out.add(boldFit(rest, w));
        return out;
    }

    static String num(int v) {
        return v < 0 ? "?" : String.valueOf(v);
    }

    /** A time as m:ss (h:mm:ss from an hour), "--" when unknown. */
    static String time(long ms) {
        if (ms < 0) {
            return "--";
        }
        long s = ms / 1000;
        if (s >= 3600) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
        }
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /** How long ago, short ("2h", "1d") for a column. */
    String ago(long at) {
        long d = Math.max(0, tab.now - at);
        if (d < HOUR) {
            return L.t("wynnlabs.endgame.ago_m", Math.max(1, d / MIN));
        }
        if (d < DAY) {
            return L.t("wynnlabs.endgame.ago_h", d / HOUR);
        }
        return L.t("wynnlabs.endgame.ago_d", d / DAY);
    }

    /** How long ago, in words ("2 h ago"). */
    String agoLong(long at) {
        long d = Math.max(0, tab.now - at);
        if (d < HOUR) {
            return L.t("wynnlabs.screen.ago_min", Math.max(1, d / MIN));
        }
        if (d < DAY) {
            return L.t("wynnlabs.screen.ago_hours", d / HOUR);
        }
        return L.n("wynnlabs.screen.ago_days", d / DAY);
    }
}
