-- Add dedicated aadhaar_pan_linked flag to agent_kyc_profiles to track whether the partner's Aadhaar is linked to their PAN.

ALTER TABLE agent_kyc_profiles
    ADD COLUMN IF NOT EXISTS aadhaar_pan_linked BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE agent_kyc_profiles
   SET aadhaar_pan_linked = COALESCE(
       (identity_details->>'aadhaarPanLinked')::boolean,
       (identity_details->>'aadhaarLinked')::boolean,
       (personal_details->>'aadhaarPanLinked')::boolean,
       (personal_details->>'aadhaarLinked')::boolean,
       FALSE
   );

