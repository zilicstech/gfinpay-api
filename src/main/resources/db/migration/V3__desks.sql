CREATE TABLE customer_otp_challenges (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sender_id   UUID NOT NULL REFERENCES dmt_senders(id) ON DELETE CASCADE,
    purpose     TEXT NOT NULL,
    otp_hash    TEXT NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    verified_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_otp_sender_purpose ON customer_otp_challenges(sender_id, purpose, created_at DESC);

CREATE TABLE bill_operators (
    id              SERIAL PRIMARY KEY,
    partner_id      TEXT NOT NULL UNIQUE,
    name            TEXT NOT NULL,
    category        TEXT NOT NULL,
    txn_type        TEXT NOT NULL,
    view_bill       BOOLEAN NOT NULL DEFAULT TRUE,
    consumer_label  TEXT NOT NULL DEFAULT 'Consumer number',
    regex           TEXT,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX idx_bill_operators_cat ON bill_operators(category, enabled);

CREATE TABLE bill_payments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id  UUID NOT NULL REFERENCES transactions(id),
    operator_id     INT NOT NULL,
    category_id     INT,
    consumer_number TEXT NOT NULL,
    customer_mobile VARCHAR(15),
    customer_name   TEXT,
    bill_amount     NUMERIC(18,4),
    due_date        DATE,
    bill_fetch      JSONB,
    partner_txn_id  TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_bill_payments_txn ON bill_payments(transaction_id);

CREATE TABLE cashout_sessions (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id   UUID NOT NULL UNIQUE REFERENCES transactions(id),
    agent_user_id    UUID NOT NULL REFERENCES users(id),
    channel          TEXT NOT NULL,
    amount           NUMERIC(18,4) NOT NULL CHECK (amount > 0),
    upi_qr_payload   TEXT,
    upi_collect_ref  TEXT,
    aadhaar_last4    CHAR(4),
    aeps_bank_iin    VARCHAR(6),
    aeps_rrn         TEXT,
    status           TEXT NOT NULL DEFAULT 'AWAITING_PAYMENT',
    expires_at       TIMESTAMPTZ NOT NULL,
    paid_at          TIMESTAMPTZ,
    dispensed_at     TIMESTAMPTZ,
    customer_name    TEXT,
    customer_mobile  VARCHAR(15),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_cashout_open ON cashout_sessions(expires_at)
    WHERE status = 'AWAITING_PAYMENT';
