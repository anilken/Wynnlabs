package io.github.anilken.wynnlabs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.anilken.wynnlabs.model.AbilityNode;
import io.github.anilken.wynnlabs.model.ItemView;
import io.github.anilken.wynnlabs.model.Klass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The connector pieces of the ability tree, as the game's tree menu shows them: which cells
 * hold a piece and which way its arms point. Wynntils' tree data has only which nodes
 * connect, not the way the line runs. The pieces come from Wynncraft's own ability map (the
 * tree as wynncraft.com draws it), shipped in the jar as assets/wynnlabs/trees/<class>.json
 * (tools/trees/update.py refreshes them), and from the tree menu whenever a page is open
 * (config/wynnlabs/trees/<class>.json), which wins where both have the cell. The J menu
 * draws the tree's lines from them ({@link TreeLayout#lines}).
 */
final class TreeConnectors {

    /** A piece: its type's name ("DOWN_LEFT_TURN" …) and its arms up, right, down, left. */
    record Piece(String type, boolean[] arms) {}

    private static final int FORMAT = 2;
    private static final int NODE_SLOTS = TreeLayout.COLS * TreeLayout.ROWS;
    private static final Map<Klass, Map<String, boolean[]>> CELLS = new ConcurrentHashMap<>();
    private static final Map<Klass, Map<String, String>> TYPES = new ConcurrentHashMap<>();
    private static final Map<Klass, Map<String, boolean[]>> MAP = new ConcurrentHashMap<>();
    private static final Map<Klass, Map<String, boolean[]>> MERGED = new ConcurrentHashMap<>();

    private TreeConnectors() {}

    private static Path file(Klass cls) {
        return Dirs.config().resolve("trees").resolve(cls.name().toLowerCase(java.util.Locale.ROOT) + ".json");
    }

    /**
     * The pieces of a class, "page:row:col" → up, right, down, left: the official map's, with
     * what the tree menu showed over it. The same map object until something changes.
     */
    static Map<String, boolean[]> get(Klass cls) {
        if (cls == null || cls == Klass.NONE) {
            return Map.of();
        }
        return MERGED.computeIfAbsent(cls, c -> {
            Map<String, boolean[]> out = new LinkedHashMap<>(MAP.computeIfAbsent(c, TreeConnectors::loadMap));
            out.putAll(CELLS.computeIfAbsent(c, TreeConnectors::load));
            return java.util.Collections.unmodifiableMap(out);
        });
    }

    /** The pieces the tree menu showed, alone. */
    static Map<String, boolean[]> seen(Klass cls) {
        if (cls == null || cls == Klass.NONE) {
            return Map.of();
        }
        return CELLS.computeIfAbsent(cls, TreeConnectors::load);
    }

