package io.github.anilken.wynnlabs.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.anilken.wynnlabs.core.Dirs;
import io.github.anilken.wynnlabs.core.L;
import io.github.anilken.wynnlabs.core.Saver;
import io.github.anilken.wynnlabs.game.GameState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds you applied and kept: the current plan (the gear panel's "Plan" pill shows it and
 * counts what you already wear of it) and the saved builds, as many as you like. Saved per
 * character in {@code config/wynnlabs/characters/<id>/saved-builds.json}; a missing or broken
 * file reads as none (a broken one is kept beside it as {@code .broken}). A plan never changes
 * on its own: only Apply, Clear and the saved builds' buttons change it. Nothing here acts in
 * the game.
 */
public final class Plans {

    /** The nine build slots in the panel's order: armour, the weapon, the accessories. */
    public static final String[] SLOTS = {"HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS", "WEAPON", "RING1", "RING2", "BRACELET",
        "NECKLACE"};

    private static final String FILE = "saved-builds.json";
    /** Name length a rename keeps. */
    public static final int MAX_NAME = 40;
    /** How long a delete can be undone, in ms. */
    static final long UNDO_MS = 6000;

    /**
     * One build: its id, name, the chosen items it was built around, the tuning strip it was
     * found with, its nine pieces by build slot (an empty slot is left out), its score and the
     * level it was found at, and when it was made and last changed.
     */
    public record Build(String id, String name, List<String> locks, Tuning.Choice tuning, Map<String, String> items,
            double score, int level, long created, long updated) {

        public Build {
            locks = List.copyOf(locks);
            items = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(items));
            tuning = tuning == null ? Tuning.Choice.PLAIN : tuning;
            name = name == null ? "" : name;
        }

        public Build named(String n) {
            return new Build(id, n, locks, tuning, items, score, level, created, updated);
        }

        public Build withId(String i) {
            return new Build(i, name, locks, tuning, items, score, level, created, updated);
        }

