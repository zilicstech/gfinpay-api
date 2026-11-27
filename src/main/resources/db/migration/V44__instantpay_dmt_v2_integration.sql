-- Direct InstantPay Domestic Money Transfer (DMT v2) schema:
-- 1. Channel & routing support for DMT (supports_dmt on InstantPay Direct, PRIMARY_FALLBACK policy)
-- 2. Admin-configurable DMT wallet surcharge columns on commission_rules
-- 3. Extended dmt_bank_list for hourly InstantPay /v2/banks sync
-- 4. System-wide one-time Remitter eKYC & live RBI BC limits on dmt_senders + onboarding sessions
-- 5. Extended beneficiary, OTP session, and webhook-free status reconfirmation columns on dmt_transfers

-- 1. Provider channel & routing support for DMT
ALTER TABLE service_provider_channels
    ADD COLUMN IF NOT EXISTS supports_dmt BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE service_provider_channels
   SET supports_dmt = TRUE,
       config_json = COALESCE(config_json, '{}'::jsonb)
                     || '{"dmt":{"min":100,"maxPerTxn":5000,"monthlyLimit":25000}}'::jsonb,
       updated_at = now()
 WHERE provider_code = 'INSTANTPAY' AND pipe_code = 'DIRECT';

INSERT INTO service_routing_policies (service_code, strategy, rr_counter, active)
VALUES ('DMT', 'PRIMARY_FALLBACK', 0, TRUE)
ON CONFLICT (service_code) DO UPDATE
   SET strategy = 'PRIMARY_FALLBACK',
       updated_at = now();

UPDATE platform_services
   SET name = 'Domestic Money Transfer',
       description = 'RBI BC-assisted IMPS & NEFT money transfer up to ₹5,000/txn and ₹25,000/month per remitter.',
       updated_at = now()
 WHERE code = 'DMT';

-- 2. Admin-configurable wallet surcharge columns on commission_rules (used by DMT)
ALTER TABLE commission_rules
    ADD COLUMN IF NOT EXISTS surcharge_type  TEXT,
    ADD COLUMN IF NOT EXISTS surcharge_value NUMERIC(18,4),
    ADD COLUMN IF NOT EXISTS surcharge_min   NUMERIC(18,4);

-- 3. Extend dmt_bank_list for InstantPay /fi/remit/out/domestic/v2/banks sync
ALTER TABLE dmt_bank_list
    ADD COLUMN IF NOT EXISTS ifsc_alias        TEXT,
    ADD COLUMN IF NOT EXISTS ifsc_global       TEXT,
    ADD COLUMN IF NOT EXISTS imps_enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS neft_enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS imps_failure_rate INT,
    ADD COLUMN IF NOT EXISTS neft_failure_rate INT,
    ADD COLUMN IF NOT EXISTS provider_code     TEXT,
    ADD COLUMN IF NOT EXISTS last_synced_at    TIMESTAMPTZ;

-- 4. Extend dmt_senders for system-wide one-time Remitter KYC & InstantPay v2 profile limits
ALTER TABLE dmt_senders
    ADD COLUMN IF NOT EXISTS provider_code           TEXT,
    ADD COLUMN IF NOT EXISTS outlet_code             TEXT,
    ADD COLUMN IF NOT EXISTS first_name              TEXT,
    ADD COLUMN IF NOT EXISTS last_name               TEXT,
    ADD COLUMN IF NOT EXISTS city                    TEXT,
    ADD COLUMN IF NOT EXISTS pincode                 VARCHAR(10),
    ADD COLUMN IF NOT EXISTS ekyc_verified           BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS ekyc_verified_at        TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS per_txn_limit           NUMERIC(18,2) NOT NULL DEFAULT 5000.00,
    ADD COLUMN IF NOT EXISTS daily_limit_max         NUMERIC(18,2),
    ADD COLUMN IF NOT EXISTS daily_limit_available   NUMERIC(18,2),
    ADD COLUMN IF NOT EXISTS monthly_limit_max       NUMERIC(18,2),
    ADD COLUMN IF NOT EXISTS monthly_limit_available NUMERIC(18,2),
    ADD COLUMN IF NOT EXISTS profile_reference_key   TEXT,
    ADD COLUMN IF NOT EXISTS profile_expires_at      TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS pid_wadh                TEXT,
    ADD COLUMN IF NOT EXISTS is_txn_otp_required     BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS is_txn_bio_required     BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS imps_allowed            BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS neft_allowed            BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS face_auth_available     BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS updated_at              TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_dmt_senders_mobile
    ON dmt_senders (mobile);

