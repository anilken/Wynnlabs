package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.build.Build;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.game.ItemJson;
import io.github.anilken.wynnlabs.market.Vault;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.SkillKind;
import io.github.anilken.wynnlabs.quests.QuestBook;
import io.github.anilken.wynnlabs.search.Entry;
import io.github.anilken.wynnlabs.search.ItemIndex;
import io.github.anilken.wynnlabs.search.Kind;
import io.github.anilken.wynnlabs.search.Source;
import io.github.anilken.wynnlabs.search.Sources;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The card's folding sections under "How do I get it": "Yours" (bag and bank pages with counts),
 * "Stats" (gear's roll ranges, an ingredient's modifiers and professions) and "Related" (the
 * set's other pieces, items from the same place, the quest that gives it, crafting for an
 * ingredient). Also the requirement line of the card's head. Worked out once per choice.
 */
final class SearchCardSections extends MenuPaint {

    private final SearchCardPanel card;
    private final SearchTab tab;
    private boolean openOwned;
    private boolean openStats;
    private boolean openRelated;
    /** Names of related items (set pieces first), and the quest the item comes from (or null). */
    private List<String> related = List.of();
    private String quest;

    SearchCardSections(SearchCardPanel card, SearchTab tab) {
        super(tab.menu);
        this.card = card;
        this.tab = tab;
    }

    void select(Entry e) {
        ItemIndex index = ItemIndex.get();
        List<String> out = new ArrayList<>();
        for (String n : index.setPieces(e)) {
            if (!n.equals(e.name())) {
                out.add(n);
            }
        }
        // items from the same named place (the first one the data gives)
        String place = null;
        for (Entry.Origin o : e.origins()) {
            if (o.name() != null && !o.name().isBlank()) {
                place = o.name();
                break;
            }
        }
        quest = e.quest();
        for (Entry.Origin o : e.origins()) {
            if (Source.Type.of(o.type()) == Source.Type.QUEST && o.name() != null) {
                quest = o.name();
            }
        }
        if (place != null) {
            for (Entry other : index.entries()) {
                if (out.size() >= 8) {
                    break;
                }
                if (other != e && !out.contains(other.name())) {
                    for (Entry.Origin o : other.origins()) {
                        if (place.equals(o.name())) {
                            out.add(other.name());
                            break;
                        }
                    }
                }
            }
        }
        related = out;
    }

    /** What the player holds of this item: the bag's count and each bank page's (by page label). */
    static Sources.Owned owned(String name) {
        int bag = 0;
        try {
            for (ItemStack s : GameState.current().inventory()) {
                if (!s.isEmpty() && ItemJson.clean(s.getHoverName().getString()).equalsIgnoreCase(name)) {
                    bag += s.getCount();
                }
            }
        } catch (RuntimeException ignored) {
            // no bag yet
        }
        Map<String, Integer> bank = new LinkedHashMap<>();
        Vault.Group gr = Vault.find(name);
        if (gr != null) {
            for (Vault.Stored st : gr.places()) {
                bank.merge(Vault.label(st.page()), st.count(), Integer::sum);
            }
        }
        return new Sources.Owned(bag, bank);
    }

