package com.fintech.instantpay;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Exact Java equivalent of InstantPay's official PHP Aadhaar encryption snippet:
 * <pre>
 * $ivlen = openssl_cipher_iv_length('aes-256-cbc');
 * $iv = openssl_random_pseudo_bytes($ivlen);
 * $ciphertext = openssl_encrypt($aadhaarNumber, 'aes-256-cbc', $encryptionKey, OPENSSL_RAW_DATA, $iv);
 * $encryptedData = base64_encode($iv . $ciphertext);
 * </pre>
 */
public final class InstantPayCrypto {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int IV_LEN = 16;
    private static final int AES_256_KEY_LEN = 32;

    private InstantPayCrypto() {}

    public static String encrypt(String plainText, String encryptionKey) {
        return encryptAadhaar(plainText, encryptionKey);
    }

    public static String encryptAadhaar(String plainAadhaar, String encryptionKey) {
        byte[] iv = new byte[IV_LEN];
        RANDOM.nextBytes(iv);
        return encryptWithIv(plainAadhaar, encryptionKey, iv);
    }

    public static String encryptWithIv(String plainText, String encryptionKey, byte[] iv16) {
        if (plainText == null || plainText.isBlank()) {
            throw new IllegalArgumentException("Plaintext to encrypt must not be blank");
        }
        if (iv16 == null || iv16.length != IV_LEN) {
            throw new IllegalArgumentException("AES-256-CBC IV must be 16 bytes");
        }
        byte[] keyBytes = normalizeKeyBytes(encryptionKey);
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(iv16));
            byte[] ciphertext = cipher.doFinal(plainText.trim().getBytes(StandardCharsets.UTF_8));
            ByteBuffer combined = ByteBuffer.allocate(IV_LEN + ciphertext.length);
            combined.put(iv16);
            combined.put(ciphertext);
            return Base64.getEncoder().encodeToString(combined.array());
        } catch (Exception e) {
            throw new IllegalStateException("InstantPay AES-256-CBC encryption failed", e);
        }
    }

    public static String decrypt(String base64IvAndCiphertext, String encryptionKey) {
        if (base64IvAndCiphertext == null || base64IvAndCiphertext.isBlank()) {
            throw new IllegalArgumentException("Ciphertext must not be blank");
        }
        byte[] raw = Base64.getDecoder().decode(base64IvAndCiphertext.trim());
        if (raw.length <= IV_LEN) {
            throw new IllegalArgumentException("Ciphertext payload too short");
        }
        byte[] iv = Arrays.copyOfRange(raw, 0, IV_LEN);
        byte[] ct = Arrays.copyOfRange(raw, IV_LEN, raw.length);
        byte[] keyBytes = normalizeKeyBytes(encryptionKey);
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("InstantPay AES-256-CBC decryption failed", e);
        }
    }

    /**
     * Matches PHP OpenSSL {@code aes-256-cbc} key normalization:
     * keys shorter than 32 bytes are NUL-padded to 32 bytes; keys longer than 32 bytes are truncated to 32 bytes.
     */
    static byte[] normalizeKeyBytes(String encryptionKey) {
        if (encryptionKey == null || encryptionKey.isBlank()) {
            throw new IllegalStateException("InstantPay encryptionKey is not configured");
        }
        byte[] raw = encryptionKey.trim().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[AES_256_KEY_LEN];
        System.arraycopy(raw, 0, out, 0, Math.min(raw.length, AES_256_KEY_LEN));
        return out;
    }
}
