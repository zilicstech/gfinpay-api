package com.fintech.ledger;

import com.fintech.commission.CommissionEngine;
import com.fintech.platform.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Synchronous wallet debit for Paysprint-shaped bill and recharge rails:
 * HOLD → PENDING → SUCCESS, then capture and commission split.
 */
@Service
public class ServiceSettlement {

    private static final Logger log = LoggerFactory.getLogger(ServiceSettlement.class);

    public record Result(UUID transactionId, BigDecimal amount, BigDecimal fee, BigDecimal total,
                         BigDecimal walletBalanceAfter, String partnerRef, String state) {}

    private final JdbcTemplate jdbc;
    private final CommissionEngine commissionEngine;
    private final WalletService walletService;
    private final TransactionService transactionService;
    private final LedgerService ledgerService;

    public ServiceSettlement(JdbcTemplate jdbc, CommissionEngine commissionEngine, WalletService walletService,
                             TransactionService transactionService, LedgerService ledgerService) {
        this.jdbc = jdbc;
        this.commissionEngine = commissionEngine;
        this.walletService = walletService;
        this.transactionService = transactionService;
        this.ledgerService = ledgerService;
    }

    @Transactional
    public Result debitAndSettle(UUID agentId, String txnType, BigDecimal amount,
                                 String partnerCode, String partnerRef, UUID idempotencyKeyId) {
        CommissionEngine.Resolution pricing = commissionEngine.resolve(agentId, txnType, amount);
        BigDecimal fee = pricing.fee();
        BigDecimal total = amount.add(fee);

        WalletService.WalletInfo wallet = walletService.lockByUser(agentId);
        if (wallet.available().compareTo(total) < 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_WALLET_BALANCE",
                    "Wallet balance is less than amount plus fee",
                    java.util.Map.of("required", total, "available", wallet.available()), false);
        }

        UUID txnId = transactionService.create(txnType, agentId, wallet.walletId(), amount, fee,
                null, null, partnerCode, idempotencyKeyId);
        ledgerService.postGroup(txnId, List.of(
                LedgerService.Entry.debit(wallet.availableAccountId(), total, txnType + " hold"),
                LedgerService.Entry.credit(wallet.holdAccountId(), total, txnType + " hold")));
        walletService.adjustCache(wallet.walletId(), total.negate(), total);
        transactionService.transition(txnId, "HOLD", null, null);
        transactionService.transition(txnId, "PENDING", partnerRef, null);
        if (!transactionService.transition(txnId, "SUCCESS", partnerRef, null)) {
            throw new IllegalStateException("Could not mark " + txnType + " SUCCESS for " + txnId);
        }

        List<LedgerService.Entry> capture = new ArrayList<>();
        capture.add(LedgerService.Entry.debit(wallet.holdAccountId(), total, txnType + " capture"));
        capture.add(LedgerService.Entry.credit(LedgerService.PLATFORM_SETTLEMENT, amount, txnType + " settlement"));
        if (fee.signum() > 0) {
            capture.add(LedgerService.Entry.credit(LedgerService.FEE_INCOME, fee, txnType + " fee"));
        }
        ledgerService.postGroup(txnId, capture);
        walletService.adjustCache(wallet.walletId(), BigDecimal.ZERO, total.negate());
        postCommissions(txnId, agentId, txnType, amount);

        log.info("SERVICE_SETTLE success type={} txnId={} total={}", txnType, txnId, total);
        return new Result(txnId, amount, fee, total, wallet.available().subtract(total), partnerRef, "SUCCESS");
    }

    private void postCommissions(UUID txnId, UUID agentId, String txnType, BigDecimal amount) {
        CommissionEngine.Resolution res = commissionEngine.resolve(agentId, txnType, amount);
        BigDecimal r = res.retailerShare();
        BigDecimal d = res.distributorShare();
        if (r.add(d).signum() <= 0 && res.platformShare().signum() <= 0) {
            return;
        }
        WalletService.WalletInfo agentWallet = walletService.lockByUser(agentId);
        UUID distributorId = jdbc.queryForObject("SELECT parent_id FROM users WHERE id = ?", UUID.class, agentId);
        UUID postingGroup = null;
        if (r.add(d).signum() > 0) {
            List<LedgerService.Entry> split = new ArrayList<>();
            split.add(LedgerService.Entry.debit(LedgerService.FEE_INCOME, r.add(d), "Commission split payout"));
            if (r.signum() > 0) {
                split.add(LedgerService.Entry.credit(agentWallet.availableAccountId(), r, "Commission: retailer"));
            }
            WalletService.WalletInfo distWallet = null;
            if (d.signum() > 0 && distributorId != null) {
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
        if (d.signum() > 0 && distributorId != null) {
            commissionEngine.recordEarning(txnId, res.ruleId(), distributorId, "DISTRIBUTOR", d, group, network);
        }
        if (res.platformShare().signum() > 0) {
            commissionEngine.recordEarning(txnId, res.ruleId(), platformUser, "PLATFORM", res.platformShare(), group, network);
        }
        log.info("COMMISSION_SPLIT type={} txnId={} retailer={} distributor={} platform={}",
                txnType, txnId, r, d, res.platformShare());
    }
}
