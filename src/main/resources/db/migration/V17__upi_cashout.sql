-- UPI cash-out references are minted by us in the initiate callback. PaySprint's sample
-- sends refid as a JSON number, so the sequence stays numeric.
CREATE SEQUENCE upi_cashout_refid_seq START WITH 100000001;

ALTER TABLE cashout_sessions
    ADD COLUMN provider_refid        BIGINT,
    ADD COLUMN ackno                  TEXT,
    ADD COLUMN bank_rrn               TEXT,
    ADD COLUMN payer_va               TEXT,
    ADD COLUMN payer_name             TEXT,
    ADD COLUMN payer_account          TEXT,
    ADD COLUMN payer_ifsc             TEXT,
    ADD COLUMN provider_comm          NUMERIC(18,4),
    ADD COLUMN provider_tds           NUMERIC(18,4),
    ADD COLUMN latitude               TEXT,
    ADD COLUMN longitude              TEXT,
    ADD COLUMN device_id              TEXT,
    ADD COLUMN err_code               TEXT,
    ADD COLUMN err_msg                TEXT,
    ADD COLUMN provider_initiated_at  TIMESTAMPTZ;

CREATE UNIQUE INDEX uq_cashout_refid
    ON cashout_sessions (provider_refid)
    WHERE provider_refid IS NOT NULL;

-- The initiate callback has no reference yet. Same retailer, amount, and mobile while
-- still INITIATED is a redelivery, not a second cash out.
CREATE UNIQUE INDEX uq_cashout_open_intent
    ON cashout_sessions (agent_user_id, amount, customer_mobile)
    WHERE status = 'INITIATED';

-- Rate stays 0 until the business confirms the slab. Shares are recorded as zero and
-- nothing is paid out.
INSERT INTO commission_rules
    (id, txn_type, slab_min, slab_max, total_rate_bp, flat_fee,
     retailer_share_pct, distributor_share_pct, platform_share_pct, effective_from)
VALUES
    ('c0000000-0000-0000-0000-000000000003', 'CASHOUT_UPI', 1, 200000, 0, NULL, 40, 20, 40, now());
