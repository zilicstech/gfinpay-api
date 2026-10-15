-- Onboarding stays on kyc_status. Bank eKYC is generic on the profile and PaySprint-specific in its own table.

UPDATE agent_kyc_profiles SET kyc_status = 'COMPLETED' WHERE kyc_status = 'VERIFIED';
UPDATE agent_kyc_profiles SET kyc_status = 'PENDING' WHERE kyc_status = 'NOT_STARTED';

ALTER TABLE agent_kyc_profiles ALTER COLUMN kyc_status SET DEFAULT 'PENDING';
ALTER TABLE agent_kyc_profiles ADD COLUMN bank_status TEXT NOT NULL DEFAULT 'PENDING';

CREATE TABLE paysprint_bank_ekyc (
    user_id            UUID NOT NULL REFERENCES users(id),
    bank_name          TEXT NOT NULL,
    status             TEXT NOT NULL DEFAULT 'PENDING',
    completed_at       TIMESTAMPTZ,
    last_response_code TEXT,
    last_message       TEXT,
    attempts           INT NOT NULL DEFAULT 0,
    last_attempt_at    TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, bank_name)
);

CREATE INDEX idx_paysprint_bank_ekyc_user ON paysprint_bank_ekyc (user_id);
