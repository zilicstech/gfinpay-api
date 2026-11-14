-- Simplified Business Registration (Individual vs Company) and Authorized Signatory KYC.

ALTER TABLE agent_kyc_profiles
    ADD COLUMN IF NOT EXISTS business_type        TEXT,
    ADD COLUMN IF NOT EXISTS work_latitude        NUMERIC(9,6),
    ADD COLUMN IF NOT EXISTS work_longitude       NUMERIC(9,6),
    ADD COLUMN IF NOT EXISTS work_location_source TEXT,
    ADD COLUMN IF NOT EXISTS work_address         JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE agent_kyc_profiles
    DROP CONSTRAINT IF EXISTS chk_business_status;

ALTER TABLE agent_kyc_profiles
    DROP CONSTRAINT IF EXISTS chk_business_type;

ALTER TABLE agent_kyc_profiles
    ADD CONSTRAINT chk_business_status CHECK (business_status IN (
        'NOT_VERIFIED',
        'DETAILS_CAPTURED',
        'AWAITING_SIGNATORY_KYC',
        'UNDER_REVIEW',
        'BUSINESS_VERIFIED',
        'REJECTED'
    )),
    ADD CONSTRAINT chk_business_type CHECK (business_type IS NULL OR business_type IN ('INDIVIDUAL', 'COMPANY'));

CREATE TABLE IF NOT EXISTS business_signatories (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id),
    cin                 TEXT NOT NULL,
    director_name       TEXT NOT NULL,
    din                 TEXT,
    is_self             BOOLEAN NOT NULL DEFAULT FALSE,
    mobile              VARCHAR(15),
    token_hash          TEXT UNIQUE,
    session_hash        TEXT UNIQUE,
    session_expires_at  TIMESTAMPTZ,
    journey_id          UUID REFERENCES onboarding_journeys(id),
    status              TEXT NOT NULL,
    verified_name       TEXT,
    name_match_score    INT,
    aadhaar_last4       CHAR(4),
    pan_last4           CHAR(4),
    pan_enc             TEXT,
    aadhaar_pan_linked  BOOLEAN NOT NULL DEFAULT FALSE,
    selfie_key          TEXT,
    face_review_status  TEXT NOT NULL DEFAULT 'PENDING',
    face_reviewed_by    UUID REFERENCES users(id),
    face_reviewed_at    TIMESTAMPTZ,
    face_review_note    TEXT,
    identity_details    JSONB NOT NULL DEFAULT '{}'::jsonb,
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    expires_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_signatory_status CHECK (status IN (
        'SELF_VERIFIED',
        'LINK_SENT',
        'IN_PROGRESS',
        'KYC_COMPLETED',
        'NEEDS_REVIEW',
        'EXPIRED',
        'REVOKED'
    )),
    CONSTRAINT chk_signatory_face CHECK (face_review_status IN ('PENDING', 'MATCHED', 'MISMATCH'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_active_signatory ON business_signatories (user_id) WHERE active;
CREATE INDEX IF NOT EXISTS idx_business_signatories_journey ON business_signatories (journey_id);

ALTER TABLE onboarding_journeys
    DROP CONSTRAINT IF EXISTS chk_journey_purpose;

ALTER TABLE onboarding_journeys
    ADD CONSTRAINT chk_journey_purpose CHECK (purpose IN ('ONBOARD', 'REVERIFY', 'SIGNATORY'));

ALTER TABLE phone_otp_challenges
    DROP CONSTRAINT IF EXISTS chk_otp_purpose;

ALTER TABLE phone_otp_challenges
    ADD CONSTRAINT chk_otp_purpose CHECK (purpose IN ('ONBOARD', 'REVERIFY', 'RESET', 'SIGNATORY'));

