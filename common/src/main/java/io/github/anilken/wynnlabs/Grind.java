package io.github.anilken.wynnlabs;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Where to level: caves, dungeons, boss altars and grind spots near your level, closest first.
 *
 * <p>Read from the map data Wynntils already downloaded ({@code wynntils/cache}); no network.
 * Grind spots carry no level there, so they get the average level of the caves around them.
 */
final class Grind {

    /**
     * {@code kind} is a language-neutral id: "cave", "dungeon", "boss altar" or "grind spot"
     * (also the API's value). Show it with {@link #kindName}.
     */
    record Spot(String kind, String name, int level, boolean estimated, int x, int y, int z, String info) {}

    static final String CAVE = "cave";
    static final String DUNGEON = "dungeon";
    static final String ALTAR = "boss altar";
    static final String GRIND_SPOT = "grind spot";

    /** The kind in the game's language ("mağara" / "cave"). */
    static String kindName(String kind) {
        return switch (kind) {
            case CAVE -> L.t("wynnlabs.grind.kind_cave");
            case DUNGEON -> L.t("wynnlabs.grind.kind_dungeon");
            case ALTAR -> L.t("wynnlabs.grind.kind_altar");
            case GRIND_SPOT -> L.t("wynnlabs.grind.kind_grind_spot");
            default -> kind;
        };
    }

    static String kindName(Spot s) {
        return kindName(s.kind());
    }

    /**
     * A place line made with {@link #kindName} ("Name mağara (Lv. …)") made short for a narrow
     * column: caves and grind spots lose the kind, a dungeon becomes "(zindan)", an altar "(boss)".
     */
    static String shortKind(String place) {
        return place.replace(" " + kindName(CAVE), "").replace(" " + kindName(GRIND_SPOT), "")
                .replace(" " + kindName(DUNGEON), " (" + kindName(DUNGEON) + ")")
                .replace(" " + kindName(ALTAR), " " + L.t("wynnlabs.grind.altar_short"));
    }

    private static final Gson GSON = new Gson();
    private static volatile List<Spot> spots;

    private Grind() {}

    static List<Spot> spots() {
        if (spots == null) {
            spots = load();
        }
        return spots;
    }

