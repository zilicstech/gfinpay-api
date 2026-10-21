-- Partner bank ids used when a retailer sends money. New rows start active.
CREATE TABLE dmt_bank_list (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bank_id    TEXT NOT NULL,
    bank_name  TEXT NOT NULL,
    active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_dmt_bank_id UNIQUE (bank_id)
);

CREATE INDEX idx_dmt_bank_list_name ON dmt_bank_list (lower(bank_name));

ALTER TABLE dmt_beneficiaries
    ADD COLUMN provider_bank_id TEXT;
