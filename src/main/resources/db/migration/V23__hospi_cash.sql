-- Hospi Cash is captured on the desk and activated from a partner workbook.
-- It is not an apply-link, so it does not live in sales_leads.

CREATE TABLE hospi_cash_sales (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    gfin_sale_code         TEXT NOT NULL UNIQUE,
    sale_channel           TEXT NOT NULL,
    retailer_user_id       UUID REFERENCES users(id),
    distributor_user_id    UUID REFERENCES users(id),
    vendor_id              UUID REFERENCES vendors(id),
    affiliate_id           UUID REFERENCES vendor_affiliates(id),
    customer_id            UUID REFERENCES customers(id),
    gender                 TEXT NOT NULL,
    customer_name          TEXT NOT NULL,
    dob                    DATE NOT NULL,
    address                TEXT NOT NULL,
    pincode                VARCHAR(10) NOT NULL,
    mobile                 VARCHAR(15) NOT NULL,
    email                  TEXT NOT NULL,
    insured_relationship   TEXT NOT NULL,
    nominee_name           TEXT NOT NULL,
    nominee_relation       TEXT NOT NULL,
    nominee_dob            DATE NOT NULL,
    good_health            TEXT NOT NULL,
    ped_details            TEXT NOT NULL,
    ped_since              TEXT NOT NULL,
    pep                    TEXT NOT NULL,
    plan_name              TEXT NOT NULL,
    premium_amount         NUMERIC(18,2) NOT NULL DEFAULT 1000,
    state                  TEXT NOT NULL DEFAULT 'SOLD',
    vendor_payment_status  TEXT NOT NULL,
    sale_txn_id            UUID REFERENCES transactions(id),
    commission_txn_id      UUID REFERENCES transactions(id),
    exported_at            TIMESTAMPTZ,
    activated_at           TIMESTAMPTZ,
    partner_policy_ref     TEXT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT hospi_channel CHECK (sale_channel IN ('INTERNAL', 'EXTERNAL')),
    CONSTRAINT hospi_state CHECK (state IN ('SOLD', 'EXPORTED', 'ACTIVATED', 'REJECTED')),
    CONSTRAINT hospi_vendor_pay CHECK (vendor_payment_status IN ('NOT_REQUIRED', 'UNPAID', 'COLLECTED')),
    CONSTRAINT hospi_party CHECK (
        (sale_channel = 'INTERNAL' AND retailer_user_id IS NOT NULL AND vendor_id IS NULL AND affiliate_id IS NULL)
        OR (sale_channel = 'EXTERNAL' AND vendor_id IS NOT NULL AND affiliate_id IS NOT NULL AND retailer_user_id IS NULL)
    )
);

CREATE INDEX idx_hospi_sales_state ON hospi_cash_sales(state, created_at DESC);
CREATE INDEX idx_hospi_sales_retailer ON hospi_cash_sales(retailer_user_id);
CREATE INDEX idx_hospi_sales_vendor ON hospi_cash_sales(vendor_id);

CREATE UNIQUE INDEX uq_hospi_open_retailer
    ON hospi_cash_sales (retailer_user_id, mobile)
    WHERE sale_channel = 'INTERNAL' AND state IN ('SOLD', 'EXPORTED');

CREATE UNIQUE INDEX uq_hospi_open_affiliate
    ON hospi_cash_sales (affiliate_id, mobile)
    WHERE sale_channel = 'EXTERNAL' AND state IN ('SOLD', 'EXPORTED');

CREATE TABLE hospi_cash_vendor_earnings (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    vendor_id   UUID NOT NULL REFERENCES vendors(id),
    sale_id     UUID NOT NULL UNIQUE REFERENCES hospi_cash_sales(id),
    amount      NUMERIC(18,2) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    settled_at  TIMESTAMPTZ
);

CREATE INDEX idx_hospi_vendor_earnings_open
    ON hospi_cash_vendor_earnings(vendor_id)
    WHERE settled_at IS NULL;

INSERT INTO platform_services (code, name, description, enabled, sort_order)
SELECT 'HOSPI_CASH',
       'Hospi Cash',
       'Hospital cash card. Retailer premium is debited from the main wallet. Reward is credited when the card is activated.',
       TRUE,
       11
 WHERE NOT EXISTS (SELECT 1 FROM platform_services WHERE code = 'HOSPI_CASH');
