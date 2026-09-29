package com.fintech.commission;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Pricing resolution: retailer-level override first, then the global rule (doc 02).
 * Shares are rounded to 2 decimals; the platform share absorbs rounding remainders
 * so retailer + distributor + platform always equals the fee exactly.
 */
@Service
public class CommissionEngine {

    public record Resolution(UUID ruleId, boolean overridden, BigDecimal fee,
                             BigDecimal retailerShare, BigDecimal distributorShare, BigDecimal platformShare) {

        public static Resolution zero() {
            return new Resolution(null, false, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    /** Originating outlet network stamped on each earning row for admin reporting. */
    public record EarningNetwork(UUID retailerUserId, UUID distributorUserId, UUID hubId) {}

    private final JdbcTemplate jdbc;

    public CommissionEngine(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Resolution resolve(UUID retailerUserId, String txnType, BigDecimal amount) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.id AS rule_id,
                       r.commission_type                                      AS commission_type,
                       r.retailer_amount                                      AS retailer_amount,
                       r.distributor_amount                                   AS distributor_amount,
                       COALESCE(o.total_rate_bp, r.total_rate_bp)             AS bp,
                       COALESCE(o.flat_fee, r.flat_fee)                       AS flat_fee,
                       COALESCE(o.retailer_share_pct, r.retailer_share_pct)   AS retailer_pct,
                       COALESCE(o.distributor_share_pct, r.distributor_share_pct) AS distributor_pct,
                       (o.id IS NOT NULL)                                     AS overridden
                  FROM commission_rules r
                  LEFT JOIN commission_rule_overrides o
                         ON o.rule_id = r.id AND o.retailer_user_id = ?
                        AND o.effective_from <= now()
                        AND (o.effective_to IS NULL OR o.effective_to > now())
                 WHERE r.txn_type = ?
                   AND ? BETWEEN r.slab_min AND r.slab_max
                   AND r.effective_from <= now()
                   AND (r.effective_to IS NULL OR r.effective_to > now())
                 ORDER BY overridden DESC
                 LIMIT 1
                """, retailerUserId, txnType, amount);
        if (rows.isEmpty()) {
            return Resolution.zero();
        }
        Map<String, Object> r = rows.get(0);
        UUID ruleId = (UUID) r.get("rule_id");
        boolean overridden = Boolean.TRUE.equals(r.get("overridden"));
        if ("FIXED".equalsIgnoreCase(String.valueOf(r.get("commission_type")))) {
            BigDecimal retailerShare = money(r.get("retailer_amount"));
            BigDecimal distributorShare = money(r.get("distributor_amount"));
            BigDecimal fee = money(r.get("flat_fee"));
            if (fee.signum() == 0) {
                fee = retailerShare.add(distributorShare);
            }
            BigDecimal platformShare = fee.subtract(retailerShare).subtract(distributorShare);
            if (platformShare.signum() < 0) {
                platformShare = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            }
            return new Resolution(ruleId, overridden, fee, retailerShare, distributorShare, platformShare);
        }
        BigDecimal flatFee = moneyOrNull(r.get("flat_fee"));
        BigDecimal fee;
        if (flatFee != null) {
            fee = flatFee.setScale(2, RoundingMode.HALF_UP);
        } else {
            int bp = ((Number) r.get("bp")).intValue();
            fee = amount.multiply(BigDecimal.valueOf(bp))
                    .divide(BigDecimal.valueOf(10_000), 2, RoundingMode.HALF_UP);
        }
        BigDecimal retailerShare = pct(fee, money(r.get("retailer_pct")));
        BigDecimal distributorShare = pct(fee, money(r.get("distributor_pct")));
        BigDecimal platformShare = fee.subtract(retailerShare).subtract(distributorShare);
        return new Resolution(ruleId, overridden, fee, retailerShare, distributorShare, platformShare);
    }

    public EarningNetwork networkForRetailer(UUID retailerUserId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.id AS retailer_user_id,
                       r.parent_id AS distributor_user_id,
                       COALESCE(r.hub_id, d.hub_id) AS hub_id
                  FROM users r
                  LEFT JOIN users d ON d.id = r.parent_id
                 WHERE r.id = ?
                """, retailerUserId);
        if (rows.isEmpty()) {
            return new EarningNetwork(retailerUserId, null, null);
        }
        Map<String, Object> row = rows.get(0);
        return new EarningNetwork(
                (UUID) row.get("retailer_user_id"),
                (UUID) row.get("distributor_user_id"),
                (UUID) row.get("hub_id"));
    }

    public void recordEarning(UUID transactionId, UUID ruleId, UUID beneficiaryUser,
                              String roleInSplit, BigDecimal amount, UUID postingGroup,
                              EarningNetwork network) {
        jdbc.update("""
                INSERT INTO commission_earnings
                    (transaction_id, rule_id, beneficiary_user, role_in_split, amount, posting_group,
                     retailer_user_id, distributor_user_id, hub_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, transactionId, ruleId, beneficiaryUser, roleInSplit, amount, postingGroup,
                network.retailerUserId(), network.distributorUserId(), network.hubId());
    }

    private static BigDecimal pct(BigDecimal fee, BigDecimal percentage) {
        return fee.multiply(percentage).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(Object value) {
        BigDecimal parsed = moneyOrNull(value);
        return parsed == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : parsed;
    }

    private static BigDecimal moneyOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.setScale(2, RoundingMode.HALF_UP);
        }
        return new BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_UP);
    }
}
