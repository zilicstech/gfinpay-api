-- Append-only request/response trace log for all outbound Emerald API calls and inbound Emerald webhooks.
-- Enables end-to-end tracing in local and production environments and sharing exact payloads with Emerald support.

CREATE TABLE IF NOT EXISTS emerald_api_logs (
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

CREATE INDEX IF NOT EXISTS idx_emerald_api_logs_created_at
    ON emerald_api_logs (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_emerald_api_logs_user_id
    ON emerald_api_logs (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_emerald_api_logs_outlet_code
    ON emerald_api_logs (outlet_code, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_emerald_api_logs_external_ref
    ON emerald_api_logs (external_ref);

CREATE INDEX IF NOT EXISTS idx_emerald_api_logs_provider_txn_id
    ON emerald_api_logs (provider_txn_id);

