CREATE TABLE catalog_categories (
    code              TEXT PRIMARY KEY,
    name              TEXT NOT NULL,
    sort_order        INT NOT NULL DEFAULT 100,
    eligibility_mode  TEXT NOT NULL DEFAULT 'MANUAL',
    payout_hint       TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE catalog_items (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code              TEXT NOT NULL UNIQUE,
    category_code     TEXT NOT NULL REFERENCES catalog_categories(code),
    name              TEXT NOT NULL,
    provider          TEXT NOT NULL DEFAULT 'PAYSPRINT',
    rail              TEXT NOT NULL,
    external_product  TEXT,
    product_key       TEXT NOT NULL,
    min_budget        NUMERIC(18,2),
    apply_url         TEXT,
    active            BOOLEAN NOT NULL DEFAULT TRUE,
    payout_hint       TEXT,
    sort_order        INT NOT NULL DEFAULT 100,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_catalog_items_product_key ON catalog_items (category_code, product_key);

CREATE TABLE vendors (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hub_id      UUID NOT NULL REFERENCES hubs(id),
    full_name   TEXT NOT NULL,
    mobile      VARCHAR(15) NOT NULL,
    email       TEXT,
    code        TEXT NOT NULL UNIQUE,
    status      TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_vendors_mobile UNIQUE (mobile)
);
CREATE INDEX idx_vendors_hub ON vendors(hub_id);

CREATE TABLE vendor_affiliates (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    vendor_id      UUID NOT NULL REFERENCES vendors(id) ON DELETE CASCADE,
    employee_name  TEXT NOT NULL,
    employee_code  TEXT NOT NULL,
    affiliate_ref  UUID NOT NULL UNIQUE,
    apply_token    TEXT NOT NULL UNIQUE,
    gfin_code      TEXT NOT NULL UNIQUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_vendor_employee UNIQUE (vendor_id, employee_code)
);
CREATE INDEX idx_vendor_affiliates_vendor ON vendor_affiliates(vendor_id);

CREATE TABLE customers (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    retailer_user_id UUID REFERENCES users(id),
    vendor_id        UUID REFERENCES vendors(id),
    affiliate_id     UUID REFERENCES vendor_affiliates(id),
    full_name        TEXT NOT NULL,
    mobile           VARCHAR(15) NOT NULL,
    email            TEXT,
    city             TEXT,
    state            TEXT,
    pincode          VARCHAR(10),
    employment_type  TEXT,
    monthly_income   NUMERIC(18,4),
    ekyc_status      TEXT NOT NULL DEFAULT 'NOT_STARTED',
    ekyc_verified_at TIMESTAMPTZ,
    ovd_type         TEXT,
    ovd_last4        VARCHAR(4),
    created_by_role  TEXT NOT NULL,
    created_by_code  TEXT NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_customer_mobile UNIQUE (mobile)
);
CREATE INDEX idx_customers_retailer ON customers(retailer_user_id);

CREATE TABLE customer_category_eligibility (
    customer_id    UUID NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    category_code  TEXT NOT NULL REFERENCES catalog_categories(code),
    status         TEXT NOT NULL DEFAULT 'PENDING',
    reason         TEXT,
    checked_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (customer_id, category_code)
);

CREATE TABLE sales_leads (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id          UUID NOT NULL REFERENCES customers(id),
    catalog_item_id      UUID NOT NULL REFERENCES catalog_items(id),
    retailer_user_id     UUID NOT NULL,
    distributor_user_id  UUID,
    state                TEXT NOT NULL DEFAULT 'LINK_CREATED',
    budget               NUMERIC(18,2),
    sale_type            TEXT NOT NULL,
    sale_provider        TEXT NOT NULL,
    sale_channel         TEXT NOT NULL DEFAULT 'INTERNAL',
    hub_id               UUID REFERENCES hubs(id),
    provider_refid       TEXT,
    link_token           TEXT NOT NULL UNIQUE,
    payment_link_url     TEXT,
    encdata              TEXT,
    link_opened_at       TIMESTAMPTZ,
    partner_status       TEXT,
    partner_status_at    TIMESTAMPTZ,
    partner_user_id      TEXT,
    partner_snapshot     JSONB,
    distributor_code     TEXT NOT NULL,
    distributor_name     TEXT NOT NULL,
    retailer_code        TEXT NOT NULL,
    retailer_name        TEXT NOT NULL,
    hub_name             TEXT NOT NULL,
    product_code         TEXT NOT NULL,
    state_history        JSONB NOT NULL DEFAULT '[]',
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_sales_open_lead_internal
    ON sales_leads (customer_id, catalog_item_id)
    WHERE sale_channel = 'INTERNAL'
      AND state IN ('LINK_CREATED', 'OPENED', 'IN_PROGRESS');
CREATE INDEX idx_sales_leads_retailer ON sales_leads (retailer_user_id, created_at DESC);
CREATE INDEX idx_sales_leads_refid ON sales_leads (provider_refid) WHERE provider_refid IS NOT NULL;
CREATE INDEX idx_sales_leads_report ON sales_leads (created_at DESC, sale_type, sale_provider, state);
CREATE INDEX idx_sales_leads_distributor ON sales_leads (distributor_user_id, created_at DESC);
CREATE INDEX idx_sales_leads_hub_created ON sales_leads (hub_id, created_at DESC);
CREATE INDEX idx_sales_leads_partner_status ON sales_leads (partner_status) WHERE partner_status IS NOT NULL;
CREATE INDEX idx_sales_leads_channel ON sales_leads (sale_channel, created_at DESC);

CREATE OR REPLACE FUNCTION validate_sales_lead_parties()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.sale_channel = 'INTERNAL' THEN
        IF NOT EXISTS (SELECT 1 FROM users WHERE id = NEW.retailer_user_id) THEN
            RAISE EXCEPTION 'INTERNAL sale requires retailer_user_id in users';
        END IF;
        IF NEW.distributor_user_id IS NOT NULL
           AND NOT EXISTS (SELECT 1 FROM users WHERE id = NEW.distributor_user_id) THEN
            RAISE EXCEPTION 'INTERNAL sale distributor_user_id must reference users';
        END IF;
    ELSIF NEW.sale_channel = 'EXTERNAL' THEN
        IF NEW.distributor_user_id IS NULL OR NEW.retailer_user_id IS NULL THEN
            RAISE EXCEPTION 'EXTERNAL sale requires distributor and retailer party ids';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM vendors WHERE id = NEW.distributor_user_id) THEN
            RAISE EXCEPTION 'EXTERNAL sale distributor_user_id must reference vendors';
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM vendor_affiliates va
             WHERE va.id = NEW.retailer_user_id
               AND va.vendor_id = NEW.distributor_user_id
        ) THEN
            RAISE EXCEPTION 'EXTERNAL sale retailer_user_id must reference vendor employee';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sales_leads_validate_parties
    BEFORE INSERT OR UPDATE OF sale_channel, distributor_user_id, retailer_user_id
    ON sales_leads
    FOR EACH ROW
    EXECUTE FUNCTION validate_sales_lead_parties();
