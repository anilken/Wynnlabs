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
