package com.fintech.platform.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class FieldCipherTest {

    @Test
    void encryptsAndDecryptsRoundTripWithUniqueIv() {
        FieldCipher cipher = new FieldCipher("test-key-material-for-aes-256-gcm");
        String pan = "ABCDE1234F";
        String c1 = cipher.encrypt(pan);
        String c2 = cipher.encrypt(pan);

        assertTrue(c1.startsWith("v1:"));
        assertNotEquals(c1, c2);
        assertEquals(pan, cipher.decrypt(c1));
        assertEquals(pan, cipher.decrypt(c2));
    }

    @Test
    void handlesNullAndEmptyInput() {
        FieldCipher cipher = new FieldCipher("test-key-material-for-aes-256-gcm");
        assertNull(cipher.encrypt(null));
        assertNull(cipher.encrypt(""));
        assertNull(cipher.decrypt(null));
        assertNull(cipher.decrypt(""));
    }

    @Test
    void encryptsAndDecryptsBinaryPayloadAndDetectsTampering() {
        FieldCipher cipher = new FieldCipher("test-key-material-for-aes-256-gcm");
        byte[] pdf = "%PDF-1.4 sample digilocker document bytes".getBytes(StandardCharsets.UTF_8);

        byte[] enc1 = cipher.encryptBytes(pdf);
        byte[] enc2 = cipher.encryptBytes(pdf);

        assertFalse(Arrays.equals(pdf, enc1));
        assertFalse(Arrays.equals(enc1, enc2));
        assertArrayEquals(pdf, cipher.decryptBytes(enc1));
        assertArrayEquals(pdf, cipher.decryptBytes(enc2));

        // Tamper with ciphertext
        byte[] tampered = Arrays.copyOf(enc1, enc1.length);
        tampered[tampered.length - 1] ^= 0x01;
        assertThrows(IllegalStateException.class, () -> cipher.decryptBytes(tampered));

        // Wrong key fails
        FieldCipher wrongKey = new FieldCipher("another-key-material-for-aes-256");
        assertThrows(IllegalStateException.class, () -> wrongKey.decryptBytes(enc1));
    }
}

