package io.github.anilken.wynnlabs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The update check's version order and its reading of GitHub's answer, with no network. */
class UpdateCheckTest {

    @Test
    void newerComparesNumbersAsNumbers() {
        assertTrue(UpdateCheck.newer("v0.3.0", "0.2.0"));
        assertTrue(UpdateCheck.newer("v0.10.0", "0.9.9"));
        assertTrue(UpdateCheck.newer("1.0.0", "v0.99.99"));
        assertTrue(UpdateCheck.newer("v0.2.1", "0.2"));
        assertFalse(UpdateCheck.newer("v0.2.0", "0.2.0"));
        assertFalse(UpdateCheck.newer("v0.2.0", "0.3.0"));
        assertFalse(UpdateCheck.newer("V0.9.0", "0.10.0"));
    }

    @Test
    void preReleasesComeBeforeTheirReleaseAndBuildMetadataIsIgnored() {
        assertTrue(UpdateCheck.newer("v1.0.0", "1.0.0-beta.2"));
        assertTrue(UpdateCheck.newer("v1.0.0-beta.10", "1.0.0-beta.2"));
        assertTrue(UpdateCheck.newer("v1.0.0-rc.1", "1.0.0-beta"));
        assertFalse(UpdateCheck.newer("v1.0.0-beta", "1.0.0"));
        assertEquals(0, UpdateCheck.compare("v0.2.0+mc1.21.11", "0.2.0"));
    }

    @Test
    void notAVersionIsNeverNewer() {
        assertFalse(UpdateCheck.newer("latest", "0.2.0"));
        assertFalse(UpdateCheck.newer("v0.3.0", "${version}"));
        assertFalse(UpdateCheck.newer("", "0.2.0"));
        assertNull(UpdateCheck.compare("v1.2.3.4", "1.2.3"));
        assertNull(UpdateCheck.compare("v1..3", "1.2.3"));
    }

    @Test
    void devAndUnknownVersionsSkipTheCheck() {
        assertTrue(UpdateCheck.checkable("0.2.0"));
        assertTrue(UpdateCheck.checkable("v0.1.0"));
        assertFalse(UpdateCheck.checkable("0.2.0-dev"));
        assertTrue(UpdateCheck.checkable("0.2.1+dev"));
        assertFalse(UpdateCheck.newer("v0.2.1", "0.2.1+dev"));
        assertTrue(UpdateCheck.newer("v0.2.2", "0.2.1+dev"));
        assertFalse(UpdateCheck.checkable("0.3.0-SNAPSHOT"));
        assertFalse(UpdateCheck.checkable("0.0.0"));
        assertFalse(UpdateCheck.checkable("${version}"));
        assertFalse(UpdateCheck.checkable(""));
        assertFalse(UpdateCheck.checkable(null));
    }

    @Test
    void readsTagAndPageFromAReleaseAnswer() {
        String json = """
                {
                  "url": "https://api.github.com/repos/anilken/wynnlabs/releases/1",
                  "html_url": "https://github.com/anilken/wynnlabs/releases/tag/v0.3.0",
                  "id": 1,
                  "tag_name": "v0.3.0",
                  "name": "v0.3.0",
                  "draft": false,
                  "prerelease": false,
                  "published_at": "2026-10-01T12:00:00Z",
                  "assets": [{"name": "wynnlabs-fabric-0.3.0.jar"}],
                  "body": "## Changes"
                }
                """;
        UpdateCheck.Release r = UpdateCheck.parse(json);
        assertEquals("v0.3.0", r.tag());
        assertEquals("https://github.com/anilken/wynnlabs/releases/tag/v0.3.0", r.url());
        assertEquals(java.time.Instant.parse("2026-10-01T12:00:00Z").toEpochMilli(), r.published());
        assertEquals(0, UpdateCheck.parse(json.replace("2026-10-01T12:00:00Z", "yesterday")).published());
    }

    // ---- the About page's state and "Check again" ----

    private static final String NEWER = "{\"tag_name\": \"v0.3.0\", \"html_url\": \"https://github.com/anilken/wynnlabs/releases/tag/v0.3.0\","
            + " \"published_at\": \"2026-10-01T12:00:00Z\"}";

