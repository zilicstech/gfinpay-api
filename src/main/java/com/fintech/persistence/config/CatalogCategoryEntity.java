package com.fintech.persistence.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "catalog_categories")
public class CatalogCategoryEntity {

    @Id
    @Column(columnDefinition = "text")
    private String code;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "eligibility_mode", nullable = false, columnDefinition = "text")
    private String eligibilityMode;

    @Column(name = "payout_hint", columnDefinition = "text")
    private String payoutHint;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public String getCode() {
        return code;
    }
}
