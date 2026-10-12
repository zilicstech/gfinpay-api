CREATE TABLE fd_providers (
    code                 TEXT PRIMARY KEY,
    name                 TEXT NOT NULL,
    enabled              BOOLEAN NOT NULL DEFAULT TRUE,
    novu                 BOOLEAN NOT NULL DEFAULT FALSE,
    fallback_rank        INT NOT NULL DEFAULT 100,
    commission_txn_type  TEXT NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE fd_budget_bands (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slab_min            NUMERIC(18, 2) NOT NULL,
    slab_max            NUMERIC(18, 2) NOT NULL,
    preferred_provider  TEXT NOT NULL REFERENCES fd_providers(code),
    preference_rank     INT NOT NULL DEFAULT 1,
    CONSTRAINT chk_fd_band_range CHECK (slab_min <= slab_max)
);
CREATE INDEX idx_fd_budget_bands_range ON fd_budget_bands (slab_min, slab_max);
CREATE UNIQUE INDEX uq_fd_band_provider ON fd_budget_bands (preferred_provider);
CREATE UNIQUE INDEX uq_fd_band_rank ON fd_budget_bands (preference_rank);

CREATE TABLE fd_commission_tiers (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_code           TEXT NOT NULL REFERENCES fd_providers(code),
    cards_min               INT NOT NULL DEFAULT 0,
    cards_max               INT NOT NULL DEFAULT 999999,
    fixed_amount_per_card   NUMERIC(18, 2) NOT NULL CHECK (fixed_amount_per_card >= 0),
    retailer_share_pct      NUMERIC(5, 2) NOT NULL,
    distributor_share_pct   NUMERIC(5, 2) NOT NULL,
    platform_share_pct      NUMERIC(5, 2) NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_fd_tier_split CHECK (
        retailer_share_pct + distributor_share_pct + platform_share_pct = 100
    ),
    CONSTRAINT chk_fd_tier_cards CHECK (cards_min <= cards_max)
);
CREATE INDEX idx_fd_commission_tiers_provider ON fd_commission_tiers (provider_code, cards_min);