    /** A fresh run of jar {@code version} with the setting {@code on}; returns how many requests started. */
    private static int[] fresh(String version, boolean on) {
        com.google.gson.JsonObject s = new com.google.gson.JsonObject();
        s.addProperty("updateCheck", on);
        Settings.use(s);
        UpdateCheck.reset();
        UpdateCheck.version = () -> version;
        int[] started = {0};
        UpdateCheck.starter = job -> started[0]++;     // no network: the answer is given by hand
        return started;
    }

    @org.junit.jupiter.api.AfterEach
    void restore() {
        UpdateCheck.reset();
        Settings.use(new com.google.gson.JsonObject());
    }

    @Test
    void checkAgainAsksOnceAndWaitsTenSeconds() {
        int[] started = fresh("0.2.0", true);
        assertEquals(UpdateCheck.Status.IDLE, UpdateCheck.state().status());
        assertTrue(UpdateCheck.checkNow(100_000));
        assertEquals(UpdateCheck.Status.CHECKING, UpdateCheck.state().status());
        assertFalse(UpdateCheck.checkNow(101_000), "one request at a time");
        UpdateCheck.finish("0.2.0", 200, NEWER, false, 102_000);
        UpdateCheck.State s = UpdateCheck.state();
        assertEquals(UpdateCheck.Status.NEWER, s.status());
        assertEquals("v0.3.0", s.latest());
        assertEquals("https://github.com/anilken/wynnlabs/releases/tag/v0.3.0", s.url());
        assertEquals(java.time.Instant.parse("2026-10-01T12:00:00Z").toEpochMilli(), s.published());
        assertEquals(102_000, s.checkedAt());
        assertFalse(UpdateCheck.checkNow(109_999), "under ten seconds after the last press");
        assertTrue(UpdateCheck.checkNow(110_000));
        assertEquals(2, started[0]);
        UpdateCheck.finish("0.2.0", 200, NEWER.replace("v0.3.0", "v0.2.0"), false, 111_000);
        assertEquals(UpdateCheck.Status.UP_TO_DATE, UpdateCheck.state().status());
    }

    @Test
    void failuresSayWhy() {
        fresh("0.2.0", true);
        UpdateCheck.finish("0.2.0", 503, "", false, 1);
        assertEquals(UpdateCheck.Reason.NO_ANSWER, UpdateCheck.state().reason());
        UpdateCheck.finish("0.2.0", 200, "{\"message\": \"rate limited\"}", false, 2);
        assertEquals(UpdateCheck.Status.FAILED, UpdateCheck.state().status());
        assertEquals(UpdateCheck.Reason.NO_ANSWER, UpdateCheck.state().reason());
        assertEquals(UpdateCheck.Reason.OFFLINE, UpdateCheck.reasonOf(new java.net.ConnectException()));
        assertEquals(UpdateCheck.Reason.OFFLINE,
                UpdateCheck.reasonOf(new java.io.IOException("lookup", new java.net.UnknownHostException("api.github.com"))));
        assertEquals(UpdateCheck.Reason.NO_ANSWER, UpdateCheck.reasonOf(new java.net.http.HttpTimeoutException("slow")));
    }

    @Test
    void offOrADevBuildAsksNothing() {
        int[] started = fresh("0.2.0", false);
        assertEquals(UpdateCheck.Reason.OFF, UpdateCheck.state().reason());
        assertFalse(UpdateCheck.checkNow(100_000));
        assertEquals(UpdateCheck.Reason.OFF, UpdateCheck.state().reason());
        started = fresh("0.0.0-dev", true);
        assertEquals(UpdateCheck.Reason.DEV, UpdateCheck.state().reason());
        assertFalse(UpdateCheck.checkNow(100_000));
        assertEquals(0, started[0]);
    }

    @Test
    void badAnswersGiveNoRelease() {
        assertNull(UpdateCheck.parse("not json {"));
        assertNull(UpdateCheck.parse("[]"));
        assertNull(UpdateCheck.parse("{\"message\": \"API rate limit exceeded\"}"));
        assertNull(UpdateCheck.parse("{\"tag_name\": \"v0.3.0\"}"));
        assertNull(UpdateCheck.parse("{\"tag_name\": \"v0.3.0\", \"html_url\": \"https://evil.example/x\"}"));
        assertNull(UpdateCheck.parse("{\"tag_name\": 3, \"html_url\": \"https://github.com/anilken/wynnlabs\"}"));
        assertNull(UpdateCheck.parse("{\"tag_name\": \"v0.3.0\", \"html_url\": \"https://github.com/a/b\", \"draft\": true}"));
    }
}
