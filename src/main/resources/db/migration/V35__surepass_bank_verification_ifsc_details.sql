-- V35: Surepass Bank Verification (/api/v1/bank-verification) metadata & IFSC details (excluding branch contact phone)

ALTER TABLE retailer_bank_accounts
    ADD COLUMN IF NOT EXISTS imps_ref_no TEXT,
    ADD COLUMN IF NOT EXISTS verification_remarks TEXT,
    ADD COLUMN IF NOT EXISTS branch_name TEXT,
    ADD COLUMN IF NOT EXISTS ifsc_details JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE agent_kyc_profiles
    DROP CONSTRAINT IF EXISTS chk_bank_verification_status;

ALTER TABLE agent_kyc_profiles
    ADD CONSTRAINT chk_bank_verification_status
        CHECK (bank_verification_status IN ('NOT_VERIFIED', 'VERIFIED', 'FAILED'));

