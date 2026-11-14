-- DigiLocker encrypted document vault, manual face verification review, and personal/business address indexes.

CREATE TABLE digilocker_documents (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    journey_id    UUID NOT NULL REFERENCES onboarding_journeys(id),
    user_id       UUID REFERENCES users(id),
    client_id     TEXT NOT NULL,
    source        TEXT NOT NULL DEFAULT 'LIST_DOCUMENTS',
    file_id       TEXT NOT NULL,
    doc_type      TEXT NOT NULL,
    name          TEXT,
    issuer        TEXT,
    description   TEXT,
    file_type     TEXT,
    mime_type     TEXT,
    mandatory     BOOLEAN NOT NULL DEFAULT FALSE,
    storage_key   TEXT,
    size_bytes    BIGINT,
    sha256        TEXT,
    encrypted     BOOLEAN NOT NULL DEFAULT TRUE,
    fetch_status  TEXT NOT NULL DEFAULT 'PENDING',
    fetch_error   TEXT,
    review_status TEXT NOT NULL DEFAULT 'PENDING',
    reviewed_by   UUID REFERENCES users(id),
    reviewed_at   TIMESTAMPTZ,
    review_note   TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_digilocker_doc UNIQUE (client_id, file_id),
    CONSTRAINT chk_dl_fetch_status  CHECK (fetch_status IN ('PENDING', 'STORED', 'FAILED')),
    CONSTRAINT chk_dl_review_status CHECK (review_status IN ('PENDING', 'APPROVED', 'REJECTED'))
);

CREATE INDEX idx_digilocker_documents_user    ON digilocker_documents (user_id, created_at DESC);
CREATE INDEX idx_digilocker_documents_journey ON digilocker_documents (journey_id);

CREATE TABLE digilocker_document_access_log (
    id          BIGSERIAL PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES digilocker_documents(id),
    accessed_by UUID NOT NULL REFERENCES users(id),
    action      TEXT NOT NULL,
    ip          TEXT,
    user_agent  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_digilocker_doc_access_doc ON digilocker_document_access_log (document_id, created_at DESC);

ALTER TABLE agent_kyc_profiles
    ADD COLUMN face_review_status TEXT NOT NULL DEFAULT 'NOT_CAPTURED',
    ADD COLUMN face_reviewed_by   UUID REFERENCES users(id),
    ADD COLUMN face_reviewed_at   TIMESTAMPTZ,
    ADD COLUMN face_review_note   TEXT,
    ADD CONSTRAINT chk_face_review_status
        CHECK (face_review_status IN ('NOT_CAPTURED', 'PENDING', 'MATCHED', 'MISMATCH'));

UPDATE agent_kyc_profiles
   SET face_review_status = 'PENDING'
 WHERE selfie_key IS NOT NULL;

CREATE INDEX idx_kyc_personal_pincode ON agent_kyc_profiles ((personal_details->'permanentAddress'->>'pincode'));
CREATE INDEX idx_kyc_shop_pincode     ON agent_kyc_profiles ((shop_address->>'pincode'));

