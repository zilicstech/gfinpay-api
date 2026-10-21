package com.fintech.persistence.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Read model plus status flips. Balance mutations stay on JdbcTemplate with row locks. */
@Entity
@Table(name = "wallets")
public class WalletEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "wallet_kind", nullable = false, columnDefinition = "text")
    private String walletKind;

    @Column(name = "available_balance", nullable = false)
    private BigDecimal availableBalance;

    @Column(name = "hold_balance", nullable = false)
    private BigDecimal holdBalance;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(nullable = false, columnDefinition = "text")
    private String status;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
