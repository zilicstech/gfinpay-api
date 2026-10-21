package com.fintech.fdcards;

import com.fintech.commission.CommissionEngine;
import com.fintech.ledger.TransactionService;
import com.fintech.ledger.WalletService;
import java.math.BigDecimal;
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
    private final WalletService walletService;
    private final TransactionService transactionService;

    public FdConversionCommission(JdbcTemplate jdbc, CommissionEngine commissionEngine,
                                  WalletService walletService, TransactionService transactionService) {
        this.jdbc = jdbc;
        this.commissionEngine = commissionEngine;
        this.walletService = walletService;
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
        UUID retailerId = (UUID) lead.get("retailer_user_id");
        BigDecimal amount = lead.get("budget") == null ? BigDecimal.ONE : (BigDecimal) lead.get("budget");
        CommissionEngine.Resolution res = commissionEngine.resolve(retailerId, "FD_CARD", amount);
        if (res.ruleId() == null
                || res.retailerShare().add(res.distributorShare()).add(res.hubShare()).signum() <= 0) {
            return;
        }
        UUID leadId = (UUID) lead.get("id");
        WalletService.WalletInfo agentWallet = walletService.lockByUser(retailerId);
        UUID txnId = transactionService.create("FD_CARD", retailerId, agentWallet.walletId(), amount, BigDecimal.ZERO,
                null, null, provider, null);
        transactionService.transition(txnId, "SUCCESS", String.valueOf(leadId), null);
        commissionEngine.creditRewards(txnId, retailerId, "FD_CARD", amount);
    }
}
