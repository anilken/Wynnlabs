package io.github.anilken.wynnlabs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.anilken.wynnlabs.model.AbilityNode;
import io.github.anilken.wynnlabs.model.AspectEntry;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.MenuKind;
import io.github.anilken.wynnlabs.model.RankedAspect;
import io.github.anilken.wynnlabs.model.Tier;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToIntFunction;

/**
 * The character's aspects against every aspect of its class (openspec aspect-collection, design
 * decision 7): the class list from {@link Wyn#classAspects}, the ability names each aspect's
 * text names from Wynntils' cached aspect data ({@code wynntils/cache/dataStaticAspects}, read
 * once off the game thread), owned and equipped tiers from {@link Wyn}, and the ranking of
 * {@link AspectRank}. Owned tiers are "known" once the player has opened the Aspects menu or any
 * owned tier was read; until then nothing is marked missing. Never equips, pages or opens
 * anything.
 *
 * <p>API contract of group 1 (design.md, "API contract"): the signatures below stay as they
 * are; group 4 fills the bodies and may add private helpers and test hooks.
 */
final class AspectBook {

    private AspectBook() {}

    /** What the list shows now; replaced whole, so a reader never sees half an update. */
    private record Book(Klass klass, List<AspectEntry> aspects, Map<String, Integer> owned, List<String> equipped,
            List<RankedAspect> ranked, Map<String, RankedAspect> byName, String archetype, boolean guessed) {

        static final Book EMPTY = new Book(Klass.NONE, List.of(), null, List.of(), List.of(), Map.of(), null, false);
    }

    private static volatile Book book = Book.EMPTY;
    /** Set by {@link #preview}: the game's own values do not replace the fake ones. */
    private static volatile boolean previewing;
    /** The Aspects menu was seen open this session (owned tiers are known even when none is owned). */
    private static volatile boolean menuSeen;
    private static volatile String inputs = "";
    private static int tick;

    // ---- the cache file, read off the game thread ----

    private static final String CACHE = "wynntils/cache/dataStaticAspects";
    private static final long RETRY_MS = 30_000;
    /** Per class: the aspects of the cache file with their ability names; null until read. */
    private static volatile Map<Klass, List<AspectEntry>> cache;
    private static volatile boolean reading;
    private static volatile long lastTry;

    /** Every client tick: notes the Aspects menu being open, refreshes owned and equipped tiers, starts the cache read. */
    static void onTick(Minecraft mc) {
        if (previewing || mc == null || mc.player == null) {
            return;
        }
        tick++;
        if (!seenRead) {
            seenRead = true;
            menuSeen |= java.nio.file.Files.exists(seenFile());
        }
        if (tick % 5 == 0 && !menuSeen) {
            MenuKind c = Wyn.container();
            if (c != null && "AspectsContainer".equals(c.type())) {
                menuSeen = true;
                remember();
            }
        }
        if (tick % 20 != 0) {
            return;
        }
        startRead();
        try {
            refresh();
        } catch (Throwable t) {
            System.out.println("[WynnLabs] aspect list failed: " + t);
        }
    }

    /** Reads the class's aspects, owned and equipped tiers and the build; ranks again when any changed. */
    private static void refresh() {
        Klass klass = Wyn.classType();
        if (klass == null || klass == Klass.NONE) {
            book = Book.EMPTY;
            inputs = "";
            return;
        }
        List<AspectEntry> aspects = withAbilities(Wyn.classAspects(klass), klass, cache);
        Map<String, Integer> owned = owned(aspects, Wyn::aspectTier, menuSeen || accountScanned());
        List<String> equipped = Wyn.equippedAspects();
        GameState g = GameState.current();
        List<AbilityNode> tree = g.abilityTree();
        Set<String> unlocked = new HashSet<>(g.unlockedAbilities());
        Set<String> planned = new HashSet<>(g.abilityPlan().keySet());
        String chosen = chosenArchetype();
        String key = L.code() + "|" + klass + "|" + aspects + "|" + owned + "|" + equipped + "|" + System.identityHashCode(tree)
                + "|" + new java.util.TreeSet<>(unlocked) + "|" + new java.util.TreeSet<>(planned) + "|" + chosen;
        if (key.equals(inputs)) {
            return;
        }
        inputs = key;
        install(klass, aspects, owned, equipped, tree, unlocked, planned, chosen);
    }

    /** Whether {@link #menuSeen} was read back from {@link #seenFile()} this session. */
    private static volatile boolean seenRead;
    /** True once Wynntils is known to hold an owned aspect of any class. */
    private static volatile boolean scannedSeen;

