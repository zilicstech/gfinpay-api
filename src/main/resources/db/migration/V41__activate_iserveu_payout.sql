-- The settlement transfer call now exists. Per-retailer priority still decides
-- whether a retailer is sent here or to Emerald.

UPDATE service_provider_channels
   SET active = TRUE,
       updated_at = now()
 WHERE provider_code = 'ISERVEU'
   AND pipe_code = 'PAYOUT';
