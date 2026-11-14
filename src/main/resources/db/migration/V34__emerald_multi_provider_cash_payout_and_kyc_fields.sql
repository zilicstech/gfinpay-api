-- Multi-provider onboarding, white-label biometric KYC, Round-Robin routing,
-- UPI Cashout (UATM), AEPS, Payouts, and unified Emerald + Vendor 2 KYC/Bank fields.

-- 1. Unified KYC & Bank Account fields
ALTER TABLE agent_kyc_profiles
    ADD COLUMN IF NOT EXISTS aadhaar_enc TEXT;

ALTER TABLE retailer_bank_accounts
    ADD COLUMN IF NOT EXISTS account_type TEXT NOT NULL DEFAULT 'SAVINGS';

ALTER TABLE retailer_bank_accounts
    DROP CONSTRAINT IF EXISTS chk_retailer_bank_account_type;

ALTER TABLE retailer_bank_accounts
    ADD CONSTRAINT chk_retailer_bank_account_type
    CHECK (account_type IN ('SAVINGS', 'CURRENT'));

-- 2. Provider + Pipe Channel Catalog (white-label channel_key exposed to retailers)
CREATE TABLE IF NOT EXISTS service_provider_channels (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    channel_key           TEXT NOT NULL UNIQUE,
    provider_code         TEXT NOT NULL,
    pipe_code             TEXT NOT NULL,
    display_label         TEXT NOT NULL,
    retailer_label        TEXT NOT NULL DEFAULT 'Merchant Banking Terminal',
    biometric_required    BOOLEAN NOT NULL DEFAULT TRUE,
    supports_upi_cashout  BOOLEAN NOT NULL DEFAULT TRUE,
    supports_aeps         BOOLEAN NOT NULL DEFAULT TRUE,
    supports_payout       BOOLEAN NOT NULL DEFAULT TRUE,
    priority_order        INT NOT NULL DEFAULT 1,
    active                BOOLEAN NOT NULL DEFAULT TRUE,
    config_json           JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_provider_pipe UNIQUE (provider_code, pipe_code)
);

INSERT INTO service_provider_channels
    (id, channel_key, provider_code, pipe_code, display_label, retailer_label,
     biometric_required, supports_upi_cashout, supports_aeps, supports_payout, priority_order, active)
VALUES
    ('e1000000-0000-0000-0000-000000000001', 'ch_cash_1', 'EMERALD', 'INSTANTPAY',
     'Emerald · InstantPay', 'Merchant Banking Terminal',
     TRUE, TRUE, TRUE, TRUE, 1, TRUE)
ON CONFLICT (provider_code, pipe_code) DO NOTHING;

-- 3. Service Routing Policies (Round-Robin vs Primary/Fallback)
CREATE TABLE IF NOT EXISTS service_routing_policies (
    service_code  TEXT PRIMARY KEY,
    strategy      TEXT NOT NULL DEFAULT 'ROUND_ROBIN',
    rr_counter    BIGINT NOT NULL DEFAULT 0,
    active        BOOLEAN NOT NULL DEFAULT TRUE,
    updated_by    UUID REFERENCES users(id),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_routing_strategy CHECK (strategy IN ('ROUND_ROBIN', 'PRIMARY_FALLBACK'))
);

INSERT INTO service_routing_policies (service_code, strategy, rr_counter, active)
VALUES
    ('UPI_CASHOUT', 'ROUND_ROBIN', 0, TRUE),
    ('AEPS', 'ROUND_ROBIN', 0, TRUE),
    ('PAYOUT', 'ROUND_ROBIN', 0, TRUE)
ON CONFLICT (service_code) DO NOTHING;

-- 4. Per-Retailer Provider Merchant Accounts (1 row per retailer + provider)
CREATE TABLE IF NOT EXISTS provider_merchant_accounts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id),
    provider_code   TEXT NOT NULL,
    entity_code     TEXT,
    outlet_code     TEXT,
    status          TEXT NOT NULL DEFAULT 'ACTIVE',
    onboarded_by    UUID REFERENCES users(id),
    onboarded_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_synced_at  TIMESTAMPTZ,
    last_error      TEXT,
    raw_outlet      JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_provider_merchant_user UNIQUE (user_id, provider_code),
    CONSTRAINT chk_provider_merchant_status CHECK (status IN ('ACTIVE', 'ONBOARDED', 'FAILED', 'SUSPENDED'))
);

