package com.fintech.persistence.finance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "recon_mismatches")
public class ReconMismatchEntity {

    @Id
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(name = "mismatch_type", nullable = false, columnDefinition = "text")
    private String mismatchType;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "partner_ref", columnDefinition = "text")
    private String partnerRef;

    @Column(columnDefinition = "text")
    private String resolution;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
