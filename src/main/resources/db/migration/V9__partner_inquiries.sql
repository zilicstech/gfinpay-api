CREATE TABLE partner_inquiries (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name             TEXT NOT NULL,
    mobile                VARCHAR(15) NOT NULL,
    email                 TEXT NOT NULL,
    city                  TEXT NOT NULL,
    message               TEXT,
    status                TEXT NOT NULL DEFAULT 'OPEN',
    hub_id                UUID REFERENCES hubs(id),
    assigned_hub_admin    UUID REFERENCES users(id),
    assigned_distributor  UUID REFERENCES users(id),
    converted_user_id     UUID REFERENCES users(id),
    converted_role        TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_partner_inquiries_status ON partner_inquiries (status, created_at DESC);
CREATE INDEX idx_partner_inquiries_hub_admin ON partner_inquiries (assigned_hub_admin);
CREATE INDEX idx_partner_inquiries_distributor ON partner_inquiries (assigned_distributor);
