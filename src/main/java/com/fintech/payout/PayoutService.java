package com.fintech.payout;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settlement Payout service ({@code POST /payout/v1/pay}) enforcing:
 * <ul>
 *   <li>Completed merchant biometric verification ({@code FULL_KYC})</li>
 *   <li>Synced primary settlement bank account ({@code bank_sync_status == 'SYNCED'})</li>
 *   <li>Minimum {@code 7} completed cash-out transactions ({@code CASHOUT_UPI} + {@code CASHOUT_AEPS})</li>
 *   <li>At least {@code 1} completed cash-out transaction within the past 7 days</li>
 * </ul>
 */
@Service
public class PayoutService {

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);
    private static final Set<String> VALID_MODES = Set.of("IMPS", "NEFT", "RTGS");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PlatformServiceGate services;
    private final ServiceProviderRouter router;
    private final EmeraldClient emeraldClient;
    private final WalletService walletService;
    private final LedgerService ledgerService;
    private final TransactionService transactionService;
    private final int minCashoutTxns;
    private final BigDecimal minAmount;
    private final BigDecimal maxAmount;

    public PayoutService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            PlatformServiceGate services,
            ServiceProviderRouter router,
            EmeraldClient emeraldClient,
            WalletService walletService,
            LedgerService ledgerService,
            TransactionService transactionService,
            @Value("${app.payout.min-cashout-txns:7}") int minCashoutTxns,
            @Value("${app.payout.min-amount:100}") BigDecimal minAmount,
            @Value("${app.payout.max-amount:200000}") BigDecimal maxAmount) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.services = services;
        this.router = router;
        this.emeraldClient = emeraldClient;
        this.walletService = walletService;
        this.ledgerService = ledgerService;
        this.transactionService = transactionService;
        this.minCashoutTxns = Math.max(1, minCashoutTxns);
        this.minAmount = minAmount.setScale(2, RoundingMode.HALF_UP);
        this.maxAmount = maxAmount.setScale(2, RoundingMode.HALF_UP);
    }

    public Map<String, Object> checkEligibility(AuthPrincipal me) {
        UUID userId = me.userId();
        boolean serviceEnabled = services.isEnabled(PlatformServiceGate.PAYOUT);

        List<Map<String, Object>> kycRows = jdbc.queryForList("""
                SELECT u.status AS user_status,
                       COALESCE(p.business_verified, FALSE) AS business_verified,
                       p.business_status,
                       p.agreement_status
                  FROM users u
                  LEFT JOIN agent_kyc_profiles p ON p.user_id = u.id
                 WHERE u.id = ?
                """, userId);
        boolean activeAccount = !kycRows.isEmpty() && "ACTIVE".equalsIgnoreCase(str(kycRows.get(0).get("user_status")));
        boolean servicesUnlocked = !kycRows.isEmpty()
                && (Boolean.TRUE.equals(kycRows.get(0).get("business_verified"))
                || "BUSINESS_VERIFIED".equalsIgnoreCase(str(kycRows.get(0).get("business_status"))))
                && "SIGNED".equalsIgnoreCase(str(kycRows.get(0).get("agreement_status")));

        List<Map<String, Object>> channelRows = jdbc.queryForList("""
                SELECT a.kyc_status, a.bank_sync_status, a.synced_bank_account_id
                  FROM provider_pipe_activations a
                  JOIN service_provider_channels c ON c.id = a.channel_id
                  JOIN provider_merchant_accounts m ON m.id = a.merchant_account_id
                 WHERE a.user_id = ?
                   AND a.enabled_for_retailer = TRUE
                   AND c.active = TRUE
                   AND c.supports_payout = TRUE
                   AND m.status = 'ACTIVE'
                 ORDER BY c.priority_order ASC
                """, userId);

        boolean biometricVerified = channelRows.stream()
                .anyMatch(r -> "FULL_KYC".equalsIgnoreCase(str(r.get("kyc_status"))));
        boolean bankSynced = channelRows.stream()
                .anyMatch(r -> "FULL_KYC".equalsIgnoreCase(str(r.get("kyc_status")))
                        && "SYNCED".equalsIgnoreCase(str(r.get("bank_sync_status"))));

        Map<String, Object> bankRow = loadPrimarySettlementBank(userId);

        int totalCompletedCashouts = countCompletedCashouts(userId, false);
        int recent7dCashouts = countCompletedCashouts(userId, true);
        int remainingCashouts = Math.max(0, minCashoutTxns - totalCompletedCashouts);

        WalletService.WalletInfo wallet = walletService.getByUser(userId);

        List<String> reasons = new ArrayList<>();
        if (!serviceEnabled) {
            reasons.add("Settlement payouts are temporarily paused for maintenance.");
        }
        if (!servicesUnlocked) {
            reasons.add("Complete business verification and sign your partner agreement.");
        }
        if (!biometricVerified) {
            reasons.add("Complete your merchant biometric verification on the Cash desk.");
        }
        boolean bankVerified = bankRow != null
                && "VERIFIED".equalsIgnoreCase(str(bankRow.get("verification_status")));
        if (bankRow == null) {
            reasons.add("Add an active settlement bank account under Settlement Banks.");
        } else if (!bankVerified) {
            reasons.add("Verify your settlement bank account under Settings → Banks.");
        } else if (!bankSynced) {
            reasons.add("Your settlement bank account is pending banking switch verification.");
        }
        if (remainingCashouts > 0) {
            reasons.add("Complete " + remainingCashouts + " more cash-out transaction"
                    + (remainingCashouts == 1 ? "" : "s") + " (minimum " + minCashoutTxns + " required to unlock payouts).");
        } else if (recent7dCashouts == 0) {
            reasons.add("At least 1 cash-out transaction in the past 7 days is required before initiating bank settlement.");
        }
        if (!wallet.active()) {
            reasons.add("Your operating wallet is frozen.");
        }

        boolean eligible = serviceEnabled && activeAccount && servicesUnlocked && biometricVerified
                && bankVerified && bankSynced
                && totalCompletedCashouts >= minCashoutTxns
                && recent7dCashouts > 0
                && wallet.active();

        Map<String, Object> settlementBank = null;
        if (bankRow != null) {
            settlementBank = new LinkedHashMap<>();
            settlementBank.put("id", bankRow.get("id"));
            settlementBank.put("accountHolderName", bankRow.get("account_name"));
            settlementBank.put("accountNumberMasked", maskAccount(str(bankRow.get("account_number"))));
            settlementBank.put("ifsc", bankRow.get("ifsc"));
            settlementBank.put("bankName", bankRow.get("bank_name"));
            settlementBank.put("branchName", bankRow.get("branch_name"));
            settlementBank.put("accountType", bankRow.getOrDefault("account_type", "SAVINGS"));
            settlementBank.put("verificationStatus", bankRow.get("verification_status"));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eligible", eligible);
        out.put("biometricVerified", biometricVerified);
        out.put("bankSynced", bankSynced);
        out.put("minCashoutRequired", minCashoutTxns);
        out.put("completedCashoutCount", totalCompletedCashouts);
        out.put("remainingCashoutCount", remainingCashouts);
        out.put("recent7DayCashoutCount", recent7dCashouts);
        out.put("availableBalance", wallet.available());
        out.put("holdBalance", wallet.hold());
        out.put("minAmount", minAmount);
        out.put("maxAmount", maxAmount);
        out.put("settlementBank", settlementBank);
        out.put("reasons", reasons);
        return out;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> initiatePayout(
            AuthPrincipal me,
            BigDecimal rawAmount,
            String transferModeRaw,
            String remarksRaw,
            String latitudeRaw,
            String longitudeRaw) {
        services.requireEnabled(PlatformServiceGate.PAYOUT);
        UUID userId = me.userId();

        Map<String, Object> eligibility = checkEligibility(me);
        if (!Boolean.TRUE.equals(eligibility.get("eligible"))) {
            @SuppressWarnings("unchecked")
            List<String> reasons = (List<String>) eligibility.getOrDefault("reasons", List.of());
            String msg = reasons.isEmpty()
                    ? "Your account is not yet eligible for bank settlement payouts."
                    : reasons.get(0);
            throw ApiException.of(HttpStatus.FORBIDDEN, "PAYOUT_NOT_ELIGIBLE", msg);
        }

        if (rawAmount == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "AMOUNT_REQUIRED", "Enter a payout amount.");
        }
        BigDecimal amount = rawAmount.setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(minAmount) < 0 || amount.compareTo(maxAmount) > 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PAYOUT_AMOUNT",
                    "Payout amount must be between ₹" + minAmount.toPlainString() + " and ₹" + maxAmount.toPlainString() + ".");
        }

        String mode = (transferModeRaw == null || transferModeRaw.isBlank())
                ? "IMPS"
                : transferModeRaw.trim().toUpperCase(Locale.ROOT);
        if (!VALID_MODES.contains(mode)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TRANSFER_MODE",
                    "Transfer mode must be IMPS, NEFT, or RTGS.");
        }
        BigDecimal lat = resolveCoordinate(latitudeRaw, userId, true);
        BigDecimal lng = resolveCoordinate(longitudeRaw, userId, false);
        String cleanRemarks = (remarksRaw == null || remarksRaw.isBlank())
                ? "Merchant bank settlement"
                : remarksRaw.trim();

        Map<String, Object> bank = loadPrimarySettlementBank(userId);
        if (bank == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BANK_REQUIRED",
                    "Add an active settlement bank account first.");
        }
        assertBranchSupportsMode(bank.get("ifsc_details_json"), mode);

        ServiceProviderRouter.RoutedChannel channel = router.selectChannel(userId, ServiceProviderRouter.PAYOUT);
        if (!"SYNCED".equalsIgnoreCase(channel.bankSyncStatus())) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "BANK_NOT_SYNCED",
                    "Your settlement bank account is pending banking switch verification.");
        }

        BigDecimal fee = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalDebit = amount.add(fee);

        // Lock wallet and place HOLD before external HTTP call
        WalletService.WalletInfo wallet = walletService.lockByUser(userId);
        if (!wallet.active()) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "WALLET_FROZEN", "Your operating wallet is frozen.");
        }
        if (wallet.available().compareTo(totalDebit) < 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_BALANCE",
                    "Insufficient wallet balance for this payout.");
        }

        String externalRef = "POUT" + System.currentTimeMillis()
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
        String accHolder = str(bank.get("account_name"));
        String accNumber = str(bank.get("account_number"));
        String maskedAcc = maskAccount(accNumber);
        String ifsc = str(bank.get("ifsc"));
        String bankName = str(bank.get("bank_name"));
        UUID bankId = (UUID) bank.get("id");

        UUID txnId = transactionService.create(
                "PAYOUT",
                userId,
                wallet.walletId(),
                amount,
                fee,
                null,
                null,
                channel.providerCode(),
                null);
        transactionService.transition(txnId, "HOLD", externalRef, null);
        setCustomerRefJson(txnId, Map.of(
                "beneficiaryName", accHolder,
                "accountMasked", maskedAcc,
                "ifsc", ifsc,
                "bankName", bankName,
                "transferMode", mode,
                "externalRef", externalRef));

        ledgerService.post(txnId, wallet.walletId(), "DEBIT", totalDebit, "Settlement payout (" + mode + " " + maskedAcc + ")");
        walletService.adjustCache(wallet.walletId(), totalDebit.negate(), totalDebit);

        UUID payoutId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payout_transfers
                    (id, transaction_id, user_id, channel_id, provider_code, pipe_code, outlet_code,
                     bank_account_id, external_ref, transfer_mode, amount, fee,
                     account_holder_name, account_number_masked, ifsc, bank_name,
                     latitude, longitude, remarks, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'HOLD')
                """,
                payoutId, txnId, userId, channel.channelId(), channel.providerCode(), channel.pipeCode(), channel.outletCode(),
                bankId, externalRef, mode, amount, fee,
                accHolder, maskedAcc, ifsc, bankName,
                lat, lng, cleanRemarks);

        EmeraldClient.PayoutResponse payRes;
        try (var ignored = EmeraldTraceContext.open(userId, "STEP4_PAYOUT", externalRef)) {
            payRes = emeraldClient.executePayout(
                    channel.outletCode(),
                    new EmeraldClient.PayoutPayRequest(
                            accHolder,
                            accNumber,
                            ifsc,
                            mode,
                            amount.toPlainString(),
                            externalRef,
                            lat.toPlainString(),
                            lng.toPlainString(),
                            cleanRemarks));
        }

        if ("SUCCESS".equals(payRes.outcome())) {
            String utr = firstNonBlank(payRes.utr(), payRes.txnId(), externalRef);
            if (transactionService.transition(txnId, "SUCCESS", utr, null)) {
                walletService.lockByWalletId(wallet.walletId());
                walletService.adjustCache(wallet.walletId(), BigDecimal.ZERO, totalDebit.negate());
            }
            jdbc.update("""
                    UPDATE payout_transfers
                       SET status = 'SUCCESS',
                           provider_txn_id = ?,
                           utr = ?,
                           provider_status_code = ?,
                           provider_message = ?,
                           updated_at = now()
                     WHERE id = ?
                    """, blankToNull(payRes.txnId()), utr, payRes.statusCode(), payRes.message(), payoutId);
            log.info("PAYOUT settled immediately id={} txnId={} amount={} utr={}", payoutId, txnId, amount, utr);
        } else if ("PENDING".equals(payRes.outcome())) {
            String pRef = firstNonBlank(payRes.txnId(), externalRef);
            transactionService.transition(txnId, "PENDING", pRef, null);
            jdbc.update("""
                    UPDATE payout_transfers
                       SET status = 'PENDING',
                           provider_txn_id = ?,
                           provider_status_code = ?,
                           provider_message = ?,
                           updated_at = now()
                     WHERE id = ?
                    """, blankToNull(payRes.txnId()), payRes.statusCode(), payRes.message(), payoutId);
            log.info("PAYOUT pending confirmation id={} txnId={} amount={} code={}", payoutId, txnId, amount, payRes.statusCode());
        } else {
            String cleanReason = EmeraldMessageSanitizer.sanitize(
                    payRes.message(),
                    "Bank declined the settlement transfer. Your wallet balance has been restored.");
            if (transactionService.transition(txnId, "FAILED", blankToNull(payRes.txnId()), cleanReason)) {
                walletService.lockByWalletId(wallet.walletId());
                ledgerService.post(txnId, wallet.walletId(), "CREDIT", totalDebit, "Settlement payout reversal");
                walletService.adjustCache(wallet.walletId(), totalDebit, totalDebit.negate());
            }
            jdbc.update("""
                    UPDATE payout_transfers
                       SET status = 'FAILED',
                           provider_txn_id = ?,
                           provider_status_code = ?,
                           provider_message = ?,
                           updated_at = now()
                     WHERE id = ?
                    """, blankToNull(payRes.txnId()), payRes.statusCode(), cleanReason, payoutId);
            log.warn("PAYOUT declined & reversed id={} txnId={} code={} reason={}",
                    payoutId, txnId, payRes.statusCode(), payRes.message());
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "PAYOUT_DECLINED", cleanReason);
        }

        return view(loadById(payoutId));
    }

    public List<Map<String, Object>> history(AuthPrincipal me) {
        services.requireEnabled(PlatformServiceGate.PAYOUT);
        return jdbc.queryForList("""
                SELECT * FROM payout_transfers
                 WHERE user_id = ?
                 ORDER BY created_at DESC
                 LIMIT 30
                """, me.userId())
                .stream()
                .map(this::view)
                .toList();
    }

    @Transactional
    public Map<String, Object> reconcileByTransactionId(UUID transactionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM payout_transfers WHERE transaction_id = ? FOR UPDATE", transactionId);
        if (rows.isEmpty()) {
            return Map.of("matched", false);
        }
        Map<String, Object> row = rows.get(0);
        String curStatus = str(row.get("status"));
        if ("HOLD".equals(curStatus) || "PENDING".equals(curStatus)) {
            UUID payoutUserId = (UUID) row.get("user_id");
            String outletCode = str(row.get("outlet_code"));
            String externalRef = str(row.get("external_ref"));
            String providerTxnId = str(row.get("provider_txn_id"));
            EmeraldClient.GenericApiResponse st;
            try (var ignored = EmeraldTraceContext.open(payoutUserId, "STEP6_PAYOUT_STATUS", externalRef)) {
                st = emeraldClient.checkPayoutStatus(outletCode, externalRef, providerTxnId);
            }
            if (!"UNCONFIGURED".equals(st.statusCode()) && st.httpStatus() > 0) {
                String upStatus = firstNonBlank(str(st.data().get("status")), st.statusCode()).toUpperCase(Locale.ROOT);
                String utr = firstNonBlank(str(st.data().get("utr")), str(st.data().get("rrn")), str(st.data().get("txnId")));
                if ("SUCCESS".equals(upStatus) || "2000".equals(upStatus)) {
                    applyFinalStatusToLockedPayout(row, "SUCCESS", st.message(), utr, providerTxnId);
                } else if ("FAILED".equals(upStatus) || "REVERSED".equals(upStatus)) {
                    applyFinalStatusToLockedPayout(row, upStatus, st.message(), utr, providerTxnId);
                }
            }
        }
        return view(loadById((UUID) row.get("id")));
    }

    @Transactional
    public UUID finalizeFromCallback(
            String externalRef,
            String providerTxnId,
            String callbackStatus,
            String rawMessage,
            String utr) {
        Map<String, Object> row = findForUpdate(externalRef, providerTxnId);
        if (row == null) {
            log.warn("PAYOUT callback unmatched externalRef={} providerTxnId={}", externalRef, providerTxnId);
            return null;
        }
        String norm = callbackStatus == null ? "" : callbackStatus.trim().toUpperCase(Locale.ROOT);
        applyFinalStatusToLockedPayout(row, norm, rawMessage, utr, providerTxnId);
        return (UUID) row.get("transaction_id");
    }

    private void applyFinalStatusToLockedPayout(
            Map<String, Object> row,
            String targetStatus,
            String rawMessage,
            String utrRaw,
            String providerTxnId) {
        UUID payoutId = (UUID) row.get("id");
        UUID txnId = (UUID) row.get("transaction_id");
        String curStatus = str(row.get("status"));
        BigDecimal amount = (BigDecimal) row.get("amount");
        BigDecimal fee = (BigDecimal) row.get("fee");
        BigDecimal total = amount.add(fee);
        Map<String, Object> txn = transactionService.getById(txnId);
        UUID walletId = (UUID) txn.get("wallet_id");

        if ("SUCCESS".equals(targetStatus)) {
            if ("HOLD".equals(curStatus) || "PENDING".equals(curStatus)) {
                String resolvedUtr = firstNonBlank(utrRaw, providerTxnId, str(row.get("external_ref")));
                if (transactionService.transition(txnId, "SUCCESS", resolvedUtr, null)) {
                    walletService.lockByWalletId(walletId);
                    walletService.adjustCache(walletId, BigDecimal.ZERO, total.negate());
                }
                jdbc.update("""
                        UPDATE payout_transfers
                           SET status = 'SUCCESS',
                               provider_txn_id = COALESCE(?, provider_txn_id),
                               utr = COALESCE(?, utr),
                               provider_message = ?,
                               updated_at = now()
                         WHERE id = ?
                        """, blankToNull(providerTxnId), blankToNull(resolvedUtr), rawMessage, payoutId);
                log.info("PAYOUT finalized SUCCESS id={} txnId={} utr={}", payoutId, txnId, resolvedUtr);
            }
        } else if ("FAILED".equals(targetStatus) || "EXPIRED".equals(targetStatus)) {
            if ("HOLD".equals(curStatus) || "PENDING".equals(curStatus)) {
                String cleanReason = EmeraldMessageSanitizer.sanitize(
                        rawMessage, "Bank declined settlement transfer; wallet balance restored.");
                if (transactionService.transition(txnId, "FAILED", blankToNull(providerTxnId), cleanReason)) {
                    WalletService.WalletInfo w = walletService.lockByWalletId(walletId);
                    ledgerService.post(txnId, w.walletId(), "CREDIT", total, "Settlement payout reversal");
                    walletService.adjustCache(walletId, total, total.negate());
                }
                jdbc.update("""
                        UPDATE payout_transfers
                           SET status = 'FAILED',
                               provider_txn_id = COALESCE(?, provider_txn_id),
                               provider_message = ?,
                               updated_at = now()
                         WHERE id = ?
                        """, blankToNull(providerTxnId), cleanReason, payoutId);
                log.info("PAYOUT finalized FAILED & refunded id={} txnId={}", payoutId, txnId);
            }
        } else if ("REVERSED".equals(targetStatus)) {
            String cleanReason = EmeraldMessageSanitizer.sanitize(
                    rawMessage, "Bank returned settlement transfer; funds credited back to wallet.");
            if ("HOLD".equals(curStatus) || "PENDING".equals(curStatus)) {
                if (transactionService.transition(txnId, "REVERSED", blankToNull(utrRaw), cleanReason)) {
                    WalletService.WalletInfo w = walletService.lockByWalletId(walletId);
                    ledgerService.post(txnId, w.walletId(), "CREDIT", total, "Settlement payout bank return refund");
                    walletService.adjustCache(walletId, total, total.negate());
                }
            } else if ("SUCCESS".equals(curStatus)) {
                // Hold was already captured on SUCCESS; credit back to available_balance directly
                if (transactionService.transition(txnId, "REVERSED", blankToNull(utrRaw), cleanReason)) {
                    WalletService.WalletInfo w = walletService.lockByWalletId(walletId);
                    ledgerService.post(txnId, w.walletId(), "CREDIT", total, "Settlement payout bank return refund");
                    walletService.adjustCache(walletId, total, BigDecimal.ZERO);
                }
            } else {
                return;
            }
            jdbc.update("""
                    UPDATE payout_transfers
                       SET status = 'REVERSED',
                           provider_txn_id = COALESCE(?, provider_txn_id),
                           utr = COALESCE(?, utr),
                           provider_message = ?,
                           updated_at = now()
                     WHERE id = ?
                    """, blankToNull(providerTxnId), blankToNull(utrRaw), cleanReason, payoutId);
            log.info("PAYOUT finalized REVERSED & refunded id={} txnId={}", payoutId, txnId);
        }
    }

    private int countCompletedCashouts(UUID userId, boolean last7DaysOnly) {
        String sql = last7DaysOnly
                ? """
                  SELECT COUNT(*)::int
                    FROM transactions
                   WHERE agent_user_id = ?
                     AND txn_type IN ('CASHOUT_UPI', 'CASHOUT_AEPS')
                     AND state = 'SUCCESS'
                     AND created_at >= now() - interval '7 days'
                  """
                : """
                  SELECT COUNT(*)::int
                    FROM transactions
                   WHERE agent_user_id = ?
                     AND txn_type IN ('CASHOUT_UPI', 'CASHOUT_AEPS')
                     AND state = 'SUCCESS'
                  """;
        Integer count = jdbc.queryForObject(sql, Integer.class, userId);
        return count == null ? 0 : count;
    }

    private Map<String, Object> loadPrimarySettlementBank(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, account_name, account_number, ifsc, bank_name, branch_name,
                       COALESCE(account_type, 'SAVINGS') AS account_type,
                       COALESCE(ifsc_details, '{}'::jsonb)::text AS ifsc_details_json,
                       verification_status
                  FROM retailer_bank_accounts
                 WHERE user_id = ? AND active = TRUE
                 ORDER BY (verification_status = 'VERIFIED') DESC, created_at DESC
                 LIMIT 1
                """, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @SuppressWarnings("unchecked")
    private void assertBranchSupportsMode(Object ifscDetailsJson, String mode) {
        if (ifscDetailsJson == null) return;
        String raw = String.valueOf(ifscDetailsJson).trim();
        if (raw.isBlank() || "{}".equals(raw)) return;
        try {
            Map<String, Object> details = mapper.readValue(raw, Map.class);
            String key = mode.toLowerCase(Locale.ROOT);
            if (Boolean.FALSE.equals(details.get(key))) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "TRANSFER_MODE_NOT_SUPPORTED",
                        "Your verified bank branch does not support " + mode + ". Please choose an available rail (e.g. NEFT or IMPS).");
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception ignored) {
        }
    }

    private Map<String, Object> findForUpdate(String externalRef, String providerTxnId) {
        if (externalRef != null && !externalRef.isBlank()) {
            List<Map<String, Object>> r = jdbc.queryForList(
                    "SELECT * FROM payout_transfers WHERE external_ref = ? FOR UPDATE", externalRef.trim());
            if (!r.isEmpty()) return r.get(0);
        }
        if (providerTxnId != null && !providerTxnId.isBlank()) {
            List<Map<String, Object>> r = jdbc.queryForList(
                    "SELECT * FROM payout_transfers WHERE provider_txn_id = ? FOR UPDATE", providerTxnId.trim());
            if (!r.isEmpty()) return r.get(0);
        }
        return null;
    }

    private Map<String, Object> loadById(UUID id) {
        return jdbc.queryForMap("SELECT * FROM payout_transfers WHERE id = ?", id);
    }

    private Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.get("id"));
        out.put("transactionId", row.get("transaction_id"));
        out.put("transferMode", row.get("transfer_mode"));
        out.put("amount", row.get("amount"));
        out.put("fee", row.get("fee"));
        out.put("accountHolderName", row.get("account_holder_name"));
        out.put("accountNumberMasked", row.get("account_number_masked"));
        out.put("ifsc", row.get("ifsc"));
        out.put("bankName", row.get("bank_name"));
        out.put("utr", row.get("utr"));
        out.put("reference", row.get("external_ref"));
        out.put("remarks", row.get("remarks"));
        out.put("status", row.get("status"));
        out.put("message", EmeraldMessageSanitizer.sanitizeNullable(str(row.get("provider_message"))));
        out.put("createdAt", row.get("created_at"));
        out.put("updatedAt", row.get("updated_at"));
        return out;
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
        return latitude ? new BigDecimal("19.0760") : new BigDecimal("72.8777");
    }

    private void setCustomerRefJson(UUID txnId, Map<String, Object> ref) {
        try {
            transactionService.setCustomerRef(txnId, mapper.writeValueAsString(ref));
        } catch (Exception ignored) {
        }
    }

    private static String maskAccount(String acc) {
        if (acc == null || acc.isBlank()) return "";
        return acc.length() <= 4 ? acc : "••••" + acc.substring(acc.length() - 4);
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

