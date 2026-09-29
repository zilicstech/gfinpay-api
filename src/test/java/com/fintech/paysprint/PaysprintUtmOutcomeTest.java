package com.fintech.paysprint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaysprintUtmOutcomeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void pendingDocSampleIsNeitherConvertedNorRejected() {
        ObjectNode param = mapper.createObjectNode();
        param.put("txn_status", "Pending");
        param.put("ex_status", "Application Incomple");
        param.put("ex_sub_status", "FD Payment Failed. C");
        assertFalse(PaysprintUtmOutcome.converted(param));
        assertFalse(PaysprintUtmOutcome.rejected(param));
    }

    @Test
    void successTxnConverts() {
        ObjectNode param = mapper.createObjectNode();
        param.put("txn_status", "SUCCESS");
        assertTrue(PaysprintUtmOutcome.converted(param));
        assertFalse(PaysprintUtmOutcome.rejected(param));
    }

    @Test
    void rejectedExecutiveRejects() {
        ObjectNode param = mapper.createObjectNode();
        param.put("ex_status", "NOT_INTERESTED");
        assertTrue(PaysprintUtmOutcome.rejected(param));
        assertFalse(PaysprintUtmOutcome.converted(param));
    }
}
