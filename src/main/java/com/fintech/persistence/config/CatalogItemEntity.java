package com.fintech.persistence.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "catalog_items")
public class CatalogItemEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, columnDefinition = "text")
    private String code;

    @Column(name = "category_code", nullable = false, columnDefinition = "text")
    private String categoryCode;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(nullable = false, columnDefinition = "text")
    private String provider;

    @Column(nullable = false, columnDefinition = "text")
    private String rail;

    @Column(name = "external_product", columnDefinition = "text")
    private String externalProduct;

    @Column(name = "product_key", nullable = false, columnDefinition = "text")
    private String productKey;

    @Column(name = "min_budget")
    private BigDecimal minBudget;

    @Column(name = "apply_url", columnDefinition = "text")
    private String applyUrl;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "payout_hint", columnDefinition = "text")
    private String payoutHint;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }
}
