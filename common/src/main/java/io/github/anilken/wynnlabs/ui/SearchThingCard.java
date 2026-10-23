package io.github.anilken.wynnlabs.ui;

import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.quests.Discoveries;
import io.github.anilken.wynnlabs.quests.QuestBook;
import io.github.anilken.wynnlabs.search.Area;
import io.github.anilken.wynnlabs.search.Compass;
import io.github.anilken.wynnlabs.search.Entry;
import io.github.anilken.wynnlabs.search.ItemIndex;
import io.github.anilken.wynnlabs.search.Kind;
import io.github.anilken.wynnlabs.search.Source;
import io.github.anilken.wynnlabs.search.SourceRank;
import io.github.anilken.wynnlabs.search.Things;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;
import java.util.Locale;

/**
 * The search card's parts for a thing of the world (mob, place, dungeon, service, resource,
 * quest, NPC, discovery) where an item has its own: the words under its name, the status line
 * (a quest done or not, a discovery found or not, where a mob lives), the label of its place
 * rows, and the sections under them: what a mob drops or a dungeon gives (each opens that item's
 * card), a resource's materials, an NPC's quests, and the way to the quest's or discovery's own
 * tab. Read-only: the one thing it changes is which tab or card is open.
 */
final class SearchThingCard extends MenuPaint {

    /** The place and content types with their own words ({@code wynnlabs.search.thing.<type>}). */
    private static final java.util.Set<String> TYPES = java.util.Set.of("city", "town", "province", "dungeon",
            "corrupted_dungeon", "raid", "cave", "boss_altar", "grind_spot", "lootrun_camp", "time_trial", "shrine");

    private final SearchCardPanel card;
    private final SearchTab tab;

    SearchThingCard(SearchCardPanel card, SearchTab tab) {
        super(tab.menu);
        this.card = card;
        this.tab = tab;
    }

    // ---- words ----

    /** "Dungeon", "City", "Mob", "Blacksmith"'s "Service": the thing's type in a word or two. */
    static String typeText(Entry e) {
        String type = e.type() == null ? "" : e.type().replace('-', '_').replace(':', '_');
        return switch (e.kind()) {
            case PLACE, CONTENT -> TYPES.contains(type) ? L.t("wynnlabs.search.thing." + type)
                    : L.t("wynnlabs.search.kind." + e.kind().name().toLowerCase(Locale.ROOT));
            case MOB -> "miniboss".equals(e.type()) ? L.t("wynnlabs.search.type.miniboss") : L.t("wynnlabs.search.kind.mob");
            default -> L.t("wynnlabs.search.kind." + e.kind().name().toLowerCase(Locale.ROOT));
        };
    }

    /** The results row's line under the name: the type and one fact ("Mob · Mesa", "Service · 65 places"). */
    static String sub(Entry e) {
        String type = typeText(e);
        String more = switch (e.kind()) {
            case MOB -> first(e, Entry.WHERE);
            case SERVICE, RESOURCE -> L.n("wynnlabs.search.thing.places", e.places().size());
            case QUEST -> first(e, Entry.NPC);
            case NPC -> first(e, Entry.QUEST);
            case DISCOVERY -> first(e, Entry.WHERE);
            default -> null;
        };
        if (e.kind() == Kind.RESOURCE && e.type() != null) {
            more = SearchResultsPanel.words(e.type()) + " · " + more;
        }
        return more == null || more.isBlank() ? type : type + " · " + more;
    }

    private static String first(Entry e, String link) {
        List<Entry.Origin> l = e.links(link);
        return l.isEmpty() ? null : l.get(0).name();
    }

    /** The results table's hint: how far the nearest place is and which way, else the type. */
    static String hint(Entry e, double[] pos) {
        List<Area> places = e.places();
        if (places.isEmpty() || pos == null) {
            return sourceType(e);
        }
        Area a = places.size() == 1 ? places.get(0) : nearest(places, pos);
        double d = Compass.distance(a, pos);
        String dir = Compass.direction(a, pos);
        return RoadmapScreen.dist((int) Math.round(d)) + (dir == null ? "" : " " + SearchCardPanel.dirName(dir));
    }

