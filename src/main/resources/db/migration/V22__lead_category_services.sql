-- Each PaySprint lead product is its own rail so it can be switched and commissioned alone.
INSERT INTO platform_services (code, name, description, enabled, sort_order)
SELECT v.code, v.name, v.description,
       COALESCE((SELECT enabled FROM platform_services WHERE code = 'LEAD_GEN'), TRUE),
       v.sort_order
  FROM (VALUES
    ('CREDIT_CARD',    'Credit card',     'PaySprint credit card lead (CC). Reward is credited when the lead converts.', 6),
    ('INSTANT_LOAN',   'Instant loan',    'PaySprint instant loan lead (IL). Reward is credited when the lead converts.', 7),
    ('PERSONAL_LOAN',  'Personal loan',   'PaySprint personal loan lead (PL). Reward is credited when the lead converts.', 8),
    ('BUSINESS_LOAN',  'Business loan',   'PaySprint business loan lead (BL). Reward is credited when the lead converts.', 9),
    ('SAVINGS',        'Savings account', 'PaySprint savings account lead (SA). Reward is credited when the lead converts.', 10)
  ) AS v(code, name, description, sort_order)
 WHERE NOT EXISTS (SELECT 1 FROM platform_services p WHERE p.code = v.code);

UPDATE platform_services
   SET enabled = FALSE, updated_at = now()
 WHERE code = 'LEAD_GEN';
