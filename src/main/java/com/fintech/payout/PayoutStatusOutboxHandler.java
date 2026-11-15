package com.fintech.payout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fintech.platform.outbox.OutboxEventHandler;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Asks the bank for a payout that stayed pending, outside the request that placed the hold. */
@Component
public class PayoutStatusOutboxHandler implements OutboxEventHandler {

    private final PayoutService payouts;

    public PayoutStatusOutboxHandler(PayoutService payouts) {
        this.payouts = payouts;
    }

    @Override
    public String eventType() {
        return "PAYOUT_STATUS_CHECK";
    }

    @Override
    public void handle(JsonNode payload) {
        if (payload == null || payload.get("transactionId") == null) {
            return;
        }
        payouts.reconcileByTransactionId(UUID.fromString(payload.get("transactionId").asText()));
    }
}
