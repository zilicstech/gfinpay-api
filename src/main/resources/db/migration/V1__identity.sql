CREATE TABLE hubs (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        TEXT NOT NULL UNIQUE,
    name        TEXT NOT NULL,
    city        TEXT NOT NULL,
    state       TEXT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'ACTIVE',
    notes       TEXT,
    created_by  UUID,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_type       TEXT NOT NULL,
    parent_id       UUID REFERENCES users(id),
    hub_id          UUID REFERENCES hubs(id),
    created_by      UUID REFERENCES users(id),
    full_name       TEXT NOT NULL,
    mobile          VARCHAR(15) NOT NULL UNIQUE,
    email           TEXT UNIQUE,
    password_hash   TEXT NOT NULL,
    code            TEXT,
    status          TEXT NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_users_parent ON users(parent_id);
CREATE INDEX idx_users_hub ON users(hub_id);
CREATE UNIQUE INDEX uq_users_code ON users(code) WHERE code IS NOT NULL;
CREATE UNIQUE INDEX uq_single_super_admin ON users (user_type) WHERE user_type = 'SUPER_ADMIN';

ALTER TABLE hubs
    ADD CONSTRAINT fk_hubs_created_by FOREIGN KEY (created_by) REFERENCES users(id);
CREATE INDEX idx_hubs_created_by ON hubs(created_by);

CREATE TABLE hub_admin_assignments (
    hub_id      UUID NOT NULL REFERENCES hubs(id) ON DELETE CASCADE,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (hub_id, user_id)
);
CREATE INDEX idx_hub_admin_user ON hub_admin_assignments(user_id);

CREATE TABLE user_sessions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    refresh_token_hash  TEXT NOT NULL,
    last_activity_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    absolute_expires_at TIMESTAMPTZ NOT NULL,
    revoked_at          TIMESTAMPTZ,
    revoked_reason      TEXT,
    rotations           INT NOT NULL DEFAULT 0,
    ip                  TEXT,
    user_agent          TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_user_sessions_active ON user_sessions (user_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_user_sessions_hash ON user_sessions (refresh_token_hash) WHERE revoked_at IS NULL;
CREATE INDEX idx_user_sessions_user ON user_sessions (user_id);

CREATE TABLE agent_kyc_profiles (
    user_id             UUID PRIMARY KEY REFERENCES users(id),
    aadhaar_ref_key     TEXT,
    aadhaar_last4       CHAR(4),
    aadhaar_verified_at TIMESTAMPTZ,
    pan_enc             TEXT,
    pan_verified_at     TIMESTAMPTZ,
    liveness_score      NUMERIC(5,2),
    liveness_passed_at  TIMESTAMPTZ,
    shop_name           TEXT,
    shop_address        JSONB,
    gstin               VARCHAR(15),
    gstin_verified_at   TIMESTAMPTZ,
    ovd_type            TEXT,
    kyc_status          TEXT NOT NULL DEFAULT 'NOT_STARTED',
    rejection_reason    TEXT,
    documents           JSONB NOT NULL DEFAULT '[]',
    paysprint_onboard_issued_at TIMESTAMPTZ,
    paysprint_onboard_token     TEXT,
    paysprint_redirect_url      TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_agent_kyc_paysprint_token
    ON agent_kyc_profiles (paysprint_onboard_token)
    WHERE paysprint_onboard_token IS NOT NULL;
