package com.fintech.sales;

import com.fintech.fdcards.FdProviderGate;
import com.fintech.persistence.config.CatalogItemRepository;
import com.fintech.platform.PlatformServiceGate;
import com.fintech.platform.web.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {

    private final JdbcTemplate jdbc;
    private final FdProviderGate fdProviders;
    private final PlatformServiceGate services;
    private final CatalogItemRepository items;

    public CatalogService(JdbcTemplate jdbc, FdProviderGate fdProviders, PlatformServiceGate services,
                          CatalogItemRepository items) {
        this.jdbc = jdbc;
        this.fdProviders = fdProviders;
        this.services = services;
        this.items = items;
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
        FdProviderGate.Directory providers = fdProviders.directory();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> category : categories) {
            String code = String.valueOf(category.get("code"));
            Map<String, Object> row = new LinkedHashMap<>(category);
            row.put("items", items.stream()
                    .filter(item -> code.equals(String.valueOf(item.get("category_code"))))
                    .filter(item -> fdItemVisible(code, String.valueOf(item.get("provider")), providers))
                    .toList());
            if (!includeInactive && !services.isCatalogCategoryAvailable(code)) {
                continue;
            }
            if (!includeInactive && ((List<?>) row.get("items")).isEmpty()) {
                continue;
            }
            out.add(row);
        }
        return out;
    }

    @Transactional
    public Map<String, Object> setActive(UUID itemId, boolean active) {
        int n = items.updateActive(itemId, active);
        if (n == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "ITEM_NOT_FOUND", "Catalog item not found");
        }
        return jdbc.queryForMap("""
                SELECT id, code, category_code, name, provider, rail, external_product, active, payout_hint, sort_order
                  FROM catalog_items WHERE id = ?
                """, itemId);
    }

    private static boolean fdItemVisible(String categoryCode, String provider, FdProviderGate.Directory providers) {
        if (!"FD_CARD".equals(categoryCode)) {
            return true;
        }
        return providers.enabled(provider);
    }
}
