package com.fintech.instantpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class InstantPayCryptoTest {

    @Test
    void encryptAndDecryptMatchesPhpAes256CbcWith32ByteKey() {
        String key32 = "253ddc7991d45f00253ddc7991d45f00";
        byte[] iv = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        String aadhaar = "209313587183";

        String encrypted = InstantPayCrypto.encryptWithIv(aadhaar, key32, iv);
        byte[] raw = Base64.getDecoder().decode(encrypted);

        // 16-byte IV + 16-byte PKCS7-padded ciphertext for 12-byte Aadhaar = 32 bytes
        assertThat(raw).hasSize(32);
        assertThat(new String(raw, 0, 16, StandardCharsets.UTF_8)).isEqualTo("0123456789abcdef");
        assertThat(InstantPayCrypto.decrypt(encrypted, key32)).isEqualTo(aadhaar);
    }

    @Test
    void encryptAndDecryptSupportsShortKeyZeroPaddedLikePhpOpenSsl() {
        String shortKey16 = "253ddc7991d45f00";
        String aadhaar = "999941057058";

        String encrypted = InstantPayCrypto.encryptAadhaar(aadhaar, shortKey16);
        assertThat(InstantPayCrypto.decrypt(encrypted, shortKey16)).isEqualTo(aadhaar);
    }

    @Test
    void payoutRemarksAndCoordinateFormattingFollowInstantPaySpec() {
        assertThat(InstantPayClient.sanitizePayoutRemarks("Merchant bank settlement #123!"))
                .isEqualTo("Merchant bank settle")
                .hasSizeLessThanOrEqualTo(20)
                .matches("[A-Za-z ]+");
        assertThat(InstantPayClient.sanitizePayoutRemarks(null)).isEqualTo("Fund Transfer");
        assertThat(InstantPayClient.scale4("21.251973", "19.0760")).isEqualTo("21.2520");
        assertThat(InstantPayClient.scale4("81.673191", "72.8777")).isEqualTo("81.6732");
    }

    @Test
    void rejectsMissingEncryptionKey() {
        assertThatThrownBy(() -> InstantPayCrypto.encryptAadhaar("209313587183", " "))
                .isInstanceOf(IllegalStateException.class);
    }
}
