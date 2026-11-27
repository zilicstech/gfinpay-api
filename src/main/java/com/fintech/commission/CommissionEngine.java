package com.fintech.commission;

import com.fintech.dmt.DmtProperties;
import com.fintech.ledger.LedgerService;
import com.fintech.ledger.WalletService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * One commission setting per service. Flat rupees or a percentage of the transaction
 * are credited to the hub, distributor, and retailer rewards wallets.
 * For DMT, also resolves the Admin-configured (or property-default) wallet surcharge ({@code fee})
 * deducted from the retailer's {@code MAIN} operating wallet.
 */
@Service
public class CommissionEngine {

    private static final Logger log = LoggerFactory.getLogger(CommissionEngine.class);

    public record Resolution(UUID ruleId, boolean overridden, BigDecimal fee,
                             BigDecimal retailerShare, BigDecimal distributorShare, BigDecimal hubShare,
                             BigDecimal platformShare) {

        public static Resolution zero() {
            BigDecimal nil = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            return new Resolution(null, false, nil, nil, nil, nil, nil);
        }
    }

    /** Originating outlet network stamped on each earning row for admin reporting. */
    public record EarningNetwork(UUID retailerUserId, UUID distributorUserId, UUID hubId) {}

    private final JdbcTemplate jdbc;
    private final WalletService walletService;
    private final LedgerService ledgerService;
    private final DmtProperties dmtProps;

    public CommissionEngine(JdbcTemplate jdbc, WalletService walletService, LedgerService ledgerService) {
        this(jdbc, walletService, ledgerService, new DmtProperties());
    }

    @Autowired
    public CommissionEngine(
            JdbcTemplate jdbc,
            WalletService walletService,
            LedgerService ledgerService,
            DmtProperties dmtProps) {
        this.jdbc = jdbc;
        this.walletService = walletService;
        this.ledgerService = ledgerService;
        this.dmtProps = dmtProps != null ? dmtProps : new DmtProperties();
    }

