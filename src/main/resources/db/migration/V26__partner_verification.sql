-- Personal and business verification. Does not change users.status, wallets, finance requests, or kyc_status.

ALTER TABLE agent_kyc_profiles
    ADD COLUMN personal_status TEXT NOT NULL DEFAULT 'NOT_VERIFIED',
    ADD COLUMN business_status TEXT NOT NULL DEFAULT 'NOT_VERIFIED',
    ADD COLUMN agreement_status TEXT NOT NULL DEFAULT 'NOT_SIGNED',
    ADD COLUMN personal_details JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN business_details JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN agreement_details JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE agent_kyc_profiles
    ADD CONSTRAINT chk_personal_status CHECK (personal_status IN ('NOT_VERIFIED', 'PERSONAL_VERIFIED')),
    ADD CONSTRAINT chk_business_status CHECK (business_status IN ('NOT_VERIFIED', 'DETAILS_CAPTURED', 'BUSINESS_VERIFIED')),
    ADD CONSTRAINT chk_agreement_status CHECK (agreement_status IN ('NOT_SIGNED', 'SIGNED'));

INSERT INTO agent_kyc_profiles (user_id, kyc_status)
SELECT u.id, 'PENDING'
  FROM users u
 WHERE u.user_type IN ('RETAILER', 'MASTER_DISTRIBUTOR')
   AND NOT EXISTS (SELECT 1 FROM agent_kyc_profiles k WHERE k.user_id = u.id);

CREATE TABLE partner_verification_events (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES users(id),
    step         TEXT NOT NULL,
    latitude     NUMERIC(9,6) NOT NULL,
    longitude    NUMERIC(9,6) NOT NULL,
    provider_ref TEXT,
    payload      JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_verification_step CHECK (step IN ('PERSONAL', 'BUSINESS', 'AGREEMENT'))
);
CREATE INDEX idx_partner_verification_user ON partner_verification_events (user_id, created_at DESC);

CREATE TABLE partner_business_documents (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES users(id),
    doc_type      TEXT NOT NULL,
    storage_key   TEXT NOT NULL,
    filename      TEXT,
    content_type  TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_partner_business_documents_user ON partner_business_documents (user_id, created_at DESC);

INSERT INTO platform_services (code, name, description, enabled, sort_order)
SELECT 'PAYOUT', 'Payout', 'Send a payout once the provider is connected.', TRUE, 11
 WHERE NOT EXISTS (SELECT 1 FROM platform_services WHERE code = 'PAYOUT');

UPDATE platform_services
   SET enabled = FALSE, updated_at = now()
 WHERE code IN ('LEAD_GEN', 'CREDIT_CARD', 'INSTANT_LOAN', 'PERSONAL_LOAN', 'BUSINESS_LOAN', 'SAVINGS');

UPDATE fd_providers
   SET enabled = FALSE, updated_at = now();
