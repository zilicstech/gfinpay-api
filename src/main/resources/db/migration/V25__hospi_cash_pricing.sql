-- Selling price, discount, and vendor commission for Hospi Cash.
-- Existing sales keep the premium already stored on the sale.

CREATE TABLE hospi_cash_settings (
    id                 BOOLEAN PRIMARY KEY DEFAULT TRUE,
    selling_price      NUMERIC(18,2) NOT NULL DEFAULT 1000,
    discount_amount    NUMERIC(18,2) NOT NULL DEFAULT 0,
    vendor_commission  NUMERIC(18,2) NOT NULL DEFAULT 0,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT hospi_settings_one CHECK (id),
    CONSTRAINT hospi_settings_price CHECK (selling_price > 0),
    CONSTRAINT hospi_settings_discount CHECK (discount_amount >= 0 AND discount_amount < selling_price),
    CONSTRAINT hospi_settings_vendor CHECK (vendor_commission >= 0)
);

INSERT INTO hospi_cash_settings (id) VALUES (TRUE);
