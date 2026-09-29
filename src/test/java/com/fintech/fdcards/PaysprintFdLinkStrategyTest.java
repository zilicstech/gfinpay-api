package com.fintech.fdcards;

import com.fintech.platform.web.ApiException;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaysprintFdLinkStrategyTest {

    @Test
    void prefersRetailerGfinCode() {
        assertEquals("GFIN123", PaysprintFdLinkStrategy.merchantCode(Map.of(
                "retailer_code", "GFIN123",
                "distributor_code", "GFIN999")));
    }

    @Test
    void fallsBackToDistributorCode() {
        assertEquals("GFIN999", PaysprintFdLinkStrategy.merchantCode(Map.of(
                "distributor_code", "GFIN999")));
    }

    @Test
    void failsWithoutCode() {
        assertThrows(ApiException.class, () -> PaysprintFdLinkStrategy.merchantCode(Map.of()));
    }
}
