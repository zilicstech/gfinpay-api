-- Payment collection and card activation are separate.
-- Commission is generated only when activation is marked SUCCESS.

ALTER TABLE hospi_cash_sales
    ADD COLUMN activation_status TEXT NOT NULL DEFAULT 'PENDING';

UPDATE hospi_cash_sales
   SET activation_status = CASE state
       WHEN 'ACTIVATED' THEN 'SUCCESS'
       WHEN 'REJECTED' THEN 'FAILED'
       ELSE 'PENDING'
   END;

ALTER TABLE hospi_cash_sales
    ADD CONSTRAINT hospi_activation CHECK (activation_status IN ('PENDING', 'SUCCESS', 'FAILED'));
