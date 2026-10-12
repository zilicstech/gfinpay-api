package com.fintech.persistence.sales;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "vendor_affiliates")
public class VendorAffiliateEntity {

    @Id
    private UUID id;

    @Column(name = "vendor_id", nullable = false)
    private UUID vendorId;

    @Column(name = "employee_name", nullable = false, columnDefinition = "text")
    private String employeeName;

    @Column(name = "employee_code", nullable = false, columnDefinition = "text")
    private String employeeCode;

    @Column(name = "affiliate_ref", nullable = false, unique = true)
    private UUID affiliateRef;

    @Column(name = "apply_token", nullable = false, unique = true, columnDefinition = "text")
    private String applyToken;

    @Column(name = "gfin_code", nullable = false, unique = true, columnDefinition = "text")
    private String gfinCode;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
