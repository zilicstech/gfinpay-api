-- iServeU Cash-Point is seeded inactive. It is not selected until an operator activates the channel
-- and a retailer is onboarded onto it. provider_api_logs is a read-only alias of the audit table.

INSERT INTO service_provider_channels
    (id, channel_key, provider_code, pipe_code, display_label, retailer_label,
     biometric_required, supports_upi_cashout, supports_aeps, supports_payout, priority_order, active, config_json)
VALUES
    ('e1000000-0000-0000-0000-000000000037', 'ch_iserveu_cashpoint', 'ISERVEU', 'CASHPOINT',
     'iServeU · Cash Point', 'Merchant Banking Terminal',
     FALSE, TRUE, FALSE, FALSE, 50, FALSE,
     '{"upiCashout":{"min":100,"max":5000,"step":100},"payeeVpa":""}'::jsonb)
ON CONFLICT (provider_code, pipe_code) DO NOTHING;

CREATE OR REPLACE VIEW provider_api_logs AS
SELECT id, call_id, request_id, direction, flow, http_method, url, endpoint_path,
       user_id, outlet_code, external_ref, provider_txn_id, provider_code, provider_message,
       http_status, latency_ms, created_at
  FROM emerald_api_logs;
