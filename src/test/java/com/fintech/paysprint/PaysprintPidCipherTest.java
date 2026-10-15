package com.fintech.paysprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class PaysprintPidCipherTest {

    @Test
    void encryptsWithAes128CbcAndBase64() throws Exception {
        PaysprintPidCipher cipher = new PaysprintPidCipher(properties("1234567890123456", "abcdef9876543210"));
        String token = cipher.encrypt("<PidData>scan</PidData>");
        assertEquals("<PidData>scan</PidData>", decrypt(token, "1234567890123456", "abcdef9876543210"));
    }

    @Test
    void rejectsAKeyThatIsNot16Bytes() {
        assertThrows(IllegalStateException.class,
                () -> new PaysprintPidCipher(properties("short", "1234567890123456")));
    }

    private static String decrypt(String token, String key, String iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8)));
        return new String(cipher.doFinal(Base64.getDecoder().decode(token)), StandardCharsets.UTF_8);
    }

    private static PaysprintProperties properties(String key, String iv) {
        return new PaysprintProperties(
                "http://localhost", "partner", "merchant", "jwt-key-at-least-32-characters",
                "authorised", key, iv, "FIXED_DEPOSIT", "1", true, "secret",
                "wadh2", "wadh6", "PP", "0", true);
    }
}
