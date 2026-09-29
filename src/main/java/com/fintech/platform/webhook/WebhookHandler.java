package com.fintech.platform.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/** Implemented by modules that finalize transactions from partner webhooks. */
public interface WebhookHandler {

    /** Provider slug this handler owns, e.g. "sponsorbank", "paymentgateway". */
    String provider();

    /** Apply the webhook; must be idempotent. Returns the affected transaction id (or null). */
    UUID handle(JsonNode payload);
}
