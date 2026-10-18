package io.github.anilken.wynnlabs;

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

/**
 * Once per game run, a few seconds after the player first shows up in a world, asks GitHub for
 * the newest release and, only when it is newer than this jar, says so in one chat line with a
 * link to the release page. Setting {@code updateCheck}: while it is off no request is made. A dev
 * or unknown version skips the check. One request, on its own thread, never retried; a failure
 * is one log line. The line goes to this client's chat only, never to the server.
 */
final class UpdateCheck {

    static final String URL = "https://api.github.com/repos/anilken/wynnlabs/releases/latest";
    /** Ticks in a world before asking: about five seconds, so joining is not crowded. */
    private static final int DELAY_TICKS = 100;

    /** A release as GitHub describes it: its tag ("v0.3.0") and its page. */
    record Release(String tag, String url) {}

    private static int ticks;
    /** Whether this run already asked (or decided not to). */
    private static boolean started;
    /** A newer release the worker found, shown on the next tick in a world. */
    private static volatile Release pending;
    private static String own = "";

    private UpdateCheck() {}

    static void onTick(Minecraft mc) {
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
        own = Platform.get().modVersion("wynnlabs").orElse("");
        if (!checkable(own)) {
            return;                             // a dev build or an unknown version: nothing to compare
        }
        String mine = own;
        Thread t = new Thread(() -> fetch(mine), "wynnlabs-update");
        t.setDaemon(true);
        t.start();
    }

    /** On the worker thread: one GET, then a note for the next tick when GitHub has a newer one. */
    private static void fetch(String mine) {
        try {
            var http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            var req = java.net.http.HttpRequest.newBuilder(URI.create(URL))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "WynnLabs/" + mine + " (Minecraft client mod; update check; https://github.com/anilken/wynnlabs)")
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();
            var res = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) {
                System.out.println("[WynnLabs] update check: HTTP " + res.statusCode());
                return;
            }
            Release r = parse(res.body());
            if (r == null) {
                System.out.println("[WynnLabs] update check: no usable release in the answer");
                return;
            }
            if (newer(r.tag(), mine)) {
                pending = r;
            }
        } catch (Throwable e) {
            System.out.println("[WynnLabs] update check failed: " + e.getClass().getSimpleName());
        }
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
            return new Release(tag.trim(), url);
        } catch (RuntimeException e) {
            return null;
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

    /** Whether this jar's version is worth comparing: a release number, not a dev build or 0.0.0. */
    static boolean checkable(String version) {
        if (version == null) {
            return false;
        }
        String v = version.toLowerCase(Locale.ROOT);
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
