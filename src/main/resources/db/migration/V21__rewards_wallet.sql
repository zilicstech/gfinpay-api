-- Commission is configured by an admin per service. The seeded slab rules are not used.
DELETE FROM commission_earnings
 WHERE rule_id IN (
    'c0000000-0000-0000-0000-000000000001',
    'c0000000-0000-0000-0000-000000000002',
    'c0000000-0000-0000-0000-000000000003',
    'c0000000-0000-0000-0000-000000000004'
 );

DELETE FROM commission_rules
 WHERE id IN (
    'c0000000-0000-0000-0000-000000000001',
    'c0000000-0000-0000-0000-000000000002',
    'c0000000-0000-0000-0000-000000000003',
    'c0000000-0000-0000-0000-000000000004'
 );

ALTER TABLE commission_rules DROP CONSTRAINT chk_split_100;
ALTER TABLE commission_rules DROP CONSTRAINT chk_fixed_amounts;
ALTER TABLE commission_rules
    ADD COLUMN hub_amount NUMERIC(18,4),
    ADD COLUMN hub_share_pct NUMERIC(5,2) NOT NULL DEFAULT 0;

-- A rewards balance is a second wallet. The operating float stays wallet_kind MAIN.
ALTER TABLE wallets ADD COLUMN wallet_kind TEXT NOT NULL DEFAULT 'MAIN';
ALTER TABLE wallets DROP CONSTRAINT IF EXISTS wallets_user_id_key;
CREATE UNIQUE INDEX uq_wallets_user_kind ON wallets (user_id, wallet_kind);

INSERT INTO wallets (id, user_id, wallet_kind)
SELECT gen_random_uuid(), u.id, 'REWARDS'
  FROM users u
 WHERE u.user_type IN ('RETAILER', 'MASTER_DISTRIBUTOR', 'HUBADMIN')
   AND NOT EXISTS (
       SELECT 1 FROM wallets w WHERE w.user_id = u.id AND w.wallet_kind = 'REWARDS'
   );
