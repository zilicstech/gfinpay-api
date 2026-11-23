package com.fintech.payout;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.emerald.EmeraldMessageSanitizer;
import com.fintech.emerald.EmeraldTraceContext;
import com.fintech.ledger.LedgerService;
import com.fintech.ledger.TransactionService;
import com.fintech.ledger.WalletService;
import com.fintech.platform.PlatformServiceGate;
import com.fintech.platform.idempotency.ExternalRefs;
import com.fintech.platform.idempotency.IdempotencyService;
import com.fintech.platform.outbox.OutboxWriter;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.tx.ShortTransactions;
import com.fintech.platform.web.ApiException;
import com.fintech.provider.ProviderDispatch;
import com.fintech.provider.ProviderRegistry;
import com.fintech.provider.ServiceProviderRouter;
import com.fintech.provider.canonical.ProviderModels;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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
    private final ProviderRegistry providers;
    private final ProviderDispatch dispatch;
    private final WalletService walletService;
    private final LedgerService ledgerService;
    private final TransactionService transactionService;
    private final ShortTransactions shortTx;
    private final IdempotencyService idempotency;
    private final OutboxWriter outbox;
    private final int minCashoutTxns;
    private final BigDecimal minAmount;
    private final BigDecimal maxAmount;

    public PayoutService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            PlatformServiceGate services,
            ServiceProviderRouter router,
            ProviderRegistry providers,
            ProviderDispatch dispatch,
            WalletService walletService,
            LedgerService ledgerService,
            TransactionService transactionService,
            ShortTransactions shortTx,
            IdempotencyService idempotency,
            OutboxWriter outbox,
            @Value("${app.payout.min-cashout-txns:7}") int minCashoutTxns,
            @Value("${app.payout.min-amount:100}") BigDecimal minAmount,
            @Value("${app.payout.max-amount:200000}") BigDecimal maxAmount) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.services = services;
        this.router = router;
        this.providers = providers;
        this.dispatch = dispatch;
        this.walletService = walletService;
        this.ledgerService = ledgerService;
        this.transactionService = transactionService;
        this.shortTx = shortTx;
        this.idempotency = idempotency;
        this.outbox = outbox;
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
                SELECT a.kyc_status, a.bank_sync_status, a.synced_bank_account_id, c.biometric_required
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

        Map<String, Object> bankRow = loadPrimarySettlementBank(userId);
        UUID primaryBankId = bankRow != null ? (UUID) bankRow.get("id") : null;

        boolean biometricVerified = channelRows.stream().anyMatch(PayoutService::payoutChannelReady);
        boolean bankSynced = channelRows.stream().anyMatch(r ->
                payoutChannelReady(r)
                        && (!requiresBankSwitchSync(r)
                        || ("SYNCED".equalsIgnoreCase(str(r.get("bank_sync_status")))
                        && (r.get("synced_bank_account_id") == null
                        || r.get("synced_bank_account_id").equals(primaryBankId)))));

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

    public Map<String, Object> initiatePayout(
            AuthPrincipal me,
            BigDecimal rawAmount,
            String transferModeRaw,
            String remarksRaw,
            String latitudeRaw,
            String longitudeRaw,
            String idempotencyKey) {
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

        ProviderDispatch.RoutedPayout route = dispatch.payout(userId);
        ServiceProviderRouter.RoutedChannel channel = route.channel();
        boolean iserveu = "ISERVEU".equalsIgnoreCase(channel.providerCode());
        UUID bankId = (UUID) bank.get("id");
        boolean sameBankSynced = "SYNCED".equalsIgnoreCase(channel.bankSyncStatus())
                && (channel.syncedBankAccountId() == null || channel.syncedBankAccountId().equals(bankId));
        if (!iserveu && !sameBankSynced) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "BANK_NOT_SYNCED",
                    "Your settlement bank account is pending banking switch verification.");
        }
        if (iserveu && !"IMPS".equals(mode) && !"NEFT".equals(mode)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TRANSFER_MODE",
                    "iServeU settlement supports IMPS and NEFT.");
        }
        SettlementCustomer customer = iserveu ? loadSettlementCustomer(userId, bank) : SettlementCustomer.empty();

        BigDecimal fee = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalDebit = amount.add(fee);
        String idemKey = ExternalRefs.requireKey(idempotencyKey);
        String canonical = amount.toPlainString() + "|" + mode + "|" + cleanRemarks + "|" + lat.toPlainString() + "|" + lng.toPlainString();
        IdempotencyService.Claim claim = idempotency.claim(userId, idemKey, canonical);
        if (claim.replay()) {
            if (claim.responseCode() != null && claim.responseCode() >= 400) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "PAYOUT_DECLINED", claim.responseBody());
            }
            return readReplay(claim.responseBody());
        }
        String externalRef = "ISERVEU".equalsIgnoreCase(channel.providerCode())
                ? ExternalRefs.payoutClientRef(claim.keyId())
                : ExternalRefs.fromKey("PO", claim.keyId());
        var port = route.port();

        HeldPayout hold = shortTx.required(() -> placeHold(
                userId, channel, bank, amount, fee, totalDebit, externalRef, mode, lat, lng, cleanRemarks));

        ProviderModels.PayoutResult payRes;
        try (var ignored = EmeraldTraceContext.open(userId, "STEP4_PAYOUT", externalRef)) {
            payRes = port.submit(channel, new ProviderModels.PayoutRequest(
                    hold.accHolder(),
                    hold.accNumber(),
                    hold.ifsc(),
                    mode,
                    amount.toPlainString(),
                    externalRef,
                    lat.toPlainString(),
                    lng.toPlainString(),
                    cleanRemarks,
                    customer.name(),
                    customer.mobile(),
                    customer.pincode(),
                    customer.bankName(),
                    customer.clientIp()));
        } catch (RuntimeException ex) {
            log.warn("PAYOUT provider call inconclusive id={} ref={}: {}", hold.payoutId(), externalRef, ex.getMessage());
            payRes = new ProviderModels.PayoutResult(ProviderModels.Outcome.PENDING, "TIMEOUT",
                    "Awaiting bank confirmation", "", "");
        }
        if (!"TIMEOUT".equals(payRes.statusCode())) {
            try {
                router.noteSuccessfulSelection(ServiceProviderRouter.PAYOUT);
            } catch (RuntimeException ex) {
                log.warn("PAYOUT round-robin note failed ref={}: {}", externalRef, ex.getMessage());
            }
        }

        ProviderModels.PayoutResult outcome = payRes;
        AppliedPayoutOutcome applied;
        try {
            applied = shortTx.required(() -> applyPayoutOutcome(hold, outcome));
        } catch (ApiException ex) {
            try {
                idempotency.complete(claim.keyId(), ex.getStatus().value(), ex.getMessage());
            } catch (Exception ignored) {
                log.warn("PAYOUT idempotency complete failed key={}", claim.keyId());
            }
            throw ex;
        }
        if (applied.declineReason() != null) {
            ApiException declined = ApiException.of(
                    HttpStatus.UNPROCESSABLE_ENTITY, "PAYOUT_DECLINED", applied.declineReason());
            try {
                idempotency.complete(claim.keyId(), declined.getStatus().value(), declined.getMessage());
            } catch (Exception ignored) {
                log.warn("PAYOUT idempotency complete failed key={}", claim.keyId());
            }
            throw declined;
        }
        completeIdempotency(claim.keyId(), 200, applied.view());
        return applied.view();
    }

    private record AppliedPayoutOutcome(Map<String, Object> view, String declineReason) {}

    private record HeldPayout(
            UUID payoutId,
            UUID txnId,
            UUID walletId,
            BigDecimal totalDebit,
            BigDecimal amount,
            String externalRef,
            String accHolder,
            String accNumber,
            String ifsc
    ) {}

    private HeldPayout placeHold(
            UUID userId,
            ServiceProviderRouter.RoutedChannel channel,
            Map<String, Object> bank,
            BigDecimal amount,
            BigDecimal fee,
            BigDecimal totalDebit,
            String externalRef,
            String mode,
            BigDecimal lat,
            BigDecimal lng,
            String cleanRemarks) {
        WalletService.WalletInfo wallet = walletService.lockByUser(userId);
        if (!wallet.active()) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "WALLET_FROZEN", "Your operating wallet is frozen.");
        }
        if (wallet.available().compareTo(totalDebit) < 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_BALANCE",
                    "Insufficient wallet balance for this payout.");
        }
        String accHolder = firstFilled(str(bank.get("verified_name")), str(bank.get("account_name")));
        String accNumber = str(bank.get("account_number"));
        String maskedAcc = maskAccount(accNumber);
        String ifsc = str(bank.get("ifsc"));
        String bankName = str(bank.get("bank_name"));
        UUID bankId = (UUID) bank.get("id");

        UUID txnId = transactionService.create(
                "PAYOUT", userId, wallet.walletId(), amount, fee, null, null, channel.providerCode(), null);
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
        return new HeldPayout(payoutId, txnId, wallet.walletId(), totalDebit, amount, externalRef, accHolder, accNumber, ifsc);
    }

    private AppliedPayoutOutcome applyPayoutOutcome(HeldPayout hold, ProviderModels.PayoutResult payRes) {
        UUID payoutId = hold.payoutId();
        UUID txnId = hold.txnId();
        if (payRes.outcome() == ProviderModels.Outcome.SUCCESS) {
            String utr = firstNonBlank(payRes.utr(), payRes.txnId(), hold.externalRef());
            if (transactionService.transition(txnId, "SUCCESS", utr, null)) {
                walletService.lockByWalletId(hold.walletId());
                walletService.adjustCache(hold.walletId(), BigDecimal.ZERO, hold.totalDebit().negate());
            }
            jdbc.update("""
                    UPDATE payout_transfers
                       SET status = 'SUCCESS', provider_txn_id = ?, utr = ?,
                           provider_status_code = ?, provider_message = ?, updated_at = now()
                     WHERE id = ?
                    """, blankToNull(payRes.txnId()), utr, payRes.statusCode(), payRes.message(), payoutId);
            log.info("PAYOUT settled immediately id={} txnId={} amount={} utr={}", payoutId, txnId, hold.amount(), utr);
        } else if (payRes.outcome() == ProviderModels.Outcome.PENDING) {
            String pRef = firstNonBlank(payRes.txnId(), hold.externalRef());
            transactionService.transition(txnId, "PENDING", pRef, null);
            jdbc.update("""
                    UPDATE payout_transfers
                       SET status = 'PENDING', provider_txn_id = ?,
                           provider_status_code = ?, provider_message = ?, updated_at = now()
                     WHERE id = ?
                    """, blankToNull(payRes.txnId()), payRes.statusCode(), payRes.message(), payoutId);
            outbox.write("PAYOUT", payoutId, "PAYOUT_STATUS_CHECK",
                    "{\"transactionId\":\"" + txnId + "\"}");
            log.info("PAYOUT pending confirmation id={} txnId={} amount={} code={}", payoutId, txnId, hold.amount(), payRes.statusCode());
        } else {
            String cleanReason = EmeraldMessageSanitizer.sanitize(
                    payRes.message(),
                    "Bank declined the settlement transfer. Your wallet balance has been restored.");
            if (transactionService.transition(txnId, "FAILED", blankToNull(payRes.txnId()), cleanReason)) {
                walletService.lockByWalletId(hold.walletId());
                ledgerService.post(txnId, hold.walletId(), "CREDIT", hold.totalDebit(), "Settlement payout reversal");
                walletService.adjustCache(hold.walletId(), hold.totalDebit(), hold.totalDebit().negate());
            }
            jdbc.update("""
                    UPDATE payout_transfers
                       SET status = 'FAILED', provider_txn_id = ?,
                           provider_status_code = ?, provider_message = ?, updated_at = now()
                     WHERE id = ?
                    """, blankToNull(payRes.txnId()), payRes.statusCode(), cleanReason, payoutId);
            log.warn("PAYOUT declined & reversed id={} txnId={} code={}", payoutId, txnId, payRes.statusCode());
            return new AppliedPayoutOutcome(null, cleanReason);
        }
        return new AppliedPayoutOutcome(view(loadById(payoutId)), null);
    }

    private Map<String, Object> readReplay(String body) {
        try {
            return mapper.readValue(body, new TypeReference<>() {});
        } catch (Exception e) {
            throw ApiException.of(HttpStatus.CONFLICT, "IDEMPOTENCY_REPLAY", "Stored response could not be read");
        }
    }

    private void completeIdempotency(UUID keyId, int code, Map<String, Object> body) {
        try {
            idempotency.complete(keyId, code, mapper.writeValueAsString(body));
        } catch (Exception e) {
            log.warn("PAYOUT idempotency complete failed key={}: {}", keyId, e.getMessage());
        }
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

    public Map<String, Object> reconcileByTransactionId(UUID transactionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM payout_transfers WHERE transaction_id = ?", transactionId);
        if (rows.isEmpty()) {
            return Map.of("matched", false);
        }
        Map<String, Object> row = rows.get(0);
        String curStatus = str(row.get("status"));
        if (!"HOLD".equals(curStatus) && !"PENDING".equals(curStatus)) {
            return view(row);
        }
        ProviderModels.PayoutStatus upstream = pollPayout(row);
        if (upstream == null || upstream.outcome() == ProviderModels.Outcome.PENDING) {
            return view(loadById((UUID) row.get("id")));
        }
        return shortTx.required(() -> {
            List<Map<String, Object>> locked = jdbc.queryForList(
                    "SELECT * FROM payout_transfers WHERE transaction_id = ? FOR UPDATE", transactionId);
            if (locked.isEmpty()) {
                return Map.of("matched", false);
            }
            Map<String, Object> current = locked.get(0);
            String lockedStatus = str(current.get("status"));
            if ("HOLD".equals(lockedStatus) || "PENDING".equals(lockedStatus)) {
                String target = upstream.outcome() == ProviderModels.Outcome.SUCCESS ? "SUCCESS" : "FAILED";
                applyFinalStatusToLockedPayout(current, target, upstream.message(), upstream.utr(), upstream.txnId());
            }
            return view(loadById((UUID) current.get("id")));
        });
    }

    private ProviderModels.PayoutStatus pollPayout(Map<String, Object> row) {
        String providerCode = str(row.get("provider_code"));
        String outletCode = str(row.get("outlet_code"));
        String externalRef = str(row.get("external_ref"));
        String providerTxnId = str(row.get("provider_txn_id"));
        if (providerCode.isBlank() || (outletCode.isBlank() && !"ISERVEU".equalsIgnoreCase(providerCode) && !"INSTANTPAY".equalsIgnoreCase(providerCode))) {
            return null;
        }
        try (var ignored = EmeraldTraceContext.open((UUID) row.get("user_id"), "STEP6_PAYOUT_STATUS", externalRef)) {
            ServiceProviderRouter.RoutedChannel channel = new ServiceProviderRouter.RoutedChannel(
                    (UUID) row.get("channel_id"), "", providerCode, str(row.get("pipe_code")),
                    outletCode, "", "", "", "{}", "");
            return providers.payout(providerCode).checkStatus(
                    channel, externalRef, providerTxnId, txnDay(row.get("created_at")));
        } catch (Exception ex) {
            log.warn("PAYOUT status poll warning id={}: {}", row.get("id"), ex.getMessage());
            return null;
        }
    }

    @Transactional
    public boolean adminResolveByTransactionId(
            UUID transactionId,
            String targetStatus,
            String partnerRef,
            String reason) {
        List<Map<String, Object>> locked = jdbc.queryForList(
                "SELECT * FROM payout_transfers WHERE transaction_id = ? FOR UPDATE", transactionId);
        if (locked.isEmpty()) {
            return false;
        }
        Map<String, Object> row = locked.get(0);
        String curStatus = str(row.get("status"));
        if (!"HOLD".equals(curStatus) && !"PENDING".equals(curStatus)) {
            throw ApiException.of(HttpStatus.CONFLICT, "PAYOUT_ALREADY_RESOLVED",
                    "Payout is already in terminal state: " + curStatus);
        }
        String normTarget = "SUCCESS".equalsIgnoreCase(targetStatus) ? "SUCCESS" : "REVERSED";
        String msg = (reason == null || reason.isBlank())
                ? ("SUCCESS".equals(normTarget)
                        ? "Manually confirmed by Admin"
                        : "Manually reversed to wallet by Admin")
                : reason.trim();
        applyFinalStatusToLockedPayout(row, normTarget, msg, partnerRef, partnerRef);
        return true;
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

    private static boolean payoutChannelReady(Map<String, Object> row) {
        String kyc = str(row.get("kyc_status"));
        if ("FULL_KYC".equalsIgnoreCase(kyc)) {
            return true;
        }
        return !requiresBankSwitchSync(row) && "NOT_REQUIRED".equalsIgnoreCase(kyc);
    }

    private static boolean requiresBankSwitchSync(Map<String, Object> row) {
        Object flag = row.get("biometric_required");
        if (flag instanceof Boolean biometric) {
            return biometric;
        }
        return !"false".equalsIgnoreCase(str(flag));
    }

    private record SettlementCustomer(String name, String mobile, String pincode, String bankName, String clientIp) {
        static SettlementCustomer empty() {
            return new SettlementCustomer("", "", "", "", "");
        }
    }

    private SettlementCustomer loadSettlementCustomer(UUID userId, Map<String, Object> bank) {
        Map<String, Object> profile = jdbc.queryForMap("""
                SELECT u.full_name, u.mobile,
                       COALESCE(k.shop_address->>'pincode',
                                k.personal_details->'permanentAddress'->>'pincode',
                                k.personal_details->>'pincode') AS pincode
                  FROM users u
                  LEFT JOIN agent_kyc_profiles k ON k.user_id = u.id
                 WHERE u.id = ?
                """, userId);
        String mobile = digits(str(profile.get("mobile")));
        if (mobile.length() > 10) {
            mobile = mobile.substring(mobile.length() - 10);
        }
        String pincode = digits(str(profile.get("pincode")));
        String bankName = str(bank.get("bank_name")).trim();
        String name = firstFilled(str(profile.get("full_name")), str(bank.get("account_name"))).trim();
        String ip = clientIp();
        if (!mobile.matches("[6-9][0-9]{9}") || !pincode.matches("[1-9][0-9]{5}") || bankName.isBlank() || name.isBlank() || ip.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "PAYOUT_PROFILE_INCOMPLETE",
                    "Settlement needs the retailer mobile, shop pincode, bank name, and a client IP.");
        }
        return new SettlementCustomer(clip(name, 25), mobile, pincode, clip(bankName, 50), ip);
    }

    private static String clientIp() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return "";
        }
        HttpServletRequest request = attrs.getRequest();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        return request.getRemoteAddr() == null ? "" : request.getRemoteAddr().trim();
    }

    private static String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    private static String firstFilled(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String clip(String value, int max) {
        String clean = value == null ? "" : value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private Map<String, Object> loadPrimarySettlementBank(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, account_name, verified_name, account_number, ifsc, bank_name, branch_name,
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

    private static String txnDay(Object createdAt) {
        if (createdAt instanceof Timestamp ts) {
            return ts.toInstant().atZone(ZoneId.of("Asia/Kolkata")).toLocalDate().toString();
        }
        return "";
    }

    private static String str(Object val) {
        return val == null ? "" : String.valueOf(val).trim();
    }
}

