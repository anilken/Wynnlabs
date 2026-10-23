package io.github.anilken.wynnlabs.search;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The world map's tiles as Wynntils downloaded them: {@code wynntils/cache/dataStaticMaps} lists
 * each tile's world rectangle (inclusive corners) and the md5 its picture is saved under in
 * {@code wynntils/cache/maps/<md5>.png}. A tile's picture is one pixel per block, so a world point
 * lands on pixel (x − x1, z − z1) of the tile that holds it. Pure: no Minecraft types, read off
 * the game thread; the pictures are read where they lie and never copied anywhere.
 *
 * <p>Besides the "Main r-c" grid the list has quest-instance tiles (Realm of Light, Kingdom of
 * Sand …), some drawn over the main world's own area. Only the ones clear of the main grid count
 * as world tiles, so a view of the main world never shows a quest's version of it.
 */
public final class MapTiles {

    /** A tile: its name, the inclusive world corners and the md5 of its picture. */
    public record Tile(String name, int x1, int z1, int x2, int z2, String md5) {

        /** Width in blocks, which is also the picture's width in pixels. */
        public int width() {
            return x2 - x1 + 1;
        }

        public int height() {
            return z2 - z1 + 1;
        }

        public boolean main() {
            return name.startsWith("Main ");
        }

        /** Whether the block column at (x, z) is on this tile. */
        public boolean contains(double x, double z) {
            return x >= x1 && x < x2 + 1 && z >= z1 && z < z2 + 1;
        }

        /** Whether the world rectangle [ax0, ax1) × [az0, az1) shares any area with this tile. */
        public boolean overlaps(double ax0, double az0, double ax1, double az1) {
            return ax0 < x2 + 1 && ax1 > x1 && az0 < z2 + 1 && az1 > z1;
        }

        /** The picture's pixel {px, pz} for a world point (fractional inside the block). */
        public double[] pixel(double x, double z) {
            return new double[] {x - x1, z - z1};
        }
    }

    private static final MapTiles NONE = new MapTiles(List.of(), null);

    private final List<Tile> all;
    /** Main tiles first, then the other tiles that do not cover any main tile. */
    private final List<Tile> world;
    private final Path dir;

    private MapTiles(List<Tile> all, Path dir) {
        this.all = List.copyOf(all);
        this.dir = dir;
        List<Tile> w = new ArrayList<>();
        for (Tile t : all) {
            if (t.main()) {
                w.add(t);
            }
        }
        List<Tile> mains = List.copyOf(w);
        for (Tile t : all) {
            if (!t.main() && mains.stream().noneMatch(m -> m.overlaps(t.x1, t.z1, t.x2 + 1, t.z2 + 1))) {
                w.add(t);
            }
        }
        this.world = List.copyOf(w);
    }

    /** No tiles: the map has no picture. */
    public static MapTiles none() {
        return NONE;
    }

    /** The tiles listed in a {@code dataStaticMaps} text; entries missing a field are skipped. */
    public static MapTiles parse(String json) {
        return new MapTiles(read(json), null);
    }

    /**
     * The tiles under {@code <cacheDir>/dataStaticMaps} whose picture is on disk; none when the list
     * is missing or unreadable. Reads files: call it off the game thread.
     */
    public static MapTiles load(Path cacheDir) {
        try {
            Path list = cacheDir.resolve("dataStaticMaps");
            if (!Files.isRegularFile(list)) {
                return NONE;
            }
            List<Tile> on = new ArrayList<>();
            for (Tile t : read(Files.readString(list, StandardCharsets.UTF_8))) {
                if (Files.isRegularFile(picture(cacheDir, t))) {
                    on.add(t);
                }
            }
            return on.isEmpty() ? NONE : new MapTiles(on, cacheDir);
        } catch (Exception e) {
            return NONE;
        }
    }

    private static List<Tile> read(String json) {
        List<Tile> out = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) {
                return out;
            }
            for (JsonElement e : root.getAsJsonArray()) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject o = e.getAsJsonObject();
                if (!o.has("name") || !o.has("x1") || !o.has("z1") || !o.has("x2") || !o.has("z2") || !o.has("md5")) {
                    continue;
                }
                int x1 = o.get("x1").getAsInt();
                int z1 = o.get("z1").getAsInt();
                int x2 = o.get("x2").getAsInt();
                int z2 = o.get("z2").getAsInt();
                String md5 = o.get("md5").getAsString();
                if (x2 < x1 || z2 < z1 || !md5.matches("[0-9a-fA-F]{32}")) {
                    continue;                                   // the md5 names a file: nothing else gets in
                }
                out.add(new Tile(o.get("name").getAsString(), x1, z1, x2, z2, md5.toLowerCase(java.util.Locale.ROOT)));
            }
        } catch (RuntimeException e) {
            out.clear();
        }
        return out;
    }

    private static Path picture(Path cacheDir, Tile t) {
        return cacheDir.resolve("maps").resolve(t.md5() + ".png");
    }

    /** Every listed tile, in the file's order. */
    public List<Tile> all() {
        return all;
    }

    /** The tiles of the world map: main ones first, then those clear of them. */
    public List<Tile> world() {
        return world;
    }

    public boolean isEmpty() {
        return world.isEmpty();
    }

    /** The picture of a tile on disk; null for a table not read from disk. */
    public Path image(Tile t) {
        return dir == null ? null : picture(dir, t);
    }

    /** The world tile holding (x, z), main tiles first; null when none does. */
    public Tile at(double x, double z) {
        for (Tile t : world) {
            if (t.contains(x, z)) {
                return t;
            }
        }
        return null;
    }

    /**
     * The world tiles that share area with the rectangle [x0, x1) × [z0, z1), main tiles first, put
     * into {@code out} (cleared first, so a caller can keep one list across frames).
     */
    public List<Tile> covering(double x0, double z0, double x1, double z1, List<Tile> out) {
        out.clear();
        for (Tile t : world) {
            if (t.overlaps(x0, z0, x1, z1)) {
                out.add(t);
            }
        }
        return out;
    }

    /** The main grid's outer rectangle {x0, z0, x1, z1} (exclusive ends); every world tile's when there is no main tile, null when empty. */
    public double[] bounds() {
        double[] b = null;
        boolean anyMain = world.stream().anyMatch(Tile::main);
        for (Tile t : world) {
            if (anyMain && !t.main()) {
                continue;
            }
            if (b == null) {
                b = new double[] {t.x1(), t.z1(), t.x2() + 1, t.z2() + 1};
            } else {
                b[0] = Math.min(b[0], t.x1());
                b[1] = Math.min(b[1], t.z1());
                b[2] = Math.max(b[2], t.x2() + 1);
                b[3] = Math.max(b[3], t.z2() + 1);
            }
        }
        return b;
    }
}
