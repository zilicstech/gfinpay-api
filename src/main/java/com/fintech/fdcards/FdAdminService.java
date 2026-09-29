package com.fintech.fdcards;

import com.fintech.platform.web.ApiException;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
public class FdAdminService {

    private static final Map<String, List<String>> CATALOG_URL_CODES = Map.of(
            FdProviderGate.ZET, List.of("SBM_FD_ZET", "IOB_FD_ZET"),
            FdProviderGate.GROWMORE, List.of("DCB_FD_GROWMORE"));

    private final JdbcTemplate jdbc;

    public FdAdminService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> listProviders() {
        List<Map<String, Object>> providers = jdbc.queryForList("""
                SELECT code, name, enabled, novu, fallback_rank, commission_txn_type, updated_at
                  FROM fd_providers
                 ORDER BY fallback_rank, code
                """);
        for (Map<String, Object> provider : providers) {
            provider.put("catalog_items", catalogItemsForProvider(String.valueOf(provider.get("code"))));
            provider.put("commission_rule", commissionRuleFor(String.valueOf(provider.get("commission_txn_type"))));
        }
        return providers;
    }

    public Map<String, Object> setProviderEnabled(String code, boolean enabled) {
        int n = jdbc.update("UPDATE fd_providers SET enabled = ?, updated_at = now() WHERE code = ?", enabled, code);
        if (n == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "PROVIDER_NOT_FOUND", "FD provider not found");
        }
        return getProvider(code);
    }

    @Transactional
    public Map<String, Object> updateProviderConfig(String code, Integer fallbackRank, Map<String, String> applyUrls) {
        if (fallbackRank != null) {
            jdbc.update("UPDATE fd_providers SET fallback_rank = ?, updated_at = now() WHERE code = ?", fallbackRank, code);
        }
        if (applyUrls != null && !applyUrls.isEmpty()) {
            List<String> itemCodes = CATALOG_URL_CODES.getOrDefault(code, List.of());
            for (String itemCode : itemCodes) {
                String url = applyUrls.get(itemCode);
                if (url != null) {
                    jdbc.update("UPDATE catalog_items SET apply_url = ? WHERE code = ?", url.isBlank() ? null : url, itemCode);
                }
            }
        }
        return getProvider(code);
    }

    public List<Map<String, Object>> listFdCards() {
        return jdbc.queryForList("""
                SELECT i.code, i.name, i.product_key, i.provider, i.rail, i.apply_url, i.active, i.sort_order,
                       p.name AS provider_name, p.enabled AS provider_enabled, p.novu
                  FROM catalog_items i
                  JOIN fd_providers p ON p.code = i.provider
                 WHERE i.category_code = 'FD_CARD'
                 ORDER BY i.sort_order, i.name
                """);
    }

    public Map<String, Object> updateFdCard(String code, String applyUrl, Boolean active) {
        if (applyUrl != null) {
            jdbc.update("UPDATE catalog_items SET apply_url = ? WHERE code = ? AND category_code = 'FD_CARD'",
                    applyUrl.isBlank() ? null : applyUrl, code);
        }
        if (active != null) {
            jdbc.update("UPDATE catalog_items SET active = ? WHERE code = ? AND category_code = 'FD_CARD'", active, code);
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT i.code, i.name, i.product_key, i.provider, i.rail, i.apply_url, i.active, i.sort_order,
                       p.name AS provider_name, p.enabled AS provider_enabled, p.novu
                  FROM catalog_items i
                  JOIN fd_providers p ON p.code = i.provider
                 WHERE i.code = ? AND i.category_code = 'FD_CARD'
                """, code);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", "FD card not found");
        }
        return rows.get(0);
    }

    public List<Map<String, Object>> listBudgetBands() {
        return jdbc.queryForList("""
                SELECT b.id, b.slab_min, b.slab_max, b.preferred_provider, b.preference_rank,
                       p.name AS preferred_provider_name
                  FROM fd_budget_bands b
                  JOIN fd_providers p ON p.code = b.preferred_provider
                 ORDER BY b.preference_rank, b.slab_min
                """);
    }

    @Transactional
    public Map<String, Object> createRule(BudgetBandInput band) {
        validateSingleBand(band, null);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fd_budget_bands (id, slab_min, slab_max, preferred_provider, preference_rank)
                VALUES (?, 0, 0, ?, ?)
                """, id, band.preferredProvider(), band.preferenceRank());
        return getRule(id);
    }

    @Transactional
    public Map<String, Object> updateRule(UUID id, BudgetBandInput band) {
        validateSingleBand(band, id);
        int n = jdbc.update("""
                UPDATE fd_budget_bands
                   SET slab_min = 0, slab_max = 0, preferred_provider = ?, preference_rank = ?
                 WHERE id = ?
                """, band.preferredProvider(), band.preferenceRank(), id);
        if (n == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "RULE_NOT_FOUND", "Rule not found");
        }
        return getRule(id);
    }

    public void deleteRule(UUID id) {
        int n = jdbc.update("DELETE FROM fd_budget_bands WHERE id = ?", id);
        if (n == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "RULE_NOT_FOUND", "Rule not found");
        }
    }

    private Map<String, Object> getRule(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT b.id, b.slab_min, b.slab_max, b.preferred_provider, b.preference_rank,
                       p.name AS preferred_provider_name
                  FROM fd_budget_bands b
                  JOIN fd_providers p ON p.code = b.preferred_provider
                 WHERE b.id = ?
                """, id);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "RULE_NOT_FOUND", "Rule not found");
        }
        return rows.get(0);
    }

    @Transactional
    public List<Map<String, Object>> replaceBudgetBands(List<BudgetBandInput> bands) {
        validateBands(bands);
        jdbc.update("DELETE FROM fd_budget_bands");
        for (BudgetBandInput band : bands) {
            jdbc.update("""
                    INSERT INTO fd_budget_bands (slab_min, slab_max, preferred_provider, preference_rank)
                    VALUES (0, 0, ?, ?)
                    """, band.preferredProvider(), band.preferenceRank());
        }
        return listBudgetBands();
    }

    public List<Map<String, Object>> listCommissionTiers() {
        return jdbc.queryForList("""
                SELECT t.id, t.provider_code, p.name AS provider_name,
                       t.cards_min, t.cards_max, t.fixed_amount_per_card,
                       t.retailer_share_pct, t.distributor_share_pct, t.platform_share_pct
                  FROM fd_commission_tiers t
                  JOIN fd_providers p ON p.code = t.provider_code
                 ORDER BY t.provider_code, t.cards_min
                """);
    }

    @Transactional
    public Map<String, Object> createCommissionTier(CommissionTierInput input) {
        validateTier(input);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fd_commission_tiers
                    (id, provider_code, cards_min, cards_max, fixed_amount_per_card,
                     retailer_share_pct, distributor_share_pct, platform_share_pct)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, input.providerCode(), input.cardsMin(), input.cardsMax(), input.fixedAmountPerCard(),
                input.retailerSharePct(), input.distributorSharePct(), input.platformSharePct());
        return getCommissionTier(id);
    }

    @Transactional
    public Map<String, Object> updateCommissionTier(UUID id, CommissionTierInput input) {
        validateTier(input);
        int n = jdbc.update("""
                UPDATE fd_commission_tiers
                   SET provider_code = ?, cards_min = ?, cards_max = ?, fixed_amount_per_card = ?,
                       retailer_share_pct = ?, distributor_share_pct = ?, platform_share_pct = ?
                 WHERE id = ?
                """, input.providerCode(), input.cardsMin(), input.cardsMax(), input.fixedAmountPerCard(),
                input.retailerSharePct(), input.distributorSharePct(), input.platformSharePct(), id);
        if (n == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "TIER_NOT_FOUND", "Commission tier not found");
        }
        return getCommissionTier(id);
    }

    public void deleteCommissionTier(UUID id) {
        int n = jdbc.update("DELETE FROM fd_commission_tiers WHERE id = ?", id);
        if (n == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "TIER_NOT_FOUND", "Commission tier not found");
        }
    }

    private Map<String, Object> getCommissionTier(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.id, t.provider_code, p.name AS provider_name,
                       t.cards_min, t.cards_max, t.fixed_amount_per_card,
                       t.retailer_share_pct, t.distributor_share_pct, t.platform_share_pct
                  FROM fd_commission_tiers t
                  JOIN fd_providers p ON p.code = t.provider_code
                 WHERE t.id = ?
                """, id);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "TIER_NOT_FOUND", "Commission tier not found");
        }
        return rows.get(0);
    }

    public Map<String, Object> upsertCommissionRule(String txnType, CommissionRuleInput input) {
        validateFixedRule(input);
        BigDecimal[] shares = percentSplit(input.flatFee(), input.retailerAmount(), input.distributorAmount());
        List<Map<String, Object>> existing = jdbc.queryForList("""
                SELECT id FROM commission_rules
                 WHERE txn_type = ? AND effective_to IS NULL
                 ORDER BY effective_from DESC LIMIT 1
                """, txnType);
        if (existing.isEmpty()) {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO commission_rules
                        (id, txn_type, slab_min, slab_max, total_rate_bp, flat_fee,
                         retailer_share_pct, distributor_share_pct, platform_share_pct,
                         commission_type, retailer_amount, distributor_amount, effective_from)
                    VALUES (?, ?, 1, 999999999, 0, ?, ?, ?, ?, 'FIXED', ?, ?, now())
                    """, id, txnType, input.flatFee(), shares[0], shares[1], shares[2],
                    input.retailerAmount(), input.distributorAmount());
        } else {
            UUID id = (UUID) existing.get(0).get("id");
            jdbc.update("""
                    UPDATE commission_rules
                       SET slab_min = 1, slab_max = 999999999, total_rate_bp = 0, flat_fee = ?,
                           retailer_share_pct = ?, distributor_share_pct = ?, platform_share_pct = ?,
                           commission_type = 'FIXED', retailer_amount = ?, distributor_amount = ?
                     WHERE id = ?
                    """, input.flatFee(), shares[0], shares[1], shares[2],
                    input.retailerAmount(), input.distributorAmount(), id);
        }
        return commissionRuleFor(txnType);
    }

    public Map<String, Object> getProvider(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT code, name, enabled, novu, fallback_rank, commission_txn_type, updated_at
                  FROM fd_providers WHERE code = ?
                """, code);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "PROVIDER_NOT_FOUND", "FD provider not found");
        }
        Map<String, Object> provider = new LinkedHashMap<>(rows.get(0));
        provider.put("catalog_items", catalogItemsForProvider(code));
        provider.put("commission_rule", commissionRuleFor(String.valueOf(provider.get("commission_txn_type"))));
        return provider;
    }

    private List<Map<String, Object>> catalogItemsForProvider(String providerCode) {
        return jdbc.queryForList("""
                SELECT code, name, product_key, rail, apply_url, active
                  FROM catalog_items
                 WHERE category_code = 'FD_CARD' AND provider = ?
                 ORDER BY sort_order
                """, providerCode);
    }

    private Map<String, Object> commissionRuleFor(String txnType) {
        List<Map<String, Object>> rules = jdbc.queryForList("""
                SELECT id, txn_type, slab_min, slab_max, total_rate_bp, flat_fee,
                       retailer_share_pct, distributor_share_pct, platform_share_pct,
                       commission_type, retailer_amount, distributor_amount
                  FROM commission_rules
                 WHERE txn_type = ? AND effective_to IS NULL
                 ORDER BY effective_from DESC LIMIT 1
                """, txnType);
        if (rules.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> rule = new LinkedHashMap<>(rules.get(0));
        BigDecimal received = decimal(rule.get("flat_fee"));
        BigDecimal retailer = decimal(rule.get("retailer_amount"));
        BigDecimal distributor = decimal(rule.get("distributor_amount"));
        if (rule.get("retailer_amount") == null && received != null) {
            retailer = pctOf(received, decimal(rule.get("retailer_share_pct")));
            distributor = pctOf(received, decimal(rule.get("distributor_share_pct")));
        }
        rule.put("profit_per_card", nz(received).subtract(nz(retailer)).subtract(nz(distributor)));
        return rule;
    }

    private static void validateFixedRule(CommissionRuleInput input) {
        if (input.retailerAmount().add(input.distributorAmount()).compareTo(input.flatFee()) > 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_SPLIT",
                    "Retailer and distributor amounts cannot exceed amount received per card");
        }
    }

    private static BigDecimal[] percentSplit(BigDecimal pool, BigDecimal retailer, BigDecimal distributor) {
        if (pool.compareTo(BigDecimal.ZERO) <= 0) {
            return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(100)};
        }
        BigDecimal hundred = BigDecimal.valueOf(100);
        BigDecimal r = retailer.multiply(hundred).divide(pool, 2, RoundingMode.HALF_UP);
        BigDecimal d = distributor.multiply(hundred).divide(pool, 2, RoundingMode.HALF_UP);
        BigDecimal p = hundred.subtract(r).subtract(d);
        if (p.compareTo(BigDecimal.ZERO) < 0) {
            p = BigDecimal.ZERO;
            d = hundred.subtract(r);
        }
        return new BigDecimal[]{r, d, p};
    }

    private static BigDecimal pctOf(BigDecimal amount, BigDecimal percentage) {
        if (amount == null || percentage == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return amount.multiply(percentage).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal d) {
            return d;
        }
        return new BigDecimal(value.toString());
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private void validateSingleBand(BudgetBandInput band, UUID exceptId) {
        if (band.preferredProvider() == null || band.preferredProvider().isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "PROVIDER_REQUIRED", "Select a provider");
        }
        if (band.preferenceRank() < 1) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_RANK", "Preference must be at least 1");
        }
        Integer providerTaken = exceptId == null
                ? jdbc.queryForObject(
                        "SELECT COUNT(*)::int FROM fd_budget_bands WHERE preferred_provider = ?",
                        Integer.class, band.preferredProvider())
                : jdbc.queryForObject(
                        "SELECT COUNT(*)::int FROM fd_budget_bands WHERE preferred_provider = ? AND id <> ?",
                        Integer.class, band.preferredProvider(), exceptId);
        if (providerTaken != null && providerTaken > 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "PROVIDER_ALREADY_RANKED",
                    "This provider already has a preference");
        }
        Integer rankTaken = exceptId == null
                ? jdbc.queryForObject(
                        "SELECT COUNT(*)::int FROM fd_budget_bands WHERE preference_rank = ?",
                        Integer.class, band.preferenceRank())
                : jdbc.queryForObject(
                        "SELECT COUNT(*)::int FROM fd_budget_bands WHERE preference_rank = ? AND id <> ?",
                        Integer.class, band.preferenceRank(), exceptId);
        if (rankTaken != null && rankTaken > 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "RANK_TAKEN",
                    "Another provider already uses this preference");
        }
    }

    private static void validateBands(List<BudgetBandInput> bands) {
        if (bands == null || bands.isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "RULES_REQUIRED", "Add at least one provider preference");
        }
        for (BudgetBandInput band : bands) {
            if (band.preferredProvider() == null || band.preferredProvider().isBlank()) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "PROVIDER_REQUIRED", "Select a provider");
            }
            if (band.preferenceRank() < 1) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_RANK", "Preference must be at least 1");
            }
        }
    }

    private static void validateTier(CommissionTierInput input) {
        if (input.cardsMin() > input.cardsMax()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TIER", "Card count min cannot exceed max");
        }
        BigDecimal sum = input.retailerSharePct().add(input.distributorSharePct()).add(input.platformSharePct());
        if (sum.compareTo(BigDecimal.valueOf(100)) != 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_SPLIT", "Shares must total 100%");
        }
    }

    public record BudgetBandInput(
            BigDecimal slabMin,
            BigDecimal slabMax,
            String preferredProvider,
            int preferenceRank) {

        public BudgetBandInput(BigDecimal slabMin, BigDecimal slabMax, String preferredProvider) {
            this(slabMin, slabMax, preferredProvider, 1);
        }
    }

    public record CommissionTierInput(
            String providerCode,
            int cardsMin,
            int cardsMax,
            BigDecimal fixedAmountPerCard,
            BigDecimal retailerSharePct,
            BigDecimal distributorSharePct,
            BigDecimal platformSharePct) {}

    public record CommissionRuleInput(
            @NotNull @PositiveOrZero BigDecimal flatFee,
            @NotNull @PositiveOrZero BigDecimal retailerAmount,
            @NotNull @PositiveOrZero BigDecimal distributorAmount) {}
}
