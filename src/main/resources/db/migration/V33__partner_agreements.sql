-- Partner Master Service Agreement (MSA) generation, release, and signing lifecycle.

CREATE SEQUENCE IF NOT EXISTS partner_agreement_no_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS partner_agreements (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agreement_no           TEXT NOT NULL UNIQUE,
    user_id                UUID NOT NULL REFERENCES users(id),
    agreement_type         TEXT NOT NULL,
    template_version       TEXT NOT NULL,
    status                 TEXT NOT NULL,
    party_snapshot         JSONB NOT NULL DEFAULT '{}'::jsonb,
    onboarding_fee         NUMERIC(12,2),
    unsigned_key           TEXT NOT NULL,
    unsigned_sha256        TEXT NOT NULL,
    page_count             INT NOT NULL DEFAULT 1,
    signatory_id           UUID REFERENCES business_signatories(id),
    signatory_name         TEXT NOT NULL,
    signatory_mobile       VARCHAR(15),
    signatory_is_self      BOOLEAN NOT NULL DEFAULT TRUE,
    generated_by           UUID NOT NULL REFERENCES users(id),
    generated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_by            UUID REFERENCES users(id),
    released_at            TIMESTAMPTZ,
    sign_method            TEXT,
    esign_mode             TEXT,
    esign_file_id          TEXT,
    esign_client_id        TEXT,
    esign_url              TEXT,
    esign_initiated_at     TIMESTAMPTZ,
    esign_provider_status  TEXT,
    esign_last_checked_at  TIMESTAMPTZ,
    esign_details          JSONB NOT NULL DEFAULT '{}'::jsonb,
    signed_key             TEXT,
    signed_sha256          TEXT,
    signed_filename        TEXT,
    signed_content_type    TEXT,
    signed_at              TIMESTAMPTZ,
    sign_latitude          NUMERIC(9,6),
    sign_longitude         NUMERIC(9,6),
    reviewed_by            UUID REFERENCES users(id),
    reviewed_at            TIMESTAMPTZ,
    review_note            TEXT,
    voided_by              UUID REFERENCES users(id),
    voided_at              TIMESTAMPTZ,
    void_reason            TEXT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_agreement_type CHECK (agreement_type IN ('RETAILER_MSA', 'DISTRIBUTOR_MSA')),
    CONSTRAINT chk_agreement_row_status CHECK (status IN (
        'DRAFT',
        'RELEASED',
        'ESIGN_PENDING',
        'UNDER_REVIEW',
        'SIGNED',
        'VOID'
    )),
    CONSTRAINT chk_sign_method CHECK (sign_method IS NULL OR sign_method IN ('ESIGN', 'UPLOAD'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_partner_agreement_live
    ON partner_agreements (user_id)
    WHERE status <> 'VOID';

CREATE INDEX IF NOT EXISTS idx_partner_agreements_esign
    ON partner_agreements (esign_client_id);

CREATE TABLE IF NOT EXISTS partner_agreement_events (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agreement_id  UUID NOT NULL REFERENCES partner_agreements(id),
    action        TEXT NOT NULL,
    actor_id      UUID REFERENCES users(id),
    note          TEXT,
    payload       JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agreement_events
    ON partner_agreement_events (agreement_id, created_at);

ALTER TABLE agent_kyc_profiles
    DROP CONSTRAINT IF EXISTS chk_agreement_status;

-- Reset legacy mock signatures (where no actual agreement document exists)
UPDATE agent_kyc_profiles
   SET agreement_status = 'NOT_SIGNED',
       agreement_details = '{}'::jsonb
 WHERE agreement_details->>'providerRef' LIKE 'MOCK-ESIGN-%'
    OR (agreement_status = 'SIGNED'
        AND NOT EXISTS (
            SELECT 1 FROM partner_agreements pa
             WHERE pa.user_id = agent_kyc_profiles.user_id
               AND pa.status = 'SIGNED'
        ));

ALTER TABLE agent_kyc_profiles
    ADD CONSTRAINT chk_agreement_status CHECK (agreement_status IN (
        'NOT_SIGNED',
        'DRAFT',
        'RELEASED',
        'ESIGN_PENDING',
        'UNDER_REVIEW',
        'SIGNED'
    ));

