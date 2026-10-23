package io.github.anilken.wynnlabs.ui;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.anilken.wynnlabs.search.MapTiles;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The map view's tile pictures: the tile table and each picture are read on a worker thread,
 * the render thread turns a finished picture into a texture on the next frame, and at most
 * {@link #MAX} textures are held (the least recently drawn goes first). {@link #close} frees them
 * all. The pictures stay in Wynntils' cache; nothing is copied.
 */
final class MapTileCache implements AutoCloseable {

    /** Textures held at once (a 2048×1024 tile is 8 MB). */
    static final int MAX = 4;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wynnlabs-map-tiles");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger SERIAL = new AtomicInteger();

    private static final class Entry {
        final Identifier id;
        final CompletableFuture<NativeImage> picture;
        DynamicTexture texture;

        Entry(Identifier id, CompletableFuture<NativeImage> picture) {
            this.id = id;
            this.picture = picture;
        }
    }

    private final int serial = SERIAL.incrementAndGet();
    private final CompletableFuture<MapTiles> table;
    /** Render thread only; access order, so the first entry is the least recently drawn. */
    private final Map<String, Entry> entries = new LinkedHashMap<>(8, 0.75f, true);
    private final Set<String> failed = new HashSet<>();
    private boolean closed;

    /** Reads the tile table under {@code cacheDir} (Wynntils' {@code cache} folder) on the worker. */
    MapTileCache(Path cacheDir) {
        table = cacheDir == null ? CompletableFuture.completedFuture(MapTiles.none())
                : CompletableFuture.supplyAsync(() -> MapTiles.load(cacheDir), WORKER);
    }

    /** The tile table once read; null while it is still being read. */
    MapTiles table() {
        return table.isDone() ? table.getNow(MapTiles.none()) : null;
    }

    /**
     * The texture for a tile when its picture is ready, null while it loads (or could not be
     * read). {@code wanted} are the tiles this frame draws: they are never evicted for another.
     * Render thread only.
     */
    Identifier texture(MapTiles.Tile tile, List<MapTiles.Tile> wanted) {
        if (closed || failed.contains(tile.md5())) {
            return null;
        }
        Entry e = entries.get(tile.md5());
        if (e == null) {
            if (!makeRoom(wanted)) {
                return null;
            }
            MapTiles t = table();
            Path file = t == null ? null : t.image(tile);
            if (file == null) {
                failed.add(tile.md5());
                return null;
            }
            e = new Entry(Identifier.fromNamespaceAndPath("wynnlabs", "search_map/" + serial + "/" + tile.md5()),
                    CompletableFuture.supplyAsync(() -> read(file), WORKER));
            entries.put(tile.md5(), e);
        }
        if (e.texture == null) {
            if (!e.picture.isDone()) {
                return null;
            }
            NativeImage img = e.picture.getNow(null);
            if (img == null) {
                entries.remove(tile.md5());
                failed.add(tile.md5());
                return null;
            }
            String name = tile.name();
            e.texture = new DynamicTexture(() -> "wynnlabs map " + name, img);   // uploads: render thread
            Minecraft.getInstance().getTextureManager().register(e.id, e.texture);
        }
        return e.id;
    }

    /** Frees the least recently drawn entry not wanted this frame until one more fits; false when all are wanted. */
    private boolean makeRoom(List<MapTiles.Tile> wanted) {
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (entries.size() >= MAX && it.hasNext()) {
            Map.Entry<String, Entry> me = it.next();
            if (isWanted(me.getKey(), wanted)) {
                continue;
            }
            free(me.getValue());
            it.remove();
        }
        return entries.size() < MAX;
    }

    private static boolean isWanted(String md5, List<MapTiles.Tile> wanted) {
        for (MapTiles.Tile t : wanted) {
            if (t.md5().equals(md5)) {
                return true;
            }
        }
        return false;
    }

    private static NativeImage read(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return NativeImage.read(NativeImage.Format.RGBA, in);
        } catch (Exception e) {
            return null;
        }
    }

    private static void free(Entry e) {
        if (e.texture != null) {
            Minecraft.getInstance().getTextureManager().release(e.id);    // closes the texture and its picture
            e.texture = null;
        } else {
            e.picture.thenAccept(img -> {
                if (img != null) {
                    img.close();
                }
            });
        }
    }

    /** Whether a tile's picture could not be read (it is not asked for again). */
    boolean failed(MapTiles.Tile tile) {
        return failed.contains(tile.md5());
    }

    /** Textures held now (for tests and the preview). */
    int held() {
        return entries.size();
    }

    /** Frees every texture and every picture still loading. Render thread only; the cache draws nothing after. */
    @Override
    public void close() {
        closed = true;
        for (Entry e : entries.values()) {
            free(e);
        }
        entries.clear();
    }
}
