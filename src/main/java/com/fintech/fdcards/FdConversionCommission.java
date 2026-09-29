package com.fintech.fdcards;

import com.fintech.commission.CommissionEngine;
import com.fintech.ledger.LedgerService;
import com.fintech.ledger.TransactionService;
import com.fintech.ledger.WalletService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Pays retailer/distributor from the provider FIXED commission rule when a lead converts. */
@Service
public class FdConversionCommission {

    private final JdbcTemplate jdbc;
    private final CommissionEngine commissionEngine;
    private final FdCommissionTierService tiers;
    private final WalletService walletService;
    private final LedgerService ledgerService;
    private final TransactionService transactionService;

    public FdConversionCommission(JdbcTemplate jdbc, CommissionEngine commissionEngine,
                                  FdCommissionTierService tiers, WalletService walletService,
                                  LedgerService ledgerService, TransactionService transactionService) {
        this.jdbc = jdbc;
        this.commissionEngine = commissionEngine;
        this.tiers = tiers;
        this.walletService = walletService;
        this.ledgerService = ledgerService;
        this.transactionService = transactionService;
    }

    @Transactional
    public void payOnConversion(Map<String, Object> lead) {
        String provider = String.valueOf(lead.get("provider"));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT commission_txn_type FROM fd_providers WHERE code = ?", provider);
        if (rows.isEmpty()) {
            return;
        }
        String txnType = String.valueOf(rows.get(0).get("commission_txn_type"));
        UUID retailerId = (UUID) lead.get("retailer_user_id");
        BigDecimal amount = lead.get("budget") == null ? BigDecimal.ONE : (BigDecimal) lead.get("budget");
        CommissionEngine.Resolution res = commissionEngine.resolve(retailerId, txnType, amount);
        BigDecimal r = res.retailerShare();
        BigDecimal d = res.distributorShare();
        if (r.add(d).signum() <= 0) {
            FdCommissionTierService.TierPayout payout = tiers.resolveForConversion(provider);
            r = payout.retailerShare();
            d = payout.distributorShare();
        }
        if (r.add(d).signum() <= 0) {
            return;
        }
        UUID leadId = (UUID) lead.get("id");
        WalletService.WalletInfo agentWallet = walletService.lockByUser(retailerId);
        UUID txnId = transactionService.create(txnType, retailerId, agentWallet.walletId(), amount, BigDecimal.ZERO,
                null, null, provider, null);
        transactionService.transition(txnId, "SUCCESS", String.valueOf(leadId), null);

        UUID distributorId = jdbc.queryForObject("SELECT parent_id FROM users WHERE id = ?", UUID.class, retailerId);
        List<LedgerService.Entry> split = new ArrayList<>();
        split.add(LedgerService.Entry.debit(LedgerService.FEE_INCOME, r.add(d), "FD commission payout"));
        if (r.signum() > 0) {
            split.add(LedgerService.Entry.credit(agentWallet.availableAccountId(), r, "FD card commission"));
        }
        WalletService.WalletInfo distWallet = null;
        if (d.signum() > 0 && distributorId != null) {
            distWallet = walletService.lockByUser(distributorId);
            split.add(LedgerService.Entry.credit(distWallet.availableAccountId(), d, "FD commission: distributor"));
        }
        UUID postingGroup = ledgerService.postGroup(txnId, split);
        if (r.signum() > 0) {
            walletService.adjustCache(agentWallet.walletId(), r, BigDecimal.ZERO);
        }
        if (distWallet != null) {
            walletService.adjustCache(distWallet.walletId(), d, BigDecimal.ZERO);
        }
        UUID ruleId = res.ruleId() != null ? res.ruleId() : ruleIdFor(txnType);
        CommissionEngine.EarningNetwork network = commissionEngine.networkForRetailer(retailerId);
        BigDecimal platform = res.platformShare();
        if (platform.signum() <= 0 && res.fee().signum() > 0) {
            platform = res.fee().subtract(r).subtract(d);
            if (platform.signum() < 0) {
                platform = BigDecimal.ZERO;
            }
        }
        UUID platformUser = jdbc.queryForObject(
                "SELECT id FROM users WHERE user_type = 'SUPER_ADMIN' LIMIT 1", UUID.class);
        if (ruleId != null) {
            if (r.signum() > 0) {
                commissionEngine.recordEarning(txnId, ruleId, retailerId, "RETAILER", r, postingGroup, network);
            }
            if (d.signum() > 0 && distributorId != null) {
                commissionEngine.recordEarning(txnId, ruleId, distributorId, "DISTRIBUTOR", d, postingGroup, network);
            }
            if (platform.signum() > 0) {
                commissionEngine.recordEarning(txnId, ruleId, platformUser, "PLATFORM", platform, postingGroup, network);
            }
        }
    }

    private UUID ruleIdFor(String txnType) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id FROM commission_rules
                 WHERE txn_type = ? AND effective_to IS NULL
                 ORDER BY effective_from DESC
                 LIMIT 1
                """, txnType);
        return rows.isEmpty() ? null : (UUID) rows.get(0).get("id");
    }
}
