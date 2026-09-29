-- ============================================================
-- V2: Seed — RBAC, platform ledger, services, FD card SKUs (inactive),
--     BBPS bill catalog, DMT/BBPS commission rules, product catalog (no hubs).
-- FD budget bands, volume tiers, and FD commission rules are configured in admin.
-- Platform config only. The super admin is created at startup from application properties.
-- ============================================================

INSERT INTO roles (id, code, description) VALUES
    (1, 'SUPER_ADMIN', 'Platform super admin'),
    (2, 'DISTRIBUTOR', 'Master distributor'),
    (3, 'RETAILER',    'Retail agent (Kirana store)'),
    (4, 'ADMIN', 'Hub admin — distributors and retailers in assigned hubs only');
SELECT setval('roles_id_seq', GREATEST((SELECT MAX(id) FROM roles), 4));

INSERT INTO permissions (id, code, description) VALUES
    (1,  'users.onboard',         'Create distributors and retailers'),
    (2,  'commissions.configure', 'Configure commission rules and retailer overrides'),
    (3,  'reports.view',          'View analytics and reports'),
    (4,  'wallet.topup',          'Top up own wallet'),
    (5,  'dmt.initiate',          'Initiate DMT transactions'),
    (6,  'senders.manage',        'Register DMT senders and beneficiaries'),
    (7,  'wallet.view',           'View own wallet and transactions'),
    (8,  'outlets.manage',        'Onboard and manage retailers in own network'),
    (9,  'bills.pay',             'Pay BBPS bills from the retailer wallet'),
    (12, 'catalog.view',          'Browse the product catalog'),
    (13, 'catalog.manage',        'Show or hide catalog items'),
    (14, 'sales.view',            'View sales leads'),
    (15, 'sales.create',          'Create customers and sales leads'),
    (16, 'settings.manage',       'Create hubs, toggle services, and configure FD cards'),
    (17, 'admins.manage',         'Create and assign hub admins');
SELECT setval('permissions_id_seq', GREATEST((SELECT MAX(id) FROM permissions), 17));

INSERT INTO role_permissions (role_id, permission_id) VALUES
    (1, 1), (1, 2), (1, 3), (1, 12), (1, 13), (1, 14), (1, 16), (1, 17),
    (2, 3), (2, 7), (2, 8), (2, 12), (2, 14), (2, 15),
    (3, 3), (3, 5), (3, 6), (3, 7), (3, 9), (3, 12), (3, 14), (3, 15),
    (4, 1), (4, 3), (4, 14);

INSERT INTO ledger_accounts (id, account_type, wallet_id) VALUES
    ('b0000000-0000-0000-0000-000000000001', 'PLATFORM_SETTLEMENT', NULL),
    ('b0000000-0000-0000-0000-000000000002', 'PG_RECEIVABLE',       NULL),
    ('b0000000-0000-0000-0000-000000000003', 'FEE_INCOME',          NULL);

INSERT INTO commission_rules
    (id, txn_type, slab_min, slab_max, total_rate_bp, flat_fee,
     retailer_share_pct, distributor_share_pct, platform_share_pct, effective_from)
VALUES
    ('c0000000-0000-0000-0000-000000000001', 'DMT',  1, 200000, 100, NULL, 40, 20, 40, now() - interval '1 day'),
    ('c0000000-0000-0000-0000-000000000002', 'BBPS', 1, 200000, 0,   8.00, 40, 20, 40, now() - interval '1 day');

INSERT INTO platform_services (code, name, description, enabled, sort_order) VALUES
    ('DMT',         'DMT',              'Domestic money transfer at the counter.', TRUE, 1),
    ('AEPS',        'AePS Cash Out',    'Aadhaar-enabled cash withdrawal on a micro-ATM.', TRUE, 2),
    ('UPI_CASHOUT', 'UPI to Cash',      'Customer pays via dynamic QR; retailer hands cash.', TRUE, 3),
    ('BBPS',        'BBPS Bill Pay',    'Electricity, water, gas, broadband, LPG, and other BBPS billers.', TRUE, 4),
    ('LEAD_GEN',    'Lead Generation',  'Credit cards, loans, savings, and catalog sales leads.', TRUE, 5);

