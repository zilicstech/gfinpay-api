package com.fintech.bills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KbillRailsTest {

    @Test
    void electricityNeedsStateAndUsesTypoPayPath() {
        assertTrue(KbillRails.locationRequired(8));
        assertEquals("/bill-pay-electricty", KbillRails.payPath(8));
        assertTrue(KbillRails.payEnabled(8));
    }

    @Test
    void waterHasNoPayRoute() {
        assertFalse(KbillRails.locationRequired(11));
        assertFalse(KbillRails.payEnabled(11));
    }
}
