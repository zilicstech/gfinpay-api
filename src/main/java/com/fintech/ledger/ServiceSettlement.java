package com.fintech.ledger;

import com.fintech.commission.CommissionEngine;
import com.fintech.platform.web.ApiException;
import java.math.BigDecimal;
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
        ledgerService.post(txnId, wallet.walletId(), "DEBIT", total, txnType + " payment");
        walletService.adjustCache(wallet.walletId(), total.negate(), BigDecimal.ZERO);
        if (!transactionService.transition(txnId, "SUCCESS", partnerRef, null)) {
            throw new IllegalStateException("Could not mark " + txnType + " SUCCESS for " + txnId);
        }
        postCommissions(txnId, agentId, txnType, amount);

        log.info("SERVICE_SETTLE success type={} txnId={} total={}", txnType, txnId, total);
        return new Result(txnId, amount, fee, total, wallet.available().subtract(total), partnerRef, "SUCCESS");
    }

    /** Debits the main wallet and marks the transaction successful. Does not credit rewards. */
    @Transactional
    public Result debitMain(UUID agentId, String txnType, BigDecimal amount, String partnerCode, String partnerRef) {
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AMOUNT", "Amount must be positive");
        }
        WalletService.WalletInfo wallet = walletService.lockByUser(agentId);
        if (wallet.available().compareTo(amount) < 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_WALLET_BALANCE",
                    "Wallet balance is less than the amount",
                    java.util.Map.of("required", amount, "available", wallet.available()), false);
        }
        UUID txnId = transactionService.create(txnType, agentId, wallet.walletId(), amount, BigDecimal.ZERO,
                null, null, partnerCode, null);
        ledgerService.post(txnId, wallet.walletId(), "DEBIT", amount, txnType + " payment");
        walletService.adjustCache(wallet.walletId(), amount.negate(), BigDecimal.ZERO);
        if (!transactionService.transition(txnId, "SUCCESS", partnerRef, null)) {
            throw new IllegalStateException("Could not mark " + txnType + " SUCCESS for " + txnId);
        }
        log.info("SERVICE_DEBIT success type={} txnId={} amount={}", txnType, txnId, amount);
        return new Result(txnId, amount, BigDecimal.ZERO, amount, wallet.available().subtract(amount), partnerRef, "SUCCESS");
    }

    private void postCommissions(UUID txnId, UUID agentId, String txnType, BigDecimal amount) {
        commissionEngine.creditRewards(txnId, agentId, txnType, amount);
    }
}
