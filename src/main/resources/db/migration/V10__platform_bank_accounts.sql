CREATE TABLE platform_bank_accounts (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_name     TEXT NOT NULL,
    account_number   TEXT NOT NULL,
    ifsc             TEXT NOT NULL,
    bank_name        TEXT NOT NULL,
    upi_id           TEXT,
    qr_key           TEXT,
    qr_filename      TEXT,
    qr_content_type  TEXT,
    active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_by       UUID REFERENCES users(id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_platform_bank_account_number ON platform_bank_accounts (account_number);
CREATE UNIQUE INDEX uq_platform_bank_upi
    ON platform_bank_accounts (lower(upi_id))
    WHERE upi_id IS NOT NULL;
CREATE INDEX idx_platform_bank_active ON platform_bank_accounts (active, bank_name);
