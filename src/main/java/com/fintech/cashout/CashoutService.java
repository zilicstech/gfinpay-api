package com.fintech.cashout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.commission.CommissionEngine;
import com.fintech.emerald.EmeraldClient;
import com.fintech.emerald.EmeraldMessageSanitizer;
import com.fintech.emerald.EmeraldTraceContext;
import com.fintech.ledger.LedgerService;
import com.fintech.ledger.TransactionService;
import com.fintech.ledger.WalletService;
import com.fintech.platform.PlatformServiceGate;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import com.fintech.provider.ServiceProviderRouter;
import com.fintech.verification.CashProvider;
import com.fintech.verification.VerificationGate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CashoutService {

    private static final Logger log = LoggerFactory.getLogger(CashoutService.class);

    private static final String SESSION_COLUMNS = """
            c.id, c.transaction_id, c.agent_user_id, c.amount, c.payable_value,
            c.status::text AS status, c.customer_name, c.customer_mobile,
            c.qr_string, c.upi_qr_payload, c.display_expiry_sec,
            c.bank_rrn, c.utr, c.ackno, c.err_code, c.err_msg,
            c.payer_name, c.payer_va, c.provider_refid,
            c.provider_code, c.pipe_code, c.outlet_code, c.external_ref, c.provider_txn_id,
            c.latitude, c.longitude,
            c.expires_at, c.paid_at, c.dispensed_at, c.created_at
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PlatformServiceGate services;
    private final VerificationGate verification;
    private final CashProvider cash;
    private final ServiceProviderRouter router;
    private final EmeraldClient emeraldClient;
    private final WalletService walletService;
    private final LedgerService ledgerService;
    private final TransactionService transactionService;
    private final CommissionEngine commissionEngine;

    public CashoutService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            PlatformServiceGate services,
            VerificationGate verification,
            CashProvider cash,
            ServiceProviderRouter router,
            EmeraldClient emeraldClient,
            WalletService walletService,
            LedgerService ledgerService,
            TransactionService transactionService,
            CommissionEngine commissionEngine) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.services = services;
        this.verification = verification;
        this.cash = cash;
        this.router = router;
        this.emeraldClient = emeraldClient;
        this.walletService = walletService;
        this.ledgerService = ledgerService;
        this.transactionService = transactionService;
        this.commissionEngine = commissionEngine;
    }

    /** Legacy placeholder check preserved for backward compatibility. */
    public Map<String, Object> startUpi(AuthPrincipal me) {
        services.requireEnabled(PlatformServiceGate.UPI_CASHOUT);
        verification.requireBusiness(me.userId());
        cash.start("UPI");
        return Map.of("mode", "UPI");
    }

    /**
     * Generates a dynamic UPI Cash Withdrawal (UATM) QR code routed via {@link ServiceProviderRouter}.
     */
    @Transactional
    public Map<String, Object> generateQr(
            AuthPrincipal me,
            Integer amountRupees,
            String customerMobileRaw,
            String customerNameRaw,
            String latitudeRaw,
            String longitudeRaw) {
        services.requireEnabled(PlatformServiceGate.UPI_CASHOUT);
        UUID userId = me.userId();
        requireUnlockedMerchant(userId);

        if (amountRupees == null || amountRupees < 200 || amountRupees > 5000 || amountRupees % 100 != 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CASHOUT_AMOUNT",
                    "UPI Cashout amount must be between ₹200 and ₹5,000 in multiples of ₹100.");
        }
        String cleanMobile = normalizeMobile(customerMobileRaw);
        if (!cleanMobile.matches("[6-9][0-9]{9}")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MOBILE",
                    "Enter a valid 10-digit customer mobile number.");
        }
        String cleanName = customerNameRaw == null ? "" : customerNameRaw.trim();
        String lat = resolveCoordinate(latitudeRaw, userId, true);
        String lng = resolveCoordinate(longitudeRaw, userId, false);

        ServiceProviderRouter.RoutedChannel channel = router.selectChannel(userId, ServiceProviderRouter.UPI_CASHOUT);
        WalletService.WalletInfo wallet = walletService.getByUser(userId);
        if (!wallet.active()) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "WALLET_FROZEN",
                    "Your operating wallet is currently frozen. Please contact support.");
        }

        String externalRef = "UPICW" + System.currentTimeMillis()
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
        BigDecimal amount = BigDecimal.valueOf(amountRupees).setScale(2, RoundingMode.HALF_UP);

        if (!"EMERALD".equalsIgnoreCase(channel.providerCode())) {
            throw ApiException.of(HttpStatus.SERVICE_UNAVAILABLE, "CHANNEL_UNSUPPORTED",
                    "Selected cashout terminal rail is not yet active.");
        }

        EmeraldClient.UatmQrResponse qrRes;
        try (var ignored = EmeraldTraceContext.open(userId, "STEP4_UPI_QR", externalRef)) {
            qrRes = emeraldClient.generateUatmQr(
                    channel.outletCode(),
                    String.valueOf(amountRupees),
                    cleanMobile,
                    lat,
                    lng,
                    externalRef);
        }

        if (!qrRes.success()) {
            String msg = EmeraldMessageSanitizer.sanitize(
                    qrRes.message(),
                    "Unable to generate UPI QR right now. Please try again shortly.");
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "QR_GENERATION_FAILED", msg);
        }

        UUID txnId = transactionService.create(
                "CASHOUT_UPI",
                userId,
                wallet.walletId(),
                amount,
                BigDecimal.ZERO,
                null,
                null,
                channel.providerCode(),
                null);
        String initPartnerRef = qrRes.txnId().isBlank() ? externalRef : qrRes.txnId();
        transactionService.transition(txnId, "PENDING", initPartnerRef, null);
        setCustomerRefJson(txnId, Map.of(
                "mobile", cleanMobile,
                "name", cleanName,
                "externalRef", externalRef));

        UUID sessionId = UUID.randomUUID();
        int expirySec = Math.max(30, qrRes.displayExpirySec());
        Instant expiresAt = Instant.now().plusSeconds(expirySec);
        BigDecimal payableValue = parseAmountOrFallback(qrRes.payableValue(), amount);

        jdbc.update("""
                INSERT INTO cashout_sessions
                    (id, transaction_id, agent_user_id, channel, channel_id, provider_code, pipe_code,
                     outlet_code, external_ref, provider_txn_id, amount, payable_value,
                     qr_string, upi_qr_payload, display_expiry_sec, status, expires_at,
                     customer_name, customer_mobile, latitude, longitude, provider_initiated_at)
                VALUES (?, ?, ?, 'UPI', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'AWAITING_PAYMENT', ?, ?, ?, ?, ?, now())
                """,
                sessionId,
                txnId,
                userId,
                channel.channelId(),
                channel.providerCode(),
                channel.pipeCode(),
                channel.outletCode(),
                externalRef,
                blankToNull(qrRes.txnId()),
                amount,
                payableValue,
                qrRes.qrString(),
                qrRes.qrString(),
                expirySec,
                Timestamp.from(expiresAt),
                blankToNull(cleanName),
                cleanMobile,
                lat,
                lng);

        log.info("UPI_CASHOUT QR generated session={} txnId={} user={} amount={} ref={}",
                sessionId, txnId, userId, amount, externalRef);
        return view(loadForUser(userId, sessionId));
    }

    public Map<String, Object> latest(AuthPrincipal me) {
        services.requireEnabled(PlatformServiceGate.UPI_CASHOUT);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT %s
                  FROM cashout_sessions c
                 WHERE c.agent_user_id = ? AND c.created_at > now() - interval '30 minutes'
                 ORDER BY c.created_at DESC
                 LIMIT 1
                """.formatted(SESSION_COLUMNS), me.userId());
        if (rows.isEmpty()) {
            return Map.of("status", "NONE");
        }
        return view(rows.get(0));
    }

    public List<Map<String, Object>> history(AuthPrincipal me) {
        services.requireEnabled(PlatformServiceGate.UPI_CASHOUT);
        return jdbc.queryForList("""
                SELECT %s
                  FROM cashout_sessions c
                 WHERE c.agent_user_id = ?
                 ORDER BY c.created_at DESC
                 LIMIT 25
                """.formatted(SESSION_COLUMNS), me.userId())
                .stream()
                .map(CashoutService::view)
                .toList();
    }

    public Map<String, Object> get(AuthPrincipal me, UUID sessionId) {
        services.requireEnabled(PlatformServiceGate.UPI_CASHOUT);
        return view(loadForUser(me.userId(), sessionId));
    }

    @Transactional
    public Map<String, Object> refresh(AuthPrincipal me, UUID sessionId) {
        services.requireEnabled(PlatformServiceGate.UPI_CASHOUT);
        Map<String, Object> row = loadForUpdateById(sessionId);
        if (!me.userId().equals(row.get("agent_user_id"))) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "CASHOUT_NOT_FOUND", "Cash-out session not found");
        }
        reconcileLockedSessionWithUpstream(row);
        return view(loadForUser(me.userId(), sessionId));
    }

    @Transactional
    public Map<String, Object> reconcileByTransactionId(UUID transactionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT %s
                  FROM cashout_sessions c
                 WHERE c.transaction_id = ?
                 FOR UPDATE
                """.formatted(SESSION_COLUMNS), transactionId);
        if (rows.isEmpty()) {
            return Map.of("matched", false);
        }
        Map<String, Object> row = rows.get(0);
        reconcileLockedSessionWithUpstream(row);
        Map<String, Object> updated = jdbc.queryForMap("""
                SELECT %s FROM cashout_sessions c WHERE c.id = ?
                """.formatted(SESSION_COLUMNS), row.get("id"));
        return view(updated);
    }

    @Transactional
    public UUID finalizeFromCallback(
            String externalRef,
            String providerTxnId,
            String callbackStatus,
            String rawMessage,
            String utr,
            String payerVpa,
            String payerName) {
        Map<String, Object> session = findSessionForUpdate(externalRef, providerTxnId);
        if (session == null) {
            log.warn("UPI_CASHOUT callback unmatched externalRef={} providerTxnId={} status={}",
                    externalRef, providerTxnId, callbackStatus);
            return null;
        }
        UUID txnId = (UUID) session.get("transaction_id");
        String normStatus = callbackStatus == null ? "" : callbackStatus.trim().toUpperCase(Locale.ROOT);

        if ("SUCCESS".equals(normStatus)) {
            settleLockedSessionSuccess(session, providerTxnId, utr, payerVpa, payerName);
        } else if ("FAILED".equals(normStatus) || "EXPIRED".equals(normStatus)) {
            String targetSessionStatus = "EXPIRED".equals(normStatus) ? "EXPIRED" : "FAILED";
            markLockedSessionUnpaidTerminal(session, targetSessionStatus, normStatus, rawMessage, providerTxnId);
        } else if ("REVERSED".equals(normStatus)) {
            handleLockedSessionReversal(session, rawMessage, utr);
        }
        return txnId;
    }

    @Transactional
    public Map<String, Object> dispense(AuthPrincipal me, UUID sessionId) {
        services.requireEnabled(PlatformServiceGate.UPI_CASHOUT);
        Map<String, Object> session = loadForUser(me.userId(), sessionId);
        if (!"PAID".equals(String.valueOf(session.get("status")))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_PAID",
                    "Hand over cash only after the payment is confirmed");
        }
        jdbc.update("""
                UPDATE cashout_sessions
                   SET status = 'CASH_DISPENSED', dispensed_at = now(), updated_at = now()
                 WHERE id = ?
                """, sessionId);
        log.info("UPI_CASHOUT dispensed session={} ref={}", sessionId, session.get("external_ref"));
        return view(loadForUser(me.userId(), sessionId));
    }

    private void reconcileLockedSessionWithUpstream(Map<String, Object> row) {
        String currentStatus = str(row.get("status"));
        if ("PAID".equals(currentStatus) || "CASH_DISPENSED".equals(currentStatus) || "REVERSED".equals(currentStatus)) {
            return;
        }

        String providerCode = str(row.get("provider_code"));
        String outletCode = str(row.get("outlet_code"));
        String providerTxnId = str(row.get("provider_txn_id"));
        String externalRef = str(row.get("external_ref"));
        String lat = firstNonBlank(str(row.get("latitude")), "19.0760");
        String lng = firstNonBlank(str(row.get("longitude")), "72.8777");

        if ("EMERALD".equalsIgnoreCase(providerCode) && !outletCode.isBlank()
                && (!providerTxnId.isBlank() || !externalRef.isBlank())) {
            UUID agentId = (UUID) row.get("agent_user_id");
            try (var ignored = EmeraldTraceContext.open(agentId, "STEP6_UPI_STATUS", externalRef)) {
                EmeraldClient.UatmStatusResponse st = emeraldClient.checkUatmQrStatus(
                        outletCode, providerTxnId, externalRef, lat, lng);
                String qrStatus = st.qrStatus();
                if ("SUCCESS".equals(qrStatus) || "PAID".equals(qrStatus) || "COMPLETED".equals(qrStatus)) {
                    settleLockedSessionSuccess(row, st.txnId(), st.utr(), st.payerVpa(), st.payerName());
                    return;
                }
                if ("FAILED".equals(qrStatus) || "DECLINED".equals(qrStatus) || "REJECTED".equals(qrStatus)) {
                    markLockedSessionUnpaidTerminal(row, "FAILED", st.statusCode(),
                            firstNonBlank(st.qrStatusMsg(), st.message()), st.txnId());
                    return;
                }
                if ("EXPIRED".equals(qrStatus)) {
                    markLockedSessionUnpaidTerminal(row, "EXPIRED", st.statusCode(),
                            firstNonBlank(st.qrStatusMsg(), "QR code expired before payment"), st.txnId());
                    return;
                }
            } catch (Exception ex) {
                log.warn("UPI_CASHOUT status poll warning session={}: {}", row.get("id"), ex.getMessage());
            }
        }

        Timestamp exp = (Timestamp) row.get("expires_at");
        if ("AWAITING_PAYMENT".equals(currentStatus)
                && exp != null
                && exp.toInstant().plusSeconds(15).isBefore(Instant.now())) {
            markLockedSessionUnpaidTerminal(row, "EXPIRED", "EXPIRED", "QR session expired without payment", providerTxnId);
        }
    }

    private void settleLockedSessionSuccess(
            Map<String, Object> session,
            String providerTxnId,
            String utrRaw,
            String payerVpa,
            String payerName) {
        UUID sessionId = (UUID) session.get("id");
        UUID txnId = (UUID) session.get("transaction_id");
        UUID agentId = (UUID) session.get("agent_user_id");
        BigDecimal amount = ((BigDecimal) session.get("amount")).setScale(2, RoundingMode.HALF_UP);
        String curStatus = str(session.get("status"));

        if ("PAID".equals(curStatus) || "CASH_DISPENSED".equals(curStatus)) {
            return;
        }

        String resolvedUtr = firstNonBlank(utrRaw, providerTxnId, str(session.get("provider_txn_id")), str(session.get("external_ref")));
        boolean transitioned = transactionService.transition(txnId, "SUCCESS", resolvedUtr, null);
        if (transitioned) {
            Map<String, Object> txn = transactionService.getById(txnId);
            UUID walletId = (UUID) txn.get("wallet_id");
            WalletService.WalletInfo wallet = walletService.lockByWalletId(walletId);
            ledgerService.post(txnId, wallet.walletId(), "CREDIT", amount, "UPI Cashout settlement");
            walletService.adjustCache(walletId, amount, BigDecimal.ZERO);
            commissionEngine.creditRewards(txnId, agentId, "CASHOUT_UPI", amount);
        }

        jdbc.update("""
                UPDATE cashout_sessions
                   SET status = 'PAID',
                       paid_at = COALESCE(paid_at, now()),
                       provider_txn_id = COALESCE(?, provider_txn_id),
                       utr = COALESCE(?, utr),
                       bank_rrn = COALESCE(?, bank_rrn),
                       payer_va = COALESCE(?, payer_va),
                       payer_name = COALESCE(?, payer_name),
                       err_code = NULL,
                       err_msg = NULL,
                       updated_at = now()
                 WHERE id = ?
                """,
                blankToNull(providerTxnId),
                blankToNull(resolvedUtr),
                blankToNull(resolvedUtr),
                blankToNull(payerVpa),
                blankToNull(payerName),
                sessionId);

        log.info("UPI_CASHOUT settled PAID session={} txnId={} amount={} utr={}", sessionId, txnId, amount, resolvedUtr);
    }

    private void markLockedSessionUnpaidTerminal(
            Map<String, Object> session,
            String sessionStatus,
            String errCode,
            String rawErrMsg,
            String providerTxnId) {
        String curStatus = str(session.get("status"));
        if ("PAID".equals(curStatus) || "CASH_DISPENSED".equals(curStatus) || "REVERSED".equals(curStatus)) {
            return;
        }
        UUID sessionId = (UUID) session.get("id");
        UUID txnId = (UUID) session.get("transaction_id");
        String cleanReason = EmeraldMessageSanitizer.sanitize(rawErrMsg, "QR code expired or payment was cancelled.");

        transactionService.transition(txnId, "FAILED", blankToNull(providerTxnId), cleanReason);
        jdbc.update("""
                UPDATE cashout_sessions
                   SET status = ?,
                       provider_txn_id = COALESCE(?, provider_txn_id),
                       err_code = ?,
                       err_msg = ?,
                       updated_at = now()
                 WHERE id = ?
                """,
                sessionStatus,
                blankToNull(providerTxnId),
                blankToNull(errCode),
                cleanReason,
                sessionId);
    }

    private void handleLockedSessionReversal(Map<String, Object> session, String rawMessage, String utr) {
        UUID sessionId = (UUID) session.get("id");
        UUID txnId = (UUID) session.get("transaction_id");
        BigDecimal amount = ((BigDecimal) session.get("amount")).setScale(2, RoundingMode.HALF_UP);
        String curStatus = str(session.get("status"));
        String reason = EmeraldMessageSanitizer.sanitize(rawMessage, "UPI Cashout reversed by payer bank");

        if ("PAID".equals(curStatus) || "CASH_DISPENSED".equals(curStatus)) {
            if (transactionService.transition(txnId, "REVERSED", blankToNull(utr), reason)) {
                Map<String, Object> txn = transactionService.getById(txnId);
                UUID walletId = (UUID) txn.get("wallet_id");
                WalletService.WalletInfo wallet = walletService.lockByWalletId(walletId);
                ledgerService.post(txnId, wallet.walletId(), "DEBIT", amount, "UPI Cashout bank reversal");
                walletService.adjustCache(walletId, amount.negate(), BigDecimal.ZERO);
            }
        } else {
            transactionService.transition(txnId, "REVERSED", blankToNull(utr), reason);
        }
        jdbc.update("""
                UPDATE cashout_sessions
                   SET status = 'REVERSED', err_msg = ?, updated_at = now()
                 WHERE id = ?
                """, reason, sessionId);
        log.warn("UPI_CASHOUT REVERSED session={} txnId={} amount={}", sessionId, txnId, amount);
    }

    private Map<String, Object> findSessionForUpdate(String externalRef, String providerTxnId) {
        if (externalRef != null && !externalRef.isBlank()) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT %s FROM cashout_sessions c WHERE c.external_ref = ? FOR UPDATE
                    """.formatted(SESSION_COLUMNS), externalRef.trim());
            if (!rows.isEmpty()) return rows.get(0);
        }
        if (providerTxnId != null && !providerTxnId.isBlank()) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT %s FROM cashout_sessions c WHERE c.provider_txn_id = ? FOR UPDATE
                    """.formatted(SESSION_COLUMNS), providerTxnId.trim());
            if (!rows.isEmpty()) return rows.get(0);
        }
        return null;
    }

    private Map<String, Object> loadForUpdateById(UUID sessionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT %s FROM cashout_sessions c WHERE c.id = ? FOR UPDATE
                """.formatted(SESSION_COLUMNS), sessionId);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "CASHOUT_NOT_FOUND", "Cash-out session not found");
        }
        return rows.get(0);
    }

    private Map<String, Object> loadForUser(UUID userId, UUID sessionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT %s
                  FROM cashout_sessions c
                 WHERE c.id = ? AND c.agent_user_id = ?
                """.formatted(SESSION_COLUMNS), sessionId, userId);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "CASHOUT_NOT_FOUND", "Cash-out session not found");
        }
        return rows.get(0);
    }

    private void requireUnlockedMerchant(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT u.status AS user_status,
                       COALESCE(p.business_verified, FALSE) AS business_verified,
                       p.business_status,
                       p.agreement_status
                  FROM users u
                  LEFT JOIN agent_kyc_profiles p ON p.user_id = u.id
                 WHERE u.id = ?
                """, userId);
        if (rows.isEmpty() || !"ACTIVE".equalsIgnoreCase(str(rows.get(0).get("user_status")))) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE", "Your account is not active.");
        }
        Map<String, Object> r = rows.get(0);
        boolean bizOk = Boolean.TRUE.equals(r.get("business_verified"))
                || "BUSINESS_VERIFIED".equalsIgnoreCase(str(r.get("business_status")));
        boolean agrOk = "SIGNED".equalsIgnoreCase(str(r.get("agreement_status")));
        if (!bizOk || !agrOk) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "ONBOARDING_INCOMPLETE",
                    "Complete business verification and sign your partner agreement before using cash services.");
        }
    }

    private String resolveCoordinate(String raw, UUID userId, boolean latitude) {
        if (raw != null && !raw.isBlank()) {
            try {
                BigDecimal bd = new BigDecimal(raw.trim());
                double d = bd.doubleValue();
                if ((latitude && d >= -90 && d <= 90) || (!latitude && d >= -180 && d <= 180)) {
                    return bd.toPlainString();
                }
            } catch (NumberFormatException ignored) {
            }
        }
        String col = latitude ? "work_latitude" : "work_longitude";
        List<BigDecimal> saved = jdbc.queryForList(
                "SELECT " + col + " FROM agent_kyc_profiles WHERE user_id = ? AND " + col + " IS NOT NULL",
                BigDecimal.class, userId);
        if (!saved.isEmpty() && saved.get(0) != null) {
            return saved.get(0).toPlainString();
        }
        throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "LOCATION_REQUIRED",
                "Allow location access in your browser to generate a cashout QR code.");
    }

    private void setCustomerRefJson(UUID txnId, Map<String, Object> ref) {
        try {
            transactionService.setCustomerRef(txnId, mapper.writeValueAsString(ref));
        } catch (Exception ignored) {
        }
    }

    private static Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.get("id"));
        out.put("transactionId", row.get("transaction_id"));
        out.put("amount", row.get("amount"));
        out.put("payableValue", row.get("payable_value") != null ? row.get("payable_value") : row.get("amount"));
        out.put("status", row.get("status"));
        out.put("qrString", firstNonBlank(str(row.get("qr_string")), str(row.get("upi_qr_payload"))));
        out.put("displayExpirySec", row.get("display_expiry_sec") != null ? row.get("display_expiry_sec") : 90);
        out.put("customerName", row.get("customer_name"));
        out.put("customerMobile", row.get("customer_mobile"));
        out.put("bankRrn", firstNonBlank(str(row.get("utr")), str(row.get("bank_rrn"))));
        out.put("utr", firstNonBlank(str(row.get("utr")), str(row.get("bank_rrn"))));
        out.put("ackno", row.get("ackno"));
        out.put("errCode", row.get("err_code"));
        out.put("errMsg", EmeraldMessageSanitizer.sanitizeNullable(str(row.get("err_msg"))));
        out.put("payerName", row.get("payer_name"));
        out.put("payerVpa", row.get("payer_va"));
        out.put("refid", firstNonBlank(str(row.get("external_ref")), str(row.get("provider_refid"))));
        out.put("expiresAt", row.get("expires_at"));
        out.put("paidAt", row.get("paid_at"));
        out.put("dispensedAt", row.get("dispensed_at"));
        out.put("createdAt", row.get("created_at"));
        return out;
    }

    private static BigDecimal parseAmountOrFallback(String raw, BigDecimal fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return new BigDecimal(raw.trim()).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String normalizeMobile(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("\\D", "");
        if (digits.length() == 12 && digits.startsWith("91")) {
            return digits.substring(2);
        }
        return digits;
    }

    private static String blankToNull(String val) {
        return (val == null || val.isBlank()) ? null : val.trim();
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return "";
    }

    private static String str(Object val) {
        return val == null ? "" : String.valueOf(val).trim();
    }
}
