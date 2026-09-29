package com.fintech.cashout;

import com.fintech.ledger.LedgerService;
import com.fintech.ledger.TransactionService;
import com.fintech.ledger.WalletService;
import com.fintech.platform.PlatformServiceGate;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import com.fintech.platform.web.FeatureUnavailable;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CashoutService {

    private static final Logger log = LoggerFactory.getLogger(CashoutService.class);

    private final JdbcTemplate jdbc;
    private final WalletService walletService;
    private final TransactionService transactionService;
    private final LedgerService ledgerService;
    private final PlatformServiceGate services;

    public CashoutService(JdbcTemplate jdbc, WalletService walletService,
                          TransactionService transactionService, LedgerService ledgerService,
                          PlatformServiceGate services) {
        this.jdbc = jdbc;
        this.walletService = walletService;
        this.transactionService = transactionService;
        this.ledgerService = ledgerService;
        this.services = services;
    }

    @Transactional
    public Map<String, Object> startUpi(AuthPrincipal me, BigDecimal amount, String customerName, String customerMobile) {
        FeatureUnavailable.throwIfCalled();
        services.requireEnabled(PlatformServiceGate.UPI_CASHOUT);
        if (amount == null || amount.compareTo(BigDecimal.ONE) < 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AMOUNT", "Enter at least ₹1");
        }
        String name = customerName == null ? "" : customerName.trim();
        if (name.length() < 2) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "CUSTOMER_REQUIRED",
                    "Record the customer's name before handing over cash");
        }
        String mobile = customerMobile == null ? "" : customerMobile.replaceAll("\\D", "");
        if (mobile.length() != 10) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "CUSTOMER_REQUIRED",
                    "Record a 10-digit mobile for the cash-out log");
        }
        WalletService.WalletInfo wallet = walletService.getByUser(me.userId());
        UUID txnId = transactionService.create("CASHOUT_UPI", me.userId(), wallet.walletId(),
                amount, BigDecimal.ZERO, null, null, "UPI_PSP", null);
        UUID sessionId = UUID.randomUUID();
        String payload = "upi://pay?pa=gfinpay@upi&pn=gfinpay%%20Outlet&am=%s&cu=INR&tn=CASH%s".formatted(
                amount.toPlainString(), sessionId.toString().substring(0, 8).toUpperCase());
        jdbc.update("""
                INSERT INTO cashout_sessions
                    (id, transaction_id, agent_user_id, channel, amount, upi_qr_payload, status, expires_at,
                     customer_name, customer_mobile)
                VALUES (?, ?, ?, 'UPI_QR', ?, ?, 'AWAITING_PAYMENT', now() + interval '10 minutes', ?, ?)
                """, sessionId, txnId, me.userId(), amount, payload, name, mobile);
        log.info("CASHOUT_START session={} txn={} amount={} mobile={}", sessionId, txnId, amount, mobile);
        return get(me, sessionId);
    }

    public Map<String, Object> get(AuthPrincipal me, UUID sessionId) {
        services.requireEnabled(PlatformServiceGate.UPI_CASHOUT);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT c.id, c.transaction_id, c.amount, c.status::text AS status, c.upi_qr_payload,
                       c.upi_collect_ref, c.expires_at, c.paid_at, c.dispensed_at, c.created_at,
                       c.customer_name, c.customer_mobile, t.state::text AS txn_state
                  FROM cashout_sessions c
                  JOIN transactions t ON t.id = c.transaction_id
                 WHERE c.id = ? AND c.agent_user_id = ?
                """, sessionId, me.userId());
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "CASHOUT_NOT_FOUND", "Cash-out session not found");
        }
        return rows.get(0);
    }

    @Transactional
    public Map<String, Object> markPaid(AuthPrincipal me, UUID sessionId) {
        services.requireEnabled(PlatformServiceGate.UPI_CASHOUT);
        Map<String, Object> session = get(me, sessionId);
        if (!"AWAITING_PAYMENT".equals(String.valueOf(session.get("status")))) {
            return session;
        }
        UUID txnId = (UUID) session.get("transaction_id");
        BigDecimal amount = (BigDecimal) session.get("amount");
        WalletService.WalletInfo wallet = walletService.lockByUser(me.userId());
        String ref = "UPI" + sessionId.toString().substring(0, 8).toUpperCase();
        transactionService.transition(txnId, "SUCCESS", ref, null);
        ledgerService.postGroup(txnId, List.of(
                LedgerService.Entry.debit(LedgerService.PLATFORM_SETTLEMENT, amount, "UPI cash-out collect"),
                LedgerService.Entry.credit(wallet.availableAccountId(), amount, "UPI cash-out received")));
        walletService.adjustCache(wallet.walletId(), amount, BigDecimal.ZERO);
        jdbc.update("""
                UPDATE cashout_sessions
                   SET status = 'PAID', upi_collect_ref = ?, paid_at = now()
                 WHERE id = ?
                """, ref, sessionId);
        log.info("CASHOUT_PAID session={} amount={}", sessionId, amount);
        return get(me, sessionId);
    }

    @Transactional
    public Map<String, Object> dispense(AuthPrincipal me, UUID sessionId) {
        services.requireEnabled(PlatformServiceGate.UPI_CASHOUT);
        Map<String, Object> session = get(me, sessionId);
        if (!"PAID".equals(String.valueOf(session.get("status")))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_PAID",
                    "Hand over cash only after the collect is paid");
        }
        jdbc.update("""
                UPDATE cashout_sessions SET status = 'CASH_DISPENSED', dispensed_at = now() WHERE id = ?
                """, sessionId);
        log.info("CASHOUT_DISPENSED session={}", sessionId);
        return get(me, sessionId);
    }

    public Map<String, Object> emptySession() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "NONE");
        return out;
    }
}
