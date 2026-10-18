package io.github.anilken.wynnlabs;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which secret discoveries this character has found, read from the Content Book when the player
 * opens its Secret Discoveries page. Wynntils' own discovery list only fills when Wynntils runs
 * its content-book scan (which opens and clicks the book by itself), so a book the player simply
 * looks at never showed up. Reads only; saved per character in characters/&lt;id&gt;/discoveries-book.json.
 */
final class DiscoveryBook {

    private static int tick;
    private static String loadedFor = "";
    private static final Set<String> FOUND = new TreeSet<>();
    private static boolean seen;

    private DiscoveryBook() {}

    /** Names found according to the Content Book, for the current character. */
    static synchronized Set<String> found() {
        load();
        return Set.copyOf(FOUND);
    }

    /** True once the Content Book's secret discoveries were seen for this character. */
    /** For the no-game preview: as if the book's discoveries had been read. */
    static synchronized void previewSeen() {
        seen = true;
    }

    static synchronized boolean seen() {
        load();
        return seen;
    }

    static void onTick(Minecraft mc) {
        if (++tick % 10 != 0 || mc.player == null || !(mc.screen instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        try {
            boolean any = false;
            Set<String> done = new TreeSet<>();
            Set<String> notDone = new TreeSet<>();
            for (var a : ContentBook.activities(mc)) {
                if (a.name() == null || !"SECRET_DISCOVERY".equals(a.type())) {
                    continue;
                }
                any = true;
                (("COMPLETED".equals(a.status())) ? done : notDone).add(a.name());
            }
            if (any) {
                record(done, notDone);
            }
        } catch (Throwable t) {
            // a menu the reader does not understand: nothing to record
        }
    }

    private static synchronized void record(Set<String> done, Set<String> notDone) {
        load();
        boolean changed = !seen;
        seen = true;
        changed |= FOUND.addAll(done);
        changed |= FOUND.removeAll(notDone);
        if (changed) {
            save();
        }
    }

    private static Path file() {
        return Dirs.config().resolve("characters").resolve(Profile.id()).resolve("discoveries-book.json");
    }

    private static void load() {
        String id = Profile.id();
        if (id.equals(loadedFor)) {
            return;
        }
        loadedFor = id;
        FOUND.clear();
        seen = false;
        if (id.isEmpty()) {
            return;
        }
        try {
            if (Files.exists(file())) {
                JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonArray()
                        .forEach(e -> FOUND.add(e.getAsString()));
                seen = true;
            }
        } catch (Exception e) {
            System.out.println("[WynnLabs] could not read discoveries-book.json: " + e);
        }
    }

    private static void save() {
        if (Profile.id().isEmpty()) {
            return;
        }
        JsonArray a = new JsonArray();
        FOUND.forEach(a::add);
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), a.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("[WynnLabs] could not save discoveries-book.json: " + e);
        }
    }
}
