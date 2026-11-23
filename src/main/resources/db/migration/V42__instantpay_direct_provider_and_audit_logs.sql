-- Direct InstantPay provider channel, signatory Aadhaar vault column,
-- biometric OTP registration columns, payout status poll throttle column,
-- and unified instantpay_api_logs audit trail.

-- 1. Seed Direct InstantPay channel (priority_order = 2; per-retailer priority 1-3 controls routing)
INSERT INTO service_provider_channels
    (id, channel_key, provider_code, pipe_code, display_label, retailer_label,
     biometric_required, supports_upi_cashout, supports_aeps, supports_payout,
     priority_order, active, config_json)
VALUES
    ('e1000000-0000-0000-0000-000000000042', 'ch_instantpay_direct', 'INSTANTPAY', 'DIRECT',
     'InstantPay · Direct', 'Merchant Banking Terminal',
     TRUE, TRUE, TRUE, TRUE,
     2, TRUE, '{"spkey":"WAP","gw":"JA","upiCashout":{"min":100,"max":5000,"step":100}}'::jsonb)
ON CONFLICT (provider_code, pipe_code) DO UPDATE
   SET display_label = EXCLUDED.display_label,
       supports_upi_cashout = TRUE,
       supports_payout = TRUE,
       biometric_required = TRUE,
       config_json = COALESCE(service_provider_channels.config_json, '{}'::jsonb) || EXCLUDED.config_json,
       updated_at = now();

-- 2. Allow encrypted 12-digit Aadhaar storage on business_signatories for COMPANY authorized signatories
ALTER TABLE business_signatories
    ADD COLUMN IF NOT EXISTS aadhaar_enc TEXT;

-- 3. Support InstantPay OutletRegistration OTP step and bank APPROVAL_PENDING states
ALTER TABLE biometric_kyc_sessions
    ADD COLUMN IF NOT EXISTS registration_ref_key VARCHAR(255),
    ADD COLUMN IF NOT EXISTS otp_verified_at TIMESTAMPTZ;

ALTER TABLE biometric_kyc_sessions
    DROP CONSTRAINT IF EXISTS chk_bio_session_status;

ALTER TABLE biometric_kyc_sessions
    ADD CONSTRAINT chk_bio_session_status
    CHECK (status IN ('AWAITING_OTP', 'INITIATED', 'APPROVAL_PENDING', 'SUCCESS', 'FAILED', 'EXPIRED'));

ALTER TABLE provider_pipe_activations
    DROP CONSTRAINT IF EXISTS chk_pipe_kyc_status;

ALTER TABLE provider_pipe_activations
    ADD CONSTRAINT chk_pipe_kyc_status
    CHECK (kyc_status IN ('NOT_REQUIRED', 'PENDING_BIOMETRIC', 'APPROVAL_PENDING', 'FULL_KYC', 'FAILED'));

-- 4. Track last /reports/txnStatus poll timestamp on payout_transfers (4-hour minimum interval)
ALTER TABLE payout_transfers
    ADD COLUMN IF NOT EXISTS last_status_polled_at TIMESTAMPTZ;

-- 5. Append-only audit log table for outbound InstantPay API calls and inbound InstantPay webhooks
CREATE TABLE IF NOT EXISTS instantpay_api_logs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    call_id             VARCHAR(64) NOT NULL,
    request_id          VARCHAR(64),
    direction           VARCHAR(24) NOT NULL,
    flow                VARCHAR(64) NOT NULL,
    http_method         VARCHAR(12) NOT NULL,
    url                 TEXT NOT NULL,
    endpoint_path       VARCHAR(255) NOT NULL,
    user_id             UUID REFERENCES users(id) ON DELETE SET NULL,
    outlet_code         VARCHAR(64),
    external_ref        VARCHAR(128),
    provider_txn_id     VARCHAR(128),
    request_headers     JSONB NOT NULL DEFAULT '{}'::jsonb,
    request_body        TEXT,
    http_status         INTEGER,
    provider_code       VARCHAR(64),
    provider_message    TEXT,
    response_headers    JSONB NOT NULL DEFAULT '{}'::jsonb,
    response_body       TEXT,
    error_message       TEXT,
    latency_ms          BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_instantpay_api_logs_created_at
    ON instantpay_api_logs (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_instantpay_api_logs_user_id
    ON instantpay_api_logs (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_instantpay_api_logs_external_ref
    ON instantpay_api_logs (external_ref);

CREATE INDEX IF NOT EXISTS idx_instantpay_api_logs_provider_txn_id
    ON instantpay_api_logs (provider_txn_id);

CREATE OR REPLACE VIEW provider_api_logs AS
SELECT id, call_id, request_id, direction, flow, http_method, url, endpoint_path,
       user_id, outlet_code, external_ref, provider_txn_id, provider_code, provider_message,
       http_status, latency_ms, created_at, 'EMERALD'::text AS rail
  FROM emerald_api_logs
UNION ALL
SELECT id, call_id, request_id, direction, flow, http_method, url, endpoint_path,
       user_id, outlet_code, external_ref, provider_txn_id, provider_code, provider_message,
       http_status, latency_ms, created_at, 'ISERVEU'::text AS rail
  FROM iserveu_api_logs
UNION ALL
SELECT id, call_id, request_id, direction, flow, http_method, url, endpoint_path,
       user_id, outlet_code, external_ref, provider_txn_id, provider_code, provider_message,
       http_status, latency_ms, created_at, 'INSTANTPAY'::text AS rail
  FROM instantpay_api_logs;
