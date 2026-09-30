ALTER TABLE sales_leads
    ADD COLUMN partner_status TEXT,
    ADD COLUMN partner_status_at TIMESTAMPTZ,
    ADD COLUMN partner_user_id TEXT;

CREATE INDEX idx_sales_leads_partner_status ON sales_leads (partner_status)
    WHERE partner_status IS NOT NULL;
