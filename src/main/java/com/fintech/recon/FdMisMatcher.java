package com.fintech.recon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class FdMisMatcher {

    private final JdbcTemplate jdbc;

    public FdMisMatcher(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Map<String, Object>> matchLead(FdMisRow row) {
        List<Map<String, Object>> customers = jdbc.queryForList(
                "SELECT id FROM customers WHERE mobile = ? LIMIT 1", row.phone());
        if (customers.isEmpty()) {
            return Optional.empty();
        }
        UUID customerId = (UUID) customers.get(0).get("id");
        List<Map<String, Object>> leads = jdbc.queryForList("""
                SELECT l.id, l.state, l.retailer_user_id, l.budget, l.provider_refid, l.customer_id,
                       l.distributor_user_id, l.hub_id,
                       i.provider, i.category_code, i.product_key, i.code AS item_code,
                       c.mobile AS customer_mobile, c.full_name AS customer_name,
                       r.full_name AS retailer_name, r.code AS retailer_code,
                       d.full_name AS distributor_name, d.code AS distributor_code,
                       h.name AS hub_name
                  FROM sales_leads l
                  JOIN catalog_items i ON i.id = l.catalog_item_id
                  JOIN customers c ON c.id = l.customer_id
                  JOIN users r ON r.id = l.retailer_user_id
                  LEFT JOIN users d ON d.id = COALESCE(l.distributor_user_id, r.parent_id)
                  LEFT JOIN hubs h ON h.id = COALESCE(l.hub_id, r.hub_id)
                 WHERE l.customer_id = ?
                   AND i.product_key = ?
                   AND i.category_code = 'FD_CARD'
                   AND (l.sale_provider = 'ZET' OR i.provider = 'ZET')
                   AND l.state <> 'EXPIRED'
                 ORDER BY l.created_at DESC
                """, customerId, row.productKey());
        if (leads.isEmpty()) {
            return Optional.empty();
        }
        if (row.matchRef() != null) {
            for (Map<String, Object> lead : leads) {
                UUID id = (UUID) lead.get("id");
                String ref = lead.get("provider_refid") == null ? null : String.valueOf(lead.get("provider_refid"));
                if (row.matchRef().equals(id.toString()) || row.matchRef().equals(ref)) {
                    return Optional.of(lead);
                }
            }
        }
        return Optional.of(leads.get(0));
    }

    public FdReconMismatch missingInternal(FdMisRow row) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("phone", row.phone());
        details.put("name", row.fullName());
        details.put("sheet", row.sheetName());
        details.put("partner_status", row.partnerStatus());
        details.put("source_column", row.sourceColumn());
        return new FdReconMismatch("MISSING_INTERNAL", row.partnerUserId(), details);
    }

    public List<FdReconMismatch> missingAtPartner(List<FdMisRow> parsedRows) {
        Set<String> seen = new HashSet<>();
        for (FdMisRow row : parsedRows) {
            seen.add(row.productKey() + ":" + row.phone());
        }
        List<FdReconMismatch> out = new ArrayList<>();
        List<Map<String, Object>> openLeads = jdbc.queryForList("""
                SELECT l.id, l.provider_refid, l.state, c.mobile, c.full_name, i.product_key
                  FROM sales_leads l
                  JOIN customers c ON c.id = l.customer_id
                  JOIN catalog_items i ON i.id = l.catalog_item_id
                 WHERE i.category_code = 'FD_CARD'
                   AND (l.sale_provider = 'ZET' OR i.provider = 'ZET')
                   AND l.payment_link_url IS NOT NULL
                   AND l.state IN ('LINK_CREATED', 'OPENED', 'IN_PROGRESS')
                """);
        for (Map<String, Object> lead : openLeads) {
            String mobile = String.valueOf(lead.get("mobile")).replaceAll("\\D", "");
            if (mobile.length() >= 10) {
                mobile = mobile.substring(mobile.length() - 10);
            }
            String productKey = String.valueOf(lead.get("product_key"));
            if (seen.contains(productKey + ":" + mobile)) {
                continue;
            }
            UUID id = (UUID) lead.get("id");
            String partnerRef = lead.get("provider_refid") == null ? id.toString() : String.valueOf(lead.get("provider_refid"));
            Map<String, Object> details = new HashMap<>();
            details.put("phone", mobile);
            details.put("name", lead.get("full_name"));
            details.put("product_key", productKey);
            details.put("lead_id", id.toString());
            details.put("state", lead.get("state"));
            out.add(new FdReconMismatch("MISSING_AT_PARTNER", partnerRef, details));
        }
        return out;
    }
}
