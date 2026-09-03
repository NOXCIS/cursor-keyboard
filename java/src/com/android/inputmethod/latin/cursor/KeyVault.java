package com.android.inputmethod.latin.cursor;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts the Cursor API key at rest with a user-set master passphrase.
 *
 * <p>On a rooted device any process running as root can read the app's shared
 * preferences, so the key is never stored in the clear. The passphrase is used
 * to derive a 256-bit AES key (PBKDF2-HMAC-SHA256, random salt) and the key is
 * sealed with AES-256-GCM. The derived key is cached in-memory for the lifetime
 * of the process so repeated Cursor taps don't re-prompt, and cleared by
 * {@link #lock()}.
 */
public final class KeyVault {
    public static final int DEFAULT_ITERATIONS = 210_000;

    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int KEY_BITS = 256;
    private static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static SecretKey sSessionKey;

    private KeyVault() {
    }

    /**
     * Encrypt {@code plaintext} (the Cursor API key) with a key derived from
     * {@code passphrase}. Returns a single base64 blob of salt || iv || ciphertext,
     * suitable for storing in {@link AgentConfig}. The derived key is cached for
     * the session.
     */
    public static String encrypt(String plaintext, String passphrase, int iterations)
            throws Exception {
        byte[] salt = randomBytes(SALT_BYTES);
        byte[] iv = randomBytes(IV_BYTES);
        SecretKey key = deriveKey(passphrase, salt, iterations);
        sSessionKey = key;

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        byte[] blob = new byte[salt.length + iv.length + ct.length];
        System.arraycopy(salt, 0, blob, 0, salt.length);
        System.arraycopy(iv, 0, blob, salt.length, iv.length);
        System.arraycopy(ct, 0, blob, salt.length + iv.length, ct.length);
        return Base64.encodeToString(blob, Base64.NO_WRAP);
    }

    /**
     * Decrypt {@code blob} (created by {@link #encrypt}) with {@code passphrase}.
     * Throws {@link javax.crypto.AEADBadTagException} for a wrong passphrase.
     */
    public static String decrypt(String blob, String passphrase, int iterations)
            throws Exception {
        byte[] salt = Arrays.copyOfRange(Base64.decode(blob, Base64.NO_WRAP), 0, SALT_BYTES);
        SecretKey key = deriveKey(passphrase, salt, iterations);
        sSessionKey = key;
        return decryptWithBlob(blob, key);
    }

    /**
     * Decrypt {@code blob} using the session key cached by a previous
     * {@link #encrypt}/{@link #decrypt}. Requires a prior unlock.
     */
    public static String decryptWithCached(String blob) throws Exception {
        if (sSessionKey == null) {
            throw new IllegalStateException("KeyVault is locked");
        }
        return decryptWithBlob(blob, sSessionKey);
    }

    private static String decryptWithBlob(String blob, SecretKey key) throws Exception {
        byte[] data = Base64.decode(blob, Base64.NO_WRAP);
        if (data.length < SALT_BYTES + IV_BYTES + TAG_BITS / 8) {
            throw new IllegalArgumentException("Invalid encrypted key blob");
        }
        byte[] iv = Arrays.copyOfRange(data, SALT_BYTES, SALT_BYTES + IV_BYTES);
        byte[] ct = Arrays.copyOfRange(data, SALT_BYTES + IV_BYTES, data.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
    }

    public static boolean isUnlocked() {
        return sSessionKey != null;
    }

    /** Clear the in-memory derived key so the API key needs the passphrase again. */
    public static void lock() {
        sSessionKey = null;
    }

    private static SecretKey deriveKey(String passphrase, byte[] salt, int iterations)
            throws Exception {
        PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] randomBytes(int length) {
        byte[] out = new byte[length];
        RANDOM.nextBytes(out);
        return out;
    }
}
