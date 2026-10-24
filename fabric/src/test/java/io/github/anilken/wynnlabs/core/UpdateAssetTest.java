package io.github.anilken.wynnlabs.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which release jars the updater trusts and which one it picks for this game. */
class UpdateAssetTest {

    private static final String FABRIC_SHA = "d4d3f04e5cb10bf4601652ca12cef4ab785d44d49a4f918af5c1c01cd126a5ad";
    private static final String NEO_SHA = "8ed4b3b425e7a8c7f986ba7c64a97e24d515eb7bbb6604905a6bb85cf2f144c4";

    /** The v0.2.4 release's assets as GitHub gives them (trimmed to the fields that matter). */
    private static final String V024 = """
            [
              {"name": "wynnlabs-fabric-0.2.4.jar", "size": 4320605, "state": "uploaded",
               "content_type": "application/java-archive",
               "digest": "sha256:d4d3f04e5cb10bf4601652ca12cef4ab785d44d49a4f918af5c1c01cd126a5ad",
               "browser_download_url": "https://github.com/anilken/Wynnlabs/releases/download/v0.2.4/wynnlabs-fabric-0.2.4.jar"},
              {"name": "wynnlabs-neoforge-0.2.4.jar", "size": 4329012, "state": "uploaded",
               "content_type": "application/java-archive",
               "digest": "sha256:8ed4b3b425e7a8c7f986ba7c64a97e24d515eb7bbb6604905a6bb85cf2f144c4",
               "browser_download_url": "https://github.com/anilken/Wynnlabs/releases/download/v0.2.4/wynnlabs-neoforge-0.2.4.jar"}
            ]
            """;

    private static List<UpdateAsset> parse(String json) {
        return UpdateAsset.parse(JsonParser.parseString(json).getAsJsonArray());
    }

    private static UpdateAsset asset(String name) {
        return new UpdateAsset(name, 10, FABRIC_SHA, UpdateAsset.PREFIX + "v0.2.4/" + name);
    }

    @Test
    void readsTheRealReleaseShape() {
        List<UpdateAsset> a = parse(V024);
        assertEquals(2, a.size());
        assertEquals(new UpdateAsset("wynnlabs-fabric-0.2.4.jar", 4320605, FABRIC_SHA,
                "https://github.com/anilken/Wynnlabs/releases/download/v0.2.4/wynnlabs-fabric-0.2.4.jar"), a.get(0));
        assertEquals(NEO_SHA, a.get(1).sha256());
    }

    @Test
    void untrustedAssetsAreLeftOut() {
        String one = V024.substring(0, V024.indexOf("},") + 1) + "]";
        assertEquals(1, parse(one).size());
        assertTrue(parse(one.replace("\"digest\": \"sha256:" + FABRIC_SHA + "\",", "")).isEmpty(), "no digest");
        assertTrue(parse(one.replace("sha256:" + FABRIC_SHA, "md5:0123456789abcdef0123456789abcdef")).isEmpty(), "md5");
        assertTrue(parse(one.replace("sha256:" + FABRIC_SHA, "sha256:abc")).isEmpty(), "short hash");
        assertTrue(parse(one.replace("https://github.com/anilken/Wynnlabs/", "https://evil.example/anilken/Wynnlabs/")).isEmpty(), "other host");
        assertTrue(parse(one.replace("https://github.com/anilken/Wynnlabs/", "https://github.com/someone/Wynnlabs/")).isEmpty(), "other repo");
        assertTrue(parse(one.replace("\"uploaded\"", "\"starter\"")).isEmpty(), "not uploaded");
        assertTrue(parse(one.replace("4320605", "0")).isEmpty(), "empty");
        assertTrue(parse(one.replace("4320605", "999999999")).isEmpty(), "too big");
        assertTrue(UpdateAsset.parse(new JsonArray()).isEmpty());
        assertTrue(UpdateAsset.parse(null).isEmpty());
    }

