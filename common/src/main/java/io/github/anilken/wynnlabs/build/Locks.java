package io.github.anilken.wynnlabs.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.anilken.wynnlabs.build.roadmap.Roadmap;
import io.github.anilken.wynnlabs.core.Dirs;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Saver;
import io.github.anilken.wynnlabs.game.GameState;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.SkillKind;
import io.github.anilken.wynnlabs.model.Slot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The items you chose to build around (the home tab's "Choose items"): any gear from the game's
 * item database, one per slot and two rings, saved per character in
 * {@code config/wynnlabs/characters/<id>/build-around.json}. The road map keeps them in their
 * slots and fits the rest around them ({@link Roadmap#build}). Names stay as the database writes
 * them, in English. Nothing here acts in the game.
 */
public final class Locks {

    /** Rings you may choose: one per hand. */
    public static final int RINGS = 2;

    private static final String FILE = "build-around.json";

    /** The character the list below was read for; null before the first read. */
    private static volatile String loadedFor;
    private static volatile List<String> names = List.of();
    private static volatile Tuning.Choice tuning = Tuning.Choice.PLAIN;
    /** Tests and the preview: this list instead of the character's file; null for the file. */
    private static volatile List<String> override;
    /** Tests and the preview: this tuning instead of the character's file; null for the file. */
    private static volatile Tuning.Choice tuningOverride;
    /** Tests: the folder that holds the characters' folders instead of config/wynnlabs/characters. */
    public static volatile Path rootOverride;

    private Locks() {}

    /** Tests and the preview: build around {@code list} (null: the saved one again); never written. */
    public static void use(List<String> list) {
        override = list == null ? null : List.copyOf(list);
    }

    /** Tests and the preview: this tuning (null: the saved one again); never written. */
    public static void useTuning(Tuning.Choice c) {
        tuningOverride = c;
    }

    /** The chosen items' names for the character you play, in the order chosen. */
    public static List<String> names() {
        List<String> o = override;
        if (o != null) {
            return o;
        }
        load();
        return names;
    }

    /** The tuning strip's setting for the character you play. */
    public static Tuning.Choice tuning() {
        Tuning.Choice o = tuningOverride;
        if (o != null) {
            return o;
        }
        load();
        return tuning;
    }

    /** Chooses these items for the character you play and saves them. */
    public static void set(List<String> list) {
        List<String> clean = new ArrayList<>();
        for (String n : list) {
            if (n != null && !n.isBlank() && !clean.contains(n)) {
                clean.add(n);
            }
        }
        if (override != null) {
            override = List.copyOf(clean);
            return;
        }
        load();
        names = List.copyOf(clean);
        save();
    }

    /** Sets the tuning strip for the character you play and saves it with the chosen items. */
    public static void setTuning(Tuning.Choice c) {
        Tuning.Choice t = c == null ? Tuning.Choice.PLAIN : c;
        if (tuningOverride != null) {
            tuningOverride = t;
            return;
        }
        load();
        tuning = t;
        save();
    }

    /** No chosen items any more (the tuning stays). */
    public static void clear() {
        set(List.of());
    }

    /** What the road map's cache key takes from the choice and the tuning: it is built again when this changes. */
    static String key() {
        String t = tuning().key();
        return String.join("|", names()) + (t.isEmpty() ? "" : "#" + t);
    }

    private static String characterId() {
        try {
            return GameState.current().characterId();
        } catch (Throwable t) {
            return "";
        }
    }

    private static Path file(String id) {
        Path root = rootOverride != null ? rootOverride : Dirs.config().resolve("characters");
        return root.resolve(id).resolve(FILE);
    }

    private static void load() {
        String id = characterId();
        if (!id.equals(loadedFor)) {
            synchronized (Locks.class) {
                if (!id.equals(loadedFor)) {
                    read(id);
                    loadedFor = id;
                }
            }
        }
    }

    private static void save() {
        String id = characterId();
        if (id.isEmpty()) {
            return;
        }
        JsonObject o = new JsonObject();
        JsonArray a = new JsonArray();
        names.forEach(a::add);
        o.add("items", a);
        Tuning.Choice t = tuning;
        JsonObject tj = new JsonObject();
        tj.addProperty("focus", t.focus());
        tj.addProperty("mana", t.mana());
        tj.addProperty("speed", t.speed());
        tj.addProperty("budget", t.budget());
        o.add("tuning", tj);
        String text = o.toString();
        Saver.mark(file(id), () -> text);
    }

    /** Reads the character's file into the list and the tuning; nothing chosen when there is none. */
    private static void read(String id) {
        names = List.of();
        tuning = Tuning.Choice.PLAIN;
        if (id.isEmpty()) {
            return;
        }
        try {
            Path f = file(id);
            Saver.settle(f);
            if (!Files.exists(f)) {
                return;
            }
            String text = Files.readString(f, StandardCharsets.UTF_8);
            Saver.known(f, text);
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            List<String> out = new ArrayList<>();
            if (o.has("items") && o.get("items").isJsonArray()) {
                o.getAsJsonArray("items").forEach(e -> out.add(e.getAsString()));
            }
            names = List.copyOf(out);
            if (o.has("tuning") && o.get("tuning").isJsonObject()) {
                JsonObject t = o.getAsJsonObject("tuning");
                int focus = t.has("focus") ? t.get("focus").getAsInt() : Tuning.BALANCED;
                tuning = new Tuning.Choice(focus < Tuning.BALANCED || focus > Tuning.TANKY ? Tuning.BALANCED : focus,
                        t.has("mana") && t.get("mana").getAsBoolean(), t.has("speed") && t.get("speed").getAsBoolean(),
                        t.has("budget") ? Math.max(0, t.get("budget").getAsLong()) : 0);
            }
        } catch (Throwable t) {
            System.out.println("[WynnLabs] could not read " + FILE + ": " + t);
        }
    }

    /** Tests: the next read takes the file again. */
    public static void forget() {
        loadedFor = null;
        names = List.of();
        tuning = Tuning.Choice.PLAIN;
    }


    // ---- what a choice means ----

    /** The kind of slot a piece goes into: the weapon as WEAPON, rings as RING, the rest as they are. */
    public static Slot kind(ItemData info) {
        return info.slot().isWeapon() ? Slot.WEAPON : info.slot();
    }

    /**
     * Whether the class can wear this piece at some level: armour and accessories, and weapons
     * of its class. Any level: a set around a piece above yours is planned for the level it needs.
     */
    public static boolean usable(ItemData info, Klass cls) {
        if (info == null) {
            return false;
        }
        Slot t = info.slot();
        if (!(t.isArmour() || t.isAccessory() || t.isWeapon())) {
            return false;
        }
        Klass need = info.requirements().klass().orElse(t.klass());
        return need == null || need == Klass.NONE || need == cls;
    }

    /**
     * The choice with {@code info} clicked: taken out when it was in, else put in, replacing the
     * piece chosen for its slot (rings: the first ring once two are chosen).
     */
    public static List<ItemData> toggle(List<ItemData> chosen, ItemData info) {
        List<ItemData> out = new ArrayList<>(chosen);
        if (out.removeIf(i -> i.name().equals(info.name()))) {
            return out;
        }
        Slot k = kind(info);
        if (k == Slot.RING) {
            long rings = out.stream().filter(i -> kind(i) == Slot.RING).count();
            if (rings >= RINGS) {
                for (int i = 0; i < out.size(); i++) {
                    if (kind(out.get(i)) == Slot.RING) {
                        out.remove(i);
                        break;
                    }
                }
            }
        } else {
            out.removeIf(i -> kind(i) == k);
        }
        out.add(info);
        return out;
    }

    /**
     * The chosen items the character can wear now, from the item database, at most one per slot
     * and two rings (a hand-edited file can hold more: the later ones are left out).
     */
    public static List<ItemData> resolve(GameState state, Collection<String> chosen) {
        Map<String, ItemData> byName = new LinkedHashMap<>();
        if (chosen.isEmpty()) {
            return List.of();
        }
        state.allGear().forEach(i -> {
            if (chosen.contains(i.name())) {
                byName.putIfAbsent(i.name(), i);
            }
        });
        List<ItemData> out = new ArrayList<>();
        for (String n : chosen) {
            ItemData info = byName.get(n);
            if (info == null || !usable(info, state.classType())) {
                continue;
            }
            Slot k = kind(info);
            long same = out.stream().filter(i -> kind(i) == k).count();
            if (same < (k == Slot.RING ? RINGS : 1)) {
                out.add(info);
            }
        }
        return out;
    }

    /**
     * Why these pieces cannot be worn together at {@code level}: the skill points they need (their
     * own bonuses counted) against the points earned by then, or a skill past 100 even with the
     * most the slots still open could give it. Null when they fit.
     */
    public record Problem(int need, int earned, SkillKind skill, int skillNeed) {

        /** "needs 120 skill points, 86 earned" / "needs 112 Dexterity, 100 is the most", in your language. */
        public String text() {
            return skill != null ? L.t("wynnlabs.around.problem_skill", skillNeed, skill.displayName())
                    : L.t("wynnlabs.around.problem_points", need, earned);
        }
    }

    public static Problem problem(Collection<ItemData> items, int level) {
        if (items.isEmpty()) {
            return null;
        }
        List<Damage.Piece> pieces = new ArrayList<>();
        items.forEach(i -> pieces.add(Damage.piece(i)));
        int[] min = Build.minPoints(pieces);
        int earned = Math.max(0, (Math.min(level, 101) - 1) * 2);
        int used = 0;
        int[] reach = null;
        for (SkillKind k : SkillKind.values()) {
            int v = min[k.ordinal()];
            used += v;
            if (v > 100) {
                if (reach == null) {
                    reach = openReach(items, level);
                }
                if (v - reach[k.ordinal()] > 100) {
                    return new Problem(0, earned, k, v);
                }
            }
        }
        return used > earned ? new Problem(used, earned, null, 0) : null;
    }

    /**
     * The most each skill could get from the armour and accessory slots none of {@code items}
     * fills, among the gear your class can wear at {@code level} (and your tomes).
     */
    private static int[] openReach(Collection<ItemData> items, int level) {
        String key = level + ":" + items.stream().map(ItemData::name).sorted().toList();
        Object[] last = lastReach;
        if (last != null && last[0].equals(key)) {
            return (int[]) last[1];             // the pick view asks every frame
        }
        GameState state = GameState.current();
        java.util.Set<Slot> taken = new java.util.HashSet<>();
        items.forEach(i -> taken.add(kind(i)));
        List<Damage.Piece> open = new ArrayList<>();
        state.allGear().filter(i -> i.level() <= level && usable(i, state.classType()) && !taken.contains(kind(i)))
                .forEach(i -> open.add(Damage.piece(i)));
        int[] reach = Build.reach(open);
        lastReach = new Object[] {key, reach};
        return reach;
    }

    /** {key, reach} of the last {@link #openReach}. */
    private static volatile Object[] lastReach;
}