    /** Remembers across restarts that the Aspects menu was seen (Wynntils keeps owned aspects per account too). */
    private static Path seenFile() {
        return Dirs.config().resolve("aspects-seen.txt");
    }

    private static void remember() {
        try {
            java.nio.file.Files.createDirectories(seenFile().getParent());
            java.nio.file.Files.writeString(seenFile(), "seen\n");
        } catch (Exception e) {
            System.out.println("[WynnLabs] could not note the Aspects menu: " + e);
        }
    }

    /**
     * Whether Wynntils has read the Aspects menu at some point: it then owns an aspect of some
     * class, and an aspect of this class it does not list is simply not owned (a new class
     * owning none of its own aspects must not ask for the menu again).
     */
    private static boolean accountScanned() {
        if (scannedSeen) {
            return true;
        }
        for (Klass k : Klass.values()) {
            if (k == Klass.NONE) {
                continue;
            }
            for (AspectEntry a : Wyn.classAspects(k)) {
                if (Wyn.aspectTier(a.name()) > 0) {
                    scannedSeen = true;
                    return true;
                }
            }
        }
        return false;
    }

    /** The archetype picked in Wynn Labs (the tree planner's), or "" when none. */
    private static String chosenArchetype() {
        try {
            return TreePlanner.chosen();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Builds the list from plain values (the game's, the preview's or a test's): picks the
     * archetype (the chosen one, else the most unlocked) and ranks.
     */
    static void install(Klass klass, List<AspectEntry> aspects, Map<String, Integer> owned, List<String> equipped,
            List<AbilityNode> tree, Set<String> unlocked, Set<String> planned, String chosen) {
        List<AspectEntry> list = aspects == null ? List.of() : List.copyOf(aspects);
        List<String> eq = equipped == null ? List.of() : List.copyOf(equipped);
        String archetype = chosen == null || chosen.isBlank() ? null : chosen;
        boolean guessed = false;
        if (archetype == null) {
            archetype = AspectRank.mostUnlocked(tree, unlocked);
            guessed = archetype != null;
        }
        Set<String> eqNames = new HashSet<>();
        for (AspectEntry a : list) {
            if (matchesEquipped(a.name(), eq)) {
                eqNames.add(a.name());
            }
        }
        List<RankedAspect> ranked = List.copyOf(AspectRank.rank(list, owned, eqNames, tree,
                unlocked == null ? Set.of() : unlocked, planned == null ? Set.of() : planned, archetype));
        Map<String, RankedAspect> byName = new LinkedHashMap<>();
        for (RankedAspect r : ranked) {
            byName.put(r.aspect().name(), r);
        }
        book = new Book(klass == null ? Klass.NONE : klass, list, owned == null ? null : Map.copyOf(owned), eq,
                ranked, Map.copyOf(byName), archetype, guessed);
    }

    /**
     * The owned tier of each aspect (0 when not owned), or null while owned tiers are unknown:
     * the Aspects menu was not seen and no tier above 0 was read (or every read failed).
     */
    static Map<String, Integer> owned(List<AspectEntry> aspects, ToIntFunction<String> tierOf, boolean menuSeen) {
        Map<String, Integer> out = new LinkedHashMap<>();
        boolean any = false;
        boolean failed = false;
        for (AspectEntry a : aspects) {
            int t = tierOf.applyAsInt(a.name());
            if (t < 0) {
                failed = true;
                t = 0;
            }
            any |= t > 0;
            out.put(a.name(), t);
        }
        if (any || (menuSeen && !failed)) {
            return out;
        }
        return null;
    }

    private static boolean matchesEquipped(String name, List<String> equipped) {
        for (String e : equipped) {
            if (e != null && name != null && e.endsWith(name)) {
                return true;
            }
        }
        return false;
    }

    // ---- read by the Endgame tab and RaidPool ----

    /** The class the list is for (the character in play); NONE when unknown. */
    static Klass klass() {
        return book.klass();
    }

    /** Whether Wynntils has downloaded the aspect data (false: the list says it is not available yet). */
    static boolean dataReady() {
        return !book.aspects().isEmpty();
    }

    /** Whether owned tiers are known (the Aspects menu was seen, or any owned tier was read). */
    static boolean known() {
        return book.owned() != null;
    }

    /** Every aspect of the class, in the data's order, with ability names when the cache was read; empty when none. */
    static List<AspectEntry> classAspects() {
        return book.aspects();
    }

    /** The owned tier of an aspect by name: 1 on, 0 missing, -1 while owned tiers are unknown. */
    static int ownedTier(String aspect) {
        Book b = book;
        if (b.owned() == null || aspect == null) {
            return -1;
        }
        Integer t = b.owned().get(aspect);
        if (t != null) {
            return t;
        }
        if (previewing) {
            return 0;
        }
        int live = Wyn.aspectTier(aspect);      // another class's aspect: Wynntils keeps them per account
        return live < 0 ? -1 : live;
    }

    /** Whether this character has the aspect with this name equipped. */
    static boolean equipped(String aspect) {
        return aspect != null && matchesEquipped(aspect, book.equipped());
    }

    /** How many of the class's aspects are owned at any tier; -1 while unknown. */
    static int ownedCount() {
        Book b = book;
        if (b.owned() == null) {
            return -1;
        }
        int n = 0;
        for (AspectEntry a : b.aspects()) {
            if (b.owned().getOrDefault(a.name(), 0) > 0) {
                n++;
            }
        }
        return n;
    }

    /** The whole list in display order (see {@link AspectRank#rank}); empty when the data is not ready. */
    static List<RankedAspect> ranked() {
        return book.ranked();
    }

    /** The aspect card's recommendation: the first ranked row (rank 1, or the first row while unknown); null when none. */
    static RankedAspect next() {
        Book b = book;
        if (b.ranked().isEmpty()) {
            return null;
        }
        if (b.owned() == null) {
            return b.ranked().get(0);
        }
        for (RankedAspect r : b.ranked()) {
            if (r.unfinished()) {
                return r;                       // rank 1 when any matches the build, else the best missing one
            }
        }
        return null;
    }

    /** The row of the aspect with this name, or null. */
    static RankedAspect byName(String aspect) {
        return aspect == null ? null : book.byName().get(aspect);
    }

    /** The archetype the ranking is for (English), or null when none could be found. */
    static String archetype() {
        return book.archetype();
    }

    /** True when no archetype was chosen and the one with the most unlocked abilities is used (the list says so). */
    static boolean archetypeGuessed() {
        return book.guessed();
    }

    // ---- preview ----

    /**
     * For the no-game preview: this class's aspects (with ability names), owned tiers by name
     * (null: the Aspects menu was never read), equipped names, and the archetype to rank for
     * (null: as in game, from the plan or the most unlocked).
     */
    static void preview(Klass klass, List<AspectEntry> aspects, Map<String, Integer> owned, List<String> equipped,
            String archetype) {
        previewing = true;
        Map<String, Integer> tiers = null;
        if (owned != null) {
            tiers = new LinkedHashMap<>();
            for (AspectEntry a : aspects == null ? List.<AspectEntry>of() : aspects) {
                tiers.put(a.name(), Math.max(0, owned.getOrDefault(a.name(), 0)));
            }
        }
        GameState g = GameState.current();
        List<AbilityNode> tree = null;
        Set<String> unlocked = Set.of();
        Set<String> planned = Set.of();
        try {
            tree = g.abilityTree();
            unlocked = new HashSet<>(g.unlockedAbilities());
            planned = new HashSet<>(g.abilityPlan().keySet());
        } catch (Throwable t) {
            // no tree in this preview: the ranking falls back to rarity
        }
        install(klass, aspects, tiers, equipped, tree, unlocked, planned, archetype != null ? archetype : chosenArchetype());
    }

    /** Back to the game's values (tests, leaving a preview). */
    static void reset() {
        previewing = false;
        menuSeen = false;
        seenRead = true;                            // tests and previews never read the player's file
        scannedSeen = false;
        inputs = "";
        book = Book.EMPTY;
    }

    // ---- ability names from Wynntils' cached aspect data ----

    /** Starts reading the cache file on its own thread, once (again every 30 s while it is missing). */
    private static void startRead() {
        if (cache != null || reading || System.currentTimeMillis() - lastTry < RETRY_MS) {
            return;
        }
        reading = true;
        lastTry = System.currentTimeMillis();
        Thread t = new Thread(() -> {
            try {
                Path f = Platform.get().gameDir().resolve(CACHE);
                if (Files.isRegularFile(f)) {
                    cache = parseCache(Files.readString(f, StandardCharsets.UTF_8));
                    inputs = "";                // rank again with the names
                }
            } catch (Throwable e) {
                System.out.println("[WynnLabs] aspect data unavailable: " + e);
            } finally {
                reading = false;
            }
        }, "wynnlabs-aspects");
        t.setDaemon(true);
        t.start();
    }

    /**
     * The class's aspects with their ability names from the cache. The class list comes from
     * Wynntils' model; when that is empty the cache's own list for the class stands in.
     */
    static List<AspectEntry> withAbilities(List<AspectEntry> aspects, Klass klass, Map<Klass, List<AspectEntry>> cached) {
        List<AspectEntry> fromCache = cached == null ? List.of() : cached.getOrDefault(klass, List.of());
        if (aspects == null || aspects.isEmpty()) {
            return fromCache;
        }
        Map<String, List<String>> names = new java.util.HashMap<>();
        for (AspectEntry c : fromCache) {
            names.put(c.name().toLowerCase(Locale.ROOT), c.abilities());
        }
        List<AspectEntry> out = new ArrayList<>(aspects.size());
        for (AspectEntry a : aspects) {
            List<String> n = names.get(a.name().toLowerCase(Locale.ROOT));
            out.add(n == null || !a.abilities().isEmpty() ? a : a.withAbilities(n));
        }
        return out;
    }

    /**
     * Parses {@code dataStaticAspects}: {@code {"shaman": {"Aspect of …": {"rarity", "tiers":
     * {"1": {"description": [[components]], "threshold"}}}}}}. The ability names are the
     * underlined spans of every tier's text, in order, each once. Unknown classes are skipped.
     */
    static Map<Klass, List<AspectEntry>> parseCache(String json) {
        Map<Klass, List<AspectEntry>> out = new EnumMap<>(Klass.class);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        for (var cls : root.entrySet()) {
            Klass klass = klassOf(cls.getKey());
            if (klass == null || !cls.getValue().isJsonObject()) {
                continue;
            }
            List<AspectEntry> list = new ArrayList<>();
            for (var e : cls.getValue().getAsJsonObject().entrySet()) {
                if (!e.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject a = e.getValue().getAsJsonObject();
                String name = a.has("name") ? a.get("name").getAsString() : e.getKey();
                Tier rarity = a.has("rarity") ? Tier.of(a.get("rarity").getAsString().toUpperCase(Locale.ROOT)) : Tier.NORMAL;
                TreeMap<Integer, JsonObject> tiers = new TreeMap<>();
                if (a.has("tiers") && a.get("tiers").isJsonObject()) {
                    for (var t : a.getAsJsonObject("tiers").entrySet()) {
                        try {
                            if (t.getValue().isJsonObject()) {
                                tiers.put(Integer.parseInt(t.getKey().strip()), t.getValue().getAsJsonObject());
                            }
                        } catch (NumberFormatException ignored) {
                            // not a tier number
                        }
                    }
                }
                List<Integer> thresholds = new ArrayList<>();
                Set<String> abilities = new LinkedHashSet<>();
                for (JsonObject t : tiers.values()) {
                    thresholds.add(t.has("threshold") ? t.get("threshold").getAsInt() : 0);
                    if (t.has("description") && t.get("description").isJsonArray()) {
                        for (JsonElement line : t.getAsJsonArray("description")) {
                            underlined(line, abilities);
                        }
                    }
                }
                list.add(new AspectEntry(name, klass, rarity, tiers.size(), thresholds, new ArrayList<>(abilities)));
            }
            out.put(klass, List.copyOf(list));
        }
        return out;
    }

    /** Adds the underlined spans of one text line; neighbouring underlined parts join into one name. */
    private static void underlined(JsonElement line, Set<String> out) {
        StringBuilder cur = new StringBuilder();
        List<JsonObject> parts = new ArrayList<>();
        flatten(line, parts);
        for (JsonObject c : parts) {
            boolean u = c.has("underline") && c.get("underline").isJsonPrimitive() && c.get("underline").getAsBoolean();
            String text = c.has("text") && c.get("text").isJsonPrimitive() ? c.get("text").getAsString() : "";
            if (u) {
                cur.append(text);
            } else if (!text.isEmpty()) {
                add(cur, out);
            }
        }
        add(cur, out);
    }

    private static void flatten(JsonElement e, List<JsonObject> out) {
        if (e == null) {
            return;
        }
        if (e.isJsonArray()) {
            for (JsonElement x : (JsonArray) e) {
                flatten(x, out);
            }
        } else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            out.add(o);
            if (o.has("extra")) {
                flatten(o.get("extra"), out);
            }
        }
    }

    private static void add(StringBuilder cur, Set<String> out) {
        String s = cur.toString().strip();
        if (!s.isEmpty()) {
            out.add(s);
        }
        cur.setLength(0);
    }

    private static Klass klassOf(String key) {
        try {
            Klass k = Klass.valueOf(key.strip().toUpperCase(Locale.ROOT));
            return k == Klass.NONE ? null : k;
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }
}