    /** "Lv 73 ✔ · Str 40 ✘ · Shaman ✔ · quest ✔": what the item asks against what the player has. */
    Component needs(Entry e) {
        MutableComponent out = Component.empty();
        GameState gs = GameState.current();
        List<Object[]> parts = new ArrayList<>();               // {text, Boolean met}
        if (e.level() > 0) {
            parts.add(new Object[] {"Lv " + e.level(), gs.level() <= 0 ? null : gs.level() >= e.level()});
        }
        if (e.klass() != Klass.NONE) {
            Klass mine = gs.classType();
            parts.add(new Object[] {nice(e.klass().name()), mine == null || mine == Klass.NONE ? null : mine == e.klass()});
        }
        e.skills().forEach((k, v) -> {
            SkillKind s = skill(k);
            if (s != null) {
                parts.add(new Object[] {s.displayName().substring(0, 3) + " " + v, Build.totalPoints(s) >= v});
            }
        });
        if (e.quest() != null) {
            Boolean done = null;
            for (QuestBook.Entry q : gs.quests()) {
                if (q.name().equalsIgnoreCase(e.quest())) {
                    done = "COMPLETED".equals(q.status());
                }
            }
            parts.add(new Object[] {L.t("wynnlabs.search.need_quest"), done});
        }
        for (int i = 0; i < parts.size(); i++) {
            Object[] p = parts.get(i);
            Boolean met = (Boolean) p[1];
            if (i > 0) {
                out.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY));
            }
            out.append(Component.literal((String) p[0]).withStyle(ChatFormatting.GRAY));
            if (met != null) {
                out.append(Component.literal(met ? " ✔" : " ✘").withStyle(met ? ChatFormatting.DARK_GREEN : ChatFormatting.RED));
            }
        }
        return out;
    }

    private static SkillKind skill(String key) {
        try {
            return SkillKind.valueOf(key.toUpperCase(Locale.ROOT).replace("DEFENSE", "DEFENCE"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---- drawing ----

    int draw(GuiGraphics g, int x, int y, int w, int mx, int my) {
        Entry e = card.entry;
        Sources.Owned have = card.have;
        int count = have.bag() + have.bank().values().stream().mapToInt(Integer::intValue).sum();
        int cy = fold(g, x, y, w, L.t("wynnlabs.search.sec_owned") + (count > 0 ? " (" + count + ")" : ""), openOwned, mx, my,
                () -> openOwned = !openOwned);
        if (openOwned) {
            if (count == 0) {
                cy = line(g, L.t("wynnlabs.search.owned_none"), x, cy, w, FAINT);
            }
            if (have.bag() > 0) {
                cy = line(g, L.t("wynnlabs.search.src.bag", have.bag()), x, cy, w, MUTED);
            }
            for (Map.Entry<String, Integer> p : have.bank().entrySet()) {
                cy = line(g, L.t("wynnlabs.search.src.bank", p.getKey(), p.getValue()), x, cy, w, MUTED);
            }
        }
        cy = fold(g, x, cy + 2, w, L.t("wynnlabs.search.sec_stats"), openStats, mx, my, () -> openStats = !openStats);
        if (openStats) {
            cy = stats(g, e, x, cy, w);
        }
        cy = fold(g, x, cy + 2, w, L.t("wynnlabs.search.sec_related"), openRelated, mx, my, () -> openRelated = !openRelated);
        if (openRelated) {
            cy = related(g, e, x, cy, w, mx, my);
        }
        return cy;
    }

    private int stats(GuiGraphics g, Entry e, int x, int y, int w) {
        int cy = y;
        if (e.kind() == Kind.INGREDIENT && !e.professions().isEmpty()) {
            List<String> prof = new ArrayList<>();
            e.professions().forEach(p -> prof.add(SearchResultsPanel.words(p)));
            cy = line(g, L.t("wynnlabs.search.professions", String.join(", ", prof)), x, cy, w, MUTED);
        }
        for (Entry.Stat s : e.stats()) {
            String range = s.min() == s.max() ? signed(s.min()) : signed(s.min()) + " … " + signed(s.max());
            cy = pair(g, SearchResultsPanel.statName(s.key()), range, x, cy, w, s.max() < 0 && s.min() < 0);
        }
        for (Map.Entry<String, Integer> m : e.modifiers().entrySet()) {
            cy = pair(g, SearchResultsPanel.statName(m.getKey()), signed(m.getValue()), x, cy, w, m.getValue() < 0);
        }
        if (cy == y) {
            cy = line(g, L.t("wynnlabs.search.stats_none"), x, cy, w, FAINT);
        }
        return cy;
    }

    private int related(GuiGraphics g, Entry e, int x, int y, int w, int mx, int my) {
        int cy = y;
        if (quest != null) {
            String q = quest;
            cy = link(g, L.t("wynnlabs.search.quest_link", q), x, cy, w, mx, my, () -> {
                menu.questsTab.questSelected = q;
                menu.openTab(2);
            });
        }
        if (e.kind() == Kind.INGREDIENT) {
            cy = link(g, L.t("wynnlabs.search.craft_link"), x, cy, w, mx, my, () -> menu.openTab(RoadmapScreen.TAB_CRAFT));
        }
        for (String n : related) {
            // searched for, as the search key does: an item outside the current results would
            // otherwise give way to the first result again on the next frame
            cy = link(g, n, x, cy, w, mx, my, () -> tab.show(n));
        }
        if (cy == y) {
            cy = line(g, L.t("wynnlabs.search.related_none"), x, cy, w, FAINT);
        }
        return cy;
    }

    /** A folding section's title row: ▸ closed, ▾ open; a click flips it. Returns the y under it. */
    private int fold(GuiGraphics g, int x, int y, int w, String title, boolean open, int mx, int my, Runnable flip) {
        boolean hover = card.over(mx, my) && mx >= x && mx < x + w && my >= y && my < y + 12;
        if (hover) {
            g.fill(x - 2, y - 1, x + w, y + 11, Theme.CARD_HOVER);
        }
        section(g, (open ? "▾ " : "▸ ") + title, x, y + 1, w);
        card.click(x - 2, y - 1, w + 2, 12, flip);
        return y + 14;
    }

    private int line(GuiGraphics g, String text, int x, int y, int w, int colour) {
        ink(g, fit(text, w - 6), x + 6, y, colour);
        return y + 11;
    }

    private int pair(GuiGraphics g, String name, String value, int x, int y, int w, boolean bad) {
        int vw = font().width(value);
        ink(g, fit(name, w - vw - 12), x + 6, y, MUTED);
        ink(g, value, x + w - vw, y, bad ? Theme.BAD : Theme.GOOD);
        return y + 11;
    }

    private int link(GuiGraphics g, String text, int x, int y, int w, int mx, int my, Runnable run) {
        String t = fit(text, w - 6);
        boolean hover = card.over(mx, my) && mx >= x && mx < x + w && my >= y - 1 && my < y + 10;
        ink(g, t, x + 6, y, hover ? Theme.GOLD_DEEP : Theme.LINK);
        card.click(x, y - 1, w, 11, run);
        return y + 11;
    }

    private static String signed(int v) {
        return v > 0 ? "+" + v : String.valueOf(v);
    }
}
