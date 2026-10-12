ALTER TABLE finance_requests DROP CONSTRAINT chk_finance_transfer_mode;

ALTER TABLE finance_requests
    ADD CONSTRAINT chk_finance_transfer_mode CHECK (
        transfer_mode IS NULL OR transfer_mode IN ('IMPS', 'NEFT', 'RTGS', 'UPI', 'QR')
    );
