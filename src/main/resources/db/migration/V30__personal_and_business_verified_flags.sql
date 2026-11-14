-- Separate flags for Personal Identity Verification and Business Verification on agent_kyc_profiles.

ALTER TABLE agent_kyc_profiles
    ADD COLUMN IF NOT EXISTS personal_identity_verified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS business_verified          BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE agent_kyc_profiles
   SET business_verified = (business_status = 'BUSINESS_VERIFIED');

UPDATE agent_kyc_profiles p
   SET personal_identity_verified = TRUE,
       personal_status = 'PERSONAL_VERIFIED'
 WHERE p.identity_status = 'VERIFIED'
   AND p.face_review_status = 'MATCHED'
   AND NOT EXISTS (
       SELECT 1
         FROM digilocker_documents d
        WHERE (d.user_id = p.user_id OR d.journey_id IN (SELECT id FROM onboarding_journeys WHERE user_id = p.user_id))
          AND (
              (UPPER(d.doc_type) = 'ADHAR' AND LOWER(d.file_type) = 'pdf')
              OR UPPER(d.doc_type) = 'AADHAAR_PHOTO'
              OR LOWER(d.file_id) = 'aadhaar_photo'
              OR (UPPER(d.doc_type) = 'PANCR' AND LOWER(d.file_type) = 'pdf')
          )
          AND d.review_status <> 'APPROVED'
   );

UPDATE agent_kyc_profiles
   SET personal_status = 'NOT_VERIFIED'
 WHERE personal_identity_verified = FALSE;

