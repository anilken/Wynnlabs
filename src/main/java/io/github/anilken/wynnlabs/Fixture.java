package io.github.anilken.wynnlabs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import io.github.anilken.wynnlabs.model.ItemData;
import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.Klass;
import io.github.anilken.wynnlabs.model.Obtain;
import io.github.anilken.wynnlabs.model.SkillKind;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Recorded game state for the tests (a fixture): everything {@link GameState} answers, in the
 * mod's own types, as plain JSON in {@code config/wynnlabs/fixtures/<name>/}:
 * <ul>
 * <li>{@code state.json}: character, skill points, professions, quests, menu values, map spots;</li>
 * <li>{@code items.json}: every stack the player carries or wears (as the mod reads it), by slot,
 * and the bank pages;</li>
 * <li>{@code geardb.json}: the gear database, one piece per line;</li>
 * <li>{@code prices.json}: the price of every name the logic asked for;</li>
 * <li>{@code rules/<id>.json} and {@code settings.json}: copies of the active files.</li>
 * </ul>
 * Written by the debug route /fixture through the {@link Sanitiser}; read back by the tests.
 */
final class Fixture {

    /** Bumped when the files change shape: the tests then ask for a new recording. */
    static final int FORMAT = 1;

    /** Optional as its value or null (Gson has no adapter for it). */
    private static final TypeAdapterFactory OPTIONAL = new TypeAdapterFactory() {
        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            if (type.getRawType() != Optional.class) {
                return null;
            }
            var inner = type.getType() instanceof ParameterizedType p ? p.getActualTypeArguments()[0] : Object.class;
            TypeAdapter<Object> value = (TypeAdapter<Object>) gson.getAdapter(TypeToken.get(inner));
            return (TypeAdapter<T>) new TypeAdapter<Optional<?>>() {
                @Override
                public void write(JsonWriter out, Optional<?> o) throws IOException {
                    if (o == null || o.isEmpty()) {
                        out.nullValue();
                    } else {
                        value.write(out, o.get());
                    }
                }

                @Override
                public Optional<?> read(JsonReader in) throws IOException {
                    if (in.peek() == JsonToken.NULL) {
                        in.nextNull();
                        return Optional.empty();
                    }
                    return Optional.ofNullable(value.read(in));
                }
            };
        }
    };

    private static GsonBuilder builder() {
        return new GsonBuilder().serializeNulls().disableHtmlEscaping().serializeSpecialFloatingPointValues()
                .registerTypeAdapterFactory(OPTIONAL);
    }

    static final Gson GSON = builder().setPrettyPrinting().create();
    private static final Gson COMPACT = builder().create();

    private Fixture() {}

    // ---- the files ----

    /** One skill: assigned (as the advice reads it), what the menu showed, gear, tomes, set. */
    record Skill(int assigned, int seen, int gear, int tome, int setBonus) {}

    /** state.json. {@code today}: what the other characters' today.json files say that matters. */
    record State(int format, long recordedAt, boolean inGame, Klass klass, int level, String characterId,
            JsonObject characters, List<String> characterIds, Map<String, JsonObject> today,
            Map<SkillKind, Skill> skills, boolean skillsKnown, int unspent, Map<String, Integer> professions,
            List<QuestBook.Entry> quests, Map<String, Integer> abilityPlan, double[] position,
            List<Grind.Spot> spots, Map<String, Double> xpRates, boolean pricesInstalled, int selectedSlot) {}

    /** A stack: its shown name (cleaned), count and the mod's reading of it (null: none). */
    record Held(String name, int count, ItemView view) {}

    /** A stack on a bank page. */
    record BankSlot(String page, int slot, String name, int count, ItemView view) {}

    /**
     * items.json. {@code stacks} holds every different stack once; the slots point into it, so
     * a ring worn from bag slot 9 is the same stack in both places, as in game. Null: empty.
     */
    record Items(List<Held> stacks, List<Integer> inventory, Map<String, Integer> armour,
            List<Integer> accessories, Integer mainHand, List<BankSlot> bank) {}

    /** geardb.json: every piece, where each comes from, and what each gear box can hold. */
    record GearDb(int format, List<ItemData> gear, Map<String, List<Obtain>> obtain,
            Map<String, List<String>> boxes) {}

    /** tree.json: the class's ability tree as the mod reads it, and the nodes unlocked. */
    record Tree(List<io.github.anilken.wynnlabs.model.AbilityNode> nodes, List<String> unlocked) {}

    /**
     * A whole fixture. {@code rules}: by character id, the active character's included;
     * {@code tree}: null in a fixture recorded before the tree was kept.
     */
    record Data(State state, Items items, GearDb gearDb, Map<String, Prices.Price> prices,
            Map<String, JsonObject> rules, JsonObject settings, Tree tree) {

        Data(State state, Items items, GearDb gearDb, Map<String, Prices.Price> prices,
                Map<String, JsonObject> rules, JsonObject settings) {
            this(state, items, gearDb, prices, rules, settings, null);
        }
    }

    /** The key a gear box's outcomes are kept under: "RELIK:UNIQUE:40-44". */
    static String boxKey(ItemView.Box b) {
        return b.slot().name() + ":" + b.tier().name() + ":" + b.low() + "-" + b.high();
    }

    static final EquipmentSlot[] ARMOUR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
        EquipmentSlot.FEET};

    // ---- recording (the debug route) ----

    /**
     * GET /fixture?name=…: records the game now into config/wynnlabs/fixtures/&lt;name&gt;/. The
     * game is read on the client thread; cleaning and writing the files happen on the caller's.
     */
    static JsonObject record(String name) throws Exception {
        JsonObject out = new JsonObject();
        if (name == null || name.isBlank()) {
            name = "default";
        }
        if (!name.matches("[A-Za-z0-9_-]{1,40}")) {
            out.addProperty("error", "name: letters, digits, - and _ only");
            return out;
        }
        Data data = Minecraft.getInstance().submit(() -> {
            GameState live = GameState.current();
            return live.inGame() && !live.characterId().isEmpty() ? capture(live) : null;
        }).get(60, java.util.concurrent.TimeUnit.SECONDS);
        if (data == null) {
            out.addProperty("error", "needs a character in game");
            return out;
        }
        Path dir = Dirs.config().resolve("fixtures").resolve(name);
        Sanitiser clean = new Sanitiser(knownIds(data), privateNames());
        Map<String, Long> sizes = write(data, dir, clean);
        out.addProperty("folder", "config/wynnlabs/fixtures/" + name);
        JsonObject files = new JsonObject();
        sizes.forEach(files::addProperty);
        out.add("files", files);
        out.addProperty("bag", data.items().inventory().stream().limit(36).filter(i -> i != null).count());
        out.addProperty("bank", data.items().bank().size());
        out.addProperty("gear", data.gearDb().gear().size());
        out.addProperty("prices", data.prices().size());
        out.addProperty("characterIdsReplaced", clean.idCount());
        out.addProperty("namesReplaced", clean.nameCount());
        return out;
    }

    /** Everything the logic reads, from the live game, unsanitised. Call on the client thread. */
    static Data capture(GameState live) {
        // the stacks, each different object once
        List<Held> stacks = new ArrayList<>();
        Map<ItemStack, Integer> refs = new IdentityHashMap<>();
        java.util.function.Function<ItemStack, Integer> ref = s -> {
            if (s == null || s.isEmpty()) {
                return null;
            }
            return refs.computeIfAbsent(s, k -> {
                stacks.add(new Held(ItemJson.clean(k.getHoverName().getString()), k.getCount(),
                        live.view(k).orElse(null)));
                return stacks.size() - 1;
            });
        };
        List<Integer> inventory = new ArrayList<>();
        live.inventory().forEach(s -> inventory.add(ref.apply(s)));
        Map<String, Integer> armour = new LinkedHashMap<>();
        for (EquipmentSlot slot : ARMOUR) {
            armour.put(slot.name(), ref.apply(live.armour(slot)));
        }
        List<Integer> accessories = new ArrayList<>();
        live.accessories().forEach(s -> accessories.add(ref.apply(s)));
        Integer mainHand = ref.apply(live.mainHand());
        List<BankSlot> bank = new ArrayList<>();
        for (String key : Vault.pageKeys()) {
            for (Vault.Stored st : Vault.page(key)) {
                if (st.item() != null) {
                    ItemStack s = Vault.stackOf(st);
                    bank.add(new BankSlot(key, st.slot(), ItemJson.clean(s.getHoverName().getString()), s.getCount(),
                            live.view(s).orElse(null)));
                }
            }
        }
        Items items = new Items(stacks, inventory, armour, accessories, mainHand, bank);

        // the gear database, with what every box seen here can hold
        List<ItemData> gear = live.allGear().toList();
        Map<String, List<Obtain>> obtain = new LinkedHashMap<>();
        gear.forEach(g -> obtain.put(g.name(), live.obtain(g)));
        Map<String, List<String>> boxes = new LinkedHashMap<>();
        List<ItemView> views = new ArrayList<>();
        stacks.forEach(h -> views.add(h.view()));
        bank.forEach(b -> views.add(b.view()));
        java.util.Set<String> professions = new java.util.TreeSet<>();
        for (ItemView v : views) {
            if (v == null) {
                continue;
            }
            if (v.box() != null) {
                boxes.computeIfAbsent(boxKey(v.box()), k -> live.possibleGears(v.box()).stream().map(ItemData::name).toList());
            }
            if (v.professions() != null) {
                professions.addAll(v.professions());
            }
        }
        GearDb gearDb = new GearDb(FORMAT, gear, obtain, boxes);

        // the prices the logic asks for: run what the tests run and note every name
        Map<String, Prices.Price> prices = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
        LiveGameState.askedPrices = prices;
        try {
            ItemJson.inventory();
            ItemJson.equipped();
            for (BankSlot b : bank) {
                if (b.view() != null) {
                    AutoAdvice.decide(b.view(), new AutoAdvice.StackFacts(null, b.name(), b.count()));
                }
            }
            Shopping.json();
            Roadmap.json();
        } finally {
            LiveGameState.askedPrices = null;
        }

        // the character
        Map<SkillKind, Skill> skills = new java.util.EnumMap<>(SkillKind.class);
        for (SkillKind s : SkillKind.values()) {
            skills.put(s, new Skill(live.assigned(s), live.assignedSeen(s), live.gearPoints(s), live.tomePoints(s),
                    live.setBonusPoints(s)));
        }
        Map<String, Integer> profLevels = new LinkedHashMap<>();
        for (String p : professions) {
            try {
                profLevels.put(p, live.professionLevel(p));
            } catch (RuntimeException unknown) {
                // not a profession: the fixture throws for it too
            }
        }
        List<String> ids = live.characterIds();
        Map<String, JsonObject> today = new LinkedHashMap<>();
        Map<String, JsonObject> rules = new LinkedHashMap<>();
        for (String id : ids) {
            JsonObject t = readOrNull(live, id, "today.json");
            if (t != null) {
                today.put(id, todayPart(t));
            }
            JsonObject r = id.equals(live.characterId()) ? Rules.get().deepCopy() : readOrNull(live, id, "rules.json");
            if (r != null) {
                rules.put(id, r);
            }
        }
        if (!rules.containsKey(live.characterId())) {
            rules.put(live.characterId(), Rules.get().deepCopy());
        }
        List<Grind.Spot> spots = live.spots();
        Map<String, Double> rates = new LinkedHashMap<>();
        for (Grind.Spot s : spots) {
            double r = live.xpRate(s.name());
            if (r != -1) {
                rates.put(s.name(), r);
            }
        }
        State state = new State(FORMAT, live.now(), live.inGame(), live.classType(), live.level(), live.characterId(),
                live.characters(), ids, today, skills, live.skillsKnown(), live.unspent(), profLevels, live.quests(),
                new LinkedHashMap<>(live.abilityPlan()), live.position(), spots, rates, live.pricesInstalled(),
                live.selectedSlot());

        JsonObject settings = new JsonObject();
        try {
            Path f = Dirs.config().resolve("settings.json");
            if (Files.exists(f)) {
                settings = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } catch (Exception ignored) {
            // the defaults then
        }
        var nodes = live.abilityTree();
        Tree tree = nodes == null || nodes.isEmpty() ? null : new Tree(nodes, new ArrayList<>(live.unlockedAbilities()));
        return new Data(state, items, gearDb, new LinkedHashMap<>(prices), rules, settings, tree);
    }

    private static JsonObject readOrNull(GameState live, String id, String file) {
        try {
            return live.characterFile(id, file);
        } catch (RuntimeException broken) {
            return null;
        }
    }

    /** Of another character's day only what the advice reads: class, level, worn levels. */
    private static JsonObject todayPart(JsonObject t) {
        JsonObject o = new JsonObject();
        for (String k : List.of("class", "level")) {
            if (t.has(k)) {
                o.add(k, t.get(k));
            }
        }
        if (t.has("worn") && t.get("worn").isJsonObject()) {
            JsonObject worn = new JsonObject();
            for (var e : t.getAsJsonObject("worn").entrySet()) {
                JsonObject slot = new JsonObject();
                if (e.getValue().isJsonObject() && e.getValue().getAsJsonObject().has("level")) {
                    slot.add("level", e.getValue().getAsJsonObject().get("level"));
                }
                worn.add(e.getKey(), slot);
            }
            o.add("worn", worn);
        }
        return o;
    }

    /** Every character id the fixture mentions, for the sanitiser. */
    private static List<String> knownIds(Data d) {
        List<String> ids = new ArrayList<>(d.state().characterIds());
        ids.add(d.state().characterId());
        for (var e : d.state().characters().getAsJsonArray("characters")) {
            var o = e.getAsJsonObject();
            if (o.has("id")) {
                ids.add(o.get("id").getAsString());
            }
        }
        for (BankSlot b : d.items().bank()) {
            if (b.page().startsWith("char:") && b.page().indexOf(':', 5) > 5) {
                ids.add(b.page().substring(5, b.page().indexOf(':', 5)));
            }
        }
        return ids;
    }

    /**
     * Names to replace: the player's own, plus "pet: …", "character: …" or "player: …" lines in
     * config/wynnlabs/fixtures/private-names.txt (kept out of the fixture folders).
     */
    private static Map<String, String> privateNames() {
        Map<String, String> names = new LinkedHashMap<>();
        try {
            names.put(Minecraft.getInstance().getUser().getName(), "Player");
        } catch (Throwable ignored) {
            // no user name
        }
        try {
            Path f = Dirs.config().resolve("fixtures").resolve("private-names.txt");
            if (Files.exists(f)) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    String[] kv = line.split(":", 2);
                    if (kv.length == 2 && !kv[1].isBlank()) {
                        String kind = kv[0].strip().toLowerCase(Locale.ROOT);
                        String fake = switch (kind) {
                            case "pet" -> "Pet";
                            case "character" -> "Character";
                            default -> "Player";
                        };
                        names.put(kv[1].strip(), fake);
                    }
                }
            }
        } catch (IOException ignored) {
            // only the player's name then
        }
        return names;
    }

    // ---- files ----

    /**
     * Writes a fixture into {@code dir}, replacing what was there; {@code clean} may be null (the
     * data is clean already). Returns file → bytes.
     */
    static Map<String, Long> write(Data d, Path dir, Sanitiser clean) throws IOException {
        java.util.function.UnaryOperator<JsonElement> c = e -> clean == null ? e : clean.clean(e);
        // the same order every time, so fake ids are handed out the same way
        JsonElement state = c.apply(GSON.toJsonTree(d.state()));
        JsonElement items = c.apply(GSON.toJsonTree(d.items()));
        JsonElement gear = c.apply(GSON.toJsonTree(d.gearDb()));
        JsonElement prices = c.apply(GSON.toJsonTree(d.prices(), new TypeToken<Map<String, Prices.Price>>() {}.getType()));
        Map<String, JsonElement> rules = new LinkedHashMap<>();
        d.rules().forEach((id, r) -> rules.put(clean == null ? id : clean.text(id), c.apply(r)));
        JsonElement settings = c.apply(d.settings());
        JsonElement tree = d.tree() == null ? null : c.apply(GSON.toJsonTree(d.tree()));

        if (Files.isDirectory(dir)) {
            try (Stream<Path> all = Files.walk(dir)) {
                for (Path p : all.sorted(Comparator.reverseOrder()).toList()) {
                    if (!p.equals(dir)) {
                        Files.delete(p);
                    }
                }
            }
        }
        Files.createDirectories(dir.resolve("rules"));
        Map<String, Long> sizes = new LinkedHashMap<>();
        sizes.put("state.json", put(dir.resolve("state.json"), pretty(state)));
        sizes.put("items.json", put(dir.resolve("items.json"), pretty(items)));
        sizes.put("geardb.json", put(dir.resolve("geardb.json"), gearText(gear.getAsJsonObject())));
        sizes.put("prices.json", put(dir.resolve("prices.json"), pretty(prices)));
        sizes.put("settings.json", put(dir.resolve("settings.json"), pretty(settings)));
        if (tree != null) {
            sizes.put("tree.json", put(dir.resolve("tree.json"), pretty(tree)));
        }
        for (var e : rules.entrySet()) {
            sizes.put("rules/" + e.getKey() + ".json", put(dir.resolve("rules").resolve(e.getKey() + ".json"),
                    pretty(e.getValue())));
        }
        return sizes;
    }

    /** Reads a fixture folder back. */
    static Data read(Path dir) throws IOException {
        State state = GSON.fromJson(Files.readString(dir.resolve("state.json"), StandardCharsets.UTF_8), State.class);
        if (state == null || state.format() != FORMAT) {
            throw new IOException(dir.getFileName() + ": fixture format " + (state == null ? "?" : state.format())
                    + ", this build reads " + FORMAT + ": record it again (/fixture)");
        }
        Items items = GSON.fromJson(Files.readString(dir.resolve("items.json"), StandardCharsets.UTF_8), Items.class);
        GearDb gear = GSON.fromJson(Files.readString(dir.resolve("geardb.json"), StandardCharsets.UTF_8), GearDb.class);
        Map<String, Prices.Price> prices = GSON.fromJson(Files.readString(dir.resolve("prices.json"), StandardCharsets.UTF_8),
                new TypeToken<LinkedHashMap<String, Prices.Price>>() {}.getType());
        Map<String, JsonObject> rules = new LinkedHashMap<>();
        Path rd = dir.resolve("rules");
        if (Files.isDirectory(rd)) {
            try (Stream<Path> files = Files.list(rd)) {
                for (Path f : files.sorted().toList()) {
                    String n = f.getFileName().toString();
                    if (n.endsWith(".json")) {
                        rules.put(n.substring(0, n.length() - 5),
                                JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject());
                    }
                }
            }
        }
        Path sf = dir.resolve("settings.json");
        JsonObject settings = Files.exists(sf)
                ? JsonParser.parseString(Files.readString(sf, StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
        Path tf = dir.resolve("tree.json");
        Tree tree = Files.exists(tf) ? GSON.fromJson(Files.readString(tf, StandardCharsets.UTF_8), Tree.class) : null;
        return new Data(state, items, gear, prices, rules, settings, tree);
    }

    private static long put(Path f, String text) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        Files.write(f, b);
        return b.length;
    }

    static String pretty(JsonElement e) {
        return GSON.toJson(e) + "\n";
    }

    /** geardb.json: pretty at the top, one compact line per piece, so a diff shows whole pieces. */
    static String gearText(JsonObject db) {
        StringBuilder sb = new StringBuilder("{\n");
        List<String> keys = new ArrayList<>(db.keySet());
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            JsonElement v = db.get(k);
            sb.append("  ").append(COMPACT.toJson(k)).append(": ");
            if (v.isJsonArray() && !v.getAsJsonArray().isEmpty()) {
                JsonArray a = v.getAsJsonArray();
                sb.append("[\n");
                for (int j = 0; j < a.size(); j++) {
                    sb.append("    ").append(COMPACT.toJson(a.get(j))).append(j + 1 < a.size() ? ",\n" : "\n");
                }
                sb.append("  ]");
            } else if (v.isJsonObject() && !v.getAsJsonObject().isEmpty()) {
                JsonObject o = v.getAsJsonObject();
                sb.append("{\n");
                List<String> ok = new ArrayList<>(o.keySet());
                for (int j = 0; j < ok.size(); j++) {
                    sb.append("    ").append(COMPACT.toJson(ok.get(j))).append(": ").append(COMPACT.toJson(o.get(ok.get(j))))
                            .append(j + 1 < ok.size() ? ",\n" : "\n");
                }
                sb.append("  }");
            } else {
                sb.append(COMPACT.toJson(v));
            }
            sb.append(i + 1 < keys.size() ? ",\n" : "\n");
        }
        return sb.append("}\n").toString();
    }
}
