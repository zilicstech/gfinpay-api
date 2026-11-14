package com.fintech.platform.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM field-level encryption for sensitive PII (such as PAN).
 */
@Component
public class FieldCipher {

    private static final String PREFIX = "v1:";
    private static final byte[] BIN_MAGIC = {'F', 'C', 'B', '1'};
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64_ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DEC = Base64.getUrlDecoder();

    private final SecretKey key;

    public FieldCipher(@Value("${app.pii.key}") String rawKey) {
        this.key = deriveKey(rawKey);
    }

    public byte[] encryptBytes(byte[] plain) {
        if (plain == null || plain.length == 0) {
            return new byte[0];
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain);
            ByteBuffer buf = ByteBuffer.allocate(BIN_MAGIC.length + IV_LENGTH + ct.length);
            buf.put(BIN_MAGIC);
            buf.put(iv);
            buf.put(ct);
            return buf.array();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt binary payload", e);
        }
    }

    public byte[] decryptBytes(byte[] blob) {
        if (blob == null || blob.length == 0) {
            return new byte[0];
        }
        int offset = 0;
        if (blob.length > BIN_MAGIC.length + IV_LENGTH
                && blob[0] == BIN_MAGIC[0]
                && blob[1] == BIN_MAGIC[1]
                && blob[2] == BIN_MAGIC[2]
                && blob[3] == BIN_MAGIC[3]) {
            offset = BIN_MAGIC.length;
        }
        if (blob.length <= offset + IV_LENGTH) {
            throw new IllegalArgumentException("Encrypted binary payload too short");
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            byte[] ct = new byte[blob.length - offset - IV_LENGTH];
            System.arraycopy(blob, offset, iv, 0, IV_LENGTH);
            System.arraycopy(blob, offset + IV_LENGTH, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(ct);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt binary payload", e);
        }
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(IV_LENGTH + ct.length);
            buf.put(iv);
            buf.put(ct);
            return PREFIX + B64_ENC.encodeToString(buf.array());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt PII field", e);
        }
    }

    public String decrypt(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        String payload = encoded.startsWith(PREFIX) ? encoded.substring(PREFIX.length()) : encoded;
        try {
            byte[] raw = B64_DEC.decode(payload);
            if (raw.length <= IV_LENGTH) {
                throw new IllegalArgumentException("Ciphertext too short");
            }
            byte[] iv = new byte[IV_LENGTH];
            byte[] ct = new byte[raw.length - IV_LENGTH];
            System.arraycopy(raw, 0, iv, 0, IV_LENGTH);
            System.arraycopy(raw, IV_LENGTH, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt PII field", e);
        }
    }

    private static SecretKey deriveKey(String rawKey) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(hash, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

