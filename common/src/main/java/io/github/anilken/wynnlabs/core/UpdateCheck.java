package io.github.anilken.wynnlabs.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Once per game run, a few seconds after the player first shows up in a world, asks GitHub for
 * the newest release and, only when it is newer than this jar, says so in one chat line with a
 * link to the release page. Setting {@code updateCheck}: while it is off no request is made. A dev
 * or unknown version skips the check. One request, on its own thread, never retried; a failure
 * is one log line. The line goes to this client's chat only, never to the server.
 *
 * <p>The settings tab's About page reads {@link #state()} and may ask again with {@link #checkNow()}:
 * only on the player's press, never in a loop, and at most once every ten seconds.
 */
public final class UpdateCheck {

    static final String URL = "https://api.github.com/repos/anilken/wynnlabs/releases/latest";
    /** Ticks in a world before asking: about five seconds, so joining is not crowded. */
    private static final int DELAY_TICKS = 100;
    /** The shortest time between two requests (a press on "Check again" sooner does nothing). */
    static final long DEBOUNCE_MS = 10_000;

    /** A release as GitHub describes it: its tag ("v0.3.0"), its page and when it came out (0: unknown). */
    record Release(String tag, String url, long published) {
        Release(String tag, String url) {
            this(tag, url, 0);
        }
    }

    /** Where the check is. */
    public enum Status { IDLE, CHECKING, UP_TO_DATE, NEWER, FAILED }

    /** Why a check found nothing: no network, GitHub gave no usable answer, the setting is off, a dev build. */
    public enum Reason { NONE, OFFLINE, NO_ANSWER, OFF, DEV }

    /**
     * What the About page shows: the status (and why it failed), this jar's version, the newest
     * release's tag, page and date, and when the answer came (0: never).
     */
    public record State(Status status, Reason reason, String own, String latest, String url, long published, long checkedAt) {}

    private static int ticks;
    /** Whether this run already asked (or decided not to). */
    private static boolean started;
    /** A newer release the worker found, shown on the next tick in a world. */
    private static volatile Release pending;
    private static String own = "";
    private static volatile State state = new State(Status.IDLE, Reason.NONE, "", null, null, 0, 0);
    /** When the last request started (0: none yet), for the ten-second wait. */
    private static long lastStart;

    /** Runs a request; tests swap it for one that runs nothing or runs at once. */
    static Consumer<Runnable> starter = job -> {
        Thread t = new Thread(job, "wynnlabs-update");
        t.setDaemon(true);
        t.start();
    };
    /** This jar's version ("" when unknown); tests give their own. */
    public static Supplier<String> version = () -> Platform.get().modVersion("wynnlabs").orElse("");

    private UpdateCheck() {}

    public static void onTick(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        Release r = pending;
        if (r != null) {
            pending = null;
            mc.gui.getChat().addMessage(line(r, own));
            return;
        }
        if (started || ++ticks < DELAY_TICKS || !Settings.on("updateCheck")) {
            return;                             // off: nothing is asked (turned on later, it asks then)
        }
        started = true;
        own = ownVersion();
        if (!checkable(own)) {
            return;                             // a dev build or an unknown version: nothing to compare
        }
        start(own, true, System.currentTimeMillis());
    }

    /**
     * What the check knows now. Before any answer: off when the setting is off, a dev build when
     * this jar has no release number, otherwise not asked yet.
     */
    public static State state() {
        State s = state;
        if (s.status() == Status.IDLE || s.reason() == Reason.OFF && Settings.on("updateCheck")) {
            String mine = ownVersion();
            if (!Settings.on("updateCheck")) {
                return new State(Status.FAILED, Reason.OFF, mine, null, null, 0, 0);
            }
            if (!checkable(mine)) {
                return new State(Status.FAILED, Reason.DEV, mine, null, null, 0, 0);
            }
            return new State(Status.IDLE, Reason.NONE, mine, null, null, 0, 0);
        }
        return s;
    }

    /** The player pressed "Check again": one request, unless one runs or started under ten seconds ago. */
    public static boolean checkNow() {
        return checkNow(System.currentTimeMillis());
    }

