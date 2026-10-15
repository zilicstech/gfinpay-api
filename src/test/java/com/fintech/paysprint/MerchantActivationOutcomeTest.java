package com.fintech.paysprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MerchantActivationOutcomeTest {

    @Test
    void codeOneIsActivated() {
        MerchantActivationOutcome outcome = MerchantActivationOutcome.from("1");
        assertEquals("SUCCESS", outcome.status());
        assertTrue(outcome.activated());
    }

    @Test
    void codeThreeStaysInProgress() {
        MerchantActivationOutcome outcome = MerchantActivationOutcome.from("3");
        assertEquals("IN PROGRESS", outcome.status());
        assertFalse(outcome.activated());
    }

    @Test
    void codeTwoAsksForAnotherScan() {
        MerchantActivationOutcome outcome = MerchantActivationOutcome.from("2");
        assertEquals("FAILED", outcome.status());
        assertTrue(outcome.message().contains("scan again"));
    }

    @Test
    void unknownCodeFailsInPlainWords() {
        assertEquals("FAILED", MerchantActivationOutcome.from("99").status());
    }
}
