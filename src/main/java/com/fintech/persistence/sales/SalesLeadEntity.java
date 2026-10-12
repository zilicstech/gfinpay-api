package com.fintech.persistence.sales;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "sales_leads")
public class SalesLeadEntity {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "catalog_item_id", nullable = false)
    private UUID catalogItemId;

    @Column(name = "retailer_user_id", nullable = false)
    private UUID retailerUserId;

    @Column(nullable = false, columnDefinition = "text")
    private String state;

    private BigDecimal budget;

    @Column(name = "sale_type", nullable = false, columnDefinition = "text")
    private String saleType;

    @Column(name = "sale_provider", nullable = false, columnDefinition = "text")
    private String saleProvider;

    @Column(name = "sale_channel", nullable = false, columnDefinition = "text")
    private String saleChannel;

    @Column(name = "distributor_user_id")
    private UUID distributorUserId;

    @Column(name = "hub_id")
    private UUID hubId;

    @Column(name = "provider_refid", columnDefinition = "text")
    private String providerRefid;

    @Column(name = "link_token", nullable = false, unique = true, columnDefinition = "text")
    private String linkToken;

    @Column(name = "payment_link_url", columnDefinition = "text")
    private String paymentLinkUrl;

    @Column(columnDefinition = "text")
    private String encdata;

    @Column(name = "link_opened_at")
    private OffsetDateTime linkOpenedAt;

    @Column(name = "partner_status", columnDefinition = "text")
    private String partnerStatus;

    @Column(name = "partner_status_at")
    private OffsetDateTime partnerStatusAt;

    @Column(name = "partner_user_id", columnDefinition = "text")
    private String partnerUserId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "partner_snapshot")
    private Map<String, Object> partnerSnapshot;

    @Column(name = "distributor_code", nullable = false, columnDefinition = "text")
    private String distributorCode;

    @Column(name = "distributor_name", nullable = false, columnDefinition = "text")
    private String distributorName;

    @Column(name = "retailer_code", nullable = false, columnDefinition = "text")
    private String retailerCode;

    @Column(name = "retailer_name", nullable = false, columnDefinition = "text")
    private String retailerName;

    @Column(name = "hub_name", nullable = false, columnDefinition = "text")
    private String hubName;

    @Column(name = "product_code", nullable = false, columnDefinition = "text")
    private String productCode;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
