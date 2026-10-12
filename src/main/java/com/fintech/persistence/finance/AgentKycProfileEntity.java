package com.fintech.persistence.finance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "agent_kyc_profiles")
public class AgentKycProfileEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "aadhaar_ref_key", columnDefinition = "text")
    private String aadhaarRefKey;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "aadhaar_last4", length = 4, columnDefinition = "char(4)")
    private String aadhaarLast4;

    @Column(name = "aadhaar_verified_at")
    private OffsetDateTime aadhaarVerifiedAt;

    @Column(name = "pan_enc", columnDefinition = "text")
    private String panEnc;

    @Column(name = "pan_verified_at")
    private OffsetDateTime panVerifiedAt;

    @Column(name = "liveness_score")
    private BigDecimal livenessScore;

    @Column(name = "liveness_passed_at")
    private OffsetDateTime livenessPassedAt;

    @Column(name = "shop_name", columnDefinition = "text")
    private String shopName;

    @Column(length = 15)
    private String gstin;

    @Column(name = "gstin_verified_at")
    private OffsetDateTime gstinVerifiedAt;

    @Column(name = "ovd_type", columnDefinition = "text")
    private String ovdType;

    @Column(name = "kyc_status", nullable = false, columnDefinition = "text")
    private String kycStatus;

    @Column(name = "rejection_reason", columnDefinition = "text")
    private String rejectionReason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public UUID getUserId() {
        return userId;
    }

    public String getKycStatus() {
        return kycStatus;
    }
}
