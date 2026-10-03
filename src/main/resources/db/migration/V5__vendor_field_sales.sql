-- Vendor field teams + EXTERNAL sales_leads (prod ships this as a single step after V4).

CREATE TABLE vendors (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hub_id      UUID NOT NULL REFERENCES hubs (id),
    full_name   TEXT NOT NULL,
    mobile      VARCHAR(15) NOT NULL,
    email       TEXT,
    code        TEXT NOT NULL UNIQUE,
    status      TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_vendors_mobile UNIQUE (mobile)
);
CREATE INDEX idx_vendors_hub ON vendors (hub_id);

CREATE TABLE vendor_affiliates (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    vendor_id      UUID NOT NULL REFERENCES vendors (id) ON DELETE CASCADE,
    employee_name  TEXT NOT NULL,
    employee_code  TEXT NOT NULL,
    affiliate_ref  UUID NOT NULL UNIQUE,
    apply_token    TEXT NOT NULL UNIQUE,
    gfin_code      TEXT NOT NULL UNIQUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_vendor_employee UNIQUE (vendor_id, employee_code)
);
CREATE INDEX idx_vendor_affiliates_vendor ON vendor_affiliates (vendor_id);

CREATE TABLE vendor_sales (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    vendor_id          UUID NOT NULL REFERENCES vendors (id) ON DELETE CASCADE,
    affiliate_id       UUID NOT NULL REFERENCES vendor_affiliates (id) ON DELETE CASCADE,
    affiliate_ref      UUID NOT NULL,
    customer_mobile    TEXT NOT NULL,
    customer_name      TEXT,
    product_key        TEXT NOT NULL,
    partner_status     TEXT,
    partner_status_at  TIMESTAMPTZ,
    partner_user_id    TEXT,
    payload            JSONB NOT NULL DEFAULT '{}'::jsonb,
    first_seen_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    customer_id        UUID REFERENCES customers (id),
    tracking_ref       UUID UNIQUE,
    CONSTRAINT uq_vendor_sale_ref UNIQUE (affiliate_ref, customer_mobile, product_key)
);
CREATE INDEX idx_vendor_sales_vendor ON vendor_sales (vendor_id, updated_at DESC);
CREATE INDEX idx_vendor_sales_affiliate ON vendor_sales (affiliate_id);

ALTER TABLE customers ALTER COLUMN retailer_user_id DROP NOT NULL;

ALTER TABLE customers
    ADD COLUMN vendor_id UUID REFERENCES vendors (id),
    ADD COLUMN affiliate_id UUID REFERENCES vendor_affiliates (id);

ALTER TABLE customers DROP CONSTRAINT IF EXISTS chk_customer_created_by_role;

ALTER TABLE customers
    ADD CONSTRAINT chk_customer_created_by_role
        CHECK (created_by_role IN ('DISTRIBUTOR', 'RETAILER', 'ADMIN', 'VENDOR'));

