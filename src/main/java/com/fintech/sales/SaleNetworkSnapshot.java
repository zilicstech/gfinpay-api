package com.fintech.sales;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Stamps report dimensions on a sale so later retailer/hub moves do not rewrite history. */
@Component
public class SaleNetworkSnapshot {

    static final Set<String> NAMED_PROVIDERS = Set.of("ZET", "PAYSPRINT", "GROWMORE");

    private final JdbcTemplate jdbc;

    public SaleNetworkSnapshot(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Attribution(String saleType, String saleProvider, UUID distributorUserId, UUID hubId) {}

    public Attribution forLead(UUID catalogItemId, UUID retailerUserId) {
        return jdbc.queryForObject("""
                SELECT i.category_code,
                       i.provider,
                       r.parent_id AS distributor_user_id,
                       COALESCE(r.hub_id, d.hub_id) AS hub_id
                  FROM catalog_items i
                  JOIN users r ON r.id = ?
                  LEFT JOIN users d ON d.id = r.parent_id
                 WHERE i.id = ?
                """,
                (rs, n) -> new Attribution(
                        rs.getString("category_code"),
                        normalizeProvider(rs.getString("provider")),
                        (UUID) rs.getObject("distributor_user_id"),
                        (UUID) rs.getObject("hub_id")),
                retailerUserId, catalogItemId);
    }

    public static String normalizeProvider(String raw) {
        if (raw == null || raw.isBlank()) {
            return "OTHERS";
        }
        String code = raw.trim().toUpperCase(Locale.ROOT);
        return NAMED_PROVIDERS.contains(code) ? code : "OTHERS";
    }
}
