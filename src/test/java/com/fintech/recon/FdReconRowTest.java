package com.fintech.recon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FdReconRowTest {

    @Test
    void unidentifiedKeepsPhoneAndUnidentifiedNetwork() {
        FdMisRow mis = new FdMisRow("ZET", "SBM", "SBM", "9999999001", "Recon Demo User",
                "u1", null, "VKYC_STARTED", "vkyc_started_time", Instant.parse("2026-09-24T16:00:00Z"),
                FdPartnerLifecycle.IN_PROGRESS, Map.of("phone_number", "9999999001", "full_name", "Recon Demo User"));
        FdReconRow row = FdReconRow.unidentified(mis);
        assertFalse(row.identified());
        assertEquals("9999999001", row.customerMobile());
        assertEquals(FdReconRow.UNIDENTIFIED, row.retailerLabel());
        assertEquals(FdReconRow.UNIDENTIFIED, row.distributorLabel());
        assertEquals("VKYC_STARTED", row.currentStatus());
        assertEquals("9999999001", ((Map<?, ?>) row.payload().get("customer")).get("mobile"));
        assertTrue(row.payload().containsKey("mis"));
    }

    @Test
    void fromMatchUsesRetailerAndDistributorLabels() {
        java.util.UUID leadId = java.util.UUID.randomUUID();
        java.util.UUID retailerId = java.util.UUID.randomUUID();
        FdMisRow mis = new FdMisRow("ZET", "SBM", "SBM", "9999999001", "Recon Demo User",
                "u1", null, "FD_PAID", "fd_payment_success_time", null,
                FdPartnerLifecycle.IN_PROGRESS, Map.of("phone_number", "9999999001"));
        FdReconRow row = FdReconRow.fromMatch(mis, Map.of(
                "id", leadId,
                "retailer_user_id", retailerId,
                "retailer_code", "R1",
                "retailer_name", "Kirana",
                "distributor_code", "D1",
                "distributor_name", "Dist",
                "hub_name", "Mumbai",
                "customer_name", "Recon Demo User"));
        assertTrue(row.identified());
        assertEquals("R1 · Kirana", row.retailerLabel());
        assertEquals("D1 · Dist", row.distributorLabel());
        assertEquals("Mumbai", row.hubLabel());
        assertEquals(leadId, row.leadId());
    }
}
