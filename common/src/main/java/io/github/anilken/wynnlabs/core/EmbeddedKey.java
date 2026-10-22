package io.github.anilken.wynnlabs.core;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The Wynnventory key the build embedded in the jar (task 7.3.6), the fallback when the player
 * saved none in {@code config/wynnlabs/wynnventory-key.txt}.
 *
 * <p>At build time the {@code embedKey} task in build.gradle reads the key from a git-ignored
 * file outside the sources, encrypts it with AES-256-GCM (javax.crypto) under a fresh random
 * 32-byte AES key and a fresh random 12-byte IV made for each build, and writes two resources
 * into the jar only:
 * <ul>
 *   <li>{@link #RESOURCE}: {@code "WLK2"}, the IV, share A of the AES key, then the ciphertext
 *       with its 16-byte GCM tag;</li>
 *   <li>{@link #SHARE}: share B of the AES key, under an unremarkable name.</li>
 * </ul>
 * The AES key is split by XOR (A is random, B = key XOR A), so neither file alone holds a usable
 * key or tells anything about it. At runtime both shares are read, the AES key is rebuilt and
 * the Wynnventory key is decrypted in memory only. The key never goes into src/, git or a log,
 * and the jar never holds it in plain text.
 *
 * <p>Be honest about what this is: obfuscation-grade protection, not secrecy. Everything needed
 * to decrypt (both shares, the IV and this class) ships in the same jar, so anyone with the jar
 * can recover the key. That is fine for a personal mod that is never published; a key that must
 * stay secret cannot live in a jar at all. build.gradle writes the same layout; the two must
 * stay the same.
 */
public final class EmbeddedKey {

    /** The encrypted key inside the jar. */
    static final String RESOURCE = "/assets/wynnlabs/embedded-key.bin";
    /** The second share of the AES key, under a name that says nothing about it. */
    static final String SHARE = "/assets/wynnlabs/ui/grain.bin";
    static final int AES_KEY_BYTES = 32;
    static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] MAGIC = {'W', 'L', 'K', '2'};
    /** Bound to the ciphertext as GCM's associated data. */
    private static final byte[] AAD = "wynnlabs-embedded-key-v2".getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER = MAGIC.length + IV_BYTES + AES_KEY_BYTES;

    private EmbeddedKey() {}

    /** The encrypted resource and the second share, as the build writes them. */
    record Sealed(byte[] blob, byte[] share) {}

    /** The embedded key, or null when this jar has none (or it cannot be read). Never logged. */
    public static String load() {
        try (InputStream a = EmbeddedKey.class.getResourceAsStream(RESOURCE);
                InputStream b = EmbeddedKey.class.getResourceAsStream(SHARE)) {
            return a == null || b == null ? null : open(a.readAllBytes(), b.readAllBytes());
        } catch (Exception e) {
            return null;
        }
    }

    /** The key sealed in these two resources; null when they are not a matching pair. */
    static String open(byte[] blob, byte[] share) {
        if (blob == null || share == null || share.length != AES_KEY_BYTES || blob.length <= HEADER
                || !Arrays.equals(Arrays.copyOf(blob, MAGIC.length), MAGIC)) {
            return null;
        }
        byte[] iv = Arrays.copyOfRange(blob, MAGIC.length, MAGIC.length + IV_BYTES);
        byte[] aes = Arrays.copyOfRange(blob, MAGIC.length + IV_BYTES, HEADER);
        for (int i = 0; i < AES_KEY_BYTES; i++) {
            aes[i] ^= share[i];
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aes, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(AAD);
            String key = new String(c.doFinal(blob, HEADER, blob.length - HEADER), StandardCharsets.UTF_8).strip();
            return key.isEmpty() ? null : key;
        } catch (Exception e) {
            return null;                        // wrong share, damaged resource
        } finally {
            Arrays.fill(aes, (byte) 0);
        }
    }

    /**
     * Seals a key as the build does: AES-256-GCM under {@code aesKey} (32 bytes) and {@code iv}
     * (12 bytes), the AES key split into the random {@code shareA} (32 bytes, kept in the blob)
     * and {@code aesKey XOR shareA} (the second resource). For tests; build.gradle does the same.
     */
    static Sealed seal(String key, byte[] aesKey, byte[] iv, byte[] shareA) throws Exception {
        if (aesKey.length != AES_KEY_BYTES || iv.length != IV_BYTES || shareA.length != AES_KEY_BYTES) {
            throw new IllegalArgumentException("aes key and share must be 32 bytes, iv 12");
        }
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(TAG_BITS, iv));
        c.updateAAD(AAD);
        byte[] sealed = c.doFinal(key.getBytes(StandardCharsets.UTF_8));
        byte[] blob = new byte[HEADER + sealed.length];
        System.arraycopy(MAGIC, 0, blob, 0, MAGIC.length);
        System.arraycopy(iv, 0, blob, MAGIC.length, IV_BYTES);
        System.arraycopy(shareA, 0, blob, MAGIC.length + IV_BYTES, AES_KEY_BYTES);
        System.arraycopy(sealed, 0, blob, HEADER, sealed.length);
        byte[] shareB = new byte[AES_KEY_BYTES];
        for (int i = 0; i < AES_KEY_BYTES; i++) {
            shareB[i] = (byte) (aesKey[i] ^ shareA[i]);
        }
        return new Sealed(blob, shareB);
    }
}
