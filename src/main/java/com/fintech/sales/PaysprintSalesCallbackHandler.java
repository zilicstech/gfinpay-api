package com.fintech.sales;

import com.fasterxml.jackson.databind.JsonNode;
import com.fintech.paysprint.PaysprintUtmOutcome;
import com.fintech.platform.webhook.WebhookHandler;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class PaysprintSalesCallbackHandler implements WebhookHandler {

    private final SalesLeadService sales;

    public PaysprintSalesCallbackHandler(SalesLeadService sales) {
        this.sales = sales;
    }

    @Override
    public String provider() {
        return "paysprint-fd";
    }

    @Override
    public UUID handle(JsonNode payload) {
        JsonNode param = payload.path("param");
        String refid = param.path("refid").asText();
        if (refid.isBlank()) {
            return null;
        }
        String note = PaysprintUtmOutcome.summary(param);
        if (PaysprintUtmOutcome.converted(param)) {
            sales.applyOutcome(refid, true, "CALLBACK: " + note);
        } else if (PaysprintUtmOutcome.rejected(param)) {
            sales.applyOutcome(refid, false, "CALLBACK: " + note);
        } else {
            sales.markInProgress(refid, "CALLBACK: " + note);
        }
        // webhook_inbox.transaction_id references transactions(id); FD sales are leads, not txns.
        return null;
    }
}
