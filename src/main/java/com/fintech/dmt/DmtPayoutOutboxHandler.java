package com.fintech.dmt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fintech.ledger.TransactionService;
import com.fintech.platform.outbox.OutboxEventHandler;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Dispatches DMT payout requests to the sponsor bank when configured (doc 03 phase 2).
 */
@Component
public class DmtPayoutOutboxHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(DmtPayoutOutboxHandler.class);

    private final TransactionService transactionService;

    public DmtPayoutOutboxHandler(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @Override
    public String eventType() {
        return "DMT_PAYOUT_REQUESTED";
    }

    @Override
    public void handle(JsonNode payload) {
        UUID txnId = UUID.fromString(payload.get("transactionId").asText());
        log.warn("DMT payout not dispatched: sponsor bank integration not configured txnId={}", txnId);
        transactionService.transition(txnId, "FAILED", null, "Sponsor bank integration not configured");
    }
}