        /** Whether this holds {@code name} as one of its chosen items. */
        public boolean locked(String item) {
            return locks.contains(item);
        }
    }

    /** One character's plans: the current one (null: none) and the saved list, in the order saved. */
    private static final class Book {
        Build current;
        final List<Build> saved = new ArrayList<>();
        int next = 1;
    }

    private static volatile String loadedFor;
    private static Book book = new Book();
    /** The last deleted build and where it stood, for Undo; null when none. */
    private static Build deleted;
    private static int deletedAt;
    private static long deletedWhen;

    private Plans() {}

    // ---- the current plan ----

    /** The plan you applied, or null. */
    public static synchronized Build current() {
        load();
        return book.current;
    }

    /** Makes {@code b} the plan (null: no plan); saved at once. */
    public static synchronized void apply(Build b) {
        load();
        book.current = b;
        removed = null;
        save();
    }

    /** The plan removed last and when, for Undo; null when none. */
    private static Build removed;
    private static long removedWhen;

    /**
     * Removes the plan (the Plan pill's ✕, a right-click on Choose items): the panel shows the
     * suggestions again; the chosen items, the strip and the saved builds stay. {@link #undoRemove}
     * brings it back for {@link #UNDO_MS}.
     */
    public static synchronized boolean remove(long now) {
        load();
        if (book.current == null) {
            return false;
        }
        Build b = book.current;
        book.current = null;
        save();
        removed = b;
        removedWhen = now;
        return true;
    }

    /** The plan that can still be brought back, or null. */
    public static synchronized Build removedUndoable(long now) {
        return removed != null && now - removedWhen <= UNDO_MS ? removed : null;
    }

    /** Undo: the removed plan is the plan again, exactly as it was. */
    public static synchronized boolean undoRemove(long now) {
        Build b = removedUndoable(now);
        if (b == null) {
            return false;
        }
        load();
        book.current = b;
        removed = null;
        save();
        return true;
    }

    /**
     * The plan's Save: into My builds (a default name when it has none) and linked to that copy;
     * when it is saved already, that copy is updated with the plan (its name and creation kept).
     * Returns the saved copy, or null without a plan.
     */
    public static synchronized Build savePlan(long now) {
        load();
        Build cur = book.current;
        if (cur == null) {
            return null;
        }
        if (isSaved(cur)) {
            return overwrite(cur.id(), cur, now);
        }
        Build kept = save(cur, now);
        book.current = cur.withId(kept.id()).named(kept.name());
        save();
        return kept;
    }

    /** Whether the plan is one of the saved builds (by id). */
    public static synchronized boolean isSaved(Build b) {
        load();
        return b != null && book.saved.stream().anyMatch(s -> s.id().equals(b.id()));
    }

    // ---- the saved builds ----

    public static synchronized List<Build> saved() {
        load();
        return List.copyOf(book.saved);
    }

    public static synchronized Build byId(String id) {
        load();
        for (Build b : book.saved) {
            if (b.id().equals(id)) {
                return b;
            }
        }
        return null;
    }

    /**
     * Adds {@code b} as a new saved build: a fresh id, a default name when it has none ("Bullseye
     * build", else "Build 3"), the times set. Returns it as kept.
     */
    public static synchronized Build save(Build b, long now) {
        load();
        String id = "b" + now + "-" + book.next;
        String name = b.name().isBlank() ? defaultName(b) : clip(b.name());
        book.next++;
        Build kept = new Build(id, name, b.locks(), b.tuning(), b.items(), b.score(), b.level(), now, now);
        book.saved.add(kept);
        save();
        return kept;
    }

    /** The name a new build gets: its first chosen item's, else "Build N". */
    public static String defaultName(Build b) {
        return b.locks().isEmpty() ? L.t("wynnlabs.plans.default", book.next) : clip(L.t("wynnlabs.plans.default_named", b.locks().get(0)));
    }

    /** Renames a saved build; an empty name keeps the old one. The current plan follows when it is that build. */
    public static synchronized boolean rename(String id, String name, long now) {
        load();
        String n = name == null ? "" : clip(name.strip());
        if (n.isEmpty()) {
            return false;
        }
        for (int i = 0; i < book.saved.size(); i++) {
            Build b = book.saved.get(i);
            if (b.id().equals(id)) {
                Build r = new Build(b.id(), n, b.locks(), b.tuning(), b.items(), b.score(), b.level(), b.created(), now);
                book.saved.set(i, r);
                if (book.current != null && book.current.id().equals(id)) {
                    book.current = book.current.named(n);
                }
                save();
                return true;
            }
        }
        return false;
    }

    /** Deletes a saved build at once; {@link #undo} brings it back for {@link #UNDO_MS}. */
    public static synchronized boolean delete(String id, long now) {
        load();
        for (int i = 0; i < book.saved.size(); i++) {
            if (book.saved.get(i).id().equals(id)) {
                deleted = book.saved.remove(i);
                deletedAt = i;
                deletedWhen = now;
                save();
                return true;
            }
        }
        return false;
    }

    /** The build that can still be brought back, or null. */
    public static synchronized Build undoable(long now) {
        return deleted != null && now - deletedWhen <= UNDO_MS ? deleted : null;
    }

    /** Brings the last deleted build back where it stood. */
    public static synchronized boolean undo(long now) {
        Build d = undoable(now);
        if (d == null) {
            return false;
        }
        load();
        book.saved.add(Math.min(deletedAt, book.saved.size()), d);
        deleted = null;
        save();
        return true;
    }

    /**
     * A saved build replaced by a fresh result ({@code fresh}'s items, locks, tuning and score),
     * keeping its id, name and when it was made. The current plan follows when it is that build.
     */
    public static synchronized Build overwrite(String id, Build fresh, long now) {
        load();
        for (int i = 0; i < book.saved.size(); i++) {
            Build b = book.saved.get(i);
            if (b.id().equals(id)) {
                Build r = new Build(b.id(), b.name(), fresh.locks(), fresh.tuning(), fresh.items(), fresh.score(), fresh.level(),
                        b.created(), now);
                book.saved.set(i, r);
                if (book.current != null && book.current.id().equals(id)) {
                    book.current = r;
                }
                save();
                return r;
            }
        }
        return null;
    }

    private static String clip(String s) {
        return s.length() > MAX_NAME ? s.substring(0, MAX_NAME) : s;
    }

    // ---- progress ----

    /**
     * How much of a plan you wear: by slot, whether the piece worn there is the planned one (by
     * name; the two rings in either hand). {@code worn}: build slot → the worn piece's name
     * (null or absent: nothing).
     */
    public static boolean[] wornSlots(Map<String, String> plan, Map<String, String> worn) {
        boolean[] out = new boolean[SLOTS.length];
        List<String> rings = new ArrayList<>();
        for (String r : new String[] {"RING1", "RING2"}) {
            if (worn.get(r) != null) {
                rings.add(worn.get(r));
            }
        }
        for (int i = 0; i < SLOTS.length; i++) {
            String want = plan.get(SLOTS[i]);
            if (want == null) {
                continue;
            }
            if (SLOTS[i].startsWith("RING")) {
                out[i] = rings.remove(want);
            } else {
                out[i] = want.equals(worn.get(SLOTS[i]));
            }
        }
        return out;
    }

    /** How many of the plan's pieces you wear. */
    static int wornCount(Map<String, String> plan, Map<String, String> worn) {
        int n = 0;
        for (boolean b : wornSlots(plan, worn)) {
            n += b ? 1 : 0;
        }
        return n;
    }

    // ---- the better-build hint ----

    /** How much better a fresh search must score than the plan before the hint shows. */
    static final double HINT_MARGIN = 0.05;

    /**
     * When to look again and whether to say so: once per level after a level-up, and only when
     * the fresh best beats the plan by {@link #HINT_MARGIN}; a right-click hides it until the
     * next level. In memory only: a restart may look once more.
     */
    public static final class Hint {
        private int checked = -1;
        private int dismissed = -1;
        private int shown = -1;

        /** Whether to search again at {@code level} for a plan found at {@code planLevel}: once per level, after a level-up. */
        synchronized boolean shouldCheck(int level, int planLevel) {
            if (level <= planLevel || level == checked) {
                return false;
            }
            checked = level;
            return true;
        }

        /** The search at {@code level} came back: the hint shows when {@code best} beats {@code plan} by the margin. */
        synchronized boolean offer(int level, double plan, double best) {
            boolean better = plan > 0 && best >= plan * (1 + HINT_MARGIN);
            shown = better ? level : -1;
            return better;
        }

        /** The level the hint is for, or -1 when none shows. */
        public synchronized int showing() {
            return shown >= 0 && shown != dismissed ? shown : -1;
        }

        /** Right-click: hidden until the next level. */
        public synchronized void dismiss() {
            dismissed = shown;
        }

        public synchronized void reset() {
            checked = -1;
            dismissed = -1;
            shown = -1;
        }
    }

    public static final Hint HINT = new Hint();

    // ---- the level row's Plan pill ----

    /**
     * The "Plan" pill at the start of the level row: there while a plan exists, selected by
     * default and again whenever a new plan comes; another level pill picked unselects it.
     */
    public static final class Pills {
        private String planId;
        private boolean picked;

        boolean shown(Build plan) {
            return plan != null;
        }

        public boolean planSelected(Build plan) {
            if (plan == null) {
                return false;
            }
            if (!plan.id().equals(planId)) {
                planId = plan.id();
                picked = false;
            }
            return !picked;
        }

        public void pickLevel() {
            picked = true;
        }

        public void pickPlan() {
            picked = false;
        }
    }

    // ---- the file ----

    private static String characterId() {
        try {
            return GameState.current().characterId();
        } catch (Throwable t) {
            return "";
        }
    }

    private static Path file(String id) {
        Path root = Locks.rootOverride != null ? Locks.rootOverride : Dirs.config().resolve("characters");
        return root.resolve(id).resolve(FILE);
    }

    /** The preview: no plan and no saved builds (written so). */
    public static synchronized void clearAll() {
        load();
        book = new Book();
        deleted = null;
        save();
    }

    /** The preview: the hint showing for {@code level}. */
    public static void previewHint(int level) {
        HINT.reset();
        HINT.offer(level, 1, 2);
    }

    /** Tests: the next read takes the file again. */
    public static synchronized void forget() {
        loadedFor = null;
        book = new Book();
        deleted = null;
        removed = null;
        HINT.reset();
    }

    private static void load() {
        String id = characterId();
        if (id.equals(loadedFor)) {
            return;
        }
        loadedFor = id;
        book = new Book();
        deleted = null;
        HINT.reset();
        if (id.isEmpty()) {
            return;
        }
        Path f = file(id);
        try {
            Saver.settle(f);
            if (!Files.exists(f)) {
                return;
            }
            String text = Files.readString(f, StandardCharsets.UTF_8);
            Saver.known(f, text);
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            if (o.has("current") && o.get("current").isJsonObject()) {
                book.current = read(o.getAsJsonObject("current"));
            }
            if (o.has("builds") && o.get("builds").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("builds")) {
                    Build b = e.isJsonObject() ? read(e.getAsJsonObject()) : null;
                    if (b != null) {
                        book.saved.add(b);
                    }
                }
            }
            book.next = o.has("next") ? Math.max(1, o.get("next").getAsInt()) : book.saved.size() + 1;
        } catch (Throwable t) {
            System.out.println("[WynnLabs] could not read " + FILE + ", kept as " + FILE + ".broken: " + t);
            book = new Book();
            try {
                Files.copy(f, f.resolveSibling(FILE + ".broken"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Throwable ignored) {
                // nothing to keep
            }
        }
    }

    private static Build read(JsonObject o) {
        try {
            List<String> locks = new ArrayList<>();
            if (o.has("locks")) {
                o.getAsJsonArray("locks").forEach(e -> locks.add(e.getAsString()));
            }
            Map<String, String> items = new LinkedHashMap<>();
            if (o.has("items")) {
                o.getAsJsonObject("items").entrySet().forEach(e -> items.put(e.getKey(), e.getValue().getAsString()));
            }
            Tuning.Choice t = Tuning.Choice.PLAIN;
            if (o.has("tuning") && o.get("tuning").isJsonObject()) {
                JsonObject tj = o.getAsJsonObject("tuning");
                int focus = tj.has("focus") ? tj.get("focus").getAsInt() : Tuning.BALANCED;
                t = new Tuning.Choice(focus < Tuning.BALANCED || focus > Tuning.TANKY ? Tuning.BALANCED : focus,
                        tj.has("mana") && tj.get("mana").getAsBoolean(), tj.has("speed") && tj.get("speed").getAsBoolean(),
                        tj.has("budget") ? Math.max(0, tj.get("budget").getAsLong()) : 0);
            }
            return new Build(o.get("id").getAsString(), o.has("name") ? o.get("name").getAsString() : "", locks, t, items,
                    o.has("score") ? o.get("score").getAsDouble() : 0, o.has("level") ? o.get("level").getAsInt() : 0,
                    o.has("created") ? o.get("created").getAsLong() : 0, o.has("updated") ? o.get("updated").getAsLong() : 0);
        } catch (Throwable t) {
            return null;                                // one broken entry: the rest still read
        }
    }

    private static JsonObject write(Build b) {
        JsonObject o = new JsonObject();
        o.addProperty("id", b.id());
        o.addProperty("name", b.name());
        JsonArray l = new JsonArray();
        b.locks().forEach(l::add);
        o.add("locks", l);
        JsonObject t = new JsonObject();
        t.addProperty("focus", b.tuning().focus());
        t.addProperty("mana", b.tuning().mana());
        t.addProperty("speed", b.tuning().speed());
        t.addProperty("budget", b.tuning().budget());
        o.add("tuning", t);
        JsonObject items = new JsonObject();
        b.items().forEach(items::addProperty);
        o.add("items", items);
        o.addProperty("score", b.score());
        o.addProperty("level", b.level());
        o.addProperty("created", b.created());
        o.addProperty("updated", b.updated());
        return o;
    }

    private static void save() {
        String id = loadedFor;
        if (id == null || id.isEmpty()) {
            return;
        }
        JsonObject o = new JsonObject();
        if (book.current != null) {
            o.add("current", write(book.current));
        }
        JsonArray a = new JsonArray();
        book.saved.forEach(b -> a.add(write(b)));
        o.add("builds", a);
        o.addProperty("next", book.next);
        String text = o.toString();
        Saver.mark(file(id), () -> text);
    }
}
