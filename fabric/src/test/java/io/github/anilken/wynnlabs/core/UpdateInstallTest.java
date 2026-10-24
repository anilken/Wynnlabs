package io.github.anilken.wynnlabs.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The install-on-exit step on temporary files: replaced, refused, restored, and what the next run hears. */
class UpdateInstallTest {

    private static final byte[] OLD = "PK the running wynn labs jar".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEW = "PK the new wynn labs jar, verified".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tmp;

    @AfterEach
    void restore() {
        UpdateInstall.reset();
        UpdatePrefs.folder = () -> Dirs.config().resolve("updates");
    }

    private Path mods() throws IOException {
        return Files.createDirectories(tmp.resolve("mods"));
    }

    /** A running jar in mods/ and a verified pending jar in the updates folder. */
    private UpdateInstall.Plan plan() throws Exception {
        Path target = Files.write(mods().resolve("wynnlabs-fabric-0.2.4.jar"), OLD);
        Path dir = Files.createDirectories(tmp.resolve("updates"));
        Path pending = Files.write(dir.resolve("wynnlabs-update.jar"), NEW);
        return new UpdateInstall.Plan(pending, UpdateDownloadTest.sha(NEW), target, dir, "v0.3.0");
    }

    private static final UpdateInstall.Writer REFUSE_MOVE = new UpdateInstall.Writer() {
        @Override
        public void replace(Path from, Path target) throws IOException {
            throw new AccessDeniedException(target.toString());           // Windows: the jar is in use
        }

        @Override
        public void overwrite(Path from, Path target) throws IOException {
            UpdateInstall.writeInto(from, target);
        }
    };

    @Test
    void onlyAPlainJarInModsCanBeReplaced() throws Exception {
        Path mods = mods();
        assertTrue(UpdateInstall.installable(Files.write(mods.resolve("wynnlabs.jar"), OLD), mods));
        assertFalse(UpdateInstall.installable(Files.write(tmp.resolve("elsewhere.jar"), OLD), mods), "outside mods");
        assertFalse(UpdateInstall.installable(Files.createDirectories(mods.resolve("folder.jar")), mods), "a directory");
        assertFalse(UpdateInstall.installable(Files.write(mods.resolve("notes.txt"), OLD), mods), "not a jar");
        assertFalse(UpdateInstall.installable(mods.resolve("missing.jar"), mods));
        assertFalse(UpdateInstall.installable(null, mods));
    }

    @Test
    void aVerifiedJarReplacesTheOldOne() throws Exception {
        UpdateInstall.Plan p = plan();
        UpdateInstall.Outcome o = UpdateInstall.install(p, UpdateInstall.writer);
        assertTrue(o.ok());
        assertEquals("move", o.reason());
        assertArrayEquals(NEW, Files.readAllBytes(p.target()));
        assertFalse(Files.exists(p.pending()));
        assertFalse(Files.exists(p.dir().resolve("previous").resolve(p.target().getFileName())), "backup removed once verified");
        assertFalse(Files.exists(p.target().resolveSibling(p.target().getFileName() + ".wlnew")));
        assertTrue(Files.exists(p.dir().resolve("outcome.json")));
    }

    @Test
    void aChangedPendingJarIsNeverInstalled() throws Exception {
        UpdateInstall.Plan p = plan();
        Files.writeString(p.pending(), "tampered with after the download");
        UpdateInstall.Outcome o = UpdateInstall.install(p, UpdateInstall.writer);
        assertFalse(o.ok());
        assertEquals("hash", o.reason());
        assertArrayEquals(OLD, Files.readAllBytes(p.target()));
    }

    @Test
    void aRefusedMoveFallsBackToWritingOver() throws Exception {
        UpdateInstall.Plan p = plan();
        UpdateInstall.Outcome o = UpdateInstall.install(p, REFUSE_MOVE);
        assertTrue(o.ok());
        assertEquals("copy-over", o.reason());
        assertArrayEquals(NEW, Files.readAllBytes(p.target()));
    }

    @Test
    void whenBothFailTheOldBytesComeBack() throws Exception {
        UpdateInstall.Plan p = plan();
        UpdateInstall.Writer broken = new UpdateInstall.Writer() {
            @Override
            public void replace(Path from, Path target) throws IOException {
                throw new AccessDeniedException(target.toString());
            }

            @Override
            public void overwrite(Path from, Path target) throws IOException {
                Files.writeString(target, "PK half");                    // a partial write, then the lock
                throw new IOException("locked");
            }
        };
        UpdateInstall.Outcome o = UpdateInstall.install(p, broken);
        assertFalse(o.ok());
        assertEquals("locked", o.reason());
        assertArrayEquals(OLD, Files.readAllBytes(p.target()), "the mod still starts");
        assertTrue(Files.exists(p.pending()), "kept for a manual copy");
        assertTrue(Files.exists(p.dir().resolve("previous").resolve(p.target().getFileName())), "and the backup");
    }

    @Test
    void theNextRunHearsTheOutcomeOnce() throws Exception {
        UpdateInstall.Plan p = plan();
        UpdateInstall.install(p, UpdateInstall.writer);
        UpdateInstall.Outcome o = UpdateInstall.readOutcome(p.dir(), "0.3.0");
        assertTrue(o.ok());
        assertEquals("v0.3.0", o.tag());
        assertNull(UpdateInstall.readOutcome(p.dir(), "0.3.0"), "said once");

        UpdateInstall.install(p, REFUSE_MOVE);                          // the pending jar is gone now: a failure
        UpdateInstall.Outcome failed = UpdateInstall.readOutcome(p.dir(), "0.3.0");
        assertFalse(failed.ok());
        assertEquals("hash", failed.reason());

        UpdateInstall.Plan q = plan();
        UpdateInstall.install(q, UpdateInstall.writer);
        assertNull(UpdateInstall.readOutcome(q.dir(), "0.2.4"), "an ok for another version than the one running says nothing");
    }

    @Test
    void scheduleIsANoOpForADevBuildOrWithoutAJar() throws Exception {
        int[] hooks = {0};
        UpdateInstall.hooks = t -> hooks[0]++;
        UpdatePrefs.folder = () -> tmp.resolve("updates");
        try {
            Path jar = Files.write(mods().resolve("wynnlabs.jar"), OLD);
            assertFalse(UpdateInstall.schedule("0.0.0+dev", "v0.3.0", "x", Optional.of(jar)));
            assertFalse(UpdateInstall.schedule("0.2.0", "v0.3.0", "x", Optional.empty()));
            assertFalse(UpdateInstall.scheduled());
            assertEquals(0, hooks[0]);
            assertTrue(UpdateInstall.schedule("0.2.0", "v0.3.0", "x", Optional.of(jar)));
            assertTrue(UpdateInstall.schedule("0.2.0", "v0.3.1", "y", Optional.of(jar)));
            assertEquals(1, hooks[0], "one hook, whatever is pending");
        } finally {
            UpdateInstall.hooks = t -> Runtime.getRuntime().addShutdownHook(t);
        }
    }
}
