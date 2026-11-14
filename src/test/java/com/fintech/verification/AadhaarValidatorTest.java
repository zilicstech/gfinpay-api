package com.fintech.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fintech.platform.web.ApiException;
import org.junit.jupiter.api.Test;

class AadhaarValidatorTest {

    private static String withValidCheckDigit(String elevenDigits) {
        for (int d = 0; d <= 9; d++) {
            String candidate = elevenDigits + d;
            if (AadhaarValidator.verhoeffValid(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Verhoeff check digit must exist for " + elevenDigits);
    }

    @Test
    void normalizesSpacesAndHyphensAndPassesVerhoeffAndLast4Match() {
        String valid = withValidCheckDigit("49911866524");
        String formatted = valid.substring(0, 4) + " " + valid.substring(4, 8) + "-" + valid.substring(8);
        String last4 = valid.substring(8);

        assertEquals(valid, AadhaarValidator.validateAndNormalize(formatted, last4));
        assertEquals(valid, AadhaarValidator.validateFormat(formatted));
    }

    @Test
    void rejectsLeadingZeroOrOneAndWrongLength() {
        String leadingOne = withValidCheckDigit("12345678901");
        ApiException ex1 = assertThrows(ApiException.class, () -> AadhaarValidator.validateFormat(leadingOne));
        assertEquals("INVALID_AADHAAR", ex1.getCode());

        ApiException ex2 = assertThrows(ApiException.class, () -> AadhaarValidator.validateFormat("2345678901"));
        assertEquals("INVALID_AADHAAR", ex2.getCode());
    }

    @Test
    void rejectsCorruptedVerhoeffChecksum() {
        String valid = withValidCheckDigit("65432198765");
        int wrongDigit = ((valid.charAt(11) - '0') + 1) % 10;
        String corrupted = valid.substring(0, 11) + wrongDigit;

        assertFalse(AadhaarValidator.verhoeffValid(corrupted));
        ApiException ex = assertThrows(ApiException.class, () -> AadhaarValidator.validateFormat(corrupted));
        assertEquals("INVALID_AADHAAR_CHECKSUM", ex.getCode());
    }

    @Test
    void rejectsMismatchedLast4AgainstVerifiedKyc() {
        String valid = withValidCheckDigit("78901234567");
        String wrongLast4 = valid.endsWith("9999") ? "8888" : "9999";

        assertTrue(AadhaarValidator.verhoeffValid(valid));
        ApiException ex = assertThrows(
                ApiException.class,
                () -> AadhaarValidator.validateAndNormalize(valid, wrongLast4));
        assertEquals("AADHAAR_LAST4_MISMATCH", ex.getCode());
    }
}