    private static String sourceType(Entry e) {
        return io.github.anilken.wynnlabs.search.SourceText.type(Things.type(e));
    }

    private static Area nearest(List<Area> places, double[] pos) {
        Area best = places.get(0);
        double bd = Double.MAX_VALUE;
        for (Area a : places) {
            double d = Compass.distance(a, pos);
            if (d < bd) {
                bd = d;
                best = a;
            }
        }
        return best;
    }

    /** A card row's words: a mob's spawn areas, a service's town, else the row's own name. */
    static String rowText(Entry e, Source s) {
        if (e.kind() == Kind.MOB) {
            return s.hasPlace() ? L.t("wynnlabs.search.thing.spawn") : L.t("wynnlabs.search.thing.no_spawn");
        }
        if (e.kind() == Kind.QUEST) {
            return L.t("wynnlabs.search.start_npc", s.name());
        }
        return s.name() == null ? e.name() : s.name();
    }

    /** What the card's map marks say: the thing, and the row's own name when it differs. */
    static String markText(Entry e, Source s) {
        return s.name() == null || s.name().equals(e.name()) ? e.name() : e.name() + " · " + s.name();
    }

    /** The head's third line: a quest done or not, a discovery found or not, where a mob lives; empty when none. */
    static Component status(Entry e) {
        MutableComponent out = Component.empty();
        switch (e.kind()) {
            case QUEST -> {
                QuestBook.Entry q = quest(e.name());
                if (q != null) {
                    boolean done = "COMPLETED".equals(q.status());
                    String key = done ? "completed" : "STARTED".equals(q.status()) ? "started" : "not_started";
                    out.append(Component.literal(L.t("wynnlabs.search.quest_status." + key)).withStyle(ChatFormatting.GRAY));
                    if (done) {
                        out.append(Component.literal(" ✔").withStyle(ChatFormatting.DARK_GREEN));
                    }
                    if (q.level() > 0) {
                        out.append(Component.literal(" · Lv " + q.level()).withStyle(ChatFormatting.GRAY));
                    }
                }
            }
            case DISCOVERY -> {
                List<Entry.Origin> id = e.links(Entry.ID);
                Discoveries.Entry d = id.isEmpty() ? null : Discoveries.englishOf(id.get(0).name());
                if (d != null) {
                    boolean found = Discoveries.done(d, Discoveries.foundInGame(), GameState.current().characterId());
                    out.append(Component.literal(L.t(found ? "wynnlabs.search.found" : "wynnlabs.search.not_found"))
                            .withStyle(ChatFormatting.GRAY));
                    out.append(Component.literal(found ? " ✔" : " ✘").withStyle(found ? ChatFormatting.DARK_GREEN : ChatFormatting.RED));
                }
            }
            case MOB, CONTENT, NPC -> {
                String where = first(e, e.kind() == Kind.NPC ? Entry.QUEST : Entry.WHERE);
                if (where != null) {
                    out.append(Component.literal(where).withStyle(ChatFormatting.GRAY));
                }
            }
            default -> {
                // places, services and resources: the head's level and type say it all
            }
        }
        return out;
    }

    private static QuestBook.Entry quest(String name) {
        for (QuestBook.Entry q : GameState.current().quests()) {
            if (q.name().equalsIgnoreCase(name)) {
                return q;
            }
        }
        return null;
    }

    /** Whether the thing has a wiki page worth a button (a service type or a resource has none of its own). */
    static boolean wiki(Entry e) {
        return e.kind() != Kind.SERVICE && e.kind() != Kind.RESOURCE;
    }

