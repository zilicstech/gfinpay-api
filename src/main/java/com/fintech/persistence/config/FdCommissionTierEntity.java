package com.fintech.persistence.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "fd_commission_tiers")
public class FdCommissionTierEntity {

    @Id
    private UUID id;

    @Column(name = "provider_code", nullable = false, columnDefinition = "text")
    private String providerCode;

    @Column(name = "cards_min", nullable = false)
    private int cardsMin;

    @Column(name = "cards_max", nullable = false)
    private int cardsMax;

    @Column(name = "fixed_amount_per_card", nullable = false)
    private BigDecimal fixedAmountPerCard;

    @Column(name = "retailer_share_pct", nullable = false)
    private BigDecimal retailerSharePct;

    @Column(name = "distributor_share_pct", nullable = false)
    private BigDecimal distributorSharePct;

    @Column(name = "platform_share_pct", nullable = false)
    private BigDecimal platformSharePct;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