-- 5. Ephemeral One-Time Remitter Onboarding Sessions (Registration -> OTP Verify -> L1 Biometric eKYC)
CREATE TABLE IF NOT EXISTS dmt_remitter_onboarding_sessions (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sender_id              UUID REFERENCES dmt_senders(id),
    agent_user_id          UUID NOT NULL REFERENCES users(id),
    channel_id             UUID REFERENCES service_provider_channels(id),
    outlet_code            TEXT,
    external_ref           TEXT NOT NULL UNIQUE,
    mobile                 VARCHAR(15) NOT NULL,
    aadhaar_last4          CHAR(4),
    stage                  TEXT NOT NULL DEFAULT 'AWAITING_OTP',
    reference_key          TEXT,
    pid_wadh               TEXT,
    otp_verified_at        TIMESTAMPTZ,
    ekyc_order_id          TEXT,
    ekyc_pool_amount       NUMERIC(18,2),
    capture_latitude       NUMERIC(10,7),
    capture_longitude      NUMERIC(10,7),
    distance_from_shop_km  NUMERIC(8,3),
    rd_rds_id              TEXT,
    rd_mi                  TEXT,
    rd_srno                TEXT,
    q_score                INT,
    provider_status_code   TEXT,
    provider_message       TEXT,
    expires_at             TIMESTAMPTZ NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_dmt_remitter_onboard_stage
        CHECK (stage IN ('AWAITING_OTP', 'AWAITING_EKYC', 'COMPLETED', 'FAILED', 'EXPIRED'))
);

CREATE INDEX IF NOT EXISTS idx_dmt_remitter_onboard_agent_mobile
    ON dmt_remitter_onboarding_sessions (agent_user_id, mobile, created_at DESC);

-- 6. Extend dmt_beneficiaries, OTP challenge tables, and dmt_transfers (with status reconfirmation fields)
ALTER TABLE dmt_beneficiaries
    ADD COLUMN IF NOT EXISTS bank_name          TEXT,
    ADD COLUMN IF NOT EXISTS beneficiary_mobile VARCHAR(15),
    ADD COLUMN IF NOT EXISTS updated_at         TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE dmt_bene_otp_sessions
    ALTER COLUMN name DROP NOT NULL,
    ALTER COLUMN bank_id DROP NOT NULL,
    ALTER COLUMN account_number_enc DROP NOT NULL,
    ALTER COLUMN ifsc DROP NOT NULL;

ALTER TABLE dmt_bene_otp_sessions
    ADD COLUMN IF NOT EXISTS action             TEXT NOT NULL DEFAULT 'REGISTER',
    ADD COLUMN IF NOT EXISTS beneficiary_id     UUID REFERENCES dmt_beneficiaries(id),
    ADD COLUMN IF NOT EXISTS provider_bene_id   TEXT,
    ADD COLUMN IF NOT EXISTS beneficiary_mobile VARCHAR(15),
    ADD COLUMN IF NOT EXISTS bank_name          TEXT,
    ADD COLUMN IF NOT EXISTS channel_id         UUID REFERENCES service_provider_channels(id),
    ADD COLUMN IF NOT EXISTS outlet_code        TEXT;

ALTER TABLE dmt_txn_otp_sessions
    ALTER COLUMN provider_bene_id DROP NOT NULL;

ALTER TABLE dmt_txn_otp_sessions
    ADD COLUMN IF NOT EXISTS channel_id    UUID REFERENCES service_provider_channels(id),
    ADD COLUMN IF NOT EXISTS outlet_code   TEXT,
    ADD COLUMN IF NOT EXISTS attempt_count INT NOT NULL DEFAULT 0;

ALTER TABLE dmt_transfers
    ADD COLUMN IF NOT EXISTS channel_id            UUID REFERENCES service_provider_channels(id),
    ADD COLUMN IF NOT EXISTS provider_code         TEXT,
    ADD COLUMN IF NOT EXISTS pipe_code             TEXT,
    ADD COLUMN IF NOT EXISTS outlet_code           TEXT,
    ADD COLUMN IF NOT EXISTS external_ref          TEXT,
    ADD COLUMN IF NOT EXISTS beneficiary_ifsc      VARCHAR(11),
    ADD COLUMN IF NOT EXISTS bank_name             TEXT,
    ADD COLUMN IF NOT EXISTS latitude              NUMERIC(10,7),
    ADD COLUMN IF NOT EXISTS longitude             NUMERIC(10,7),
    ADD COLUMN IF NOT EXISTS distance_from_shop_km NUMERIC(8,3),
    ADD COLUMN IF NOT EXISTS pool_amount           NUMERIC(18,4),
    ADD COLUMN IF NOT EXISTS last_status_polled_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS reconfirmed_at        TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS status_poll_count     INT NOT NULL DEFAULT 0;

CREATE UNIQUE INDEX IF NOT EXISTS uq_dmt_transfers_external_ref
    ON dmt_transfers (external_ref)
    WHERE external_ref IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_dmt_transfers_status_created
    ON dmt_transfers (status, created_at);
