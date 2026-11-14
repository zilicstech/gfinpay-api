package com.fintech.emerald;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class EmeraldMessageSanitizerTest {

    @Test
    void stripsAllVendorPipeOutletAndEntityIdentifiers() {
        String raw = "Pipe sync failed: Emerald InstantPay rejected outlet OUT_57_05 on https://myemerald.in/v1 (code EMD00099 ENT000057 UPITOCASH9001)";
        String sanitized = EmeraldMessageSanitizer.sanitize(raw, "Fallback error");

        String lower = sanitized.toLowerCase(Locale.ROOT);
        assertFalse(lower.contains("emerald"), "Must strip Emerald: " + sanitized);
        assertFalse(lower.contains("instantpay"), "Must strip InstantPay: " + sanitized);
        assertFalse(lower.contains("instant pay"), "Must strip Instant Pay: " + sanitized);
        assertFalse(lower.contains("myemerald"), "Must strip domain: " + sanitized);
        assertFalse(lower.contains("out_57_05"), "Must strip outlet code: " + sanitized);
        assertFalse(lower.contains("emd00099"), "Must strip EMD code: " + sanitized);
        assertFalse(lower.contains("ent000057"), "Must strip entity code: " + sanitized);
        assertFalse(lower.contains("upitocash"), "Must strip product code: " + sanitized);
    }

    @Test
    void returnsFallbackWhenBlankOrPureVendorName() {
        assertEquals("Service unavailable", EmeraldMessageSanitizer.sanitize("   ", "Service unavailable"));
        assertEquals("Service unavailable", EmeraldMessageSanitizer.sanitize("Emerald", "Service unavailable"));
        assertNull(EmeraldMessageSanitizer.sanitizeNullable(null));
    }
}

