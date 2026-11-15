package com.fintech.platform.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fintech.emerald.EmeraldPayoutAdapter;
import com.fintech.emerald.EmeraldUpiCashoutAdapter;
import com.fintech.ledger.TransactionService;
import com.fintech.platform.web.ApiException;
import com.fintech.platform.web.TextValues;
import com.fintech.provider.canonical.ProviderModels;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MoneySafetyTest {

    @Test
    void externalRefIsStableAndShort() {
        UUID key = UUID.fromString("11111111-2222-3333-4444-555555555555");
        String first = ExternalRefs.fromKey("CW", key);
        assertEquals(first, ExternalRefs.fromKey("CW", key));
        assertTrue(first.length() <= 19);
        assertTrue(first.startsWith("CW"));
    }

    @Test
    void missingIdempotencyKeyIsRejected() {
        ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class, () -> ExternalRefs.requireKey(" "));
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", ex.getCode());
    }

    @Test
    void ledgerScaleIsFourPlaces() {
        assertEquals(new BigDecimal("10.5000"), TextValues.money(new BigDecimal("10.5")));
    }

    @Test
    void secondSuccessTransitionIsNotApplied() {
        assertTrue(TransactionService.allowed("PENDING", "SUCCESS"));
        org.junit.jupiter.api.Assertions.assertFalse(TransactionService.allowed("SUCCESS", "SUCCESS"));
        org.junit.jupiter.api.Assertions.assertFalse(TransactionService.allowed("SUCCESS", "PENDING"));
    }

    @Test
    void emeraldStatusesMapToCanonicalOutcomes() {
        assertEquals("SUCCESS", EmeraldUpiCashoutAdapter.normalize("PAID"));
        assertEquals("FAILED", EmeraldUpiCashoutAdapter.normalize("DECLINED"));
        assertEquals(ProviderModels.Outcome.SUCCESS, EmeraldPayoutAdapter.outcome("2000"));
        assertEquals(ProviderModels.Outcome.FAILED, EmeraldPayoutAdapter.outcome("REVERSED"));
        assertEquals(ProviderModels.Outcome.PENDING, EmeraldPayoutAdapter.outcome("2001"));
    }
}
