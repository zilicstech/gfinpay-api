package com.fintech.recon.zet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fintech.recon.FdReconOutcome;
import com.fintech.recon.ZetFdReconHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

/** Requires local Postgres with sales_leads from docs/zet-recon-demo/seed-mixed-channel.sql */
@SpringBootTest
@ActiveProfiles("local")
class MixedChannelZetReconLiveTest {

    @Autowired(required = false)
    private ZetFdReconHandler handler;

    @Autowired(required = false)
    private JdbcTemplate jdbc;

    @Test
    void reconcileMixedInternalAndVendorExcel() throws Exception {
        assumeTrue(handler != null && jdbc != null, "Spring context not available");
        assumeTrue(postgresReachable(), "Local Postgres not running");

        Path xlsx = Path.of("src/test/resources/recon/zet-mixed-internal-vendor.xlsx");
        if (!Files.exists(xlsx)) {
            GenerateMixedChannelReconXlsx.write(xlsx);
        }
        byte[] bytes = Files.readAllBytes(xlsx);
        MockMultipartFile file = new MockMultipartFile("file", "zet-mixed.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);

        FdReconOutcome outcome = handler.reconcile(file);

        assertEquals(6, outcome.eligible());
        assertTrue(outcome.matched() >= 5, "expected most rows to match local leads");
        assertTrue(outcome.statusCounts().containsKey("ACTIVATED"));
        assertTrue(outcome.statusCounts().containsKey("VKYC_STARTED"));

        @SuppressWarnings("unchecked")
        Map<String, Integer> activated = (Map<String, Integer>) outcome.statusCounts().get("ACTIVATED");
        assertTrue(activated.get("internal") >= 0);
        assertTrue(activated.get("vendor") >= 1);

        Integer vendorActivated = jdbc.queryForObject("""
                SELECT COUNT(*)::int FROM sales_leads
                 WHERE id = ?::uuid AND sale_channel = 'EXTERNAL' AND partner_status = 'ACTIVATED'
                """, Integer.class, "27edb71d-72a2-4a3f-b5ec-81a001045b63");
        assertTrue(vendorActivated != null && vendorActivated >= 1);
    }

    private boolean postgresReachable() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
