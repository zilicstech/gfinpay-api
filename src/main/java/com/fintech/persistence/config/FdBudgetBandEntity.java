package com.fintech.persistence.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "fd_budget_bands")
public class FdBudgetBandEntity {

    @Id
    private UUID id;

    @Column(name = "slab_min", nullable = false)
    private BigDecimal slabMin;

    @Column(name = "slab_max", nullable = false)
    private BigDecimal slabMax;

    @Column(name = "preferred_provider", nullable = false, columnDefinition = "text")
    private String preferredProvider;

    @Column(name = "preference_rank", nullable = false)
    private int preferenceRank;

    public UUID getId() {
        return id;
    }
}
