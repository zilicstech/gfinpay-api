ALTER TABLE recon_batches
    ADD COLUMN IF NOT EXISTS status_counts JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS unidentified_rows INT NOT NULL DEFAULT 0;

ALTER TABLE recon_batches DROP CONSTRAINT IF EXISTS uq_recon_day;

ALTER TABLE recon_mismatches DROP CONSTRAINT IF EXISTS recon_mismatches_batch_id_fkey;
ALTER TABLE recon_mismatches
    ADD CONSTRAINT recon_mismatches_batch_id_fkey
        FOREIGN KEY (batch_id) REFERENCES recon_batches (id) ON DELETE CASCADE;

CREATE TABLE recon_rows (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id             UUID NOT NULL REFERENCES recon_batches (id) ON DELETE CASCADE,
    customer_mobile      TEXT,
    customer_name        TEXT,
    lead_id              UUID REFERENCES sales_leads (id) ON DELETE SET NULL,
    retailer_user_id     UUID REFERENCES users (id) ON DELETE SET NULL,
    distributor_user_id  UUID REFERENCES users (id) ON DELETE SET NULL,
    hub_id               UUID REFERENCES hubs (id) ON DELETE SET NULL,
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