CREATE TABLE vendor_card_links (
    id              UUID PRIMARY KEY,
    affiliate_id    UUID NOT NULL REFERENCES vendor_affiliates (id) ON DELETE CASCADE,
    customer_id     UUID NOT NULL REFERENCES customers (id) ON DELETE CASCADE,
    catalog_item_id UUID NOT NULL REFERENCES catalog_items (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_vendor_card_links_affiliate ON vendor_card_links (affiliate_id, created_at DESC);
CREATE INDEX idx_vendor_card_links_customer ON vendor_card_links (customer_id);

ALTER TABLE sales_leads
    ADD COLUMN sale_channel TEXT NOT NULL DEFAULT 'INTERNAL';

ALTER TABLE sales_leads
    ADD CONSTRAINT chk_sales_leads_channel CHECK (sale_channel IN ('INTERNAL', 'EXTERNAL'));

UPDATE sales_leads l
   SET distributor_user_id = COALESCE(l.distributor_user_id, r.parent_id)
  FROM users r
 WHERE l.sale_channel = 'INTERNAL'
   AND l.distributor_user_id IS NULL
   AND r.id = l.retailer_user_id;

ALTER TABLE sales_leads DROP CONSTRAINT IF EXISTS sales_leads_retailer_user_id_fkey;
ALTER TABLE sales_leads DROP CONSTRAINT IF EXISTS sales_leads_distributor_user_id_fkey;

DROP INDEX IF EXISTS uq_sales_open_lead;

CREATE UNIQUE INDEX uq_sales_open_lead_internal
    ON sales_leads (customer_id, catalog_item_id)
    WHERE sale_channel = 'INTERNAL'
      AND state IN ('LINK_CREATED', 'OPENED', 'IN_PROGRESS');

CREATE INDEX idx_sales_leads_channel ON sales_leads (sale_channel, created_at DESC);

ALTER TABLE sales_leads
    ADD COLUMN distributor_code TEXT,
    ADD COLUMN distributor_name TEXT,
    ADD COLUMN retailer_code TEXT,
    ADD COLUMN retailer_name TEXT,
    ADD COLUMN hub_name TEXT,
    ADD COLUMN product_code TEXT;

UPDATE sales_leads l
   SET retailer_code = r.code,
       retailer_name = r.full_name,
       distributor_code = COALESCE(
           (SELECT u.code FROM users u WHERE u.id = l.distributor_user_id),
           pd.code,
           '—'),
       distributor_name = COALESCE(
           (SELECT u.full_name FROM users u WHERE u.id = l.distributor_user_id),
           pd.full_name,
           '—'),
       hub_name = COALESCE((SELECT name FROM hubs WHERE id = l.hub_id), '—'),
       product_code = COALESCE(
           (SELECT product_key FROM catalog_items WHERE id = l.catalog_item_id),
           'UNKNOWN')
  FROM users r
  LEFT JOIN users pd ON pd.id = r.parent_id
 WHERE l.sale_channel = 'INTERNAL'
   AND r.id = l.retailer_user_id;

UPDATE sales_leads
   SET retailer_code = COALESCE(retailer_code, '—'),
       retailer_name = COALESCE(retailer_name, '—'),
       distributor_code = COALESCE(distributor_code, '—'),
       distributor_name = COALESCE(distributor_name, '—'),
       hub_name = COALESCE(hub_name, '—'),
       product_code = COALESCE(product_code, 'UNKNOWN')
 WHERE sale_channel = 'INTERNAL'
   AND (retailer_code IS NULL OR product_code IS NULL);

UPDATE sales_leads
   SET product_code = 'UNKNOWN'
 WHERE product_code IS NULL;

UPDATE sales_leads
   SET retailer_code = COALESCE(retailer_code, '—'),
       retailer_name = COALESCE(retailer_name, '—'),
       distributor_code = COALESCE(distributor_code, '—'),
       distributor_name = COALESCE(distributor_name, '—'),
       hub_name = COALESCE(hub_name, '—')
 WHERE retailer_code IS NULL
    OR retailer_name IS NULL
    OR distributor_code IS NULL
    OR distributor_name IS NULL
    OR hub_name IS NULL;

ALTER TABLE sales_leads
    ALTER COLUMN distributor_code SET NOT NULL,
    ALTER COLUMN distributor_name SET NOT NULL,
    ALTER COLUMN retailer_code SET NOT NULL,
    ALTER COLUMN retailer_name SET NOT NULL,
    ALTER COLUMN hub_name SET NOT NULL,
    ALTER COLUMN product_code SET NOT NULL;

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
    ELSE
        RAISE EXCEPTION 'Invalid sale_channel';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sales_leads_validate_parties
    BEFORE INSERT OR UPDATE OF sale_channel, distributor_user_id, retailer_user_id
    ON sales_leads
    FOR EACH ROW
    EXECUTE FUNCTION validate_sales_lead_parties();

ALTER TABLE recon_rows DROP CONSTRAINT IF EXISTS recon_rows_retailer_user_id_fkey;
ALTER TABLE recon_rows DROP CONSTRAINT IF EXISTS recon_rows_distributor_user_id_fkey;