    /** {@link #checkNow()} at {@code now}; false when nothing was asked (waiting, off, a dev build). */
    static synchronized boolean checkNow(long now) {
        if (state.status() == Status.CHECKING || lastStart != 0 && now - lastStart < DEBOUNCE_MS) {
            return false;
        }
        String mine = ownVersion();
        if (!Settings.on("updateCheck")) {
            state = new State(Status.FAILED, Reason.OFF, mine, null, null, 0, now);
            return false;
        }
        if (!checkable(mine)) {
            state = new State(Status.FAILED, Reason.DEV, mine, null, null, 0, now);
            return false;
        }
        start(mine, false, now);
        return true;
    }

    private static synchronized void start(String mine, boolean auto, long now) {
        lastStart = now;
        State was = state;
        state = new State(Status.CHECKING, Reason.NONE, mine, was.latest(), was.url(), was.published(), was.checkedAt());
        starter.accept(() -> fetch(mine, auto));
    }

    private static String ownVersion() {
        try {
            String v = version.get();
            return v == null ? "" : v;
        } catch (Throwable e) {
            return "";
        }
    }

    /** On the worker thread: one GET, then the state (and, for the automatic check, a chat note). */
    private static void fetch(String mine, boolean auto) {
        try {
            var http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            var req = java.net.http.HttpRequest.newBuilder(URI.create(URL))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "WynnLabs/" + mine + " (Minecraft client mod; update check; https://github.com/anilken/wynnlabs)")
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();
            var res = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            finish(mine, res.statusCode(), res.body(), auto, System.currentTimeMillis());
        } catch (Throwable e) {
            System.out.println("[WynnLabs] update check failed: " + e.getClass().getSimpleName());
            state = new State(Status.FAILED, reasonOf(e), mine, null, null, 0, System.currentTimeMillis());
        }
    }

    /** GitHub's answer into the state; a newer release found by the automatic check is also said in chat. */
    static void finish(String mine, int status, String body, boolean auto, long now) {
        if (status != 200) {
            System.out.println("[WynnLabs] update check: HTTP " + status);
            state = new State(Status.FAILED, Reason.NO_ANSWER, mine, null, null, 0, now);
            return;
        }
        Release r = parse(body);
        if (r == null) {
            System.out.println("[WynnLabs] update check: no usable release in the answer");
            state = new State(Status.FAILED, Reason.NO_ANSWER, mine, null, null, 0, now);
            return;
        }
        boolean newer = newer(r.tag(), mine);
        state = new State(newer ? Status.NEWER : Status.UP_TO_DATE, Reason.NONE, mine, r.tag(), r.url(), r.published(), now);
        if (newer && auto) {
            pending = r;
        }
    }

    /** No network (no route, no name, no connection) or GitHub not answering (a timeout, anything else). */
    static Reason reasonOf(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.UnknownHostException || t instanceof java.net.ConnectException
                    || t instanceof java.net.NoRouteToHostException || t instanceof java.net.http.HttpConnectTimeoutException) {
                return Reason.OFFLINE;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return Reason.NO_ANSWER;
    }

    /** The preview's About shots: this state, as if a check had answered. */
    public static synchronized void preview(State s) {
        state = s;
        lastStart = 0;
    }

    /** Tests: as a fresh game run. */
    public static synchronized void reset() {
        state = new State(Status.IDLE, Reason.NONE, "", null, null, 0, 0);
        lastStart = 0;
        pending = null;
    }

    /** "Wynn Labs ▸ A new version is out: v0.3.0 (you have v0.2.0) · [Download]", the link clickable. */
    static Component line(Release r, String mine) {
        String have = mine.startsWith("v") ? mine : "v" + mine;
        Component download = L.c("wynnlabs.update.download").withStyle(s -> s
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(r.url())))
                .withHoverEvent(new HoverEvent.ShowText(L.c("wynnlabs.update.download_hover"))));
        return L.c("wynnlabs.wear.prefix").withStyle(ChatFormatting.GOLD)
                .append(L.c("wynnlabs.update.newer", r.tag(), have).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" · ").withStyle(ChatFormatting.GRAY))
                .append(download);
    }

    /**
     * The release in GitHub's answer, or null when it has no tag, its page is not on github.com,
     * or it is a draft or a pre-release.
     */
    static Release parse(String json) {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject o = root.getAsJsonObject();
            if (flag(o, "draft") || flag(o, "prerelease")) {
                return null;
            }
            String tag = text(o, "tag_name");
            String url = text(o, "html_url");
            if (tag == null || tag.isBlank() || url == null || !url.startsWith("https://github.com/")) {
                return null;
            }
            URI.create(url);                    // a page we could not open is no use
            return new Release(tag.trim(), url, instant(text(o, "published_at")));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** An ISO time ("2026-10-01T12:00:00Z") in epoch milliseconds, 0 when missing or not a time. */
    private static long instant(String iso) {
        if (iso == null) {
            return 0;
        }
        try {
            return java.time.Instant.parse(iso).toEpochMilli();
        } catch (java.time.format.DateTimeParseException e) {
            return 0;
        }
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private static boolean flag(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    /**
     * Whether this jar's version is worth comparing: a release number, not a dev build or 0.0.0.
     * A local build after a release ("0.2.1+dev", build metadata) compares as that release.
     */
    static boolean checkable(String version) {
        String core = core(version);
        if (core == null) {
            return false;
        }
        String v = core.toLowerCase(Locale.ROOT);
        if (v.contains("dev") || v.contains("snapshot")) {
            return false;
        }
        int[] n = numbers(version);
        return n != null && (n[0] | n[1] | n[2]) != 0;
    }

    /** Whether {@code latest} is a newer version than {@code mine} (a leading "v" ignored). */
    static boolean newer(String latest, String mine) {
        Integer c = compare(latest, mine);
        return c != null && c > 0;
    }

    /**
     * Semver order of two versions, numbers compared as numbers ("0.10.0" after "0.9.0"), a
     * pre-release ("1.0.0-beta") before its release, build metadata ("+mc1.21") ignored; null
     * when either is not a version.
     */
    static Integer compare(String a, String b) {
        int[] x = numbers(a);
        int[] y = numbers(b);
        if (x == null || y == null) {
            return null;
        }
        for (int i = 0; i < 3; i++) {
            if (x[i] != y[i]) {
                return Integer.compare(x[i], y[i]);
            }
        }
        String pa = pre(a);
        String pb = pre(b);
        if (pa.isEmpty() || pb.isEmpty()) {
            return Boolean.compare(pa.isEmpty(), pb.isEmpty());  // a release after its pre-releases
        }
        return comparePre(pa, pb);
    }

    /** The three version numbers ("v1.2" is 1.2.0), or null when it does not start with a number. */
    static int[] numbers(String version) {
        String core = core(version);
        if (core == null) {
            return null;
        }
        int dash = core.indexOf('-');
        if (dash >= 0) {
            core = core.substring(0, dash);
        }
        String[] parts = core.split("\\.", -1);
        if (parts.length == 0 || parts.length > 3) {
            return null;
        }
        int[] n = new int[3];
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 9 || !parts[i].chars().allMatch(Character::isDigit)) {
                return null;
            }
            n[i] = Integer.parseInt(parts[i]);
        }
        return n;
    }

    /** The version without spaces, a leading "v" and build metadata; null when blank. */
    private static String core(String version) {
        if (version == null) {
            return null;
        }
        String v = version.trim();
        if (v.startsWith("v") || v.startsWith("V")) {
            v = v.substring(1);
        }
        int plus = v.indexOf('+');
        if (plus >= 0) {
            v = v.substring(0, plus);
        }
        return v.isEmpty() ? null : v;
    }

    /** The pre-release part ("beta.2" of "1.0.0-beta.2"), empty for a release. */
    private static String pre(String version) {
        String core = core(version);
        int dash = core == null ? -1 : core.indexOf('-');
        return dash < 0 ? "" : core.substring(dash + 1);
    }

    /** Semver pre-release order: dot-separated, numbers as numbers and before words, shorter first. */
    private static int comparePre(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            boolean nx = !x[i].isEmpty() && x[i].length() <= 9 && x[i].chars().allMatch(Character::isDigit);
            boolean ny = !y[i].isEmpty() && y[i].length() <= 9 && y[i].chars().allMatch(Character::isDigit);
            int c;
            if (nx && ny) {
                c = Integer.compare(Integer.parseInt(x[i]), Integer.parseInt(y[i]));
            } else if (nx != ny) {
                c = nx ? -1 : 1;
            } else {
                c = x[i].compareTo(y[i]);
            }
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(x.length, y.length);
    }
}
