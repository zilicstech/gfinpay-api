DROP INDEX IF EXISTS uq_finance_pending_wallet;
DROP INDEX IF EXISTS uq_finance_pending_withdraw;

CREATE INDEX idx_finance_open_wallet
    ON finance_requests (retailer_user_id, created_at DESC)
    WHERE kind = 'WALLET_TOPUP' AND status = 'PENDING';

CREATE INDEX idx_finance_open_withdraw
    ON finance_requests (retailer_user_id, created_at DESC)
    WHERE kind = 'EARNINGS_WITHDRAW' AND status = 'PENDING';
