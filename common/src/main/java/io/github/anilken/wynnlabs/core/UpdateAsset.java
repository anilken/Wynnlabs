package io.github.anilken.wynnlabs.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One jar attached to a GitHub release, as far as the updater trusts it: its file name, its size
 * in bytes, the SHA-256 GitHub publishes for it (64 lower-case hex digits) and its download link
 * on this project's release page. Assets without a SHA-256, not fully uploaded, empty or
 * oversized, or hosted anywhere else are never read in.
 */
public record UpdateAsset(String name, long size, String sha256, String url) {

    /** Where every release jar lives (GitHub writes the owner's name with a capital W; compared ignoring case). */
    static final String PREFIX = "https://github.com/anilken/wynnlabs/releases/download/";
    /** No Wynn Labs jar comes near this; anything bigger is not ours. */
    static final long MAX_SIZE = 64L << 20;
    /** The one Minecraft version releases are built for; a jar name without "mc" is for this one. */
    static final String BUILT_FOR = "1.21.11";

    private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");

    /** "fabric" or "neoforge"; tests give their own. */
    static Supplier<String> loader = () -> Platform.get().loaderName();
    /** The running Minecraft version ("1.21.11"), null when unknown; tests give their own. */
    static Supplier<String> minecraft = UpdateAsset::runningMinecraft;

    /** The usable assets of a release's {@code assets} array, in order. */
    static List<UpdateAsset> parse(JsonArray assets) {
        List<UpdateAsset> out = new ArrayList<>();
        if (assets == null) {
            return out;
        }
        for (JsonElement e : assets) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject a = e.getAsJsonObject();
            String name = text(a, "name");
            String digest = text(a, "digest");
            String url = text(a, "browser_download_url");
            long size = a.has("size") && a.get("size").isJsonPrimitive() && a.getAsJsonPrimitive("size").isNumber()
                    ? a.get("size").getAsLong() : -1;
            if (name == null || url == null || !"uploaded".equals(text(a, "state"))
                    || size < 1 || size > MAX_SIZE
                    || !url.toLowerCase(Locale.ROOT).startsWith(PREFIX) || url.contains("..")) {
                continue;
            }
            String sha = digest == null || !digest.startsWith("sha256:") ? null
                    : digest.substring("sha256:".length()).toLowerCase(Locale.ROOT);
            if (sha == null || !HEX.matcher(sha).matches()) {
                continue;                       // no SHA-256 (or another hash): never downloaded
            }
            out.add(new UpdateAsset(name, size, sha, url));
        }
        return out;
    }

    /** {@link #choose(List, String, String, String)} for this game's loader and Minecraft version. */
    static UpdateAsset choose(List<UpdateAsset> assets, String tag) {
        String l;
        String mc;
        try {
            l = loader.get();
            mc = minecraft.get();
        } catch (Throwable e) {
            return null;
        }
        return choose(assets, tag, l, mc);
    }

    /**
     * The one jar for {@code loader} named {@code wynnlabs-<loader>-<tag without v>.jar}, with an
     * optional "+mc1.21.11" or "-mc1.21.11" that must equal {@code mc}; a name without it is for
     * {@link #BUILT_FOR} (or fits when {@code mc} is unknown). None or several fitting: null.
     */
    static UpdateAsset choose(List<UpdateAsset> assets, String tag, String loader, String mc) {
        if (assets == null || tag == null || loader == null || loader.isBlank()) {
            return null;
        }
        String version = tag.trim().startsWith("v") || tag.trim().startsWith("V") ? tag.trim().substring(1) : tag.trim();
        Pattern p = Pattern.compile("wynnlabs-" + Pattern.quote(loader.toLowerCase(Locale.ROOT)) + "-" + Pattern.quote(version)
                + "(?:[+-]mc([0-9][0-9.]*))?\\.jar", Pattern.CASE_INSENSITIVE);
        UpdateAsset found = null;
        for (UpdateAsset a : assets) {
            Matcher m = p.matcher(a.name());
            if (!m.matches()) {
                continue;
            }
            String named = m.group(1);
            boolean fits = named != null ? named.equals(mc) : mc == null || mc.equals(BUILT_FOR);
            if (!fits) {
                continue;
            }
            if (found != null) {
                return null;                    // two jars could be meant: offer the page instead
            }
            found = a;
        }
        return found;
    }

    private static String runningMinecraft() {
        try {
            return net.minecraft.SharedConstants.getCurrentVersion().name();
        } catch (Throwable e) {
            return null;
        }
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }
}
