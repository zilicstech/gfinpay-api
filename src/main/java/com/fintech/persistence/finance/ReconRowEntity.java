package com.fintech.persistence.finance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "recon_rows")
public class ReconRowEntity {

    @Id
    private UUID id;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(name = "customer_mobile", columnDefinition = "text")
    private String customerMobile;

    @Column(name = "customer_name", columnDefinition = "text")
    private String customerName;

    @Column(name = "lead_id")
    private UUID leadId;

    @Column(name = "retailer_user_id")
    private UUID retailerUserId;

    @Column(name = "distributor_user_id")
    private UUID distributorUserId;

    @Column(name = "hub_id")
    private UUID hubId;

    @Column(name = "retailer_label", nullable = false, columnDefinition = "text")
    private String retailerLabel;

    @Column(name = "distributor_label", nullable = false, columnDefinition = "text")
    private String distributorLabel;

    @Column(name = "hub_label", columnDefinition = "text")
    private String hubLabel;

    @Column(nullable = false)
    private boolean identified;

    @Column(name = "current_status", nullable = false, columnDefinition = "text")
    private String currentStatus;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