    private static JsonElement read(String name) throws Exception {
        Path f = Platform.get().gameDir().resolve("wynntils/cache/" + name);
        return GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), JsonElement.class);
    }

    private static List<Spot> load() {
        List<Spot> out = new ArrayList<>();
        try {
            for (JsonElement e : read("dataStaticCaveInfo").getAsJsonArray()) {
                JsonObject c = e.getAsJsonObject();
                JsonObject loc = c.getAsJsonObject("location");
                List<String> rewards = new ArrayList<>();
                c.getAsJsonArray("rewards").forEach(r -> rewards.add(ItemJson.clean(r.getAsString())));
                String info = c.get("length").getAsString() + ", " + c.get("difficulty").getAsString()
                        + ", " + String.join(", ", rewards);
                out.add(new Spot(CAVE, c.get("name").getAsString(), c.get("requirements").getAsInt(), false,
                        loc.get("x").getAsInt(), loc.get("y").getAsInt(), loc.get("z").getAsInt(), info));
            }
        } catch (Exception e) {
            System.out.println("[WynnLabs] caves unavailable: " + e);
        }
        List<JsonObject> unleveled = new ArrayList<>();
        try {
            for (JsonElement e : read("dataStaticCombatMapFeatures").getAsJsonArray()) {
                JsonObject f = e.getAsJsonObject();
                String cat = f.get("categoryId").getAsString();
                String kind = switch (cat) {
                    case "wynntils:content:dungeon" -> DUNGEON;
                    case "wynntils:content:boss-altar" -> ALTAR;
                    case "wynntils:content:grind-spot" -> GRIND_SPOT;
                    default -> null;
                };
                if (kind == null) {
                    continue;
                }
                JsonObject a = f.getAsJsonObject("attributes");
                JsonObject loc = f.getAsJsonObject("location");
                if (a.has("level")) {
                    out.add(new Spot(kind, a.get("label").getAsString(), a.get("level").getAsInt(), false,
                            loc.get("x").getAsInt(), loc.get("y").getAsInt(), loc.get("z").getAsInt(), ""));
                } else {
                    unleveled.add(f);
                }
            }
        } catch (Exception e) {
            System.out.println("[WynnLabs] map features unavailable: " + e);
        }
        // Grind spots: guess the level from the three nearest caves
        List<Spot> caves = out.stream().filter(s -> s.kind().equals(CAVE)).toList();
        for (JsonObject f : unleveled) {
            JsonObject loc = f.getAsJsonObject("location");
            int x = loc.get("x").getAsInt();
            int z = loc.get("z").getAsInt();
            List<Spot> near = caves.stream()
                    .sorted(Comparator.comparingDouble(c -> dist(c.x(), c.z(), x, z)))
                    .limit(3).toList();
            if (near.isEmpty() || dist(near.get(0).x(), near.get(0).z(), x, z) > 600) {
                continue;
            }
            String label = f.getAsJsonObject("attributes").get("label").getAsString();
            Integer known = Rules.spotLevel(label);
            if (known != null) {
                out.add(new Spot(GRIND_SPOT, label, known, false, x, loc.get("y").getAsInt(), z, ""));
                continue;
            }
            // a guess, so lean high: the toughest cave nearby plus a margin (an average hid
            // the strong mobs at Desert Altar)
            int lv = near.stream().mapToInt(Spot::level).max().orElse(0) + 2;
            out.add(new Spot(GRIND_SPOT, label, lv, true, x, loc.get("y").getAsInt(), z, ""));
        }
        return List.copyOf(out);
    }

    private static double dist(int x1, int z1, int x2, int z2) {
        return Math.hypot(x1 - x2, z1 - z2);
    }

    /** Spots from a bit below to a bit above your level, closest first. */
    static List<Spot> near(int level, int max) {
        GameState state = GameState.current();
        int below = (int) Rules.sub("grind", "levelsBelow", 2);
        int above = (int) Rules.sub("grind", "levelsAbove", 3);
        double[] at = state.position();
        double px = at[0];
        double pz = at[1];
        return state.spots().stream()
                .filter(s -> s.level() >= level - below && s.level() + hardness(s) <= level + above)
                // closest first, but a spot that measurably pays well (XpMeter) jumps ahead
                .sorted(Comparator.comparingDouble(s -> Math.hypot(s.x() - px, s.z() - pz)
                        - Math.max(0, state.xpRate(s.name())) / 50.0))
                .limit(max)
                .toList();
    }

    /**
     * Extra levels a spot asks for beyond its number: hard caves and guessed grind spots hit
     * harder than their level says; easy ones count as listed.
     */
    static int hardness(Spot s) {
        String info = s.info() == null ? "" : s.info().toLowerCase(java.util.Locale.ROOT);
        String[] parts = info.split(",\\s*");
        String diff = parts.length > 1 ? parts[1] : "";      // "length, difficulty, ..."
        int h = switch (diff) {
            case "hard" -> 4;
            case "medium" -> 1;
            default -> 0;
        };
        // altars spawn one big boss, dungeons are long runs: both ask more than their number
        h += switch (s.kind()) {
            case ALTAR -> 6;
            case DUNGEON -> 3;
            default -> 0;
        };
        // where you keep dying is harder than it looks (Ölüm koçu)
        if (DeathCoach.deathsNear(s.x(), s.z()) >= 2) {
            h += 3;
        }
        // a fragile character (low effective HP for the level) needs a wider margin
        Build.Plan plan = Build.plan();
        if (plan != null && plan.current() != null) {
            int level = Wyn.level();
            double norm = 45.0 * level;                    // a sturdy build at this level
            if (plan.current().ehp() < norm) {
                h += 2;
            }
        }
        return h + (s.estimated() ? 1 : 0);
    }

    /** "420 blok kuzeydoğu" ("420 blocks northeast") from where you stand. */
    static String direction(Spot s) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            return "";
        }
        double dx = s.x() - p.getX();
        double dz = s.z() - p.getZ();
        long d = Math.round(Math.hypot(dx, dz));
        // Minecraft: -z is north, +x is east
        String[] names = {L.t("wynnlabs.grind.dir_east"), L.t("wynnlabs.grind.dir_southeast"), L.t("wynnlabs.grind.dir_south"),
                L.t("wynnlabs.grind.dir_southwest"), L.t("wynnlabs.grind.dir_west"), L.t("wynnlabs.grind.dir_northwest"),
                L.t("wynnlabs.grind.dir_north"), L.t("wynnlabs.grind.dir_northeast")};
        double angle = Math.toDegrees(Math.atan2(dz, dx));
        int i = (int) Math.round(((angle % 360) + 360) % 360 / 45.0) % 8;
        return L.t("wynnlabs.grind.distance", d, names[i]);
    }

    static String line(Spot s) {
        return L.t("wynnlabs.grind.line", s.name(), kindName(s), (s.estimated() ? "~" : "") + s.level(), direction(s));
    }

    static JsonObject json() {
        JsonObject o = new JsonObject();
        int level = Minecraft.getInstance().player == null ? 0 : Wyn.level();
        JsonArray arr = new JsonArray();
        for (Spot s : near(level, 12)) {
            JsonObject j = new JsonObject();
            j.addProperty("kind", s.kind());
            j.addProperty("name", s.name());
            j.addProperty("level", s.level());
            if (s.estimated()) {
                j.addProperty("levelEstimated", true);
            }
            j.addProperty("x", s.x());
            j.addProperty("y", s.y());
            j.addProperty("z", s.z());
            j.addProperty("from", direction(s));
            if (!s.info().isEmpty()) {
                j.addProperty("info", s.info().toLowerCase(Locale.ROOT).equals(s.info()) ? s.info() : s.info());
            }
            arr.add(j);
        }
        o.addProperty("level", level);
        o.add("spots", arr);
        o.addProperty("note", "caves, dungeons, boss altars and grind spots from Wynntils' map data, "
                + "closest first; grind spot levels are estimated from nearby caves");
        return o;
    }
}
