package com.fintech.persistence.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "commission_rules")
public class CommissionRuleEntity {

    @Id
    private UUID id;

    @Column(name = "txn_type", nullable = false, columnDefinition = "text")
    private String txnType;

    @Column(name = "slab_min", nullable = false)
    private BigDecimal slabMin;

    @Column(name = "slab_max", nullable = false)
    private BigDecimal slabMax;

    @Column(name = "total_rate_bp", nullable = false)
    private int totalRateBp;

    @Column(name = "flat_fee")
    private BigDecimal flatFee;

    @Column(name = "retailer_share_pct", nullable = false)
    private BigDecimal retailerSharePct;

    @Column(name = "distributor_share_pct", nullable = false)
    private BigDecimal distributorSharePct;

    @Column(name = "platform_share_pct", nullable = false)
    private BigDecimal platformSharePct;

    @Column(name = "commission_type", nullable = false, columnDefinition = "text")
    private String commissionType;

    @Column(name = "retailer_amount")
    private BigDecimal retailerAmount;

    @Column(name = "distributor_amount")
    private BigDecimal distributorAmount;

    @Column(name = "effective_from", nullable = false)
    private OffsetDateTime effectiveFrom;

    @Column(name = "effective_to")
    private OffsetDateTime effectiveTo;
}
