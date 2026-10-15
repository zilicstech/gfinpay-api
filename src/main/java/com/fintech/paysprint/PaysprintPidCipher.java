package com.fintech.paysprint;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** AES-128-CBC then base64, matching PaySprint's PHP PID encryption. */
@Component
public class PaysprintPidCipher {

    private final byte[] key;
    private final byte[] iv;

    public PaysprintPidCipher(PaysprintProperties props) {
        this.key = require16(props.aesKey(), "PAYSPRINT_AES_KEY");
        this.iv = require16(props.aesIv(), "PAYSPRINT_AES_IV");
    }

    public String encrypt(String pidXml) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] raw = cipher.doFinal(pidXml.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt the fingerprint data");
        }
    }

    private static byte[] require16(String value, String name) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length != 16) {
            throw new IllegalStateException(name + " must be 16 bytes");
        }
        return bytes;
    }
}
