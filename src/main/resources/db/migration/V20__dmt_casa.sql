-- A remitter belongs to the retailer who queried them. PaySprint keys the same mobile per merchant.
ALTER TABLE dmt_senders DROP CONSTRAINT dmt_senders_mobile_key;
ALTER TABLE dmt_senders ALTER COLUMN full_name DROP NOT NULL;
ALTER TABLE dmt_senders
    ADD COLUMN provider_status       TEXT,
    ADD COLUMN provider_limit        NUMERIC(18,4),
    ADD COLUMN provider_checked_at   TIMESTAMPTZ,
    ADD COLUMN ao_url                TEXT,
    ADD COLUMN ao_sdk_token          TEXT,
    ADD COLUMN ao_account_no         TEXT,
    ADD COLUMN ao_account_status     TEXT,
    ADD COLUMN ao_access_type        TEXT,
    ADD COLUMN ao_verification_type  TEXT,
    ADD COLUMN ao_refid              TEXT;

CREATE UNIQUE INDEX uq_dmt_senders_agent_mobile ON dmt_senders (registered_by, mobile);

-- PaySprint's list returns a masked account, so the full number is optional.
ALTER TABLE dmt_beneficiaries ALTER COLUMN account_number_enc DROP NOT NULL;
ALTER TABLE dmt_beneficiaries
    ADD COLUMN provider_bene_id TEXT,
    ADD COLUMN account_masked   TEXT,
    ADD COLUMN verified_name    TEXT,
    ADD COLUMN deleted_at       TIMESTAMPTZ;

CREATE UNIQUE INDEX uq_dmt_bene_provider
    ON dmt_beneficiaries (sender_id, provider_bene_id)
    WHERE provider_bene_id IS NOT NULL AND deleted_at IS NULL;

CREATE TABLE dmt_bene_otp_sessions (
    id                  UUID PRIMARY KEY,
    sender_id           UUID NOT NULL REFERENCES dmt_senders(id),
    agent_user_id       UUID NOT NULL REFERENCES users(id),
    name                TEXT NOT NULL,
    bank_id             TEXT NOT NULL,
    account_number_enc  TEXT NOT NULL,
    ifsc                VARCHAR(11) NOT NULL,
    stateresp           TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ NOT NULL,
    consumed_at         TIMESTAMPTZ
);

CREATE TABLE dmt_txn_otp_sessions (
    id                UUID PRIMARY KEY,
    sender_id         UUID NOT NULL REFERENCES dmt_senders(id),
    agent_user_id     UUID NOT NULL REFERENCES users(id),
    beneficiary_id    UUID NOT NULL REFERENCES dmt_beneficiaries(id),
    provider_bene_id  TEXT NOT NULL,
    amount            NUMERIC(18,4) NOT NULL,
    fee               NUMERIC(18,4) NOT NULL,
    txn_type          TEXT NOT NULL,
    reference_id      TEXT NOT NULL UNIQUE,
    stateresp         TEXT NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ NOT NULL,
    consumed_at       TIMESTAMPTZ
);

CREATE SEQUENCE dmt_reference_seq START WITH 100000001;

CREATE TABLE dmt_transfers (
    id                    UUID PRIMARY KEY,
    transaction_id        UUID NOT NULL UNIQUE REFERENCES transactions(id),
    agent_user_id         UUID NOT NULL REFERENCES users(id),
    sender_id             UUID NOT NULL REFERENCES dmt_senders(id),
    beneficiary_id        UUID REFERENCES dmt_beneficiaries(id),
    reference_id          TEXT NOT NULL UNIQUE,
    amount                NUMERIC(18,4) NOT NULL,
    fee                   NUMERIC(18,4) NOT NULL DEFAULT 0,
    txn_type              TEXT NOT NULL,
    provider_ackno        TEXT,
    provider_utr          TEXT,
    provider_txn_status   TEXT,
    provider_charge       NUMERIC(18,4),
    provider_gst          NUMERIC(18,4),
    provider_tds          NUMERIC(18,4),
    provider_commission   NUMERIC(18,4),
    provider_bc_share     NUMERIC(18,4),
    bene_name             TEXT,
    account_masked        TEXT,
    npci_code             TEXT,
    status                TEXT NOT NULL,
    refund_state          TEXT NOT NULL DEFAULT 'NONE',
    refund_stateresp      TEXT,
    err_msg               TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_dmt_transfers_agent ON dmt_transfers (agent_user_id, created_at DESC);

-- Account-opening debit is the amount PaySprint names. Our own fee stays zero.
INSERT INTO commission_rules
    (id, txn_type, slab_min, slab_max, total_rate_bp, flat_fee,
     retailer_share_pct, distributor_share_pct, platform_share_pct, effective_from)
VALUES
    ('c0000000-0000-0000-0000-000000000004', 'DMT_AO', 1, 200000, 0, NULL, 40, 20, 40, now());
