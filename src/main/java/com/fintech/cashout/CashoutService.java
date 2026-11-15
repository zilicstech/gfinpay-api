package com.fintech.cashout;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.commission.CommissionEngine;
import com.fintech.emerald.EmeraldMessageSanitizer;
import com.fintech.emerald.EmeraldTraceContext;
import com.fintech.ledger.LedgerService;
import com.fintech.ledger.TransactionService;
import com.fintech.ledger.WalletService;
import com.fintech.platform.PlatformServiceGate;
import com.fintech.platform.idempotency.ExternalRefs;
import com.fintech.platform.idempotency.IdempotencyService;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.tx.ShortTransactions;
import com.fintech.platform.web.ApiException;
import com.fintech.provider.ChannelLimits;
import com.fintech.provider.ProviderDispatch;
import com.fintech.provider.ProviderRegistry;
import com.fintech.provider.ServiceProviderRouter;
import com.fintech.provider.canonical.ProviderModels;
import com.fintech.verification.VerificationGate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    private final ServiceProviderRouter router;
    private final ProviderRegistry providers;
    private final ProviderDispatch dispatch;
    private final WalletService walletService;
    private final LedgerService ledgerService;
    private final TransactionService transactionService;
    private final CommissionEngine commissionEngine;
    private final ShortTransactions shortTx;
    private final IdempotencyService idempotency;

    public CashoutService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            PlatformServiceGate services,
            VerificationGate verification,
            ServiceProviderRouter router,
            ProviderRegistry providers,
            ProviderDispatch dispatch,
            WalletService walletService,
            LedgerService ledgerService,
            TransactionService transactionService,
            CommissionEngine commissionEngine,
            ShortTransactions shortTx,
            IdempotencyService idempotency) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.services = services;
        this.verification = verification;
        this.router = router;
        this.providers = providers;
        this.dispatch = dispatch;
        this.walletService = walletService;
        this.ledgerService = ledgerService;
        this.transactionService = transactionService;
        this.commissionEngine = commissionEngine;
        this.shortTx = shortTx;
        this.idempotency = idempotency;
    }

    /**
     * Generates a dynamic UPI cash-out QR. The provider call runs outside any row lock.
     */
    public Map<String, Object> generateQr(
            AuthPrincipal me,
            Integer amountRupees,
            String customerMobileRaw,
            String customerNameRaw,
            String latitudeRaw,
            String longitudeRaw,
            String idempotencyKey) {
        String idemKey = ExternalRefs.requireKey(idempotencyKey);
        services.requireEnabled(PlatformServiceGate.UPI_CASHOUT);
        UUID userId = me.userId();
        requireUnlockedMerchant(userId);

        String cleanMobile = normalizeMobile(customerMobileRaw);
        if (!cleanMobile.matches("[6-9][0-9]{9}")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MOBILE",
                    "Enter a valid 10-digit customer mobile number.");
        }
        String cleanName = customerNameRaw == null ? "" : customerNameRaw.trim();
        String lat = resolveCoordinate(latitudeRaw, userId, true);
        String lng = resolveCoordinate(longitudeRaw, userId, false);

        ProviderDispatch.RoutedUpi route = dispatch.upiCashout(userId);
        ServiceProviderRouter.RoutedChannel channel = route.channel();
        ChannelLimits.MoneyRule rule = ChannelLimits.upiCashout(channel.configJson());
        if (amountRupees == null || amountRupees < rule.min() || amountRupees > rule.max()
                || rule.step() <= 0 || amountRupees % rule.step() != 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CASHOUT_AMOUNT",
                    "UPI Cashout amount must be between ₹" + rule.min() + " and ₹" + rule.max()
                            + " in multiples of ₹" + rule.step() + ".");
        }
        WalletService.WalletInfo wallet = walletService.getByUser(userId);
        if (!wallet.active()) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "WALLET_FROZEN",
                    "Your operating wallet is currently frozen. Please contact support.");
        }

        String canonical = amountRupees + "|" + cleanMobile + "|" + cleanName + "|" + lat + "|" + lng;
        IdempotencyService.Claim claim = idempotency.claim(userId, idemKey, canonical);
        if (claim.replay()) {
            return readReplay(claim.responseBody());
        }
        String externalRef = ExternalRefs.fromKey("CW", claim.keyId());
        BigDecimal amount = BigDecimal.valueOf(amountRupees).setScale(2, RoundingMode.HALF_UP);

        ProviderModels.QrResult qrRes;
        try (var ignored = EmeraldTraceContext.open(userId, "STEP4_UPI_QR", externalRef)) {
            qrRes = route.port().generateQr(
                    channel,
                    new ProviderModels.QrRequest(amountRupees, cleanMobile, cleanName, lat, lng, externalRef));
        }
        if (!qrRes.success()) {
            String fallbackMsg = "Unable to generate UPI QR right now. Please try again shortly.";
            String rawMsg = qrRes.message();
            if (rawMsg != null && rawMsg.toLowerCase(Locale.ROOT).contains("successfully")) {
                rawMsg = fallbackMsg;
            }
            String msg = EmeraldMessageSanitizer.sanitize(rawMsg, fallbackMsg);
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "QR_GENERATION_FAILED", msg);
        }
        router.noteSuccessfulSelection(ServiceProviderRouter.UPI_CASHOUT);

        Map<String, Object> created = shortTx.required(() -> persistQrSession(
                userId, wallet, channel, amount, externalRef, qrRes, cleanName, cleanMobile, lat, lng));
        completeIdempotency(claim.keyId(), created);
        return created;
    }

    public Map<String, Object> limits(AuthPrincipal me) {
        services.requireEnabled(PlatformServiceGate.UPI_CASHOUT);
        ChannelLimits.MoneyRule rule;
        try {
            ServiceProviderRouter.RoutedChannel channel = router.selectChannel(me.userId(), ServiceProviderRouter.UPI_CASHOUT);
            rule = ChannelLimits.upiCashout(channel.configJson());
        } catch (ApiException ex) {
            rule = ChannelLimits.upiCashout("{}");
        }
        return Map.of("min", rule.min(), "max", rule.max(), "step", rule.step());
    }

    private Map<String, Object> persistQrSession(
            UUID userId,
            WalletService.WalletInfo wallet,
            ServiceProviderRouter.RoutedChannel channel,
            BigDecimal amount,
            String externalRef,
            ProviderModels.QrResult qrRes,
            String cleanName,
            String cleanMobile,
            String lat,
            String lng) {
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
        String storedQr = qrRes.storedQr();

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
                storedQr,
                storedQr,
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

    public Map<String, Object> refresh(AuthPrincipal me, UUID sessionId) {
        services.requireEnabled(PlatformServiceGate.UPI_CASHOUT);
        Map<String, Object> row = loadForUser(me.userId(), sessionId);
        ProviderModels.StatusResult upstream = pollUpstream(row);
        return shortTx.required(() -> {
            Map<String, Object> locked = loadForUpdateById(sessionId);
            if (!me.userId().equals(locked.get("agent_user_id"))) {
                throw ApiException.of(HttpStatus.NOT_FOUND, "CASHOUT_NOT_FOUND", "Cash-out session not found");
            }
            applyPolledStatus(locked, upstream);
            return view(loadForUser(me.userId(), sessionId));
        });
    }

    public Map<String, Object> reconcileByTransactionId(UUID transactionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT %s
                  FROM cashout_sessions c
                 WHERE c.transaction_id = ?
                """.formatted(SESSION_COLUMNS), transactionId);
        if (rows.isEmpty()) {
            return Map.of("matched", false);
        }
        ProviderModels.StatusResult upstream = pollUpstream(rows.get(0));
        return shortTx.required(() -> {
            List<Map<String, Object>> lockedRows = jdbc.queryForList("""
                    SELECT %s
                      FROM cashout_sessions c
                     WHERE c.transaction_id = ?
                     FOR UPDATE
                    """.formatted(SESSION_COLUMNS), transactionId);
            if (lockedRows.isEmpty()) {
                return Map.of("matched", false);
            }
            Map<String, Object> locked = lockedRows.get(0);
            applyPolledStatus(locked, upstream);
            return view(jdbc.queryForMap("""
                    SELECT %s FROM cashout_sessions c WHERE c.id = ?
                    """.formatted(SESSION_COLUMNS), locked.get("id")));
        });
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

    private ProviderModels.StatusResult pollUpstream(Map<String, Object> row) {
        String currentStatus = str(row.get("status"));
        if ("PAID".equals(currentStatus) || "CASH_DISPENSED".equals(currentStatus) || "REVERSED".equals(currentStatus)) {
            return null;
        }
        String providerCode = str(row.get("provider_code"));
        String outletCode = str(row.get("outlet_code"));
        String providerTxnId = str(row.get("provider_txn_id"));
        String externalRef = str(row.get("external_ref"));
        if (providerCode.isBlank() || outletCode.isBlank() || (providerTxnId.isBlank() && externalRef.isBlank())) {
            return null;
        }
        String lat = firstNonBlank(str(row.get("latitude")), "19.0760");
        String lng = firstNonBlank(str(row.get("longitude")), "72.8777");
        UUID agentId = (UUID) row.get("agent_user_id");
        try (var ignored = EmeraldTraceContext.open(agentId, "STEP6_UPI_STATUS", externalRef)) {
            ServiceProviderRouter.RoutedChannel channel = new ServiceProviderRouter.RoutedChannel(
                    null, "", providerCode, str(row.get("pipe_code")), outletCode, "", "", "", "{}");
            return providers.upi(providerCode).checkStatus(channel, providerTxnId, externalRef, lat, lng);
        } catch (Exception ex) {
            log.warn("UPI_CASHOUT status poll warning session={}: {}", row.get("id"), ex.getMessage());
            return null;
        }
    }

    private void applyPolledStatus(Map<String, Object> row, ProviderModels.StatusResult upstream) {
        String currentStatus = str(row.get("status"));
        if ("PAID".equals(currentStatus) || "CASH_DISPENSED".equals(currentStatus) || "REVERSED".equals(currentStatus)) {
            return;
        }
        String providerTxnId = str(row.get("provider_txn_id"));
        if (upstream != null) {
            String qrStatus = upstream.qrStatus() == null ? "" : upstream.qrStatus();
            if ("SUCCESS".equals(qrStatus)) {
                settleLockedSessionSuccess(row, upstream.txnId(), upstream.utr(), upstream.payerVpa(), upstream.payerName());
                return;
            }
            if ("FAILED".equals(qrStatus)) {
                markLockedSessionUnpaidTerminal(row, "FAILED", upstream.statusCode(), upstream.message(), upstream.txnId());
                return;
            }
            if ("EXPIRED".equals(qrStatus)) {
                markLockedSessionUnpaidTerminal(row, "EXPIRED", upstream.statusCode(),
                        firstNonBlank(upstream.message(), "QR code expired before payment"), upstream.txnId());
                return;
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
        String qr = firstNonBlank(str(row.get("qr_string")), str(row.get("upi_qr_payload")));
        String status = str(row.get("status"));
        if ("AWAITING_PAYMENT".equals(status)) {
            status = "QR_GENERATED";
        }
        int displayExpirySec = row.get("display_expiry_sec") instanceof Number n ? n.intValue() : 90;
        Timestamp expTs = (Timestamp) row.get("expires_at");
        long remainingSec = expTs != null
                ? Math.max(0L, Duration.between(Instant.now(), expTs.toInstant()).getSeconds())
                : displayExpirySec;
        out.put("status", status);
        out.put("qrString", qr);
        out.put("qrKind", qrKind(qr));
        out.put("displayExpirySec", displayExpirySec);
        out.put("remainingSec", remainingSec);
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

    private static String qrKind(String qr) {
        if (qr == null || qr.isBlank()) {
            return "";
        }
        String value = qr.trim();
        if (value.regionMatches(true, 0, "upi:", 0, 4) || value.startsWith("000201")) {
            return "UPI_STRING";
        }
        return "PNG_BASE64";
    }

    private Map<String, Object> readReplay(String body) {
        try {
            return mapper.readValue(body, new TypeReference<>() {});
        } catch (Exception e) {
            throw ApiException.of(HttpStatus.CONFLICT, "IDEMPOTENCY_REPLAY", "Stored response could not be read");
        }
    }

    private void completeIdempotency(UUID keyId, Map<String, Object> body) {
        try {
            idempotency.complete(keyId, 200, mapper.writeValueAsString(body));
        } catch (Exception e) {
            log.warn("UPI_CASHOUT idempotency complete failed key={}: {}", keyId, e.getMessage());
        }
    }
}
