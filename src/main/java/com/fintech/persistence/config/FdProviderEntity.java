package com.fintech.persistence.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "fd_providers")
public class FdProviderEntity {

    @Id
    @Column(columnDefinition = "text")
    private String code;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false)
    private boolean novu;

    @Column(name = "fallback_rank", nullable = false)
    private int fallbackRank;

    @Column(name = "commission_txn_type", nullable = false, columnDefinition = "text")
    private String commissionTxnType;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public String getCode() {
        return code;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
