-- Every inbound callback, including repeats and ones we do not apply.
-- webhook_inbox stays the processing queue and is not a history of arrivals.

CREATE TABLE provider_callbacks (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider         TEXT NOT NULL,
    source           TEXT NOT NULL,
    event            TEXT,
    status           TEXT,
    merchant_code    TEXT,
    payload          JSONB,
    outcome          TEXT NOT NULL,
    rejection_reason TEXT,
    received_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_provider_callbacks_provider_received
    ON provider_callbacks (provider, received_at DESC);

CREATE INDEX idx_provider_callbacks_merchant
    ON provider_callbacks (merchant_code)
    WHERE merchant_code IS NOT NULL;
