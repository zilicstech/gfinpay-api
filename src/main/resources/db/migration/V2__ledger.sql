CREATE TABLE wallets (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID NOT NULL UNIQUE REFERENCES users(id),
    available_balance  NUMERIC(18,4) NOT NULL DEFAULT 0 CHECK (available_balance >= 0),
    hold_balance       NUMERIC(18,4) NOT NULL DEFAULT 0 CHECK (hold_balance >= 0),
    version            BIGINT NOT NULL DEFAULT 0,
    status             TEXT NOT NULL DEFAULT 'ACTIVE',
    is_active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE idempotency_keys (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idem_key       TEXT NOT NULL,
    agent_user_id  UUID NOT NULL REFERENCES users(id),
    request_hash   TEXT NOT NULL,
    response_code  SMALLINT,
    response_body  JSONB,
    status         TEXT NOT NULL DEFAULT 'IN_FLIGHT',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_idem UNIQUE (agent_user_id, idem_key)
);

CREATE TABLE dmt_senders (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    mobile              VARCHAR(15) NOT NULL UNIQUE,
    full_name           TEXT NOT NULL,
    kyc_level           TEXT NOT NULL DEFAULT 'MIN_KYC',
    partner_sender_ref  TEXT,
    registered_by       UUID NOT NULL REFERENCES users(id),
    ovd_type            TEXT,
    ovd_last4           CHAR(4),
    address_line        TEXT,
    pan_last4           CHAR(4),
    mobile_verified_at  TIMESTAMPTZ,
    ovd_captured_at     TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_dmt_senders_registered_by ON dmt_senders(registered_by);

CREATE TABLE dmt_beneficiaries (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sender_id          UUID NOT NULL REFERENCES dmt_senders(id),
    name               TEXT NOT NULL,
    account_number_enc TEXT NOT NULL,
    account_last4      CHAR(4) NOT NULL,
    ifsc               VARCHAR(11) NOT NULL,
    partner_bene_ref   TEXT,
    verified_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE transactions (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    txn_type           TEXT NOT NULL,
    state              TEXT NOT NULL DEFAULT 'INITIATED',
    agent_user_id      UUID NOT NULL REFERENCES users(id),
    wallet_id          UUID NOT NULL REFERENCES wallets(id),
    amount             NUMERIC(18,4) NOT NULL CHECK (amount > 0),
    fee                NUMERIC(18,4) NOT NULL DEFAULT 0,
    dmt_sender_id      UUID REFERENCES dmt_senders(id),
    beneficiary_id     UUID REFERENCES dmt_beneficiaries(id),
    customer_ref       JSONB,
    partner_code       TEXT,
    partner_ref        TEXT,
    idempotency_key_id UUID REFERENCES idempotency_keys(id),
    failure_reason     TEXT,
    state_history      JSONB NOT NULL DEFAULT '[]',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_txn_agent_time ON transactions(agent_user_id, created_at DESC);
CREATE INDEX idx_txn_state ON transactions(state) WHERE state IN ('HOLD', 'PENDING');
CREATE UNIQUE INDEX uq_txn_partner_ref ON transactions(partner_code, partner_ref)
    WHERE partner_ref IS NOT NULL;
CREATE INDEX idx_txn_dmt_sender ON transactions(dmt_sender_id) WHERE dmt_sender_id IS NOT NULL;

CREATE TABLE ledger_entries (
    id             BIGSERIAL PRIMARY KEY,
    transaction_id UUID NOT NULL REFERENCES transactions(id),
    posting_group  UUID NOT NULL,
    wallet_id      UUID NOT NULL REFERENCES wallets(id),
    direction      TEXT NOT NULL,
    amount         NUMERIC(18,4) NOT NULL CHECK (amount > 0),
    narration      TEXT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ledger_txn ON ledger_entries(transaction_id);
CREATE INDEX idx_ledger_wallet ON ledger_entries(wallet_id, created_at);

CREATE OR REPLACE FUNCTION forbid_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();
