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

    private static final String LEAD_SELECT = """
            SELECT l.id, l.state, l.sale_channel, l.retailer_user_id, l.budget, l.provider_refid, l.customer_id,
                   l.distributor_user_id, l.hub_id,
                   l.distributor_code, l.distributor_name, l.retailer_code, l.retailer_name,
                   l.hub_name, l.product_code, l.sale_provider, l.sale_provider AS provider, l.sale_type,
                   c.mobile AS customer_mobile, c.full_name AS customer_name
              FROM sales_leads l
              JOIN customers c ON c.id = l.customer_id
            """;

    private final JdbcTemplate jdbc;

    public FdMisMatcher(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Map<String, Object>> matchLead(FdMisRow row) {
        if (row.matchRef() != null && !row.matchRef().isBlank()) {
            Optional<Map<String, Object>> byRef = matchByProviderRef(row);
            if (byRef.isPresent()) {
                return byRef;
            }
        }
        List<Map<String, Object>> customers = jdbc.queryForList(
                "SELECT id FROM customers WHERE mobile = ? LIMIT 1", row.phone());
        if (customers.isEmpty()) {
            return Optional.empty();
        }
        UUID customerId = (UUID) customers.get(0).get("id");
        List<Map<String, Object>> leads = jdbc.queryForList(LEAD_SELECT + """
                 WHERE l.customer_id = ?
                   AND l.product_code = ?
                   AND l.sale_type = 'FD_CARD'
                   AND (l.sale_provider = 'ZET')
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

    private Optional<Map<String, Object>> matchByProviderRef(FdMisRow row) {
        String ref = row.matchRef().trim();
        List<Map<String, Object>> leads = jdbc.queryForList(LEAD_SELECT + """
                 WHERE (l.provider_refid = ? OR l.id::text = ?)
                   AND l.sale_type = 'FD_CARD'
                   AND (l.sale_provider = 'ZET')
                   AND l.state <> 'EXPIRED'
                 LIMIT 1
                """, ref, ref);
        if (leads.isEmpty()) {
            return Optional.empty();
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
                SELECT l.id, l.provider_refid, l.state, l.product_code, c.mobile, c.full_name
                  FROM sales_leads l
                  JOIN customers c ON c.id = l.customer_id
                 WHERE l.sale_type = 'FD_CARD'
                   AND l.sale_channel = 'INTERNAL'
                   AND l.sale_provider = 'ZET'
                   AND l.payment_link_url IS NOT NULL
                   AND l.state IN ('LINK_CREATED', 'OPENED', 'IN_PROGRESS')
                """);
        for (Map<String, Object> lead : openLeads) {
            String mobile = String.valueOf(lead.get("mobile")).replaceAll("\\D", "");
            if (mobile.length() >= 10) {
                mobile = mobile.substring(mobile.length() - 10);
            }
            String productKey = String.valueOf(lead.get("product_code"));
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
