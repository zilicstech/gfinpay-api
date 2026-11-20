-- Per-retailer routing priority and the iServeU payee VPA. Existing Emerald
-- activations stay first so live cash-out does not move.

ALTER TABLE provider_pipe_activations
    ADD COLUMN IF NOT EXISTS retailer_priority SMALLINT,
    ADD COLUMN IF NOT EXISTS payee_vpa TEXT;

UPDATE provider_pipe_activations a
   SET retailer_priority = 1
  FROM service_provider_channels c
 WHERE c.id = a.channel_id
   AND c.provider_code = 'EMERALD'
   AND a.retailer_priority IS NULL;

ALTER TABLE provider_pipe_activations
    DROP CONSTRAINT IF EXISTS chk_retailer_priority;

ALTER TABLE provider_pipe_activations
    ADD CONSTRAINT chk_retailer_priority
    CHECK (retailer_priority IS NULL OR retailer_priority BETWEEN 1 AND 3);

INSERT INTO service_provider_channels
    (id, channel_key, provider_code, pipe_code, display_label, retailer_label,
     biometric_required, supports_upi_cashout, supports_aeps, supports_payout, priority_order, active, config_json)
VALUES
    ('e1000000-0000-0000-0000-000000000038', 'ch_iserveu_payout', 'ISERVEU', 'PAYOUT',
     'iServeU · Payout', 'Merchant Banking Terminal',
     FALSE, FALSE, FALSE, TRUE, 50, FALSE,
     '{}'::jsonb)
ON CONFLICT (provider_code, pipe_code) DO NOTHING;