CREATE INDEX IF NOT EXISTS idx_provider_merchant_outlet
    ON provider_merchant_accounts (provider_code, outlet_code)
    WHERE outlet_code IS NOT NULL;

-- 5. Per-Retailer Pipe Activations (biometric FULL_KYC & bank sync state per channel)
CREATE TABLE IF NOT EXISTS provider_pipe_activations (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID NOT NULL REFERENCES users(id),
    channel_id              UUID NOT NULL REFERENCES service_provider_channels(id),
    merchant_account_id     UUID NOT NULL REFERENCES provider_merchant_accounts(id),
    pipe_outlet_code        TEXT,
    onboarding_status       TEXT NOT NULL DEFAULT 'ONBOARDED',
    kyc_status              TEXT NOT NULL DEFAULT 'PENDING_BIOMETRIC',
    biometric_verified_at   TIMESTAMPTZ,
    bank_sync_status        TEXT NOT NULL DEFAULT 'NOT_SYNCED',
    synced_bank_account_id  UUID REFERENCES retailer_bank_accounts(id),
    bank_sync_remarks       TEXT,
    enabled_for_retailer    BOOLEAN NOT NULL DEFAULT TRUE,
    last_error              TEXT,
    last_synced_at          TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_provider_pipe_user UNIQUE (user_id, channel_id),
    CONSTRAINT chk_pipe_onboarding_status CHECK (onboarding_status IN ('PENDING', 'ONBOARDED', 'ACTIVE', 'FAILED', 'SUSPENDED')),
    CONSTRAINT chk_pipe_bank_sync_status CHECK (bank_sync_status IN ('NOT_SYNCED', 'SYNCED', 'FAILED')),
    CONSTRAINT chk_pipe_kyc_status CHECK (kyc_status IN ('NOT_REQUIRED', 'PENDING_BIOMETRIC', 'FULL_KYC', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_pipe_activation_outlet
    ON provider_pipe_activations (pipe_outlet_code)
    WHERE pipe_outlet_code IS NOT NULL;

-- 6. Ephemeral Biometric KYC Sessions (stores referenceKey, pidWadh & RD metadata; never raw PidData or Aadhaar)
CREATE TABLE IF NOT EXISTS biometric_kyc_sessions (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               UUID NOT NULL REFERENCES users(id),
    channel_id            UUID NOT NULL REFERENCES service_provider_channels(id),
    external_ref          TEXT NOT NULL UNIQUE,
    reference_key         TEXT,
    pid_wadh              TEXT,
    reference_type        TEXT,
    status                TEXT NOT NULL DEFAULT 'INITIATED',
    capture_latitude      NUMERIC(10,7),
    capture_longitude     NUMERIC(10,7),
    rd_rds_id             TEXT,
    rd_rds_ver            TEXT,
    rd_dp_id              TEXT,
    rd_mi                 TEXT,
    rd_srno               TEXT,
    q_score               INT,
    nm_points             INT,
    provider_status_code  TEXT,
    provider_message      TEXT,
    expires_at            TIMESTAMPTZ NOT NULL,
    submitted_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_bio_session_status CHECK (status IN ('INITIATED', 'SUCCESS', 'FAILED', 'EXPIRED'))
);

CREATE INDEX IF NOT EXISTS idx_bio_sessions_user_created
    ON biometric_kyc_sessions (user_id, created_at DESC);

-- 7. Extend cashout_sessions for Inline Dynamic UPI QR (UATM) & Multi-Provider Routing
ALTER TABLE cashout_sessions
    ADD COLUMN IF NOT EXISTS channel_id         UUID REFERENCES service_provider_channels(id),
    ADD COLUMN IF NOT EXISTS provider_code      TEXT,
    ADD COLUMN IF NOT EXISTS pipe_code          TEXT,
    ADD COLUMN IF NOT EXISTS outlet_code        TEXT,
    ADD COLUMN IF NOT EXISTS external_ref       TEXT,
    ADD COLUMN IF NOT EXISTS provider_txn_id    TEXT,
    ADD COLUMN IF NOT EXISTS qr_string          TEXT,
    ADD COLUMN IF NOT EXISTS payable_value      NUMERIC(18,2),
    ADD COLUMN IF NOT EXISTS display_expiry_sec INT,
    ADD COLUMN IF NOT EXISTS utr                TEXT,
    ADD COLUMN IF NOT EXISTS updated_at         TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE UNIQUE INDEX IF NOT EXISTS uq_cashout_external_ref
    ON cashout_sessions (external_ref)
    WHERE external_ref IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_cashout_provider_txn
    ON cashout_sessions (provider_txn_id)
    WHERE provider_txn_id IS NOT NULL;

-- 8. AEPS Transactions (Cash Withdrawal, Balance Enquiry, Mini Statement)
CREATE TABLE IF NOT EXISTS aeps_transactions (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id        UUID REFERENCES transactions(id),
    user_id               UUID NOT NULL REFERENCES users(id),
    channel_id            UUID REFERENCES service_provider_channels(id),
    provider_code         TEXT NOT NULL,
    pipe_code             TEXT NOT NULL,
    outlet_code           TEXT,
    external_ref          TEXT NOT NULL UNIQUE,
    provider_txn_id       TEXT,
    aeps_type             TEXT NOT NULL,
    bank_iin              TEXT NOT NULL,
    bank_name             TEXT,
    customer_mobile       VARCHAR(15),
    aadhaar_last4         CHAR(4),
    amount                NUMERIC(18,2) NOT NULL DEFAULT 0,
    bank_rrn              TEXT,
    available_balance     NUMERIC(18,2),
    mini_statement        JSONB,
    latitude              NUMERIC(10,7),
    longitude             NUMERIC(10,7),
    status                TEXT NOT NULL DEFAULT 'PENDING',
    provider_status_code  TEXT,
    provider_message      TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_aeps_type CHECK (aeps_type IN ('CASH_WITHDRAWAL', 'BALANCE_ENQUIRY', 'MINI_STATEMENT', 'AADHAAR_PAY')),
    CONSTRAINT chk_aeps_status CHECK (status IN ('INITIATED', 'PENDING', 'SUCCESS', 'FAILED', 'REVERSED'))
);

CREATE INDEX IF NOT EXISTS idx_aeps_user_created ON aeps_transactions (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_aeps_provider_txn ON aeps_transactions (provider_txn_id) WHERE provider_txn_id IS NOT NULL;

-- 9. Payout Transfers (IMPS / NEFT / RTGS Settlement)
CREATE TABLE IF NOT EXISTS payout_transfers (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id        UUID NOT NULL UNIQUE REFERENCES transactions(id),
    user_id               UUID NOT NULL REFERENCES users(id),
    channel_id            UUID REFERENCES service_provider_channels(id),
    provider_code         TEXT NOT NULL,
    pipe_code             TEXT NOT NULL,
    outlet_code           TEXT,
    bank_account_id       UUID REFERENCES retailer_bank_accounts(id),
    external_ref          TEXT NOT NULL UNIQUE,
    provider_txn_id       TEXT,
    transfer_mode         TEXT NOT NULL DEFAULT 'IMPS',
    amount                NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    fee                   NUMERIC(18,2) NOT NULL DEFAULT 0,
    account_holder_name   TEXT NOT NULL,
    account_number_masked TEXT NOT NULL,
    ifsc                  VARCHAR(11) NOT NULL,
    bank_name             TEXT,
    utr                   TEXT,
    latitude              NUMERIC(10,7),
    longitude             NUMERIC(10,7),
    remarks               TEXT,
    status                TEXT NOT NULL DEFAULT 'HOLD',
    provider_status_code  TEXT,
    provider_message      TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_payout_mode CHECK (transfer_mode IN ('IMPS', 'NEFT', 'RTGS', 'UPI')),
    CONSTRAINT chk_payout_status CHECK (status IN ('HOLD', 'PENDING', 'SUCCESS', 'FAILED', 'REVERSED'))
);

CREATE INDEX IF NOT EXISTS idx_payout_user_created ON payout_transfers (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_payout_provider_txn ON payout_transfers (provider_txn_id) WHERE provider_txn_id IS NOT NULL;
