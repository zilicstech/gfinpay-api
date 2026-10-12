package com.fintech.persistence.finance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "finance_requests")
public class FinanceRequestEntity {

    @Id
    private UUID id;

    @Column(nullable = false, columnDefinition = "text")
    private String kind;

    @Column(name = "retailer_user_id", nullable = false)
    private UUID retailerUserId;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, columnDefinition = "text")
    private String utr;

    @Column(name = "proof_key", nullable = false, columnDefinition = "text")
    private String proofKey;

    @Column(name = "proof_filename", columnDefinition = "text")
    private String proofFilename;

    @Column(name = "proof_content_type", columnDefinition = "text")
    private String proofContentType;

    @Column(columnDefinition = "text")
    private String remarks;

    @Column(nullable = false, columnDefinition = "text")
    private String status;

    @Column(name = "auditor_user_id")
    private UUID auditorUserId;

    @Column(name = "auditor_notes", columnDefinition = "text")
    private String auditorNotes;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column(name = "wallet_txn_id")
    private UUID walletTxnId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
