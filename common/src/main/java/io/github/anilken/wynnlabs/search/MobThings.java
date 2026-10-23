package io.github.anilken.wynnlabs.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The mobs of the search: first every mob the item data names as a source (an ingredient's
 * droppedBy, a miniboss of gear's dropMeta) with its spawn areas and what it drops, then the
 * wiki's other mobs ({@link MobWiki}) with their level, where they live and their drops. A wiki
 * row of the same name adds its level, place text and drops to the data's mob. Pure.
 */
final class MobThings {

    /** A data mob's type: it drops items the data knows (spawn areas when the data has them). */
    static final String DROPS = "drops";
    /** A miniboss the gear data names. */
    static final String MINIBOSS = "miniboss";
    /** A mob only the wiki lists. */
    static final String WIKI = "wiki";

    private MobThings() {}

    /** One mutable mob while the lists are merged. */
    private static final class Mob {
        final String name;
        String type;
        int level;
        String where = "";
        final List<Area> areas = new ArrayList<>();
        final List<String> drops = new ArrayList<>();

        Mob(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }

    static List<Entry> of(List<Entry> items, List<MobWiki.Mob> wiki) {
        Map<String, Mob> mobs = new LinkedHashMap<>();
        for (Entry e : items) {
            for (Entry.Origin o : e.origins()) {
                boolean mob = o.type().equals("mob");
                if (!mob && !o.type().equals("miniboss") || o.name() == null) {
                    continue;
                }
                String name = clean(o.name());
                if (name.isEmpty()) {
                    continue;
                }
                Mob m = mobs.computeIfAbsent(key(name), k -> new Mob(name, mob ? DROPS : MINIBOSS));
                o.areas().stream().filter(a -> !m.areas.contains(a)).forEach(m.areas::add);
                if (!m.drops.contains(e.name())) {
                    m.drops.add(e.name());
                }
            }
        }
        for (MobWiki.Mob w : wiki) {
            Mob m = mobs.computeIfAbsent(key(w.name()), k -> new Mob(w.name(), WIKI));
            if (m.level == 0) {
                m.level = w.level();
            }
            String place = w.where().isEmpty() ? w.region() : w.where();
            if (m.where.isEmpty()) {
                m.where = place == null ? "" : place;
            }
            w.drops().stream().filter(d -> !m.drops.contains(d)).forEach(m.drops::add);
        }
        List<Entry> out = new ArrayList<>(mobs.size());
        for (Mob m : mobs.values()) {
            List<Entry.Origin> links = new ArrayList<>();
            if (!m.areas.isEmpty()) {
                links.add(new Entry.Origin(Entry.AT, null, List.copyOf(m.areas)));
            }
            if (!m.where.isEmpty()) {
                links.add(new Entry.Origin(Entry.WHERE, m.where, List.of()));
            }
            for (String d : m.drops) {
                links.add(new Entry.Origin(Entry.ITEM, d, List.of()));
            }
            out.add(Entry.thing(m.name, Kind.MOB, m.type, m.level, links));
        }
        return out;
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT).trim();
    }

    /** A mob name without the font's private icons or broken characters ("�Fool Eater" → "Fool Eater"). */
    static String clean(String name) {
        StringBuilder b = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '�' || c >= '' && c <= '' || Character.isSurrogate(c) || c == '§') {
                if (c == '§') {
                    i++;                                // a colour code takes the next letter with it
                }
                continue;
            }
            b.append(c);
        }
        return b.toString().replaceAll("\\s+", " ").trim();
    }
}
