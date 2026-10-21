-- Existing scans were sent with is_casa = 0, so DMT stays off until a later scan asks for it.
ALTER TABLE paysprint_bank_ekyc
    ADD COLUMN is_dmt_active BOOLEAN NOT NULL DEFAULT FALSE;
