package io.github.anilken.wynnlabs.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The download into the updates folder: checked, reused, cleaned up, GitHub hosts only; no network. */
class UpdateDownloadTest {

    private static final byte[] JAR = "PK new wynn labs jar bytes".getBytes(StandardCharsets.UTF_8);
    private static final String URL = "https://github.com/anilken/Wynnlabs/releases/download/v0.3.0/wynnlabs-fabric-0.3.0.jar";

    @TempDir
    Path dir;

    @AfterEach
    void restore() {
        UpdateDownload.reset();
        UpdateCheck.reset();
        UpdateDownload.ownJar = UpdateDownload::findOwnJar;
    }

    static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    private static UpdateAsset asset(byte[] b) throws Exception {
        return new UpdateAsset("wynnlabs-fabric-0.3.0.jar", b.length, sha(b), URL);
    }

    /** Answers every GET with {@code body}, recording the URIs asked. */
    private static UpdateDownload.Opener serve(InputStream body, List<URI> asked) {
        return uri -> {
            asked.add(uri);
            return new UpdateDownload.Hop(200, null, body);
        };
    }

    /** No file left behind in the updates folder but the ones named. */
    private void onlyFiles(String... names) throws IOException {
        List<String> left = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (var s = Files.list(dir)) {
                s.forEach(p -> left.add(p.getFileName().toString()));
            }
        }
        assertEquals(List.of(names), left);
    }

    @Test
    void aMatchingDownloadBecomesThePendingJar() throws Exception {
        List<URI> asked = new ArrayList<>();
        double[] last = {0};
        assertEquals(UpdateDownload.Failure.NONE,
                UpdateDownload.fetch(serve(new ByteArrayInputStream(JAR), asked), asset(JAR), dir, null, p -> last[0] = p));
        assertArrayEquals(JAR, Files.readAllBytes(dir.resolve("wynnlabs-update.jar")));
        assertEquals(1.0, last[0]);
        assertEquals(List.of(URI.create(URL)), asked);
        onlyFiles("wynnlabs-update.jar");
    }

    @Test
    void aWrongHashShortReadOrOversizeLeavesNothing() throws Exception {
        UpdateAsset a = asset(JAR);
        byte[] other = "PK other bytes of the same len".getBytes(StandardCharsets.UTF_8);
        UpdateAsset sameSize = new UpdateAsset(a.name(), other.length, a.sha256(), URL);
        assertEquals(UpdateDownload.Failure.HASH, UpdateDownload.fetch(serve(new ByteArrayInputStream(other), new ArrayList<>()),
                sameSize, dir, null, p -> { }));
        onlyFiles();
        byte[] half = java.util.Arrays.copyOf(JAR, JAR.length / 2);
        assertEquals(UpdateDownload.Failure.SIZE, UpdateDownload.fetch(serve(new ByteArrayInputStream(half), new ArrayList<>()),
                a, dir, null, p -> { }));
        onlyFiles();
        byte[] more = (new String(JAR, StandardCharsets.UTF_8) + " and more").getBytes(StandardCharsets.UTF_8);
        assertEquals(UpdateDownload.Failure.SIZE, UpdateDownload.fetch(serve(new ByteArrayInputStream(more), new ArrayList<>()),
                a, dir, null, p -> { }));
        onlyFiles();
    }

    @Test
    void aConnectionLostMidwayDeletesThePart() throws Exception {
        InputStream dropping = new InputStream() {
            private int n;

            @Override
            public int read() throws IOException {
                if (n++ >= JAR.length * 2 / 5) {
                    throw new IOException("connection reset");      // at 40%
                }
                return JAR[n - 1];
            }
        };
        assertEquals(UpdateDownload.Failure.NETWORK,
                UpdateDownload.fetch(serve(dropping, new ArrayList<>()), asset(JAR), dir, null, p -> { }));
        onlyFiles();
    }

    @Test
    void redirectsStayOnGitHub() throws Exception {
        List<URI> asked = new ArrayList<>();
        String storage = "https://release-assets.githubusercontent.com/github-production-release-asset/1?sig=x";
        UpdateDownload.Opener github = uri -> {
            asked.add(uri);
            return uri.getHost().equals("github.com") ? new UpdateDownload.Hop(302, storage, null)
                    : new UpdateDownload.Hop(200, null, new ByteArrayInputStream(JAR));
        };
        assertEquals(UpdateDownload.Failure.NONE, UpdateDownload.fetch(github, asset(JAR), dir, null, p -> { }));
        assertEquals(2, asked.size());
        Files.delete(dir.resolve("wynnlabs-update.jar"));

        asked.clear();
        UpdateDownload.Opener elsewhere = uri -> {
            asked.add(uri);
            return new UpdateDownload.Hop(302, "https://evil.example/jar", null);
        };
        assertEquals(UpdateDownload.Failure.HOST, UpdateDownload.fetch(elsewhere, asset(JAR), dir, null, p -> { }));
        assertEquals(1, asked.size(), "the other host is never asked");
        onlyFiles();
        UpdateDownload.Opener plain = uri -> new UpdateDownload.Hop(302, "http://objects.githubusercontent.com/x", null);
        assertEquals(UpdateDownload.Failure.HOST, UpdateDownload.fetch(plain, asset(JAR), dir, null, p -> { }), "https only");
        UpdateDownload.Opener loop = uri -> new UpdateDownload.Hop(302, URL, null);
        assertEquals(UpdateDownload.Failure.NETWORK, UpdateDownload.fetch(loop, asset(JAR), dir, null, p -> { }), "too many hops");
        UpdateAsset elsewhereAsset = new UpdateAsset("x.jar", JAR.length, sha(JAR), "https://objects.githubusercontent.com/x.jar");
        assertEquals(UpdateDownload.Failure.HOST, UpdateDownload.fetch(serve(new ByteArrayInputStream(JAR), new ArrayList<>()),
                elsewhereAsset, dir, null, p -> { }), "the first link must be the release page's");
        onlyFiles();
    }

    @Test
    void aMatchingPendingJarIsReusedAndAStaleOneReplaced() throws Exception {
        Files.write(dir.resolve("wynnlabs-update.jar"), JAR);
        List<URI> asked = new ArrayList<>();
        assertEquals(UpdateDownload.Failure.NONE,
                UpdateDownload.fetch(serve(new ByteArrayInputStream(JAR), asked), asset(JAR), dir, null, p -> { }));
        assertTrue(asked.isEmpty(), "nothing downloaded again");

        Files.writeString(dir.resolve("wynnlabs-update.jar"), "an older pending jar");
        assertEquals(UpdateDownload.Failure.NONE,
                UpdateDownload.fetch(serve(new ByteArrayInputStream(JAR), asked), asset(JAR), dir, null, p -> { }));
        assertEquals(1, asked.size());
        assertArrayEquals(JAR, Files.readAllBytes(dir.resolve("wynnlabs-update.jar")));

        Path running = Files.write(dir.resolve("running.jar"), JAR);
        Files.delete(dir.resolve("wynnlabs-update.jar"));
        assertEquals(UpdateDownload.Failure.SAME,
                UpdateDownload.fetch(serve(new ByteArrayInputStream(JAR), asked), asset(JAR), dir, running, p -> { }));
        assertEquals(1, asked.size(), "this jar is already that release");
    }

    @Test
    void oneDownloadAtATimeAndNoneForADevBuild() throws Exception {
        UpdateCheck.version = () -> "0.2.0";
        Path jar = Files.writeString(dir.resolve("wynnlabs.jar"), "old");
        UpdateDownload.ownJar = () -> Optional.of(jar);
        int[] started = {0};
        UpdateDownload.starter = job -> started[0]++;
        assertTrue(UpdateDownload.start(asset(JAR), "v0.3.0", false));
        assertEquals(UpdateDownload.Phase.DOWNLOADING, UpdateDownload.phase());
        assertFalse(UpdateDownload.start(asset(JAR), "v0.3.0", false), "one runs already");
        assertEquals(1, started[0]);

        UpdateDownload.reset();
        UpdateCheck.version = () -> "0.0.0+dev";
        assertFalse(UpdateDownload.start(asset(JAR), "v0.3.0", false));
        UpdateCheck.version = () -> "0.2.0";
        UpdateDownload.ownJar = Optional::empty;
        assertFalse(UpdateDownload.start(asset(JAR), "v0.3.0", false));
        assertEquals(UpdateDownload.Failure.NO_JAR, UpdateDownload.failure());
        assertEquals(1, started[0]);
    }
}