INSERT INTO bill_operators (partner_id, name, category, txn_type, view_bill, consumer_label, regex) VALUES
    ('101', 'MSEDCL',              'ELECTRICITY',     'BBPS',    TRUE,  'Consumer number',           '^[0-9A-Za-z]{6,20}$'),
    ('102', 'BSES Rajdhani',       'ELECTRICITY',     'BBPS',    TRUE,  'CA number',                 '^[0-9]{8,12}$'),
    ('103', 'BSES Yamuna',         'ELECTRICITY',     'BBPS',    TRUE,  'CA number',                 '^[0-9]{8,12}$'),
    ('104', 'Tata Power Mumbai',   'ELECTRICITY',     'BBPS',    TRUE,  'Consumer number',           '^[0-9]{9,12}$'),
    ('105', 'BESCOM',              'ELECTRICITY',     'BBPS',    TRUE,  'RR number',                 '^[0-9A-Za-z]{6,16}$'),
    ('106', 'TSSPDCL',             'ELECTRICITY',     'BBPS',    TRUE,  'USC number',                '^[0-9]{8,13}$'),
    ('201', 'Delhi Jal Board',     'WATER',           'BBPS',    TRUE,  'K number',                  '^[0-9]{8,12}$'),
    ('202', 'BMC Water',           'WATER',           'BBPS',    TRUE,  'Consumer number',           '^[0-9]{8,14}$'),
    ('301', 'Indraprastha Gas',    'GAS',             'BBPS',    TRUE,  'BP number',                 '^[0-9]{8,12}$'),
    ('302', 'Mahanagar Gas',       'GAS',             'BBPS',    TRUE,  'Consumer number',           '^[0-9]{8,12}$'),
    ('401', 'Airtel Broadband',    'BROADBAND',       'BBPS',    TRUE,  'Account / mobile',          '^[0-9]{8,12}$'),
    ('402', 'JioFiber',            'BROADBAND',       'BBPS',    TRUE,  'Service ID',                '^[0-9]{10,12}$'),
    ('403', 'ACT Fibernet',        'BROADBAND',       'BBPS',    TRUE,  'Account number',            '^[0-9]{8,14}$'),
    ('501', 'Bharat Gas',          'LPG',             'BBPS',    TRUE,  'Consumer number',           '^[0-9]{10,17}$'),
    ('502', 'HP Gas',              'LPG',             'BBPS',    TRUE,  'Consumer number',           '^[0-9]{10,17}$'),
    ('503', 'Indane',              'LPG',             'BBPS',    TRUE,  'Consumer number',           '^[0-9]{10,17}$'),
    ('601', 'HDFC Credit Card',    'CREDIT_CARD',     'BBPS',    TRUE,  'Card last 6 / account',     '^[0-9]{6,16}$'),
    ('602', 'SBI Credit Card',     'CREDIT_CARD',     'BBPS',    TRUE,  'Card last 6 / account',     '^[0-9]{6,16}$'),
    ('603', 'ICICI Credit Card',   'CREDIT_CARD',     'BBPS',    TRUE,  'Card last 6 / account',     '^[0-9]{6,16}$'),
    ('701', 'Bajaj Finserv',       'LOAN',            'BBPS',    TRUE,  'Loan account',              '^[0-9A-Za-z]{8,18}$'),
    ('702', 'HDFC Bank Loan',      'LOAN',            'BBPS',    TRUE,  'Loan account',              '^[0-9A-Za-z]{8,18}$'),
    ('801', 'ICICI Lombard',       'INSURANCE',       'BBPS',    TRUE,  'Policy number',             '^[0-9A-Za-z]{8,20}$'),
    ('802', 'HDFC ERGO',           'INSURANCE',       'BBPS',    TRUE,  'Policy number',             '^[0-9A-Za-z]{8,20}$'),
    ('901', 'School / college fee','EDUCATION',       'BBPS',    TRUE,  'Student / admission ID',    '^[0-9A-Za-z]{4,16}$'),
    ('902', 'BMC Property Tax',    'MUNICIPAL',       'BBPS',    TRUE,  'Property ID',               '^[0-9A-Za-z]{6,16}$'),
    ('903', 'MCD Property Tax',    'MUNICIPAL',       'BBPS',    TRUE,  'Property ID',               '^[0-9A-Za-z]{6,16}$');