    @Test
    void picksTheJarForThisLoader() {
        List<UpdateAsset> a = parse(V024);
        assertEquals("wynnlabs-fabric-0.2.4.jar", UpdateAsset.choose(a, "v0.2.4", "fabric", "1.21.11").name());
        assertEquals("wynnlabs-neoforge-0.2.4.jar", UpdateAsset.choose(a, "v0.2.4", "neoforge", "1.21.11").name());
        assertNull(UpdateAsset.choose(List.of(a.get(0)), "v0.2.4", "neoforge", "1.21.11"), "only a Fabric jar");
        assertEquals("wynnlabs-fabric-0.2.4.jar", UpdateAsset.choose(a, "v0.2.4", "fabric", null).name(), "Minecraft unknown");
    }

    @Test
    void theMinecraftVersionAndTheReleaseNumberMustFit() {
        assertEquals("wynnlabs-fabric-0.2.4+mc1.21.11.jar",
                UpdateAsset.choose(List.of(asset("wynnlabs-fabric-0.2.4+mc1.21.11.jar")), "v0.2.4", "fabric", "1.21.11").name());
        assertEquals("wynnlabs-fabric-0.2.4-mc1.21.11.jar",
                UpdateAsset.choose(List.of(asset("wynnlabs-fabric-0.2.4-mc1.21.11.jar")), "v0.2.4", "fabric", "1.21.11").name());
        assertNull(UpdateAsset.choose(List.of(asset("wynnlabs-fabric-0.2.4+mc1.21.12.jar")), "v0.2.4", "fabric", "1.21.11"));
        assertNull(UpdateAsset.choose(List.of(asset("wynnlabs-fabric-0.2.4.jar")), "v0.2.4", "fabric", "1.21.12"),
                "a name without mc is for the version releases are built for");
        assertNull(UpdateAsset.choose(List.of(asset("wynnlabs-fabric-0.2.3.jar")), "v0.2.4", "fabric", "1.21.11"), "an old jar");
        assertNull(UpdateAsset.choose(List.of(asset("wynnlabs-fabric-0.2.4-sources.jar")), "v0.2.4", "fabric", "1.21.11"));
        assertNull(UpdateAsset.choose(List.of(asset("wynnlabs-fabric-0.2.4.jar"), asset("wynnlabs-fabric-0.2.4+mc1.21.11.jar")),
                "v0.2.4", "fabric", "1.21.11"), "two could be meant");
    }

    @Test
    void releaseNotesBecomePlainLines() {
        String body = "## [v0.2.4](https://github.com/anilken/Wynnlabs/compare/v0.2.3...v0.2.4) (2026-10-23)\n\n"
                + "### New Features\n\n"
                + "* **roadmap:** more options per slot, with a Trade Market filter ([#66](https://github.com/anilken/Wynnlabs/pull/66))"
                + " ([8ee7330](https://github.com/anilken/Wynnlabs/commit/8ee7330e48d7a5c7de5825a32ccda278920a3a10))\n"
                + "* **home:** \"now\" weighs whole sets and what you take off ([#42](https://github.com/anilken/Wynnlabs/pull/42))"
                + " ([5714dfb](https://github.com/anilken/Wynnlabs/commit/5714dfbd6b9a31c730626c3a2123ca676859ff27))\n\n"
                + "### Code Refactoring\n\n"
                + "* move the classes into feature packages ([#59](https://github.com/anilken/Wynnlabs/pull/59))"
                + " ([0a58f13](https://github.com/anilken/Wynnlabs/commit/0a58f130fdc38c458250fd02e179c67b8f3663e6))\n";
        List<ReleaseNotes.Line> l = ReleaseNotes.lines(body);
        assertEquals(List.of(
                new ReleaseNotes.Line("v0.2.4 (2026-10-23)", true, false),
                new ReleaseNotes.Line("New Features", true, false),
                new ReleaseNotes.Line("roadmap: more options per slot, with a Trade Market filter", false, true),
                new ReleaseNotes.Line("home: \"now\" weighs whole sets and what you take off", false, true),
                new ReleaseNotes.Line("Code Refactoring", true, false),
                new ReleaseNotes.Line("move the classes into feature packages", false, true)), l);
        assertTrue(ReleaseNotes.lines(null).isEmpty());
    }
}