    /** The badge drawn as a thing's picture, in a slot of {@code size}. */
    static void picture(GuiGraphics g, Entry e, int x, int y, int size) {
        String badge = Things.badge(e);
        // a plain rounded square in the badge's colour: the seal's stamped look reads as an award at this size
        int c = Theme.sourceFill(badge);
        int r = Math.max(2, size / 8);
        g.fill(x + r, y, x + size - r, y + size, c);
        g.fill(x, y + r, x + size, y + size - r, c);
        for (int i = 1; i < r; i++) {
            int in = r - (int) Math.round(Math.sqrt((double) r * r - (double) (r - i) * (r - i)));
            g.fill(x + in, y + i, x + r, y + i + 1, c);
            g.fill(x + size - r, y + i, x + size - in, y + i + 1, c);
            g.fill(x + in, y + size - i - 1, x + r, y + size - i, c);
            g.fill(x + size - r, y + size - i - 1, x + size - in, y + size - i, c);
        }
        int icon = size >= 30 ? 16 : 8;
        Art.icon(g, badge, x + (size - icon) / 2, y + (size - icon) / 2, icon, Theme.CREAM);
    }

    /** The cross-link of an item's source row: the thing it names, or null (the effort guard keeps owned rows out). */
    static Entry linked(Source s) {
        return SourceRank.effort(s.type()) == 0 ? null : Things.of(ItemIndex.get(), s.type(), s.name());
    }

    // ---- the sections under the rows ----

    int draw(GuiGraphics g, Entry e, int x, int y, int w, int mx, int my) {
        int cy = y;
        ItemIndex index = ItemIndex.get();
        List<Entry.Origin> items = e.links(Entry.ITEM);
        if (!items.isEmpty()) {
            String title = switch (e.kind()) {
                case MOB -> L.t("wynnlabs.search.sec_drops");
                case RESOURCE -> L.t("wynnlabs.search.sec_materials");
                default -> L.t("wynnlabs.search.sec_from_here");
            };
            section(g, "▾ " + title + " (" + items.size() + ")", x, cy + 1, w);
            cy += 14;
            for (Entry.Origin o : items) {
                Entry item = index.byKey(o.name());
                cy = item == null ? line(g, o.name(), x, cy, w) : link(g, o.name(), x, cy, w, mx, my, () -> tab.show(item.key()));
            }
        }
        switch (e.kind()) {
            case QUEST -> cy = link(g, L.t("wynnlabs.search.open_quests"), x, cy + 2, w, mx, my, () -> {
                menu.questsTab.questSelected = e.name();
                menu.openTab(2);
            });
            case NPC -> {
                for (Entry.Origin q : e.links(Entry.QUEST)) {
                    Entry quest = index.byKey("quest:" + q.name());
                    cy = quest == null ? line(g, q.name(), x, cy, w)
                            : link(g, L.t("wynnlabs.search.quest_link", q.name()), x, cy, w, mx, my, () -> tab.show(quest.key()));
                }
            }
            case DISCOVERY -> {
                List<Entry.Origin> id = e.links(Entry.ID);
                if (!id.isEmpty()) {
                    String discovery = id.get(0).name();
                    cy = link(g, L.t("wynnlabs.search.open_discoveries"), x, cy + 2, w, mx, my, () -> {
                        menu.discoveries.discSelected = discovery;
                        menu.openTab(RoadmapScreen.TAB_DISCOVERIES);
                    });
                }
            }
            default -> {
                // the rows above are all there is
            }
        }
        return cy;
    }

    private int line(GuiGraphics g, String text, int x, int y, int w) {
        ink(g, fit(text, w - 6), x + 6, y, MUTED);
        return y + 11;
    }

    private int link(GuiGraphics g, String text, int x, int y, int w, int mx, int my, Runnable run) {
        String t = fit(text, w - 6);
        boolean hover = card.over(mx, my) && mx >= x && mx < x + w && my >= y - 1 && my < y + 10;
        ink(g, t, x + 6, y, hover ? Theme.GOLD_DEEP : Theme.LINK);
        card.click(x, y - 1, w, 11, run);
        return y + 11;
    }
}