INSERT INTO catalog_categories (code, name, sort_order, eligibility_mode, payout_hint) VALUES
    ('CREDIT_CARD',    'Credit card',     10, 'MANUAL', '₹0 – ₹2,900 per activation'),
    ('FD_CARD',        'FD card',         20, 'MANUAL', 'Secured cards. Separate from credit cards.'),
    ('INSTANT_LOAN',   'Instant loan',    30, 'MANUAL', '1.10% per disbursement'),
    ('PERSONAL_LOAN',  'Personal loan',   40, 'MANUAL', '1.10% – 2.10% per disbursement'),
    ('BUSINESS_LOAN',  'Business loan',   50, 'MANUAL', '1.10% – 1.60% per disbursement'),
    ('SAVINGS',        'Savings account', 60, 'MANUAL', '₹150 – ₹800 per case');

INSERT INTO catalog_items
    (code, category_code, name, provider, rail, external_product, product_key, min_budget, apply_url, active, payout_hint, sort_order)
VALUES
    ('CREDIT_CARD',   'CREDIT_CARD',   'Credit Card',      'PAYSPRINT', 'PAYSPRINT_LEAD', 'CC', 'CREDIT_CARD', NULL, NULL, TRUE,
     'Lender choice happens on the Paysprint journey', 10),
    ('SBM_FD',        'FD_CARD',       'SBM FD Card',      'PAYSPRINT', 'PAYSPRINT_FD',   'FD', 'SBM', NULL, NULL, FALSE,
     'Novu FD secured card (SBM)', 10),
    ('SBM_FD_ZET',    'FD_CARD',       'SBM secured card', 'ZET', 'ZET_LINK', 'FD', 'SBM', NULL,
     'https://sbm-zet-card.zetapp.in/onboarding/verify?utm_campaign=zet-rupay&utm_source=Gfinpay&sub1={ref}', FALSE, NULL, 30),
    ('IOB_FD_ZET',    'FD_CARD',       'IOB secured card', 'ZET', 'ZET_LINK', 'FD', 'IOB', NULL,
     'https://zet-card.zetapp.in/iob-onboarding/verify?utm_campaign=zet-rupay&utm_source=Gfinpay&sub1={ref}&referrer=bank%3Diob-web', FALSE, NULL, 40),
    ('INSTANT_LOAN',  'INSTANT_LOAN',  'Instant Loan',     'PAYSPRINT', 'PAYSPRINT_LEAD', 'IL', 'INSTANT_LOAN', 5000, NULL, TRUE,
     'Web journey. Lender choice on Paysprint', 10),
    ('PERSONAL_LOAN', 'PERSONAL_LOAN', 'Personal Loan',    'PAYSPRINT', 'PAYSPRINT_LEAD', 'PL', 'PERSONAL_LOAN', 25000, NULL, TRUE,
     'Web journey. Lender choice on Paysprint', 10),
    ('BUSINESS_LOAN', 'BUSINESS_LOAN', 'Business Loan',    'PAYSPRINT', 'PAYSPRINT_LEAD', 'BL', 'BUSINESS_LOAN', 50000, NULL, TRUE,
     'Web journey. Lender choice on Paysprint', 10),
    ('SAVINGS',       'SAVINGS',       'Savings Account',  'PAYSPRINT', 'PAYSPRINT_LEAD', 'SA', 'SAVINGS', NULL, NULL, TRUE,
     'Kotak 811, Airtel Payments Bank, Equitas', 10),
    ('DCB_FD_GROWMORE', 'FD_CARD', 'DCB secured card', 'GROWMORE', 'GROWMORE_LINK', 'FD', 'DCB', NULL, NULL, FALSE,
     'Novu journey via GrowMore', 50);

INSERT INTO fd_providers (code, name, enabled, novu, fallback_rank, commission_txn_type) VALUES
    ('ZET',       'ZET',       TRUE,  FALSE, 1, 'FD_ZET'),
    ('PAYSPRINT', 'PaySprint', TRUE,  TRUE,  2, 'FD_PAYSPRINT'),
    ('GROWMORE',  'GrowMore',  FALSE, TRUE,  3, 'FD_GROWMORE');
