package io.github.anilken.wynnlabs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Where the fixture folders are (fabric/src/test/resources/fixtures/, passed in by the build). */
final class Fixtures {

    private static boolean told;

    private Fixtures() {}

    static Path root() {
        return Path.of(System.getProperty("wynnlabs.fixtures", "fabric/src/test/resources/fixtures"));
    }

    /** True with -PupdateFixtures: write the expected files instead of comparing. */
    static boolean update() {
        return Boolean.getBoolean("wynnlabs.updateFixtures");
    }

    /** Every folder with a state.json, by name. */
    static List<Path> folders() {
        Path root = root();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs.filter(d -> Files.exists(d.resolve("state.json"))).sorted().toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Why the fixture tests skip; said once per run in the build log. */
    static synchronized String none() {
        String why = "no fixture folders in " + root() + ": fixture tests skipped";
        if (!told) {
            told = true;
            note(why);
        }
        return why;
    }

    /** A line for the build log. */
    static void note(String line) {
        System.out.println("[fixtures] " + line);
    }
}
