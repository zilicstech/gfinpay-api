package com.fintech.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PublicAppUrlTest {

    @Test
    void stripsWwwFromGeneratedLinks() {
        assertEquals("https://gfinpay.com", PublicAppUrl.canonicalOrigin("https://www.gfinpay.com/"));
        assertEquals("https://gfinpay.com", PublicAppUrl.canonicalOrigin("https://gfinpay.com"));
    }

    @Test
    void corsAllowsApexAndWww() {
        List<String> origins = PublicAppUrl.corsOrigins("https://www.gfinpay.com");
        assertTrue(origins.contains("https://www.gfinpay.com"));
        assertTrue(origins.contains("https://gfinpay.com"));
        List<String> fromApex = PublicAppUrl.corsOrigins("https://gfinpay.com");
        assertTrue(fromApex.contains("https://gfinpay.com"));
        assertTrue(fromApex.contains("https://www.gfinpay.com"));
    }

    @Test
    void corsDoesNotInventWwwForLocalhost() {
        assertEquals(List.of("http://localhost:3001"), PublicAppUrl.corsOrigins("http://localhost:3001"));
    }
}
