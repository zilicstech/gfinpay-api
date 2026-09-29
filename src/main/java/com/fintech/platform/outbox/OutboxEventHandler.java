package com.fintech.platform.outbox;

import com.fasterxml.jackson.databind.JsonNode;

/** Implemented by modules that dispatch outbox events to external partners. */
public interface OutboxEventHandler {

    /** The event_type this handler processes, e.g. DMT_PAYOUT_REQUESTED. */
    String eventType();

    /** Perform the external call. Throwing marks the event FAILED for retry with backoff. */
    void handle(JsonNode payload);
}
