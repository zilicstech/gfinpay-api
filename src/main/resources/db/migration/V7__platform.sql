CREATE TABLE platform_services (
    code        TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    description TEXT NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order  INT NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE recon_batches (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider           TEXT NOT NULL,
    business_date      DATE NOT NULL,
    mis_file_uri       TEXT NOT NULL,
    total_rows         INT,
    matched_rows       INT,
    unidentified_rows  INT NOT NULL DEFAULT 0,
    status             TEXT NOT NULL DEFAULT 'RUNNING',
    status_counts      JSONB NOT NULL DEFAULT '{}'::jsonb,
    started_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at        TIMESTAMPTZ
);

CREATE TABLE recon_rows (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id             UUID NOT NULL REFERENCES recon_batches(id) ON DELETE CASCADE,
    customer_mobile      TEXT,
    customer_name        TEXT,
    lead_id              UUID REFERENCES sales_leads(id) ON DELETE SET NULL,
    retailer_user_id     UUID,
    distributor_user_id  UUID,
    hub_id               UUID REFERENCES hubs(id) ON DELETE SET NULL,
    retailer_label       TEXT NOT NULL,
    distributor_label    TEXT NOT NULL,
    hub_label            TEXT,
    identified           BOOLEAN NOT NULL DEFAULT FALSE,
    current_status       TEXT NOT NULL,
    payload              JSONB NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_recon_rows_batch ON recon_rows (batch_id);
CREATE INDEX idx_recon_rows_mobile ON recon_rows (customer_mobile);
CREATE INDEX idx_recon_rows_status ON recon_rows (batch_id, current_status);

CREATE TABLE recon_mismatches (
    id             BIGSERIAL PRIMARY KEY,
    batch_id       UUID NOT NULL REFERENCES recon_batches(id) ON DELETE CASCADE,
    mismatch_type  TEXT NOT NULL,
    transaction_id UUID REFERENCES transactions(id),
    partner_ref    TEXT,
    details        JSONB NOT NULL,
    resolution     TEXT,
    resolved_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE outbox_events (
    id             BIGSERIAL PRIMARY KEY,
    aggregate_type TEXT NOT NULL,
    aggregate_id   UUID NOT NULL,
    event_type     TEXT NOT NULL,
    payload        JSONB NOT NULL,
    status         TEXT NOT NULL DEFAULT 'NEW',
    attempts       SMALLINT NOT NULL DEFAULT 0,
    next_retry_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    dispatched_at  TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_outbox_pick ON outbox_events(next_retry_at) WHERE status IN ('NEW', 'FAILED');

CREATE TABLE webhook_inbox (
    id                BIGSERIAL PRIMARY KEY,
    provider          TEXT NOT NULL,
    external_event_id TEXT NOT NULL,
    payload           JSONB NOT NULL,
    signature_valid   BOOLEAN NOT NULL,
    status            TEXT NOT NULL DEFAULT 'RECEIVED',
    transaction_id    UUID REFERENCES transactions(id),
    received_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at      TIMESTAMPTZ,
    CONSTRAINT uq_webhook_event UNIQUE (provider, external_event_id)
);

CREATE TABLE shedlock (
    name       VARCHAR(64) PRIMARY KEY,
    lock_until TIMESTAMPTZ NOT NULL,
    locked_at  TIMESTAMPTZ NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
