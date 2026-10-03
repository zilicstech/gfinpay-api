package com.fintech.recon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FdReconOutcomeTest {

    @Test
    void statusCountsSplitInternalAndVendor() {
        FdMisRow internalMis = mis("9000000001", "VKYC_STARTED");
        FdMisRow vendorMis = mis("9000000002", "ACTIVATED");
        FdReconRow internal = FdReconRow.fromMatch(internalMis, Map.of(
                "id", UUID.randomUUID(),
                "sale_channel", "INTERNAL",
                "retailer_code", "R1",
                "retailer_name", "Shop",
                "distributor_code", "D1",
                "distributor_name", "Dist",
                "hub_name", "Hub",
                "customer_name", "A"));
        FdReconRow vendor = FdReconRow.fromMatch(vendorMis, Map.of(
                "id", UUID.randomUUID(),
                "sale_channel", "EXTERNAL",
                "retailer_code", "E1",
                "retailer_name", "Emp",
                "distributor_code", "V1",
                "distributor_name", "Vendor",
                "hub_name", "Hub",
                "customer_name", "B"));
        FdReconRow sameStatusInternal = FdReconRow.fromMatch(mis("9000000003", "VKYC_STARTED"), Map.of(
                "id", UUID.randomUUID(),
                "sale_channel", "INTERNAL",
                "retailer_code", "R2",
                "retailer_name", "Shop2",
                "distributor_code", "D2",
                "distributor_name", "Dist2",
                "hub_name", "Hub",
                "customer_name", "C"));

        FdReconOutcome outcome = new FdReconOutcome(3, 3, 0, 0, 0, "ZET_EXCEL", List.of(),
                List.of(internal, vendor, sameStatusInternal));

        @SuppressWarnings("unchecked")
        Map<String, Integer> vkyc = (Map<String, Integer>) outcome.statusCounts().get("VKYC_STARTED");
        assertEquals(2, vkyc.get("total"));
        assertEquals(2, vkyc.get("internal"));
        assertEquals(0, vkyc.get("vendor"));

        @SuppressWarnings("unchecked")
        Map<String, Integer> activated = (Map<String, Integer>) outcome.statusCounts().get("ACTIVATED");
        assertEquals(1, activated.get("total"));
        assertEquals(0, activated.get("internal"));
        assertEquals(1, activated.get("vendor"));
    }

    private static FdMisRow mis(String phone, String status) {
        return new FdMisRow("ZET", "SBM", "SBM", phone, "Test", "u1", null, status, "col",
                Instant.parse("2026-03-01T00:00:00Z"), FdPartnerLifecycle.IN_PROGRESS, Map.of());
    }
}
