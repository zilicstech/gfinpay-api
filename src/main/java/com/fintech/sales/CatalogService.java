package com.fintech.sales;

import com.fintech.fdcards.FdProviderGate;
import com.fintech.platform.web.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CatalogService {

    private final JdbcTemplate jdbc;
    private final FdProviderGate fdProviders;

    public CatalogService(JdbcTemplate jdbc, FdProviderGate fdProviders) {
        this.jdbc = jdbc;
        this.fdProviders = fdProviders;
    }

    public List<Map<String, Object>> list(boolean includeInactive) {
        List<Map<String, Object>> categories = jdbc.queryForList("""
                SELECT code, name, sort_order, eligibility_mode, payout_hint
                  FROM catalog_categories
                 ORDER BY sort_order, name
                """);
        String itemSql = """
                SELECT id, code, category_code, name, provider, rail, external_product, active, payout_hint, sort_order
                  FROM catalog_items
                """ + (includeInactive ? "" : " WHERE active ") + """
                 ORDER BY sort_order, name
                """;
        List<Map<String, Object>> items = jdbc.queryForList(itemSql);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> category : categories) {
            String code = String.valueOf(category.get("code"));
            Map<String, Object> row = new LinkedHashMap<>(category);
            row.put("items", items.stream()
                    .filter(item -> code.equals(String.valueOf(item.get("category_code"))))
                    .filter(item -> fdItemVisible(code, String.valueOf(item.get("provider"))))
                    .toList());
            if (!includeInactive && ((List<?>) row.get("items")).isEmpty()) {
                continue;
            }
            out.add(row);
        }
        return out;
    }

    public Map<String, Object> setActive(UUID itemId, boolean active) {
        int n = jdbc.update("UPDATE catalog_items SET active = ? WHERE id = ?", active, itemId);
        if (n == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "ITEM_NOT_FOUND", "Catalog item not found");
        }
        return jdbc.queryForMap("""
                SELECT id, code, category_code, name, provider, rail, external_product, active, payout_hint, sort_order
                  FROM catalog_items WHERE id = ?
                """, itemId);
    }

    private boolean fdItemVisible(String categoryCode, String provider) {
        if (!"FD_CARD".equals(categoryCode)) {
            return true;
        }
        return fdProviders.isProviderEnabled(provider);
    }
}