    /**
     * The shipped map's pieces: {@code cells} of "page:row:col" → the icon named by the
     * piece's arms ("connector_right_down_left"), as tools/trees/update.py writes them from
     * api.wynncraft.com/v3/ability/map/<class>.
     */
    static Map<String, boolean[]> parseShipped(JsonObject map) {
        Map<String, boolean[]> out = new LinkedHashMap<>();
        if (map == null || !map.has("cells") || !map.get("cells").isJsonObject()) {
            return out;
        }
        for (var e : map.getAsJsonObject("cells").entrySet()) {
            boolean[] arms = arms(text(map.getAsJsonObject("cells"), e.getKey()));
            if (arms != null && e.getKey().split(":").length == 3) {
                out.put(e.getKey(), arms);
            }
        }
        return out;
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    /** The arms a map icon names ("connector_up_down" → up and down), or null for another icon. */
    static boolean[] arms(String icon) {
        if (icon == null || !icon.startsWith("connector_")) {
            return null;
        }
        boolean[] out = new boolean[4];
        int n = 0;
        for (String part : icon.substring("connector_".length()).split("_")) {
            int i = switch (part) {
                case "up" -> TreeLayout.UP;
                case "right" -> TreeLayout.RIGHT;
                case "down" -> TreeLayout.DOWN;
                case "left" -> TreeLayout.LEFT;
                default -> -1;
            };
            if (i >= 0) {
                out[i] = true;
                n++;
            }
        }
        return n == 0 ? null : out;
    }

    private static Map<String, boolean[]> loadMap(Klass cls) {
        String name = "/assets/wynnlabs/trees/" + cls.name().toLowerCase(java.util.Locale.ROOT) + ".json";
        try (var in = TreeConnectors.class.getResourceAsStream(name)) {
            if (in != null) {
                return parseShipped(JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            }
        } catch (Exception e) {
            System.out.println("[WynnLabs] ability map for " + cls + ": " + e);
        }
        return new LinkedHashMap<>();
    }

    /** A seen piece's type name, for the game's picture of it; null for an unknown cell. */
    static String type(Klass cls, String key) {
        if (cls == null || cls == Klass.NONE) {
            return null;
        }
        get(cls);
        Map<String, String> t = TYPES.get(cls);
        return t == null ? null : t.get(key);
    }

    private static Map<String, boolean[]> load(Klass cls) {
        Map<String, boolean[]> out = new LinkedHashMap<>();
        Map<String, String> types = new LinkedHashMap<>();
        try {
            Path f = file(cls);
            if (Files.exists(f)) {
                JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject cells = o.getAsJsonObject("cells");
                for (var e : cells.entrySet()) {
                    // format 1 kept the arms alone; 2 the piece's type with them
                    JsonArray a = e.getValue().isJsonArray() ? e.getValue().getAsJsonArray()
                            : e.getValue().getAsJsonObject().getAsJsonArray("arms");
                    boolean[] d = new boolean[4];
                    for (int i = 0; i < 4 && i < a.size(); i++) {
                        d[i] = a.get(i).getAsBoolean();
                    }
                    out.put(e.getKey(), d);
                    if (e.getValue().isJsonObject() && e.getValue().getAsJsonObject().has("type")) {
                        types.put(e.getKey(), e.getValue().getAsJsonObject().get("type").getAsString());
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[WynnLabs] tree connectors for " + cls + ": " + e);
        }
        TYPES.put(cls, types);
        return out;
    }

    private static void save(Klass cls, Map<String, boolean[]> cells, Map<String, String> types) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("format", FORMAT);
            JsonObject c = new JsonObject();
            for (var e : cells.entrySet()) {
                JsonArray a = new JsonArray();
                for (boolean b : e.getValue()) {
                    a.add(b);
                }
                JsonObject piece = new JsonObject();
                if (types.get(e.getKey()) != null) {
                    piece.addProperty("type", types.get(e.getKey()));
                }
                piece.add("arms", a);
                c.add(e.getKey(), piece);
            }
            o.add("cells", c);
            Path f = file(cls);
            Files.createDirectories(f.getParent());
            Files.writeString(f, o.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("[WynnLabs] tree connectors for " + cls + ": " + e);
        }
    }

    /**
     * Reads the page open in the tree menu: the page is the one the node items on it belong
     * to, the pieces are the other items in the 9 x 6 grid. Nothing happens on any other menu.
     * Returns how many pieces were read.
     */
    static int capture(AbstractContainerScreen<?> screen) {
        GameState gs = GameState.current();
        var tree = gs.abilityTree();
        Klass cls = gs.classType();
        if (tree == null || tree.isEmpty() || cls == null || cls == Klass.NONE) {
            return 0;
        }
        Map<String, AbilityNode> byName = new HashMap<>();
        for (AbilityNode n : tree) {
            byName.put(n.name(), n);
        }
        Map<Integer, Integer> votes = new HashMap<>();
        Map<Integer, Piece> pieces = new HashMap<>();
        var inventory = Minecraft.getInstance().player == null ? null : Minecraft.getInstance().player.getInventory();
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container == inventory || slot.index >= NODE_SLOTS) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            ItemView v = WynntilsItems.view(stack).orElse(null);
            String name = ItemJson.abilityName(v != null && v.abilityName() != null ? v.abilityName()
                    : stack.getHoverName().getString());
            AbilityNode n = byName.get(name);
            if (n != null && n.where() != null) {
                votes.merge(n.where().page(), 1, Integer::sum);
                continue;
            }
            Piece piece = Wyn.connector(stack);
            if (piece != null) {
                pieces.put(slot.index, piece);
            }
        }
        if (votes.isEmpty() || pieces.isEmpty()) {
            return 0;
        }
        int page = votes.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
        Map<String, boolean[]> before = seen(cls);
        Map<String, String> typesBefore = TYPES.getOrDefault(cls, Map.of());
        Map<String, boolean[]> cells = new LinkedHashMap<>(before);
        Map<String, String> types = new LinkedHashMap<>(typesBefore);
        // the page's old pieces go: a piece that is gone stays gone
        String prefix = page + ":";
        cells.keySet().removeIf(k -> k.startsWith(prefix));
        types.keySet().removeIf(k -> k.startsWith(prefix));
        for (var e : pieces.entrySet()) {
            String key = TreeLayout.connectorKey(page, e.getKey() / TreeLayout.COLS, e.getKey() % TreeLayout.COLS);
            cells.put(key, e.getValue().arms());
            types.put(key, e.getValue().type());
        }
        if (!same(before, cells) || !typesBefore.equals(types)) {
            CELLS.put(cls, cells);
            TYPES.put(cls, types);
            MERGED.remove(cls);
            save(cls, cells, types);
        }
        return pieces.size();
    }

    private static boolean same(Map<String, boolean[]> a, Map<String, boolean[]> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (var e : a.entrySet()) {
            if (!java.util.Arrays.equals(e.getValue(), b.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /** For a fixture or a test: these pieces for the class (arms only), nothing read or saved. */
    static void use(Klass cls, Map<String, boolean[]> cells) {
        MERGED.remove(cls);
        if (cells == null) {
            CELLS.remove(cls);
            TYPES.remove(cls);
            MAP.remove(cls);
        } else {
            CELLS.put(cls, new LinkedHashMap<>(cells));
            TYPES.put(cls, new LinkedHashMap<>());
            MAP.put(cls, new LinkedHashMap<>());
        }
    }

    /** The pieces as JSON, for the local API and fixtures. */
    static JsonElement json(Klass cls) {
        JsonObject c = new JsonObject();
        for (var e : get(cls).entrySet()) {
            JsonArray a = new JsonArray();
            for (boolean b : e.getValue()) {
                a.add(b);
            }
            c.add(e.getKey(), a);
        }
        return c;
    }
}
