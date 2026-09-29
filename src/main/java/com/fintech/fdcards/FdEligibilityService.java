package com.fintech.fdcards;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class FdEligibilityService {

    private final JdbcTemplate jdbc;
    private final FdProviderGate gate;

    public FdEligibilityService(JdbcTemplate jdbc, FdProviderGate gate) {
        this.jdbc = jdbc;
        this.gate = gate;
    }

    public Map<String, Object> evaluate(UUID customerId, BigDecimal budget, List<Map<String, Object>> catalogItems) {
        Map<String, Object> out = new LinkedHashMap<>();
        catalogItems = catalogItems.stream()
                .filter(item -> gate.isProviderEnabled(String.valueOf(item.get("provider"))))
                .toList();
        if (!gate.isModuleEnabled()) {
            out.put("products", List.of());
            out.put("message", "FD card sales are switched off");
            return out;
        }

        List<Map<String, Object>> leads = jdbc.queryForList("""
                SELECT l.id, l.catalog_item_id, l.state, l.payment_link_url, i.provider
                  FROM sales_leads l
                  JOIN catalog_items i ON i.id = l.catalog_item_id
                 WHERE l.customer_id = ?
                   AND i.category_code = 'FD_CARD'
                   AND l.state IN ('LINK_CREATED', 'OPENED', 'IN_PROGRESS', 'CONVERTED', 'ACTIVATED')
                """, customerId);

        Map<String, Map<String, Object>> openByProvider = new LinkedHashMap<>();
        for (Map<String, Object> lead : leads) {
            String provider = String.valueOf(lead.get("provider"));
            boolean open = !"CONVERTED".equals(String.valueOf(lead.get("state")))
                    && !"ACTIVATED".equals(String.valueOf(lead.get("state")));
            if (open && !openByProvider.containsKey(provider)) {
                openByProvider.put(provider, lead);
            }
        }

        boolean zetUsed = leads.stream().anyMatch(lead -> FdProviderGate.ZET.equals(String.valueOf(lead.get("provider"))));
        boolean novuUsed = leads.stream().anyMatch(lead -> gate.isNovuProvider(String.valueOf(lead.get("provider"))));

        List<Map<String, Object>> products = new ArrayList<>();
        for (String provider : preferenceOrder()) {
            if (!gate.isProviderEnabled(provider)) {
                continue;
            }
            boolean zet = FdProviderGate.ZET.equals(provider);
            boolean novu = gate.isNovuProvider(provider);
            if (zet && zetUsed) {
                continue;
            }
            if (novu && novuUsed) {
                continue;
            }
            if (!zet && !novu) {
                continue;
            }
            Map<String, Object> open = openByProvider.get(provider);
            int rank = preferenceRank(provider);
            for (Map<String, Object> item : catalogItems) {
                if (!provider.equals(String.valueOf(item.get("provider")))) {
                    continue;
                }
                Map<String, Object> lead = open != null && item.get("id").equals(open.get("catalog_item_id")) ? open : null;
                products.add(productRow(item, lead, provider, rank));
            }
        }

        out.put("products", products);
        if (products.isEmpty()) {
            out.put("message", emptyMessage(zetUsed, novuUsed));
        }
        return out;
    }

    /** Preference rules first, then any provider not listed, by fallback rank. */
    private List<String> preferenceOrder() {
        List<Map<String, Object>> rules = jdbc.queryForList("""
                SELECT preferred_provider
                  FROM fd_budget_bands
                 ORDER BY preference_rank, preferred_provider
                """);
        List<Map<String, Object>> providers = jdbc.queryForList("""
                SELECT code FROM fd_providers ORDER BY fallback_rank, code
                """);
        Set<String> order = new LinkedHashSet<>();
        for (Map<String, Object> rule : rules) {
            order.add(String.valueOf(rule.get("preferred_provider")));
        }
        for (Map<String, Object> row : providers) {
            order.add(String.valueOf(row.get("code")));
        }
        return List.copyOf(order);
    }

    private static String emptyMessage(boolean zetUsed, boolean novuUsed) {
        if (zetUsed && novuUsed) {
            return "This customer already has a ZET app link and a NOVU app link";
        }
        return "No FD card is available right now. Check provider preference and that the cards are active.";
    }

    private static Map<String, Object> itemFor(List<Map<String, Object>> items, UUID id) {
        for (Map<String, Object> item : items) {
            if (id.equals(item.get("id"))) {
                return item;
            }
        }
        return Map.of("id", id, "code", "", "name", "Secured card", "product_key", "");
    }

    private int preferenceRank(String provider) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT preference_rank FROM fd_budget_bands WHERE preferred_provider = ?
                """, provider);
        if (!rows.isEmpty() && rows.get(0).get("preference_rank") != null) {
            return ((Number) rows.get(0).get("preference_rank")).intValue();
        }
        List<Map<String, Object>> fallback = jdbc.queryForList(
                "SELECT fallback_rank FROM fd_providers WHERE code = ?", provider);
        if (!fallback.isEmpty() && fallback.get(0).get("fallback_rank") != null) {
            return 1000 + ((Number) fallback.get(0).get("fallback_rank")).intValue();
        }
        return 2000;
    }

    private static Map<String, Object> productRow(Map<String, Object> item, Map<String, Object> lead,
                                                  String provider, int preferenceRank) {
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("id", item.get("id"));
        product.put("code", item.get("code"));
        product.put("name", item.get("name"));
        product.put("product_key", item.get("product_key"));
        product.put("provider", provider);
        product.put("preference_rank", preferenceRank);
        if (lead != null) {
            product.put("existing_lead_id", lead.get("id"));
            product.put("payment_link_url", lead.get("payment_link_url"));
            product.put("state", lead.get("state"));
        }
        return product;
    }
}
