-- Consolidated baseline: full platform schema (replaces historical V1–V15 deltas).
-- Lifecycle / type columns use TEXT (no PostgreSQL ENUMs) so values can evolve without migrations.

CREATE TABLE hubs (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        TEXT NOT NULL UNIQUE,
    name        TEXT NOT NULL,
    city        TEXT NOT NULL,
    state       TEXT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'ACTIVE',
    notes       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_type       TEXT NOT NULL,
    parent_id       UUID REFERENCES users(id),          -- MD for retailers, Super Admin for MDs
    hub_id          UUID REFERENCES hubs(id),
    created_by      UUID REFERENCES users(id),          -- admin who onboarded this user (admin-driven onboarding, no self-serve signup)
    full_name       TEXT NOT NULL,
    mobile          VARCHAR(15) NOT NULL UNIQUE,
    email           TEXT UNIQUE,
    password_hash   TEXT NOT NULL,                       -- argon2id
    mfa_secret_enc  TEXT,                                -- TOTP, KMS-encrypted
    code            TEXT,
    status          TEXT NOT NULL DEFAULT 'PENDING_KYC',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- hierarchy sanity: only SUPER_ADMIN may be parentless
    CONSTRAINT chk_hierarchy CHECK (
        (user_type = 'SUPER_ADMIN' AND parent_id IS NULL)
        OR (user_type <> 'SUPER_ADMIN' AND parent_id IS NOT NULL)
    )
);
CREATE INDEX idx_users_parent ON users(parent_id);
CREATE INDEX idx_users_hub ON users(hub_id);
CREATE UNIQUE INDEX uq_users_code ON users(code) WHERE code IS NOT NULL;

ALTER TABLE hubs ADD COLUMN created_by UUID REFERENCES users(id);
CREATE INDEX idx_hubs_created_by ON hubs(created_by);

CREATE TABLE roles (
    id          SMALLSERIAL PRIMARY KEY,
    code        TEXT NOT NULL UNIQUE,        -- e.g. RETAILER_STANDARD, MD_ADMIN
    description TEXT
);

CREATE TABLE permissions (
    id          SERIAL PRIMARY KEY,
    code        TEXT NOT NULL UNIQUE,        -- e.g. dmt.initiate, wallet.topup, reports.view
    description TEXT
);

