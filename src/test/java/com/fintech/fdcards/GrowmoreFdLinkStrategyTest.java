package com.fintech.fdcards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class GrowmoreFdLinkStrategyTest {

    private final GrowmoreFdLinkStrategy strategy = new GrowmoreFdLinkStrategy();

    @Test
    void putsLeadRefInUtmCampaign() {
        String template = "https://www.novio.in/fixed-deposit-credit-card/apply/?advisor_code=CRD0158101"
                + "&utm_org_code=ORG03094&utm_source=Affiliate&utm_campaign={ref}";
        FdJourneyResult result = strategy.build(Map.of(
                "apply_url", template,
                "provider_refid", "5d288bd5-393f-4da7-81d3-99cc3082b766"), null);

        assertEquals("GET", result.method());
        assertTrue(result.url().contains("utm_campaign=5d288bd5-393f-4da7-81d3-99cc3082b766"));
        assertTrue(result.url().contains("advisor_code=CRD0158101"));
    }
}
