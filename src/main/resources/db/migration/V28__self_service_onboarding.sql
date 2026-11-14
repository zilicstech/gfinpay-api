-- Self-service partner onboarding, re-verification, bank verification, and business review.

CREATE TABLE onboarding_invites (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    purpose            TEXT NOT NULL DEFAULT 'ONBOARD',
    role               TEXT NOT NULL,
    full_name          TEXT NOT NULL,
    mobile             VARCHAR(15) NOT NULL,
    email              TEXT,
    parent_id          UUID REFERENCES users(id),
    hub_id             UUID REFERENCES hubs(id),
    user_id            UUID REFERENCES users(id),
    token_hash         TEXT NOT NULL UNIQUE,
    session_hash       TEXT UNIQUE,
    session_expires_at TIMESTAMPTZ,
    status             TEXT NOT NULL DEFAULT 'SENT',
    expires_at         TIMESTAMPTZ NOT NULL,
    created_by         UUID NOT NULL REFERENCES users(id),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_invite_purpose CHECK (purpose IN ('ONBOARD', 'REVERIFY')),
    CONSTRAINT chk_invite_role CHECK (role IN ('RETAILER', 'MASTER_DISTRIBUTOR')),
    CONSTRAINT chk_invite_status CHECK (status IN ('SENT', 'IN_PROGRESS', 'NEEDS_REVIEW', 'COMPLETED', 'EXPIRED', 'REVOKED'))
);

CREATE UNIQUE INDEX uq_open_invite_mobile ON onboarding_invites (mobile)
    WHERE status IN ('SENT', 'IN_PROGRESS', 'NEEDS_REVIEW');
CREATE INDEX idx_onboarding_invites_creator ON onboarding_invites (created_by, created_at DESC);
CREATE INDEX idx_onboarding_invites_parent ON onboarding_invites (parent_id, created_at DESC);
CREATE INDEX idx_onboarding_invites_hub ON onboarding_invites (hub_id, created_at DESC);

CREATE TABLE onboarding_journeys (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invite_id        UUID REFERENCES onboarding_invites(id),
    user_id          UUID REFERENCES users(id),
    purpose          TEXT NOT NULL,
    current_step     TEXT NOT NULL DEFAULT 'LINK_SENT',
    status           TEXT NOT NULL DEFAULT 'IN_PROGRESS',
    identity         JSONB NOT NULL DEFAULT '{}'::jsonb,
    failure_counts   JSONB NOT NULL DEFAULT '{}'::jsonb,
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_activity_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ,
    CONSTRAINT chk_journey_purpose CHECK (purpose IN ('ONBOARD', 'REVERIFY')),
    CONSTRAINT chk_journey_status CHECK (status IN ('IN_PROGRESS', 'NEEDS_REVIEW', 'COMPLETED', 'ABANDONED', 'EXPIRED'))
);

CREATE INDEX idx_onboarding_journeys_invite ON onboarding_journeys (invite_id);
CREATE INDEX idx_onboarding_journeys_user ON onboarding_journeys (user_id, started_at DESC);
CREATE INDEX idx_onboarding_journeys_status ON onboarding_journeys (status, current_step);

CREATE TABLE onboarding_journey_events (
    id          BIGSERIAL PRIMARY KEY,
    journey_id  UUID REFERENCES onboarding_journeys(id),
    user_id     UUID REFERENCES users(id),
    step        TEXT NOT NULL,
    outcome     TEXT NOT NULL,
    detail      JSONB NOT NULL DEFAULT '{}'::jsonb,
    latitude    NUMERIC(9,6),
    longitude   NUMERIC(9,6),
    ip          TEXT,
    user_agent  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_journey_event_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'INFO'))
);

CREATE INDEX idx_journey_events_journey ON onboarding_journey_events (journey_id, created_at ASC);
CREATE INDEX idx_journey_events_user ON onboarding_journey_events (user_id, created_at DESC);

CREATE FUNCTION onboarding_journey_events_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'onboarding_journey_events is append-only';
END;
$$;

CREATE TRIGGER onboarding_journey_events_no_update
    BEFORE UPDATE OR DELETE ON onboarding_journey_events
    FOR EACH ROW EXECUTE FUNCTION onboarding_journey_events_immutable();

CREATE TABLE phone_otp_challenges (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    mobile       VARCHAR(15) NOT NULL,
    purpose      TEXT NOT NULL,
    subject_id   UUID NOT NULL,
    otp_hash     TEXT NOT NULL,
    attempts     INT NOT NULL DEFAULT 0,
    expires_at   TIMESTAMPTZ NOT NULL,
    verified_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_otp_purpose CHECK (purpose IN ('ONBOARD', 'REVERIFY', 'RESET'))
);

CREATE INDEX idx_otp_mobile_created ON phone_otp_challenges (mobile, created_at DESC);
CREATE INDEX idx_otp_subject_created ON phone_otp_challenges (subject_id, purpose, created_at DESC);

ALTER TABLE users
    ADD COLUMN pin_hash TEXT,
    ADD COLUMN failed_login_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN locked_until TIMESTAMPTZ;

ALTER TABLE agent_kyc_profiles
    ADD COLUMN identity_status TEXT NOT NULL DEFAULT 'NOT_VERIFIED',
    ADD COLUMN identity_verified_at TIMESTAMPTZ,
    ADD COLUMN identity_details JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN pan_last4 CHAR(4),
    ADD COLUMN selfie_key TEXT,
    ADD COLUMN bank_verification_status TEXT NOT NULL DEFAULT 'NOT_VERIFIED',
    ADD COLUMN business_reviewed_by UUID REFERENCES users(id),
    ADD COLUMN business_reviewed_at TIMESTAMPTZ,
    ADD COLUMN business_review_note TEXT;

ALTER TABLE agent_kyc_profiles
    DROP CONSTRAINT IF EXISTS chk_business_status;

ALTER TABLE agent_kyc_profiles
    ADD CONSTRAINT chk_identity_status CHECK (identity_status IN ('NOT_VERIFIED', 'VERIFIED', 'REVERIFY_REQUIRED')),
    ADD CONSTRAINT chk_bank_verification_status CHECK (bank_verification_status IN ('NOT_VERIFIED', 'VERIFIED')),
    ADD CONSTRAINT chk_business_status CHECK (business_status IN ('NOT_VERIFIED', 'DETAILS_CAPTURED', 'UNDER_REVIEW', 'BUSINESS_VERIFIED', 'REJECTED'));

ALTER TABLE retailer_bank_accounts
    ADD COLUMN verification_status TEXT NOT NULL DEFAULT 'NOT_VERIFIED',
    ADD COLUMN verified_name TEXT,
    ADD COLUMN name_match_score NUMERIC(5,2),
    ADD COLUMN provider_ref TEXT,
    ADD COLUMN verified_at TIMESTAMPTZ;

ALTER TABLE retailer_bank_accounts
    ADD CONSTRAINT chk_retailer_bank_verification_status CHECK (verification_status IN ('NOT_VERIFIED', 'VERIFIED', 'FAILED'));

