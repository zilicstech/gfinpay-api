package com.fintech.iserveu;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-256-CBC with a random IV prepended, matching the iServeU sample. */
public final class IserveuCrypto {

    private static final SecureRandom RANDOM = new SecureRandom();

    private IserveuCrypto() {}

    public static byte[] key(String base64Key) {
        byte[] decoded = Base64.getDecoder().decode(base64Key);
        if (decoded.length != 32) {
            throw new IllegalArgumentException("iServeU encryption key must decode to 32 bytes");
        }
        return decoded;
    }

    public static String encrypt(String plain, byte[] key) {
        try {
            byte[] iv = new byte[16];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("iServeU encryption failed", e);
        }
    }

    public static String decrypt(String token, byte[] key) {
        try {
            byte[] combined = Base64.getDecoder().decode(token);
            byte[] iv = Arrays.copyOfRange(combined, 0, 16);
            byte[] cipherText = Arrays.copyOfRange(combined, 16, combined.length);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            String plain = new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8).trim();
            int last = Math.max(plain.lastIndexOf('}'), plain.lastIndexOf(']'));
            return last >= 0 ? plain.substring(0, last + 1) : plain;
        } catch (Exception e) {
            throw new IllegalStateException("iServeU decryption failed", e);
        }
    }
}