    public Resolution resolve(UUID retailerUserId, String txnType, BigDecimal amount) {
        BigDecimal nil = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal base = amount == null ? nil : amount.setScale(2, RoundingMode.HALF_UP);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.id AS rule_id,
                       r.commission_type AS commission_type,
                       r.retailer_amount AS retailer_amount,
                       r.distributor_amount AS distributor_amount,
                       r.hub_amount AS hub_amount,
                       r.retailer_share_pct AS retailer_pct,
                       r.distributor_share_pct AS distributor_pct,
                       r.hub_share_pct AS hub_pct,
                       r.surcharge_type AS surcharge_type,
                       r.surcharge_value AS surcharge_value,
                       r.surcharge_min AS surcharge_min
                  FROM commission_rules r
                 WHERE r.txn_type = ?
                   AND r.effective_from <= now()
                   AND (r.effective_to IS NULL OR r.effective_to > now())
                 ORDER BY r.effective_from DESC
                 LIMIT 1
                """, txnType);
        if (rows.isEmpty()) {
            BigDecimal fallbackFee = "DMT".equalsIgnoreCase(txnType)
                    ? computeSurcharge(base, dmtProps.defaultSurchargeMode(), dmtProps.defaultSurchargeValue(), dmtProps.defaultSurchargeMin())
                    : nil;
            return new Resolution(null, false, fallbackFee, nil, nil, nil, nil);
        }
        Map<String, Object> r = rows.get(0);
        UUID ruleId = (UUID) r.get("rule_id");
        BigDecimal fee = resolveSurchargeForRow(txnType, base, r);
        String type = String.valueOf(r.get("commission_type"));
        if ("FLAT".equalsIgnoreCase(type) || "FIXED".equalsIgnoreCase(type)) {
            return new Resolution(ruleId, false, fee,
                    money(r.get("retailer_amount")), money(r.get("distributor_amount")), money(r.get("hub_amount")), nil);
        }
        return new Resolution(ruleId, false, fee,
                pct(base, money(r.get("retailer_pct"))),
                pct(base, money(r.get("distributor_pct"))),
                pct(base, money(r.get("hub_pct"))),
                nil);
    }

    private BigDecimal resolveSurchargeForRow(String txnType, BigDecimal base, Map<String, Object> row) {
        BigDecimal nil = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        if (!"DMT".equalsIgnoreCase(txnType)) {
            return nil;
        }
        Object rawType = row.get("surcharge_type");
        BigDecimal val = moneyOrNull(row.get("surcharge_value"));
        BigDecimal min = moneyOrNull(row.get("surcharge_min"));
        if (rawType == null || String.valueOf(rawType).isBlank() || val == null) {
            return computeSurcharge(base, dmtProps.defaultSurchargeMode(), dmtProps.defaultSurchargeValue(), dmtProps.defaultSurchargeMin());
        }
        return computeSurcharge(base, String.valueOf(rawType), val, min == null ? nil : min);
    }

    public static BigDecimal computeSurcharge(BigDecimal amount, String modeRaw, BigDecimal value, BigDecimal minAmount) {
        BigDecimal nil = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        if (amount == null || amount.signum() <= 0 || value == null) {
            return nil;
        }
        BigDecimal cleanVal = value.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        if ("FLAT".equalsIgnoreCase(modeRaw) || "FIXED".equalsIgnoreCase(modeRaw)) {
            return cleanVal;
        }
        BigDecimal computed = pct(amount, cleanVal);
        BigDecimal floor = minAmount == null ? nil : minAmount.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        return computed.max(floor);
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
        if (ruleId == null || amount == null || amount.signum() <= 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO commission_earnings
                    (transaction_id, rule_id, beneficiary_user, role_in_split, amount, posting_group,
                     retailer_user_id, distributor_user_id, hub_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (transaction_id, beneficiary_user) DO NOTHING
                """, transactionId, ruleId, beneficiaryUser, roleInSplit, amount, postingGroup,
                network.retailerUserId(), network.distributorUserId(), network.hubId());
    }

    /** Credits the configured reward into each party's rewards wallet. */
    public void creditRewards(UUID txnId, UUID retailerId, String txnType, BigDecimal amount) {
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*)::int FROM commission_earnings WHERE transaction_id = ?", Integer.class, txnId);
        if (existing != null && existing > 0) {
            return;
        }
        Resolution res = resolve(retailerId, txnType, amount);
        if (res.ruleId() == null) {
            log.info("COMMISSION_SKIP txnId={} type={} reason=no_rule", txnId, txnType);
            return;
        }
        if (res.retailerShare().signum() <= 0 && res.distributorShare().signum() <= 0 && res.hubShare().signum() <= 0) {
            return;
        }
        EarningNetwork network = networkForRetailer(retailerId);
        UUID postingGroup = UUID.randomUUID();
        pay(txnId, retailerId, res.retailerShare(), "Commission: retailer", res.ruleId(), "RETAILER", postingGroup, network);
        if (network.distributorUserId() != null) {
            pay(txnId, network.distributorUserId(), res.distributorShare(), "Commission: distributor",
                    res.ruleId(), "DISTRIBUTOR", postingGroup, network);
        }
        UUID hubAdmin = hubAdmin(network.hubId());
        if (hubAdmin != null && !hubAdmin.equals(retailerId) && !hubAdmin.equals(network.distributorUserId())) {
            pay(txnId, hubAdmin, res.hubShare(), "Commission: hub", res.ruleId(), "HUB", postingGroup, network);
        }
        log.info("COMMISSION_REWARDS txnId={} type={} retailer={} distributor={} hub={}",
                txnId, txnType, res.retailerShare(), res.distributorShare(), res.hubShare());
    }

    /**
     * Idempotently reverses previously credited rewards when a settled transaction
     * is later reversed/refunded by the destination bank.
     */
    public void reverseRewards(UUID txnId, String reason) {
        List<Map<String, Object>> earnings = jdbc.queryForList("""
                SELECT id, beneficiary_user, amount
                  FROM commission_earnings
                 WHERE transaction_id = ?
                """, txnId);
        if (earnings.isEmpty()) {
            return;
        }
        for (Map<String, Object> row : earnings) {
            UUID earningId = (UUID) row.get("id");
            UUID userId = (UUID) row.get("beneficiary_user");
            BigDecimal amount = money(row.get("amount"));
            if (userId != null && amount.signum() > 0) {
                WalletService.WalletInfo rewards = walletService.lockRewards(userId);
                BigDecimal clawback = rewards.available().min(amount);
                if (clawback.signum() > 0) {
                    ledgerService.post(txnId, rewards.walletId(), "DEBIT", clawback,
                            reason == null || reason.isBlank() ? "Commission reversal" : reason);
                    walletService.adjustCache(rewards.walletId(), clawback.negate(), BigDecimal.ZERO);
                }
            }
            jdbc.update("DELETE FROM commission_earnings WHERE id = ?", earningId);
        }
        log.info("COMMISSION_REVERSED txnId={} count={}", txnId, earnings.size());
    }

    private void pay(UUID txnId, UUID userId, BigDecimal amount, String narration, UUID ruleId,
                     String role, UUID postingGroup, EarningNetwork network) {
        if (userId == null || amount == null || amount.signum() <= 0) {
            return;
        }
        WalletService.WalletInfo rewards = walletService.lockRewards(userId);
        ledgerService.post(txnId, rewards.walletId(), "CREDIT", amount, narration);
        walletService.adjustCache(rewards.walletId(), amount, BigDecimal.ZERO);
        recordEarning(txnId, ruleId, userId, role, amount, postingGroup, network);
    }

    private UUID hubAdmin(UUID hubId) {
        if (hubId == null) {
            return null;
        }
        List<UUID> rows = jdbc.query("""
                SELECT id FROM users
                 WHERE user_type = 'HUBADMIN' AND hub_id = ? AND status = 'ACTIVE'
                 ORDER BY created_at
                 LIMIT 1
                """, (rs, row) -> rs.getObject("id", UUID.class), hubId);
        return rows.isEmpty() ? null : rows.get(0);
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
