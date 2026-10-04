ALTER TABLE bill_payments DROP CONSTRAINT IF EXISTS bill_payments_operator_id_fkey;

ALTER TABLE bill_payments
    ADD COLUMN IF NOT EXISTS category_id INT,
    ADD COLUMN IF NOT EXISTS partner_txn_id TEXT;
