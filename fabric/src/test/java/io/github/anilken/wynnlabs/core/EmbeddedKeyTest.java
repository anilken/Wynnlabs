package io.github.anilken.wynnlabs.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The key built into the jar (task 7.3.6): AES-256-GCM, the AES key split in two shares, round trip on the fake key. */
class EmbeddedKeyTest {

    private static final String KEY = "test-key";
    private static final SecureRandom RANDOM = new SecureRandom();

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    private static EmbeddedKey.Sealed seal() throws Exception {
        return EmbeddedKey.seal(KEY, random(32), random(12), random(32));
    }

    @Test
    void theFakeKeyRoundTrips() throws Exception {
        EmbeddedKey.Sealed s = seal();
        assertEquals(KEY, EmbeddedKey.open(s.blob(), s.share()));
        assertEquals(4 + 12 + 32 + KEY.length() + 16, s.blob().length, "magic, IV, share A, ciphertext, GCM tag");
        assertEquals(32, s.share().length);
    }

    @Test
    void neitherResourceHoldsThePlainKey() throws Exception {
        EmbeddedKey.Sealed s = seal();
        assertFalse(new String(s.blob(), StandardCharsets.ISO_8859_1).contains(KEY));
        assertFalse(new String(s.share(), StandardCharsets.ISO_8859_1).contains(KEY));
    }

    @Test
    void eachBuildSealsDifferently() throws Exception {
        assertFalse(java.util.Arrays.equals(seal().blob(), seal().blob()), "fresh AES key, IV and share per build");
    }

    @Test
    void oneShareAloneOrADamagedResourceGivesNothing() throws Exception {
        EmbeddedKey.Sealed s = seal();
        assertNull(EmbeddedKey.open(s.blob(), random(32)), "the wrong second share");
        assertNull(EmbeddedKey.open(s.blob(), new byte[32]), "share A alone");
        byte[] damaged = s.blob().clone();
        damaged[damaged.length - 1] ^= 1;
        assertNull(EmbeddedKey.open(damaged, s.share()), "the GCM tag catches a change");
        assertNull(EmbeddedKey.open(null, s.share()));
        assertNull(EmbeddedKey.open(new byte[10], s.share()));
        assertNull(EmbeddedKey.open(s.blob(), new byte[5]));
    }

    @Test
    void testsNeverSeeAnEmbeddedKey() {
        assertNull(EmbeddedKey.load(), "the key is added to the jar only, never to the resources tests run on");
    }
}
