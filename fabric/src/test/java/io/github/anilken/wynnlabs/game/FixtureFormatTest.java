package io.github.anilken.wynnlabs.game;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fixture files read back into the model and written again give the same bytes. */
class FixtureFormatTest {

    private static List<Path> files(Path dir) throws Exception {
        try (Stream<Path> all = Files.walk(dir)) {
            return all.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static void assertSameFiles(Path a, Path b) throws Exception {
        List<String> names = new ArrayList<>();
        for (Path f : files(a)) {
            names.add(a.relativize(f).toString());
        }
        List<String> other = new ArrayList<>();
        for (Path f : files(b)) {
            other.add(b.relativize(f).toString());
        }
        assertEquals(names, other, "same files");
        for (String n : names) {
            assertArrayEquals(Files.readAllBytes(a.resolve(n)), Files.readAllBytes(b.resolve(n)), n + " changed on the way");
        }
    }

    @Test
    void syntheticStateRoundTrips(@TempDir Path tmp) throws Exception {
        TestGame.boot();
        Fixture.Data data = SyntheticData.data("a1b2c3d4", "e5f60718", "Somebody", "Fluffy");
        Path first = tmp.resolve("first");
        Path second = tmp.resolve("second");
        Fixture.write(data, first, null);
        Fixture.Data back = Fixture.read(first);
        Fixture.write(back, second, null);
        assertSameFiles(first, second);
        // Optional survives as Optional, a price never seen as a known null
        assertEquals(Optional.empty(), back.gearDb().gear().get(0).set());
        assertTrue(back.gearDb().gear().get(1).set().isPresent());
        assertTrue(back.prices().containsKey("Leafy Relik"));
        assertNull(back.prices().get("Leafy Relik"));
        assertEquals(Optional.empty(), back.gearDb().obtain().get("Keen Measure").get(1).name());
    }

    @Test
    void committedFixturesRoundTrip(@TempDir Path tmp) throws Exception {
        List<Path> dirs = Fixtures.folders();
        Assumptions.assumeFalse(dirs.isEmpty(), Fixtures::none);
        TestGame.boot();
        for (Path dir : dirs) {
            Path out = tmp.resolve(dir.getFileName().toString());
            Fixture.write(Fixture.read(dir), out, null);
            // the recorded files only: expected/ belongs to the tests
            Path inputs = tmp.resolve(dir.getFileName() + "-inputs");
            for (Path f : files(dir)) {
                Path rel = dir.relativize(f);
                if (!rel.startsWith("expected")) {
                    Files.createDirectories(inputs.resolve(rel).getParent());
                    Files.copy(f, inputs.resolve(rel));
                }
            }
            assertSameFiles(inputs, out);
        }
    }
}
