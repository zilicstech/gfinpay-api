package com.fintech.dmt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fintech.commission.CommissionEngine;
import com.fintech.ledger.LedgerService;
import com.fintech.ledger.TransactionService;
import com.fintech.ledger.WalletService;
import com.fintech.platform.webhook.WebhookHandler;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DMT finalization — doc 03 phase 3. Idempotent: the state machine no-ops on webhook redelivery.
 * SUCCESS: capture the hold + post commission splits. FAILED: compensating reversal releases the hold.
 */
@Service
public class DmtFinalizationService implements WebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(DmtFinalizationService.class);

    private final JdbcTemplate jdbc;
    private final TransactionService transactionService;
    private final WalletService walletService;
    private final LedgerService ledgerService;
    private final CommissionEngine commissionEngine;

    public DmtFinalizationService(JdbcTemplate jdbc, TransactionService transactionService,
                                  WalletService walletService, LedgerService ledgerService,
                                  CommissionEngine commissionEngine) {
        this.jdbc = jdbc;
        this.transactionService = transactionService;
        this.walletService = walletService;
        this.ledgerService = ledgerService;
        this.commissionEngine = commissionEngine;
    }

    @Override
    public String provider() {
        return "sponsorbank";
    }

    @Override
    @Transactional
    public UUID handle(JsonNode payload) {
        UUID txnId = UUID.fromString(payload.get("clientRef").asText());
        String status = payload.get("status").asText();
        String utr = payload.path("utr").asText(null);
        return finalize(txnId, status, utr);
    }

    @Transactional
    public UUID finalize(UUID txnId, String status, String utr) {
        long startMs = System.currentTimeMillis();
        log.info("DMT_FINALIZE started txnId={} status={}", txnId, status);
        try {
            Map<String, Object> txn = transactionService.getById(txnId);
            BigDecimal amount = (BigDecimal) txn.get("amount");
            BigDecimal fee = (BigDecimal) txn.get("fee");
            BigDecimal total = amount.add(fee);
            UUID walletId = (UUID) txn.get("wallet_id");
            UUID agentId = (UUID) txn.get("agent_user_id");

            if ("SUCCESS".equals(status)) {
                if (!transactionService.transition(txnId, "SUCCESS", utr, null)) {
                    log.info("DMT_FINALIZE no-op (already terminal) txnId={}", txnId);
                    return txnId;
                }
                WalletService.WalletInfo wallet = walletService.lockByWalletId(walletId);

                // Capture: release hold into settlement + fee income
                List<LedgerService.Entry> capture = new ArrayList<>();
                capture.add(LedgerService.Entry.debit(wallet.holdAccountId(), total, "DMT capture"));
                capture.add(LedgerService.Entry.credit(LedgerService.PLATFORM_SETTLEMENT, amount, "DMT payout settlement"));
                if (fee.signum() > 0) {
                    capture.add(LedgerService.Entry.credit(LedgerService.FEE_INCOME, fee, "DMT convenience fee"));
                }
                ledgerService.postGroup(txnId, capture);
                walletService.adjustCache(walletId, BigDecimal.ZERO, total.negate());

                postCommissions(txnId, agentId, amount, wallet);
                log.info("DMT_FINALIZE success txnId={} captured={} utr={}", txnId, total, utr);
            } else {
                if (!transactionService.transition(txnId, "FAILED", utr, "Partner reported failure")) {
                    return txnId;
                }
                WalletService.WalletInfo wallet = walletService.lockByWalletId(walletId);
                ledgerService.postGroup(txnId, List.of(
                        LedgerService.Entry.debit(wallet.holdAccountId(), total, "DMT reversal - release hold"),
                        LedgerService.Entry.credit(wallet.availableAccountId(), total, "DMT reversal - refund")));
                walletService.adjustCache(walletId, total, total.negate());
                log.info("DMT_FINALIZE failed-and-reversed txnId={} released={}", txnId, total);
            }
            return txnId;
        } finally {
            log.info("DMT_FINALIZE completed txnId={} in {} ms", txnId, System.currentTimeMillis() - startMs);
        }
    }

    private void postCommissions(UUID txnId, UUID agentId, BigDecimal amount,
                                 WalletService.WalletInfo agentWallet) {
        CommissionEngine.Resolution res = commissionEngine.resolve(agentId, "DMT", amount);
        BigDecimal r = res.retailerShare();
        BigDecimal d = res.distributorShare();
        if (r.add(d).signum() <= 0 && res.platformShare().signum() <= 0) {
            return;
        }
        UUID distributorId = jdbc.queryForObject("SELECT parent_id FROM users WHERE id = ?", UUID.class, agentId);
        UUID postingGroup = null;
        if (r.add(d).signum() > 0) {
            List<LedgerService.Entry> split = new ArrayList<>();
            split.add(LedgerService.Entry.debit(LedgerService.FEE_INCOME, r.add(d), "Commission split payout"));
            if (r.signum() > 0) {
                split.add(LedgerService.Entry.credit(agentWallet.availableAccountId(), r, "Commission: retailer"));
            }
            WalletService.WalletInfo distWallet = null;
            if (d.signum() > 0) {
                distWallet = walletService.lockByUser(distributorId);
                split.add(LedgerService.Entry.credit(distWallet.availableAccountId(), d, "Commission: distributor"));
            }
            postingGroup = ledgerService.postGroup(txnId, split);
            if (r.signum() > 0) {
                walletService.adjustCache(agentWallet.walletId(), r, BigDecimal.ZERO);
            }
            if (distWallet != null) {
                walletService.adjustCache(distWallet.walletId(), d, BigDecimal.ZERO);
            }
        }
        UUID group = postingGroup != null ? postingGroup : UUID.randomUUID();
        UUID platformUser = jdbc.queryForObject(
                "SELECT id FROM users WHERE user_type = 'SUPER_ADMIN' LIMIT 1", UUID.class);
        CommissionEngine.EarningNetwork network = commissionEngine.networkForRetailer(agentId);
        if (r.signum() > 0) {
            commissionEngine.recordEarning(txnId, res.ruleId(), agentId, "RETAILER", r, group, network);
        }
        if (d.signum() > 0) {
            commissionEngine.recordEarning(txnId, res.ruleId(), distributorId, "DISTRIBUTOR", d, group, network);
        }
        if (res.platformShare().signum() > 0) {
            commissionEngine.recordEarning(txnId, res.ruleId(), platformUser, "PLATFORM", res.platformShare(), group, network);
        }
        log.info("COMMISSION_SPLIT txnId={} retailer={} distributor={} platform={}",
                txnId, r, d, res.platformShare());
    }
}
