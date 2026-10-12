CREATE TABLE commission_rules (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    txn_type               TEXT NOT NULL,
    slab_min               NUMERIC(18,4) NOT NULL,
    slab_max               NUMERIC(18,4) NOT NULL,
    total_rate_bp          INT NOT NULL,
    flat_fee               NUMERIC(18,4),
    retailer_share_pct     NUMERIC(5,2) NOT NULL,
    distributor_share_pct  NUMERIC(5,2) NOT NULL,
    platform_share_pct     NUMERIC(5,2) NOT NULL,
    commission_type        TEXT NOT NULL DEFAULT 'PERCENT',
    retailer_amount        NUMERIC(18,4),
    distributor_amount     NUMERIC(18,4),
    effective_from         TIMESTAMPTZ NOT NULL,
    effective_to           TIMESTAMPTZ,
    CONSTRAINT chk_split_100 CHECK (
        retailer_share_pct + distributor_share_pct + platform_share_pct = 100
    ),
    CONSTRAINT chk_fixed_amounts CHECK (
        commission_type <> 'FIXED'
        OR (
            retailer_amount IS NOT NULL
            AND distributor_amount IS NOT NULL
            AND retailer_amount >= 0
            AND distributor_amount >= 0
            AND flat_fee IS NOT NULL
            AND retailer_amount + distributor_amount <= flat_fee
        )
    )
);

CREATE TABLE commission_earnings (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id      UUID NOT NULL REFERENCES transactions(id),
    rule_id             UUID NOT NULL REFERENCES commission_rules(id),
    beneficiary_user    UUID NOT NULL REFERENCES users(id),
    role_in_split       TEXT NOT NULL,
    amount              NUMERIC(18,4) NOT NULL CHECK (amount >= 0),
    posting_group       UUID NOT NULL,
    retailer_user_id    UUID REFERENCES users(id),
    distributor_user_id UUID REFERENCES users(id),
    hub_id              UUID REFERENCES hubs(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_split UNIQUE (transaction_id, beneficiary_user)
);
CREATE INDEX idx_commission_earnings_created ON commission_earnings (created_at);
CREATE INDEX idx_commission_earnings_hub_created ON commission_earnings (hub_id, created_at);
CREATE INDEX idx_commission_earnings_retailer_created ON commission_earnings (retailer_user_id, created_at);
CREATE INDEX idx_commission_earnings_distributor_created ON commission_earnings (distributor_user_id, created_at);

CREATE TABLE finance_requests (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind                TEXT NOT NULL,
    retailer_user_id    UUID NOT NULL REFERENCES users(id),
    requested_by        UUID NOT NULL REFERENCES users(id),
    amount              NUMERIC(18,4) NOT NULL CHECK (amount > 0),
    utr                 TEXT NOT NULL,
    proof_key           TEXT NOT NULL,
    proof_filename      TEXT,
    proof_content_type  TEXT,
    remarks             TEXT,
    status              TEXT NOT NULL DEFAULT 'PENDING',
    auditor_user_id     UUID REFERENCES users(id),
    auditor_notes       TEXT,
    reviewed_at         TIMESTAMPTZ,
    wallet_txn_id       UUID REFERENCES transactions(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_finance_pending_activation
    ON finance_requests (retailer_user_id)
    WHERE kind = 'ACTIVATION' AND status = 'PENDING';
CREATE UNIQUE INDEX uq_finance_pending_wallet
    ON finance_requests (retailer_user_id)
    WHERE kind = 'WALLET_TOPUP' AND status = 'PENDING';
CREATE UNIQUE INDEX uq_finance_pending_withdraw
    ON finance_requests (retailer_user_id)
    WHERE kind = 'EARNINGS_WITHDRAW' AND status = 'PENDING';
CREATE INDEX idx_finance_requests_kind_status ON finance_requests (kind, status, created_at DESC);
CREATE INDEX idx_finance_requests_retailer ON finance_requests (retailer_user_id, created_at DESC);
