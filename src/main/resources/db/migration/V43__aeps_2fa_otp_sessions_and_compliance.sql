-- Separate "UPI Cashpoint" and "AePS Banking" service labels, fix AEPS -> CASHOUT_AEPS commission rule mapping,
-- add AePS Merchant 2FA sessions, >₹5,000 Transaction OTP challenge sessions, and AePS compliance columns.

-- 1. Rename platform_services display labels for independent Admin Settings toggles & descriptions
UPDATE platform_services
   SET name = 'UPI Cashpoint',
       description = 'Dynamic UPI QR cash-out counter settled directly to retailer wallet.',
       updated_at = now()
 WHERE code = 'UPI_CASHOUT';

UPDATE platform_services
   SET name = 'AePS Banking',
       description = 'Aadhaar-enabled Cash Withdrawal, Balance Enquiry, and Mini Statement via L1 RD scanner.',
       updated_at = now()
 WHERE code = 'AEPS';

-- Migrate any commission rule saved under 'AEPS' so it matches 'CASHOUT_AEPS' ledger settlements
UPDATE commission_rules
   SET txn_type = 'CASHOUT_AEPS'
 WHERE txn_type = 'AEPS'
   AND NOT EXISTS (
       SELECT 1 FROM commission_rules WHERE txn_type = 'CASHOUT_AEPS' AND effective_to IS NULL
   );

-- 2. Configure InstantPay Direct channel AePS limits and prevent unconfigured Emerald AePS interception
UPDATE service_provider_channels
   SET supports_aeps = TRUE,
       config_json = COALESCE(config_json, '{}'::jsonb)
                     || '{"aeps":{"min":100,"max":10000,"step":50,"cwOtpThreshold":5000}}'::jsonb,
       updated_at = now()
 WHERE provider_code = 'INSTANTPAY' AND pipe_code = 'DIRECT';

-- Default AEPS routing to PRIMARY_FALLBACK (deterministic retailer_priority 1 -> 2 -> 3)
-- so daily chargeable Merchant 2FA stays sticky to the retailer's primary AePS switch.
UPDATE service_routing_policies
   SET strategy = 'PRIMARY_FALLBACK',
       updated_at = now()
 WHERE service_code = 'AEPS';

-- 3. Merchant Daily / Per-Txn 2FA Sessions (tracks outletLoginStatus & outletLogin per IST calendar day)
CREATE TABLE IF NOT EXISTS aeps_merchant_2fa_sessions (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                   UUID NOT NULL REFERENCES users(id),
    channel_id                UUID NOT NULL REFERENCES service_provider_channels(id),
    outlet_code               TEXT,
    login_date_ist            DATE NOT NULL,
    status                    TEXT NOT NULL DEFAULT 'LOGIN_REQUIRED',
    aadhaar_last4             CHAR(4),
    is_txn_bio_login_required BOOLEAN NOT NULL DEFAULT FALSE,
    face_auth_available       BOOLEAN NOT NULL DEFAULT FALSE,
    iris_auth_available       BOOLEAN NOT NULL DEFAULT FALSE,
    provider_txn_id           TEXT,
    provider_status_code      TEXT,
    provider_message          TEXT,
    last_logged_in_at         TIMESTAMPTZ,
    last_checked_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_aeps_2fa_user_channel_day UNIQUE (user_id, channel_id, login_date_ist),
    CONSTRAINT chk_aeps_2fa_status CHECK (status IN ('LOGIN_REQUIRED', 'LOGGED_IN', 'FAILED', 'DORMANT_OUTLET'))
);

CREATE INDEX IF NOT EXISTS idx_aeps_2fa_user_date
    ON aeps_merchant_2fa_sessions (user_id, login_date_ist DESC);

-- 4. Ephemeral >₹5,000 AePS Cash Withdrawal OTP Challenge Sessions (10-minute TTL)
CREATE TABLE IF NOT EXISTS aeps_otp_sessions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL REFERENCES users(id),
    channel_id        UUID NOT NULL REFERENCES service_provider_channels(id),
    outlet_code       TEXT,
    bank_iin          TEXT NOT NULL,
    aadhaar_hmac      VARCHAR(64) NOT NULL,
    customer_mobile   VARCHAR(15) NOT NULL,
    amount            NUMERIC(18,2) NOT NULL,
    reference_key     TEXT,
    otp_required      BOOLEAN NOT NULL DEFAULT TRUE,
    validity_text     TEXT,
    status_code       TEXT,
    provider_message  TEXT,
    expires_at        TIMESTAMPTZ NOT NULL,
    consumed_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_aeps_otp_sessions_user_created
    ON aeps_otp_sessions (user_id, created_at DESC);

-- 5. Extend aeps_transactions for Cash Deposit, compliance telemetry, and /reports/txnStatus throttling
ALTER TABLE aeps_transactions
    DROP CONSTRAINT IF EXISTS chk_aeps_type;

ALTER TABLE aeps_transactions
    ADD CONSTRAINT chk_aeps_type
    CHECK (aeps_type IN ('CASH_WITHDRAWAL', 'BALANCE_ENQUIRY', 'MINI_STATEMENT', 'CASH_DEPOSIT', 'AADHAAR_PAY'));

ALTER TABLE aeps_transactions
    DROP CONSTRAINT IF EXISTS chk_aeps_status;

ALTER TABLE aeps_transactions
    ADD CONSTRAINT chk_aeps_status
    CHECK (status IN ('INITIATED', 'HOLD', 'PENDING', 'SUCCESS', 'FAILED', 'REVERSED'));

ALTER TABLE aeps_transactions
    ADD COLUMN IF NOT EXISTS aadhaar_hmac          VARCHAR(64),
    ADD COLUMN IF NOT EXISTS masked_account_number TEXT,
    ADD COLUMN IF NOT EXISTS is_onus               BOOLEAN,
    ADD COLUMN IF NOT EXISTS payable_value         NUMERIC(18,2),
    ADD COLUMN IF NOT EXISTS otp_reference_key     TEXT,
    ADD COLUMN IF NOT EXISTS biometric_mismatch    BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS distance_from_shop_km NUMERIC(8,3),
    ADD COLUMN IF NOT EXISTS last_status_polled_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_aeps_user_aadhaar_hmac_created
    ON aeps_transactions (user_id, aadhaar_hmac, created_at DESC)
    WHERE aadhaar_hmac IS NOT NULL;
