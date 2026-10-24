package io.github.anilken.wynnlabs.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * Downloads a release jar into {@code config/wynnlabs/updates/}, never into {@code mods/}. The
 * bytes go to {@code wynnlabs-update.jar.part} through a SHA-256; only when the size and the hash
 * equal what GitHub published does the file become {@code wynnlabs-update.jar}, and every other
 * end deletes the part. Redirects are followed by hand, each hop over https to GitHub's own hosts.
 * A pending jar that already matches is kept instead of downloaded again. One download at a time,
 * on its own thread; the UI reads {@link #progress()} each frame.
 */
public final class UpdateDownload {

    public enum Phase { IDLE, DOWNLOADING, READY, FAILED }

    /** Why a download ended without a jar; NONE when it worked. */
    public enum Failure { NONE, NETWORK, HOST, SIZE, HASH, NO_JAR, SAME, DISK }

    static final String PENDING = "wynnlabs-update.jar";
    private static final int MAX_HOPS = 5;
    private static final Set<String> HOSTS = Set.of("github.com", "api.github.com",
            "objects.githubusercontent.com", "release-assets.githubusercontent.com");

    /** One HTTP answer: its status, its Location header (null if none) and its body. */
    record Hop(int status, String location, InputStream body) {}

    /** Makes one GET without following redirects; tests answer by hand. */
    @FunctionalInterface
    interface Opener {
        Hop open(URI uri) throws IOException;
    }

    /** A hop to a host that is not GitHub's (or not over https): the download stops. */
    static final class HostRefused extends IOException {
        HostRefused(String what) {
            super(what);
        }
    }

    private static volatile Phase phase = Phase.IDLE;
    private static volatile double progress;
    private static volatile Failure failure = Failure.NONE;
    private static volatile String tag;
    /** A download started from chat (or the auto update) ended: its result in chat on the next tick. */
    private static volatile Phase notice;

    /** Runs a download; tests swap it for one that runs nothing or runs at once. */
    static Consumer<Runnable> starter = job -> {
        Thread t = new Thread(job, "wynnlabs-update-download");
        t.setDaemon(true);
        t.start();
    };
    static Opener opener = UpdateDownload::get;
    /** This jar's path when it can be replaced; tests give their own. */
    static java.util.function.Supplier<Optional<Path>> ownJar = UpdateDownload::findOwnJar;

    private UpdateDownload() {}

    public static Phase phase() {
        return phase;
    }

    /** 0..1 while downloading. */
    public static double progress() {
        return progress;
    }

    public static Failure failure() {
        return failure;
    }

    /** The release the download is (or was) for. */
    public static String tag() {
        return tag;
    }

    /**
     * Starts downloading {@code asset} of release {@code tag}; false when one already runs, the
     * jar is already pending for this tag, or this build cannot be updated. {@code chat}: started
     * from the chat line or by the auto update, which hear the result in chat.
     */
    public static synchronized boolean start(UpdateAsset asset, String forTag, boolean chat) {
        if (asset == null || phase == Phase.DOWNLOADING || phase == Phase.READY && forTag.equals(tag)) {
            return false;
        }
        String mine = UpdateCheck.version.get();
        if (!Versions.checkable(mine)) {
            return false;                       // a dev build is never updated
        }
        Optional<Path> jar = ownJar.get();
        tag = forTag;
        progress = 0;
        if (jar.isEmpty()) {
            failure = Failure.NO_JAR;           // run from classes or a nested jar: the page link instead
            phase = Phase.FAILED;
            return false;
        }
        failure = Failure.NONE;
        phase = Phase.DOWNLOADING;
        starter.accept(() -> run(asset, forTag, chat, jar.get(), mine));
        return true;
    }

    private static void run(UpdateAsset asset, String forTag, boolean chat, Path jar, String mine) {
        Failure f;
        try {
            f = fetch(opener, asset, UpdatePrefs.folder(), jar, p -> progress = p);
        } catch (Throwable e) {
            f = Failure.NETWORK;
        }
        if (f == Failure.NONE && !UpdateInstall.schedule(mine, forTag, asset.sha256(), Optional.of(jar))) {
            f = Failure.NO_JAR;
        }
        System.out.println("[WynnLabs] update: download of " + forTag + (f == Failure.NONE ? " ready" : " failed: " + f));
        failure = f;
        progress = f == Failure.NONE ? 1 : 0;
        phase = f == Failure.NONE ? Phase.READY : Phase.FAILED;
        if (chat) {
            notice = phase;
        }
    }

    /** The jar Wynn Labs was loaded from, when it is a plain jar in {@code mods/}. */
    static Optional<Path> findOwnJar() {
        try {
            Optional<Path> p = Platform.get().modFile("wynnlabs");
            Path mods = Platform.get().gameDir().resolve("mods");
            return p.filter(j -> UpdateInstall.installable(j, mods));
        } catch (Throwable e) {
            return Optional.empty();
        }
    }

    /** How a download started from chat ended (READY or FAILED), once; null when there is nothing to say. */
    public static Phase takeNotice() {
        Phase n = notice;
        notice = null;
        return n;
    }

    /**
     * Gets {@code asset} into {@code dir}/{@value #PENDING}: kept when already there with the
     * right hash, otherwise downloaded and checked. {@code running} is this jar; when it already
     * has the hash there is nothing to do ({@link Failure#SAME}).
     */
    static Failure fetch(Opener opener, UpdateAsset asset, Path dir, Path running, DoubleConsumer progress) throws IOException {
        if (running != null && Files.isRegularFile(running) && asset.sha256().equals(sha256(running))) {
            return Failure.SAME;
        }
        Path pending = dir.resolve(PENDING);
        if (Files.isRegularFile(pending)) {
            if (asset.sha256().equals(sha256(pending))) {
                progress.accept(1);
                return Failure.NONE;            // pressed twice, or a run before: nothing to download
            }
            Files.delete(pending);
        }
        InputStream in;
        try {
            in = follow(opener, asset.url());
        } catch (HostRefused e) {
            System.out.println("[WynnLabs] update: refused a download hop to " + e.getMessage());
            return Failure.HOST;
        } catch (IOException e) {
            return Failure.NETWORK;
        }
        Path part = dir.resolve(PENDING + ".part");
        boolean done = false;
        try (InputStream body = in) {
            Files.createDirectories(dir);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long n = 0;
            byte[] buf = new byte[64 * 1024];
            try (OutputStream out = Files.newOutputStream(part)) {
                int r;
                while ((r = body.read(buf)) >= 0) {
                    n += r;
                    if (n > asset.size()) {
                        return Failure.SIZE;    // more than GitHub said: not the file we asked for
                    }
                    sha.update(buf, 0, r);
                    out.write(buf, 0, r);
                    progress.accept((double) n / asset.size());
                }
            }
            if (n != asset.size()) {
                return Failure.SIZE;
            }
            if (!HexFormat.of().formatHex(sha.digest()).equals(asset.sha256())) {
                return Failure.HASH;
            }
            try {
                Files.move(part, pending, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, pending, StandardCopyOption.REPLACE_EXISTING);
            }
            done = true;
            return Failure.NONE;
        } catch (NoSuchAlgorithmException e) {
            return Failure.DISK;
        } catch (IOException e) {
            return Failure.NETWORK;
        } finally {
            if (!done) {
                Files.deleteIfExists(part);
            }
        }
    }

    /** The body behind {@code url}, following at most {@value #MAX_HOPS} redirects, each to GitHub over https. */
    static InputStream follow(Opener opener, String url) throws IOException {
        if (url == null || !url.toLowerCase(Locale.ROOT).startsWith(UpdateAsset.PREFIX)) {
            throw new HostRefused(String.valueOf(url));
        }
        URI u = URI.create(url);
        for (int hop = 0; hop <= MAX_HOPS; hop++) {
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(u.getScheme()) || !HOSTS.contains(host)) {
                throw new HostRefused(u.getScheme() + "://" + host);
            }
            Hop h = opener.open(u);
            int s = h.status();
            if (s == 301 || s == 302 || s == 303 || s == 307 || s == 308) {
                close(h.body());
                if (h.location() == null) {
                    throw new IOException("redirect without a location");
                }
                u = u.resolve(h.location());
                continue;
            }
            if (s != 200) {
                close(h.body());
                throw new IOException("HTTP " + s);
            }
            return h.body();
        }
        throw new IOException("too many redirects");
    }

    private static void close(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // nothing to keep
            }
        }
    }

    /** One real GET with redirects off. */
    private static Hop get(URI uri) throws IOException {
        var http = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build();
        var req = java.net.http.HttpRequest.newBuilder(uri)
                .header("User-Agent", "WynnLabs/" + UpdateCheck.version.get() + " (Minecraft client mod; updater; https://github.com/anilken/wynnlabs)")
                .timeout(Duration.ofSeconds(30))
                .GET().build();
        try {
            var res = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            return new Hop(res.statusCode(), res.headers().firstValue("location").orElse(null), res.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    /** A file's SHA-256 in lower-case hex. */
    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) >= 0) {
                sha.update(buf, 0, r);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** The preview's About shots: this phase, as if a download had run. */
    public static synchronized void preview(Phase p, double fraction, Failure f, String forTag) {
        phase = p;
        progress = fraction;
        failure = f;
        tag = forTag;
    }

    /** Tests: as a fresh run. */
    static synchronized void reset() {
        phase = Phase.IDLE;
        progress = 0;
        failure = Failure.NONE;
        tag = null;
        notice = null;
    }
}
