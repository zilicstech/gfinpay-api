package com.fintech.persistence.sales;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "customers")
public class CustomerEntity {

    @Id
    private UUID id;

    @Column(name = "retailer_user_id")
    private UUID retailerUserId;

    @Column(name = "vendor_id")
    private UUID vendorId;

    @Column(name = "affiliate_id")
    private UUID affiliateId;

    @Column(name = "full_name", nullable = false, columnDefinition = "text")
    private String fullName;

    @Column(nullable = false, unique = true, length = 15)
    private String mobile;

    @Column(columnDefinition = "text")
    private String email;
    @Column(columnDefinition = "text")
    private String city;
    @Column(columnDefinition = "text")
    private String state;
    @Column(columnDefinition = "text")
    private String pincode;

    @Column(name = "employment_type", columnDefinition = "text")
    private String employmentType;

    @Column(name = "monthly_income")
    private BigDecimal monthlyIncome;

    @Column(name = "ekyc_status", nullable = false, columnDefinition = "text")
    private String ekycStatus;

    @Column(name = "ekyc_verified_at")
    private OffsetDateTime ekycVerifiedAt;

    @Column(name = "ovd_type", columnDefinition = "text")
    private String ovdType;

    @Column(name = "ovd_last4", length = 4)
    private String ovdLast4;

    @Column(name = "created_by_role", nullable = false, columnDefinition = "text")
    private String createdByRole;

    @Column(name = "created_by_code", nullable = false, columnDefinition = "text")
    private String createdByCode;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
