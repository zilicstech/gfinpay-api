-- Cash-Point is available for per-retailer priority. The retailer card does not
-- expose a separate platform pause. Payout stays inactive until the transfer call exists.

UPDATE service_provider_channels
   SET active = TRUE,
       updated_at = now()
 WHERE provider_code = 'ISERVEU'
   AND pipe_code = 'CASHPOINT';
