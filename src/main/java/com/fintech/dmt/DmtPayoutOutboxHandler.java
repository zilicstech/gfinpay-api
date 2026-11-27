package com.fintech.dmt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fintech.platform.outbox.OutboxEventHandler;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Handles legacy/async {@code DMT_STATUS_CHECK} outbox events via {@link DmtCasaService}.
 */
@Component
public class DmtPayoutOutboxHandler implements OutboxEventHandler {

    private final DmtCasaService dmtService;

    public DmtPayoutOutboxHandler(DmtCasaService dmtService) {
        this.dmtService = dmtService;
    }

    @Override
    public String eventType() {
        return "DMT_STATUS_CHECK";
    }

    @Override
    public void handle(JsonNode payload) {
        if (payload == null || payload.get("transactionId") == null) {
            return;
        }
        dmtService.reconcileByTransactionId(UUID.fromString(payload.get("transactionId").asText()), false);
    }
}
