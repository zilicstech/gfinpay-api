package com.fintech.fdcards;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class FdCommissionTierService {

    public record TierPayout(
            BigDecimal perCardPool,
            BigDecimal retailerShare,
            BigDecimal distributorShare,
            BigDecimal platformShare) {}

    private final JdbcTemplate jdbc;

    public FdCommissionTierService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int monthlyConvertedCount(String providerCode) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)::int
                  FROM sales_leads l
                  JOIN catalog_items i ON i.id = l.catalog_item_id
                 WHERE l.state = 'CONVERTED'
                   AND i.provider = ?
                   AND l.updated_at >= date_trunc('month', CURRENT_TIMESTAMP)
                """, Integer.class, providerCode);
        return count == null ? 0 : count;
    }

    public TierPayout resolveForConversion(String providerCode) {
        int cardsThisMonth = monthlyConvertedCount(providerCode);
        List<Map<String, Object>> tiers = jdbc.queryForList("""
                SELECT fixed_amount_per_card, retailer_share_pct, distributor_share_pct, platform_share_pct
                  FROM fd_commission_tiers
                 WHERE provider_code = ?
                   AND ? BETWEEN cards_min AND cards_max
                 ORDER BY cards_min DESC
                 LIMIT 1
                """, providerCode, cardsThisMonth);
        if (tiers.isEmpty()) {
            return new TierPayout(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        Map<String, Object> tier = tiers.get(0);
        BigDecimal pool = ((BigDecimal) tier.get("fixed_amount_per_card")).setScale(2, RoundingMode.HALF_UP);
        return new TierPayout(
                pool,
                pct(pool, (BigDecimal) tier.get("retailer_share_pct")),
                pct(pool, (BigDecimal) tier.get("distributor_share_pct")),
                pct(pool, (BigDecimal) tier.get("platform_share_pct")));
    }

    private static BigDecimal pct(BigDecimal amount, BigDecimal pct) {
        return amount.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}