CREATE TABLE role_permissions (
    role_id       SMALLINT NOT NULL REFERENCES roles(id),
    permission_id INT NOT NULL REFERENCES permissions(id),
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id),
    role_id SMALLINT NOT NULL REFERENCES roles(id),
    PRIMARY KEY (user_id, role_id)
);
CREATE TABLE agent_kyc_profiles (
    user_id             UUID PRIMARY KEY REFERENCES users(id),
    aadhaar_ref_key     TEXT,            -- UIDAI reference id; raw Aadhaar never stored
    aadhaar_last4       CHAR(4),
    aadhaar_verified_at TIMESTAMPTZ,
    pan_enc             TEXT,            -- KMS envelope-encrypted
    pan_verified_at     TIMESTAMPTZ,     -- NSDL verification timestamp
    liveness_score      NUMERIC(5,2),
    liveness_passed_at  TIMESTAMPTZ,
    shop_name           TEXT,
    shop_address        JSONB,
    gstin               VARCHAR(15),
    gstin_verified_at   TIMESTAMPTZ,
    ovd_type            TEXT,
    kyc_status          TEXT NOT NULL DEFAULT 'NOT_STARTED',
    rejection_reason    TEXT,
    documents           JSONB NOT NULL DEFAULT '[]',   -- [{type, gcs_uri, uploaded_at}]
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE agent_ekyc_challenges (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    otp_hash    TEXT NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    verified_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_agent_ekyc_challenges ON agent_ekyc_challenges(user_id, created_at DESC);

CREATE TABLE wallets (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID NOT NULL UNIQUE REFERENCES users(id),
    available_balance  NUMERIC(18,4) NOT NULL DEFAULT 0 CHECK (available_balance >= 0),
    hold_balance       NUMERIC(18,4) NOT NULL DEFAULT 0 CHECK (hold_balance >= 0),
    version            BIGINT NOT NULL DEFAULT 0,       -- optimistic lock
    status             TEXT NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | FROZEN
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ledger_accounts (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_type TEXT NOT NULL,
    wallet_id    UUID REFERENCES wallets(id),   -- NULL for platform-level accounts
    currency     CHAR(3) NOT NULL DEFAULT 'INR',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_wallet_account UNIQUE (wallet_id, account_type)
);

-- APPEND-ONLY. No UPDATE/DELETE ever. Corrections = reversing entries.
CREATE TABLE ledger_entries (
    id             BIGSERIAL PRIMARY KEY,
    transaction_id UUID NOT NULL,               -- FK added after transactions table
    posting_group  UUID NOT NULL,               -- entries that must balance together
    account_id     UUID NOT NULL REFERENCES ledger_accounts(id),
    direction      TEXT NOT NULL,
    amount         NUMERIC(18,4) NOT NULL CHECK (amount > 0),
    narration      TEXT NOT NULL,               -- e.g. 'DMT hold', 'commission: retailer'
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ledger_txn   ON ledger_entries(transaction_id);
CREATE INDEX idx_ledger_acct  ON ledger_entries(account_id, created_at);

-- Immutability guard
CREATE OR REPLACE FUNCTION forbid_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();
-- plus: REVOKE UPDATE, DELETE ON ledger_entries FROM app_role;

-- Balance guard: each posting_group must sum to zero at commit time
CREATE OR REPLACE FUNCTION assert_posting_balanced() RETURNS trigger AS $$
DECLARE diff NUMERIC(18,4);
BEGIN
    SELECT COALESCE(SUM(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END), 0)
      INTO diff
      FROM ledger_entries
     WHERE posting_group = NEW.posting_group;
    IF diff <> 0 THEN
        RAISE EXCEPTION 'unbalanced posting_group %: off by %', NEW.posting_group, diff;
    END IF;
    RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_posting_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED               -- checked at COMMIT, so groups insert atomically
    FOR EACH ROW EXECUTE FUNCTION assert_posting_balanced();
CREATE TABLE transactions (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    txn_type           TEXT NOT NULL,
    state              TEXT NOT NULL DEFAULT 'INITIATED',
    agent_user_id      UUID NOT NULL REFERENCES users(id),
    wallet_id          UUID NOT NULL REFERENCES wallets(id),
    amount             NUMERIC(18,4) NOT NULL CHECK (amount > 0),
    fee                NUMERIC(18,4) NOT NULL DEFAULT 0,
    -- service-specific references
    dmt_sender_id      UUID,                    -- FK added below
    beneficiary_id     UUID,
    customer_ref       JSONB,                   -- masked customer descriptors (name, mobile)
    partner_code       TEXT,                    -- which external provider handles it
    partner_ref        TEXT,                    -- partner's transaction id (RRN/UTR)
    idempotency_key_id UUID,                    -- FK added below
    failure_reason     TEXT,
    state_history      JSONB NOT NULL DEFAULT '[]',  -- [{state, at, actor}]
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_txn_agent_time ON transactions(agent_user_id, created_at DESC);
CREATE INDEX idx_txn_state ON transactions(state) WHERE state IN ('HOLD','PENDING');
CREATE UNIQUE INDEX uq_txn_partner_ref ON transactions(partner_code, partner_ref)
    WHERE partner_ref IS NOT NULL;

ALTER TABLE ledger_entries
    ADD CONSTRAINT fk_ledger_txn FOREIGN KEY (transaction_id) REFERENCES transactions(id);
CREATE TABLE idempotency_keys (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idem_key       TEXT NOT NULL,
    agent_user_id  UUID NOT NULL REFERENCES users(id),
    request_hash   TEXT NOT NULL,               -- SHA-256 of canonicalized body
    response_code  SMALLINT,
    response_body  JSONB,                       -- stored for replay
    status         TEXT NOT NULL DEFAULT 'IN_FLIGHT', -- IN_FLIGHT | COMPLETED
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ NOT NULL,        -- e.g. 48h retention
    CONSTRAINT uq_idem UNIQUE (agent_user_id, idem_key)
);

ALTER TABLE transactions
    ADD CONSTRAINT fk_txn_idem FOREIGN KEY (idempotency_key_id) REFERENCES idempotency_keys(id);

CREATE TABLE outbox_events (
    id             BIGSERIAL PRIMARY KEY,
    aggregate_type TEXT NOT NULL,               -- 'TRANSACTION'
    aggregate_id   UUID NOT NULL,
    event_type     TEXT NOT NULL,               -- 'DMT_PAYOUT_REQUESTED', ...
    payload        JSONB NOT NULL,
    status         TEXT NOT NULL DEFAULT 'NEW', -- NEW | DISPATCHED | FAILED | DEAD
    attempts       SMALLINT NOT NULL DEFAULT 0,
    next_retry_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    dispatched_at  TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_outbox_pick ON outbox_events(next_retry_at) WHERE status IN ('NEW','FAILED');

CREATE TABLE webhook_inbox (
    id                BIGSERIAL PRIMARY KEY,
    provider          TEXT NOT NULL,
    external_event_id TEXT NOT NULL,            -- provider's event id → dedupe
    payload           JSONB NOT NULL,
    signature_valid   BOOLEAN NOT NULL,
    status            TEXT NOT NULL DEFAULT 'RECEIVED', -- RECEIVED | PROCESSED | FAILED | IGNORED
    transaction_id    UUID REFERENCES transactions(id),
    received_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at      TIMESTAMPTZ,
    CONSTRAINT uq_webhook_event UNIQUE (provider, external_event_id)
);

-- ShedLock: guarantees each Spring @Scheduled job (outbox dispatcher, status
-- poller, EOD recon, idempotency cleanup) runs on exactly one Cloud Run
-- instance at a time when the service scales out.
CREATE TABLE shedlock (
    name       VARCHAR(64) PRIMARY KEY,     -- job name
    lock_until TIMESTAMPTZ NOT NULL,
    locked_at  TIMESTAMPTZ NOT NULL,
    locked_by  VARCHAR(255) NOT NULL        -- instance identifier
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

CREATE TABLE dmt_beneficiaries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sender_id       UUID NOT NULL REFERENCES dmt_senders(id),
    name            TEXT NOT NULL,
    account_number_enc TEXT NOT NULL,           -- encrypted
    account_last4   CHAR(4) NOT NULL,
    ifsc            VARCHAR(11) NOT NULL,
    partner_bene_ref TEXT,
    verified_at     TIMESTAMPTZ,                -- penny-drop verification
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE transactions
    ADD CONSTRAINT fk_txn_sender FOREIGN KEY (dmt_sender_id) REFERENCES dmt_senders(id),
    ADD CONSTRAINT fk_txn_bene   FOREIGN KEY (beneficiary_id) REFERENCES dmt_beneficiaries(id);

CREATE TABLE cashout_sessions (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id   UUID NOT NULL UNIQUE REFERENCES transactions(id),
    agent_user_id    UUID NOT NULL REFERENCES users(id),
    channel          TEXT NOT NULL,
    amount           NUMERIC(18,4) NOT NULL CHECK (amount > 0),
    -- UPI_QR channel
    upi_qr_payload   TEXT,                     -- dynamic QR string shown on agent screen
    upi_collect_ref  TEXT,                     -- PSP reference for the collect/pay
    -- AEPS channel
    aadhaar_last4    CHAR(4),                  -- customer Aadhaar last-4 only
    aeps_bank_iin    VARCHAR(6),               -- customer's issuing bank IIN
    aeps_rrn         TEXT,                     -- retrieval reference number
    status           TEXT NOT NULL DEFAULT 'AWAITING_PAYMENT',
    expires_at       TIMESTAMPTZ NOT NULL,     -- dynamic QR validity window
    paid_at          TIMESTAMPTZ,
    dispensed_at     TIMESTAMPTZ,
    customer_name    TEXT,
    customer_mobile  VARCHAR(15),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_cashout_open ON cashout_sessions(expires_at)
    WHERE status = 'AWAITING_PAYMENT';

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
    operator_id     INT NOT NULL REFERENCES bill_operators(id),
    consumer_number TEXT NOT NULL,
    customer_mobile VARCHAR(15),
    customer_name   TEXT,
    bill_amount     NUMERIC(18,4),
    due_date        DATE,
    bill_fetch      JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_bill_payments_txn ON bill_payments(transaction_id);

CREATE TABLE recharge_orders (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id  UUID NOT NULL REFERENCES transactions(id),
    operator_id     INT NOT NULL REFERENCES bill_operators(id),
    mobile          VARCHAR(15) NOT NULL,
    plan_name       TEXT,
    amount          NUMERIC(18,4) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_recharge_orders_txn ON recharge_orders(transaction_id);

CREATE TABLE platform_services (
    code        TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    description TEXT NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order  INT NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE admin_report_runs (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    report_type TEXT NOT NULL,
    title       TEXT NOT NULL,
    payload     JSONB NOT NULL DEFAULT '{}',
    created_by  UUID REFERENCES users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE hub_admin_assignments (
    hub_id      UUID NOT NULL REFERENCES hubs(id) ON DELETE CASCADE,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (hub_id, user_id)
);
CREATE INDEX idx_hub_admin_user ON hub_admin_assignments(user_id);
CREATE TABLE commission_rules (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    txn_type      TEXT NOT NULL,
    slab_min      NUMERIC(18,4) NOT NULL,
    slab_max      NUMERIC(18,4) NOT NULL,
    total_rate_bp INT NOT NULL,                 -- basis points of amount (or use flat_fee)
    flat_fee      NUMERIC(18,4),
    retailer_share_pct    NUMERIC(5,2) NOT NULL,
    distributor_share_pct NUMERIC(5,2) NOT NULL,
    platform_share_pct    NUMERIC(5,2) NOT NULL,
    commission_type   TEXT NOT NULL DEFAULT 'PERCENT',
    retailer_amount   NUMERIC(18,4),
    distributor_amount NUMERIC(18,4),
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to   TIMESTAMPTZ,
    CONSTRAINT chk_split_100 CHECK (
        retailer_share_pct + distributor_share_pct + platform_share_pct = 100
    ),
    CONSTRAINT chk_commission_type CHECK (commission_type IN ('FIXED', 'PERCENT')),
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

-- Retailer-level pricing: admin can override the global markup/split for a
-- specific retailer. Resolution order at transaction time:
--   1. commission_rule_overrides (retailer match, effective window)
--   2. commission_rules (global slab)
CREATE TABLE commission_rule_overrides (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_id          UUID NOT NULL REFERENCES commission_rules(id),
    retailer_user_id UUID NOT NULL REFERENCES users(id),
    total_rate_bp    INT,                       -- NULL = inherit from global rule
    flat_fee         NUMERIC(18,4),             -- NULL = inherit
    retailer_share_pct    NUMERIC(5,2),         -- NULL = inherit
    distributor_share_pct NUMERIC(5,2),
    platform_share_pct    NUMERIC(5,2),
    effective_from   TIMESTAMPTZ NOT NULL,
    effective_to     TIMESTAMPTZ,
    created_by       UUID NOT NULL REFERENCES users(id),   -- configuring admin
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_override UNIQUE (rule_id, retailer_user_id, effective_from),
    -- if any share is overridden, all three must be, and must total 100
    CONSTRAINT chk_override_split CHECK (
        (retailer_share_pct IS NULL AND distributor_share_pct IS NULL AND platform_share_pct IS NULL)
        OR (retailer_share_pct + distributor_share_pct + platform_share_pct = 100)
    )
);
CREATE INDEX idx_override_retailer ON commission_rule_overrides(retailer_user_id);

CREATE TABLE commission_earnings (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id      UUID NOT NULL REFERENCES transactions(id),
    rule_id             UUID NOT NULL REFERENCES commission_rules(id),
    beneficiary_user    UUID NOT NULL REFERENCES users(id),
    role_in_split       TEXT NOT NULL,             -- RETAILER | DISTRIBUTOR | PLATFORM
    amount              NUMERIC(18,4) NOT NULL CHECK (amount >= 0),
    posting_group       UUID NOT NULL,             -- links to the ledger entries that paid it
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
CREATE TABLE customers (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    retailer_user_id UUID NOT NULL REFERENCES users(id),
    full_name        TEXT NOT NULL,
    mobile           VARCHAR(15) NOT NULL,
    email            TEXT,
    city             TEXT,
    state            TEXT,
    pincode          VARCHAR(10),
    employment_type  TEXT,
    monthly_income   NUMERIC(18,4),
    ekyc_status      TEXT NOT NULL DEFAULT 'NOT_STARTED',
    ekyc_verified_at TIMESTAMPTZ,
    ovd_type         TEXT,
    ovd_last4        VARCHAR(4),
    created_by_role  TEXT NOT NULL,
    created_by_code  TEXT NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_customer_mobile UNIQUE (mobile),
    CONSTRAINT chk_customer_created_by_role CHECK (created_by_role IN ('DISTRIBUTOR', 'RETAILER', 'ADMIN'))
);

CREATE TABLE customer_ekyc_challenges (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id UUID NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    otp_hash    TEXT NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    verified_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_customer_ekyc_challenges ON customer_ekyc_challenges(customer_id, created_at DESC);

CREATE TABLE catalog_categories (
    code              TEXT PRIMARY KEY,
    name              TEXT NOT NULL,
    sort_order        INT NOT NULL DEFAULT 100,
    eligibility_mode  TEXT NOT NULL DEFAULT 'MANUAL',
    payout_hint       TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE catalog_items (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code              TEXT NOT NULL UNIQUE,
    category_code     TEXT NOT NULL REFERENCES catalog_categories(code),
    name              TEXT NOT NULL,
    provider          TEXT NOT NULL DEFAULT 'PAYSPRINT',
    rail              TEXT NOT NULL,
    external_product  TEXT,
    product_key       TEXT NOT NULL,
    min_budget        NUMERIC(18,2),
    apply_url         TEXT,
    active            BOOLEAN NOT NULL DEFAULT TRUE,
    payout_hint       TEXT,
    sort_order        INT NOT NULL DEFAULT 100,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_catalog_items_product_key ON catalog_items (category_code, product_key);

CREATE TABLE customer_category_eligibility (
    customer_id    UUID NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    category_code  TEXT NOT NULL REFERENCES catalog_categories(code),
    status         TEXT NOT NULL DEFAULT 'PENDING',
    reason         TEXT,
    checked_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (customer_id, category_code)
);

CREATE TABLE sales_leads (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id       UUID NOT NULL REFERENCES customers(id),
    catalog_item_id   UUID NOT NULL REFERENCES catalog_items(id),
    retailer_user_id  UUID NOT NULL REFERENCES users(id),
    state             TEXT NOT NULL DEFAULT 'LINK_CREATED',
    budget            NUMERIC(18,2),
    sale_type         TEXT NOT NULL,
    sale_provider     TEXT NOT NULL,
    distributor_user_id UUID REFERENCES users(id),
    hub_id            UUID REFERENCES hubs(id),
    provider_refid    TEXT,
    link_token        TEXT NOT NULL UNIQUE,
    payment_link_url  TEXT,
    encdata           TEXT,
    link_opened_at    TIMESTAMPTZ,
    state_history     JSONB NOT NULL DEFAULT '[]',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_sale_provider CHECK (sale_provider IN ('ZET', 'PAYSPRINT', 'GROWMORE', 'OTHERS'))
);
CREATE UNIQUE INDEX uq_sales_open_lead
    ON sales_leads (customer_id, catalog_item_id)
 WHERE state IN ('LINK_CREATED', 'OPENED', 'IN_PROGRESS');
CREATE INDEX idx_sales_leads_retailer ON sales_leads (retailer_user_id, created_at DESC);
CREATE INDEX idx_sales_leads_refid ON sales_leads (provider_refid) WHERE provider_refid IS NOT NULL;
CREATE INDEX idx_sales_leads_report ON sales_leads (created_at DESC, sale_type, sale_provider, state);
CREATE INDEX idx_sales_leads_distributor ON sales_leads (distributor_user_id, created_at DESC);
CREATE INDEX idx_sales_leads_hub_created ON sales_leads (hub_id, created_at DESC);

CREATE TABLE recon_batches (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider      TEXT NOT NULL,
    business_date DATE NOT NULL,
    mis_file_uri  TEXT NOT NULL,                -- GCS path
    total_rows    INT,
    matched_rows  INT,
    status        TEXT NOT NULL DEFAULT 'RUNNING', -- RUNNING | COMPLETED | COMPLETED_WITH_MISMATCHES
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    CONSTRAINT uq_recon_day UNIQUE (provider, business_date)
);

CREATE TABLE fd_providers (
    code                 TEXT PRIMARY KEY,
    name                 TEXT NOT NULL,
    enabled              BOOLEAN NOT NULL DEFAULT TRUE,
    novu                 BOOLEAN NOT NULL DEFAULT FALSE,
    fallback_rank        INT NOT NULL DEFAULT 100,
    commission_txn_type  TEXT NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE fd_budget_bands (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slab_min            NUMERIC(18, 2) NOT NULL,
    slab_max            NUMERIC(18, 2) NOT NULL,
    preferred_provider  TEXT NOT NULL REFERENCES fd_providers (code),
    preference_rank     INT NOT NULL DEFAULT 1,
    CONSTRAINT chk_fd_band_range CHECK (slab_min <= slab_max)
);
CREATE INDEX idx_fd_budget_bands_range ON fd_budget_bands (slab_min, slab_max);
CREATE UNIQUE INDEX uq_fd_band_provider ON fd_budget_bands (preferred_provider);
CREATE UNIQUE INDEX uq_fd_band_rank ON fd_budget_bands (preference_rank);

CREATE TABLE fd_commission_tiers (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_code           TEXT NOT NULL REFERENCES fd_providers (code),
    cards_min               INT NOT NULL DEFAULT 0,
    cards_max               INT NOT NULL DEFAULT 999999,
    fixed_amount_per_card   NUMERIC(18, 2) NOT NULL CHECK (fixed_amount_per_card >= 0),
    retailer_share_pct      NUMERIC(5, 2) NOT NULL,
    distributor_share_pct   NUMERIC(5, 2) NOT NULL,
    platform_share_pct      NUMERIC(5, 2) NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_fd_tier_split CHECK (
        retailer_share_pct + distributor_share_pct + platform_share_pct = 100
    ),
    CONSTRAINT chk_fd_tier_cards CHECK (cards_min <= cards_max)
);
CREATE INDEX idx_fd_commission_tiers_provider ON fd_commission_tiers (provider_code, cards_min);

CREATE TABLE recon_mismatches (
    id             BIGSERIAL PRIMARY KEY,
    batch_id       UUID NOT NULL REFERENCES recon_batches(id),
    mismatch_type  TEXT NOT NULL,   -- MISSING_INTERNAL | MISSING_AT_PARTNER | AMOUNT_MISMATCH | STATE_MISMATCH
    transaction_id UUID REFERENCES transactions(id),
    partner_ref    TEXT,
    details        JSONB NOT NULL,
    resolution     TEXT,            -- set by ops after manual/automated fix
    resolved_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
