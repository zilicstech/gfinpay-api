package com.fintech.verification;

import com.fintech.platform.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Validates 12-digit Indian Aadhaar numbers (format, Verhoeff checksum, and optional DigiLocker last-4 match).
 */
public final class AadhaarValidator {

    private static final int[][] D = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
            {1, 2, 3, 4, 0, 6, 7, 8, 9, 5},
            {2, 3, 4, 0, 1, 7, 8, 9, 5, 6},
            {3, 4, 0, 1, 2, 8, 9, 5, 6, 7},
            {4, 0, 1, 2, 3, 9, 5, 6, 7, 8},
            {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
            {6, 5, 9, 8, 7, 1, 0, 4, 3, 2},
            {7, 6, 5, 9, 8, 2, 1, 0, 4, 3},
            {8, 7, 6, 5, 9, 3, 2, 1, 0, 4},
            {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}
    };

    private static final int[][] P = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
            {1, 5, 7, 6, 2, 8, 3, 0, 9, 4},
            {5, 8, 0, 3, 7, 9, 6, 1, 4, 2},
            {8, 9, 1, 6, 0, 4, 3, 5, 2, 7},
            {9, 4, 5, 3, 1, 2, 6, 8, 7, 0},
            {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
            {2, 7, 9, 3, 8, 0, 6, 4, 1, 5},
            {7, 0, 4, 6, 9, 1, 3, 2, 5, 8}
    };

    private AadhaarValidator() {}

    public static String validateAndNormalize(String rawAadhaar, String expectedLast4) {
        return normalizeAndValidate(rawAadhaar, expectedLast4);
    }

    public static String validateFormat(String rawAadhaar) {
        return normalizeAndValidate(rawAadhaar, null);
    }

    public static String normalizeAndValidate(String rawAadhaar, String expectedLast4) {
        String digits = rawAadhaar == null ? "" : rawAadhaar.replaceAll("\\D", "");
        if (!digits.matches("^[2-9]\\d{11}$")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AADHAAR",
                    "Enter a valid 12-digit Aadhaar number");
        }
        if (!verhoeffValid(digits)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AADHAAR_CHECKSUM",
                    "Entered Aadhaar number failed checksum validation. Please check all 12 digits.");
        }
        String cleanLast4 = expectedLast4 == null ? "" : expectedLast4.replaceAll("\\D", "");
        if (cleanLast4.length() == 4 && !digits.endsWith(cleanLast4)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "AADHAAR_LAST4_MISMATCH",
                    "Entered Aadhaar does not match your DigiLocker verified Aadhaar ending in " + cleanLast4);
        }
        return digits;
    }

    public static boolean verhoeffValid(String digits) {
        if (digits == null || !digits.matches("^\\d{12}$")) {
            return false;
        }
        int c = 0;
        for (int i = 0; i < digits.length(); i++) {
            int digit = digits.charAt(digits.length() - 1 - i) - '0';
            c = D[c][P[i % 8][digit]];
        }
        return c == 0;
    }
}
