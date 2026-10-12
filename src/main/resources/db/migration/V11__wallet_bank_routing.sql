CREATE TABLE retailer_bank_accounts (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL REFERENCES users(id),
    account_name     TEXT NOT NULL,
    account_number   TEXT NOT NULL,
    ifsc             TEXT NOT NULL,
    bank_name        TEXT NOT NULL,
    active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_retailer_bank_account UNIQUE (user_id, account_number)
);

CREATE INDEX idx_retailer_bank_user ON retailer_bank_accounts (user_id, active);

ALTER TABLE finance_requests
    ADD COLUMN collection_account_id UUID REFERENCES platform_bank_accounts(id),
    ADD COLUMN transfer_mode TEXT,
    ADD COLUMN payout_account_id UUID REFERENCES retailer_bank_accounts(id);

ALTER TABLE finance_requests
    ADD CONSTRAINT chk_finance_transfer_mode CHECK (
        transfer_mode IS NULL OR transfer_mode IN ('IMPS', 'NEFT', 'RTGS', 'UPI')
    );
