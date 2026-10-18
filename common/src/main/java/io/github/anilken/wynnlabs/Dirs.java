package io.github.anilken.wynnlabs;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/** Where the mod keeps its files: {@code config/wynnlabs/}. Every file path starts here. */
final class Dirs {

    private Dirs() {}

    static Path config() {
        return FabricLoader.getInstance().getConfigDir().resolve("wynnlabs");
    }

    /**
     * Once, before anything reads config: an install from before the rename keeps its data. When
     * {@code config/wynninvapi/} exists and {@code config/wynnlabs/} does not, the old tree is
     * copied (never moved, so the old jar still works after a rollback) and a marker is written.
     */
    static void migrate() {
        Path old = FabricLoader.getInstance().getConfigDir().resolve("wynninvapi");
        Path now = config();
        if (!Files.isDirectory(old) || Files.exists(now)) {
            return;
        }
        Path tmp = now.resolveSibling("wynnlabs-migrating");
        try {
            deleteTree(tmp);                    // a copy cut short last time
            try (Stream<Path> all = Files.walk(old)) {
                for (Path p : (Iterable<Path>) all::iterator) {
                    Path to = tmp.resolve(old.relativize(p).toString());
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(to);
                    } else {
                        Files.copy(p, to, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }
            }
            Files.writeString(tmp.resolve("migrated-from-wynninvapi"), "copied " + java.time.Instant.now());
            Files.move(tmp, now);               // all or nothing: a half copy never looks finished
            System.out.println("[WynnLabs] copied config/wynninvapi to config/wynnlabs");
        } catch (IOException e) {
            System.out.println("[WynnLabs] could not copy config/wynninvapi: " + e);
        }
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> all = Files.walk(p)) {
            for (Path q : all.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(q);
            }
        }
    }
}
