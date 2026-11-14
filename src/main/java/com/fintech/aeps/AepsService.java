package com.fintech.aeps;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.biometric.RdPidXmlParser;
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
import com.fintech.verification.AadhaarValidator;
import java.math.BigDecimal;
import java.math.RoundingMode;
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

/**
 * Aadhaar Enabled Payment System (AePS) service supporting Cash Withdrawal ({@code CW}),
 * Balance Enquiry ({@code BE}), and Mini Statement ({@code MS}) with UIDAI L1 RD Service XML parsing.
 */
@Service
public class AepsService {

    private static final Logger log = LoggerFactory.getLogger(AepsService.class);

    private static final List<Map<String, String>> NPCI_BANKS = List.of(
            Map.of("iin", "607094", "name", "State Bank of India"),
            Map.of("iin", "607152", "name", "HDFC Bank"),
            Map.of("iin", "508534", "name", "ICICI Bank"),
            Map.of("iin", "607153", "name", "Axis Bank"),
            Map.of("iin", "607027", "name", "Punjab National Bank"),
            Map.of("iin", "606985", "name", "Bank of Baroda"),
            Map.of("iin", "607396", "name", "Canara Bank"),
            Map.of("iin", "607161", "name", "Union Bank of India"),
            Map.of("iin", "508505", "name", "Bank of India"),
            Map.of("iin", "990309", "name", "Kotak Mahindra Bank"),
            Map.of("iin", "607189", "name", "IndusInd Bank"),
            Map.of("iin", "608117", "name", "IDFC First Bank"),
            Map.of("iin", "607618", "name", "Yes Bank"),
            Map.of("iin", "608314", "name", "India Post Payments Bank"),
            Map.of("iin", "990320", "name", "Airtel Payments Bank"),
            Map.of("iin", "608001", "name", "Fino Payments Bank")
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PlatformServiceGate services;
    private final ServiceProviderRouter router;
    private final EmeraldClient emeraldClient;
    private final WalletService walletService;
    private final LedgerService ledgerService;
    private final TransactionService transactionService;
    private final CommissionEngine commissionEngine;

    public AepsService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            PlatformServiceGate services,
            ServiceProviderRouter router,
            EmeraldClient emeraldClient,
            WalletService walletService,
            LedgerService ledgerService,
            TransactionService transactionService,
            CommissionEngine commissionEngine) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.services = services;
        this.router = router;
        this.emeraldClient = emeraldClient;
        this.walletService = walletService;
        this.ledgerService = ledgerService;
        this.transactionService = transactionService;
        this.commissionEngine = commissionEngine;
    }

    public List<Map<String, String>> listBanks() {
        return NPCI_BANKS;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> transact(
            AuthPrincipal me,
            String modeRaw,
            String aadhaarRaw,
            String bankIinRaw,
            String customerMobileRaw,
            Integer amountRupees,
            String pidXml,
            String latitudeRaw,
            String longitudeRaw) {
        services.requireEnabled(PlatformServiceGate.AEPS);
        UUID userId = me.userId();
        requireUnlockedMerchant(userId);

        String aepsType = normalizeAepsType(modeRaw);
        String cleanAadhaar = AadhaarValidator.validateFormat(aadhaarRaw);
        String aadhaarLast4 = cleanAadhaar.substring(8);

        String bankIin = bankIinRaw == null ? "" : bankIinRaw.replaceAll("\\D", "");
        if (bankIin.length() < 6) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_BANK_IIN",
                    "Select a valid customer bank.");
        }
        String bankName = NPCI_BANKS.stream()
                .filter(b -> bankIin.equals(b.get("iin")))
                .map(b -> b.get("name"))
                .findFirst()
                .orElse("Bank (" + bankIin + ")");

        String cleanMobile = normalizeMobile(customerMobileRaw);
        if (!cleanMobile.matches("[6-9][0-9]{9}")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MOBILE",
                    "Enter a valid 10-digit customer mobile number.");
        }

        BigDecimal amount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        if ("CASH_WITHDRAWAL".equals(aepsType)) {
            if (amountRupees == null || amountRupees < 100 || amountRupees > 10000 || amountRupees % 50 != 0) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AEPS_AMOUNT",
                        "AePS Cash Withdrawal amount must be between ₹100 and ₹10,000 in multiples of ₹50.");
            }
            amount = BigDecimal.valueOf(amountRupees).setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal lat = resolveCoordinate(latitudeRaw, userId, true);
        BigDecimal lng = resolveCoordinate(longitudeRaw, userId, false);
        RdPidXmlParser.ParsedPidData parsedPid = RdPidXmlParser.parse(pidXml);

        ServiceProviderRouter.RoutedChannel channel = router.selectChannel(userId, ServiceProviderRouter.AEPS);
        WalletService.WalletInfo wallet = walletService.getByUser(userId);
        if (!wallet.active()) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "WALLET_FROZEN", "Your operating wallet is frozen.");
        }

        String externalRef = "AEPS" + System.currentTimeMillis()
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));

        UUID txnId = null;
        if ("CASH_WITHDRAWAL".equals(aepsType)) {
            txnId = transactionService.create(
                    "CASHOUT_AEPS",
                    userId,
                    wallet.walletId(),
                    amount,
                    BigDecimal.ZERO,
                    null,
                    null,
                    channel.providerCode(),
                    null);
            transactionService.transition(txnId, "PENDING", externalRef, null);
        }

        UUID aepsId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO aeps_transactions
                    (id, transaction_id, user_id, channel_id, provider_code, pipe_code, outlet_code,
                     external_ref, aeps_type, bank_iin, bank_name, customer_mobile, aadhaar_last4,
                     amount, latitude, longitude, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                """,
                aepsId,
                txnId,
                userId,
                channel.channelId(),
                channel.providerCode(),
                channel.pipeCode(),
                channel.outletCode(),
                externalRef,
                aepsType,
                bankIin,
                bankName,
                cleanMobile,
                aadhaarLast4,
                amount,
                lat,
                lng);

        Map<String, Object> upstreamPayload = new LinkedHashMap<>();
        upstreamPayload.put("pipeCode", channel.pipeCode());
        upstreamPayload.put("txnType", aepsType);
        upstreamPayload.put("bankIin", bankIin);
        upstreamPayload.put("mobile", cleanMobile);
        upstreamPayload.put("amount", amount.toPlainString());
        upstreamPayload.put("externalRef", externalRef);
        upstreamPayload.put("latitude", lat.toPlainString());
        upstreamPayload.put("longitude", lng.toPlainString());
        upstreamPayload.put("biometricData", parsedPid.toEmeraldBiometricData(cleanAadhaar));

        EmeraldClient.GenericApiResponse res;
        try (var ignored = EmeraldTraceContext.open(userId, "STEP4_AEPS_" + aepsType, externalRef)) {
            res = emeraldClient.executeAeps(channel.outletCode(), upstreamPayload);
        }
        if ("AEPS_PATH_UNCONFIGURED".equals(res.statusCode())) {
            String msg = "AePS banking switch route is awaiting upstream activation on this environment.";
            markFailed(aepsId, txnId, res.statusCode(), msg, null);
            throw ApiException.of(HttpStatus.SERVICE_UNAVAILABLE, "AEPS_SWITCH_PENDING", msg);
        }

        Map<String, Object> data = res.data();
        String providerTxnId = firstNonBlank(str(data.get("txnId")), str(data.get("transactionId")));
        String rrn = firstNonBlank(str(data.get("rrn")), str(data.get("bankRrn")), str(data.get("utr")), providerTxnId);
        BigDecimal availBal = parseBigDecimalOrNull(data.get("availableBalance"));
        Object miniStmt = data.get("miniStatement");

        if (res.success()) {
            settleSuccess(aepsId, txnId, userId, aepsType, amount, providerTxnId, rrn, availBal, miniStmt, res.statusCode(), res.message());
        } else if ("2001".equals(res.statusCode()) || "PENDING".equalsIgnoreCase(str(data.get("status")))) {
            jdbc.update("""
                    UPDATE aeps_transactions
                       SET provider_txn_id = COALESCE(?, provider_txn_id),
                           provider_status_code = ?,
                           provider_message = ?,
                           updated_at = now()
                     WHERE id = ?
                    """, blankToNull(providerTxnId), res.statusCode(), res.message(), aepsId);
        } else {
            String cleanReason = EmeraldMessageSanitizer.sanitize(
                    res.message(),
                    "AePS transaction was declined by the customer's bank.");
            markFailed(aepsId, txnId, res.statusCode(), cleanReason, providerTxnId);
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "AEPS_DECLINED", cleanReason);
        }

        return view(loadById(aepsId));
    }

    public List<Map<String, Object>> history(AuthPrincipal me) {
        services.requireEnabled(PlatformServiceGate.AEPS);
        return jdbc.queryForList("""
                SELECT * FROM aeps_transactions
                 WHERE user_id = ?
                 ORDER BY created_at DESC
                 LIMIT 25
                """, me.userId())
                .stream()
                .map(this::view)
                .toList();
    }

    @Transactional
    public UUID finalizeFromCallback(
            String externalRef,
            String providerTxnId,
            String callbackStatus,
            String rawMessage,
            String rrn,
            BigDecimal availableBalance,
            Object miniStatement) {
        Map<String, Object> row = findForUpdate(externalRef, providerTxnId);
        if (row == null) {
            log.warn("AEPS callback unmatched externalRef={} providerTxnId={}", externalRef, providerTxnId);
            return null;
        }
        UUID aepsId = (UUID) row.get("id");
        UUID txnId = (UUID) row.get("transaction_id");
        UUID userId = (UUID) row.get("user_id");
        String aepsType = str(row.get("aeps_type"));
        BigDecimal amount = (BigDecimal) row.get("amount");
        String norm = callbackStatus == null ? "" : callbackStatus.trim().toUpperCase(Locale.ROOT);

        if ("SUCCESS".equals(norm)) {
            settleSuccess(aepsId, txnId, userId, aepsType, amount, providerTxnId, rrn, availableBalance, miniStatement, "CALLBACK", rawMessage);
        } else if ("FAILED".equals(norm) || "EXPIRED".equals(norm)) {
            String cleanReason = EmeraldMessageSanitizer.sanitize(rawMessage, "AePS transaction failed at bank.");
            markFailed(aepsId, txnId, norm, cleanReason, providerTxnId);
        } else if ("REVERSED".equals(norm)) {
            String reason = EmeraldMessageSanitizer.sanitize(rawMessage, "AePS transaction reversed by bank.");
            if (txnId != null && "SUCCESS".equalsIgnoreCase(str(row.get("status")))) {
                if (transactionService.transition(txnId, "REVERSED", blankToNull(rrn), reason)) {
                    Map<String, Object> txn = transactionService.getById(txnId);
                    UUID walletId = (UUID) txn.get("wallet_id");
                    WalletService.WalletInfo w = walletService.lockByWalletId(walletId);
                    ledgerService.post(txnId, w.walletId(), "DEBIT", amount, "AePS Cashout bank reversal");
                    walletService.adjustCache(walletId, amount.negate(), BigDecimal.ZERO);
                }
            } else if (txnId != null) {
                transactionService.transition(txnId, "REVERSED", blankToNull(rrn), reason);
            }
            jdbc.update("""
                    UPDATE aeps_transactions
                       SET status = 'REVERSED', provider_message = ?, updated_at = now()
                     WHERE id = ?
                    """, reason, aepsId);
        }
        return txnId;
    }

    private void settleSuccess(
            UUID aepsId,
            UUID txnId,
            UUID userId,
            String aepsType,
            BigDecimal amount,
            String providerTxnId,
            String rrn,
            BigDecimal availableBalance,
            Object miniStatement,
            String statusCode,
            String message) {
        Map<String, Object> cur = loadById(aepsId);
        if ("SUCCESS".equalsIgnoreCase(str(cur.get("status")))) {
            return;
        }
        String resolvedRrn = firstNonBlank(rrn, providerTxnId, str(cur.get("external_ref")));

        if ("CASH_WITHDRAWAL".equalsIgnoreCase(aepsType) && txnId != null && amount != null && amount.signum() > 0) {
            if (transactionService.transition(txnId, "SUCCESS", resolvedRrn, null)) {
                Map<String, Object> txn = transactionService.getById(txnId);
                UUID walletId = (UUID) txn.get("wallet_id");
                WalletService.WalletInfo w = walletService.lockByWalletId(walletId);
                ledgerService.post(txnId, w.walletId(), "CREDIT", amount, "AePS Cash Withdrawal settlement");
                walletService.adjustCache(walletId, amount, BigDecimal.ZERO);
                commissionEngine.creditRewards(txnId, userId, "CASHOUT_AEPS", amount);
            }
        }

        String miniJson = null;
        if (miniStatement != null) {
            try {
                miniJson = mapper.writeValueAsString(miniStatement);
            } catch (JsonProcessingException ignored) {
            }
        }

        jdbc.update("""
                UPDATE aeps_transactions
                   SET status = 'SUCCESS',
                       provider_txn_id = COALESCE(?, provider_txn_id),
                       bank_rrn = COALESCE(?, bank_rrn),
                       available_balance = COALESCE(?, available_balance),
                       mini_statement = COALESCE(?::jsonb, mini_statement),
                       provider_status_code = ?,
                       provider_message = ?,
                       updated_at = now()
                 WHERE id = ?
                """,
                blankToNull(providerTxnId),
                blankToNull(resolvedRrn),
                availableBalance,
                miniJson,
                statusCode,
                message,
                aepsId);
    }

    private void markFailed(UUID aepsId, UUID txnId, String code, String cleanReason, String providerTxnId) {
        Map<String, Object> cur = loadById(aepsId);
        if ("SUCCESS".equalsIgnoreCase(str(cur.get("status")))) {
            return;
        }
        if (txnId != null) {
            transactionService.transition(txnId, "FAILED", blankToNull(providerTxnId), cleanReason);
        }
        jdbc.update("""
                UPDATE aeps_transactions
                   SET status = 'FAILED',
                       provider_txn_id = COALESCE(?, provider_txn_id),
                       provider_status_code = ?,
                       provider_message = ?,
                       updated_at = now()
                 WHERE id = ?
                """, blankToNull(providerTxnId), code, cleanReason, aepsId);
    }

    private Map<String, Object> findForUpdate(String externalRef, String providerTxnId) {
        if (externalRef != null && !externalRef.isBlank()) {
            List<Map<String, Object>> r = jdbc.queryForList(
                    "SELECT * FROM aeps_transactions WHERE external_ref = ? FOR UPDATE", externalRef.trim());
            if (!r.isEmpty()) return r.get(0);
        }
        if (providerTxnId != null && !providerTxnId.isBlank()) {
            List<Map<String, Object>> r = jdbc.queryForList(
                    "SELECT * FROM aeps_transactions WHERE provider_txn_id = ? FOR UPDATE", providerTxnId.trim());
            if (!r.isEmpty()) return r.get(0);
        }
        return null;
    }

    private Map<String, Object> loadById(UUID id) {
        return jdbc.queryForMap("SELECT * FROM aeps_transactions WHERE id = ?", id);
    }

    private Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.get("id"));
        out.put("transactionId", row.get("transaction_id"));
        out.put("aepsType", row.get("aeps_type"));
        out.put("bankIin", row.get("bank_iin"));
        out.put("bankName", row.get("bank_name"));
        out.put("customerMobile", row.get("customer_mobile"));
        out.put("aadhaarMasked", "XXXX-XXXX-" + str(row.get("aadhaar_last4")));
        out.put("amount", row.get("amount"));
        out.put("bankRrn", row.get("bank_rrn"));
        out.put("availableBalance", row.get("available_balance"));
        out.put("miniStatement", parseJsonOrNull(row.get("mini_statement")));
        out.put("status", row.get("status"));
        out.put("message", EmeraldMessageSanitizer.sanitizeNullable(str(row.get("provider_message"))));
        out.put("reference", row.get("external_ref"));
        out.put("createdAt", row.get("created_at"));
        return out;
    }

    private Object parseJsonOrNull(Object raw) {
        if (raw == null) return null;
        try {
            return mapper.readValue(String.valueOf(raw), Object.class);
        } catch (Exception e) {
            return null;
        }
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
                    "Complete business verification and sign your partner agreement first.");
        }
    }

    private BigDecimal resolveCoordinate(String raw, UUID userId, boolean latitude) {
        if (raw != null && !raw.isBlank()) {
            try {
                BigDecimal bd = new BigDecimal(raw.trim());
                double d = bd.doubleValue();
                if ((latitude && d >= -90 && d <= 90) || (!latitude && d >= -180 && d <= 180)) {
                    return bd;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        String col = latitude ? "work_latitude" : "work_longitude";
        List<BigDecimal> saved = jdbc.queryForList(
                "SELECT " + col + " FROM agent_kyc_profiles WHERE user_id = ? AND " + col + " IS NOT NULL",
                BigDecimal.class, userId);
        if (!saved.isEmpty() && saved.get(0) != null) {
            return saved.get(0);
        }
        throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "LOCATION_REQUIRED",
                "Allow location access in your browser to run an AePS transaction.");
    }

    private static String normalizeAepsType(String raw) {
        String s = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "CW", "CASH_WITHDRAWAL" -> "CASH_WITHDRAWAL";
            case "BE", "BALANCE_ENQUIRY" -> "BALANCE_ENQUIRY";
            case "MS", "MINI_STATEMENT" -> "MINI_STATEMENT";
            default -> throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AEPS_MODE",
                    "AePS mode must be CW (Cash Withdrawal), BE (Balance Enquiry), or MS (Mini Statement).");
        };
    }

    private static BigDecimal parseBigDecimalOrNull(Object raw) {
        if (raw == null) return null;
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) return null;
        try {
            return new BigDecimal(s).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String normalizeMobile(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("\\D", "");
        return (digits.length() == 12 && digits.startsWith("91")) ? digits.substring(2) : digits;
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

