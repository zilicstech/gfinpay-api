package com.fintech.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.aeps.AepsService;
import com.fintech.cashout.CashoutService;
import com.fintech.dmt.DmtCasaLedger;
import com.fintech.dmt.DmtCasaService;
import com.fintech.emerald.EmeraldCallbackService;
import com.fintech.identity.AdminPlatformService;
import com.fintech.identity.DeskService;
import com.fintech.payout.PayoutService;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Step 6: Manual transaction status verification & reconciliation service
 * for both White-Label Retailer/Distributor desks and Admin Operations.
 */
@Service
public class TransactionStatusVerificationService {

    private static final Logger log = LoggerFactory.getLogger(TransactionStatusVerificationService.class);
    private static final Set<String> NON_TERMINAL_STATES = Set.of("INITIATED", "HOLD", "PENDING");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final DeskService deskService;
    private final AdminPlatformService adminService;
    private final CashoutService cashoutService;
    private final PayoutService payoutService;
    private final AepsService aepsService;
    private final EmeraldCallbackService callbackService;
    private final DmtCasaLedger dmtCasaLedger;
    private final TransactionService transactionService;
    private final WalletService walletService;
    private final LedgerService ledgerService;
    private DmtCasaService dmtCasaService;

    @Autowired(required = false)
    public void setDmtCasaService(DmtCasaService dmtCasaService) {
        this.dmtCasaService = dmtCasaService;
    }

    @Autowired
    public TransactionStatusVerificationService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            DeskService deskService,
            AdminPlatformService adminService,
            CashoutService cashoutService,
            PayoutService payoutService,
            AepsService aepsService,
            EmeraldCallbackService callbackService,
            DmtCasaLedger dmtCasaLedger,
            TransactionService transactionService,
            WalletService walletService,
            LedgerService ledgerService) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.deskService = deskService;
        this.adminService = adminService;
        this.cashoutService = cashoutService;
        this.payoutService = payoutService;
        this.aepsService = aepsService;
        this.callbackService = callbackService;
        this.dmtCasaLedger = dmtCasaLedger;
        this.transactionService = transactionService;
        this.walletService = walletService;
        this.ledgerService = ledgerService;
    }

    public TransactionStatusVerificationService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            DeskService deskService,
            AdminPlatformService adminService,
            CashoutService cashoutService,
            PayoutService payoutService,
            EmeraldCallbackService callbackService,
            DmtCasaLedger dmtCasaLedger,
            TransactionService transactionService,
            WalletService walletService,
            LedgerService ledgerService) {
        this(jdbc, mapper, deskService, adminService, cashoutService, payoutService, null,
                callbackService, dmtCasaLedger, transactionService, walletService, ledgerService);
    }

    public TransactionStatusVerificationService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            DeskService deskService,
            AdminPlatformService adminService,
            CashoutService cashoutService,
            PayoutService payoutService,
            EmeraldCallbackService callbackService) {
        this(jdbc, mapper, deskService, adminService, cashoutService, payoutService, null, callbackService,
                null, null, null, null);
    }

    @Transactional
    public Map<String, Object> verifyForDesk(AuthPrincipal me, UUID txnId) {
        Map<String, Object> before = deskService.getTransaction(me, txnId);
        String previousState = str(before.get("state"));
        String txnType = str(before.get("txn_type"));

        String customMessage = reconcileTransaction(txnId, txnType, previousState, false);

        Map<String, Object> after = deskService.getTransaction(me, txnId);
        String currentState = str(after.get("state"));
        boolean changed = !previousState.equals(currentState);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transaction", after);
        out.put("previousState", previousState);
        out.put("currentState", currentState);
        out.put("changed", changed);
        out.put("verifiedAt", Instant.now());
        out.put("message", (!changed && customMessage != null) ? customMessage : buildSummaryMessage(currentState, changed));
        return out;
    }

    @Transactional
    public Map<String, Object> verifyForAdmin(UUID txnId) {
        Map<String, Object> before = adminService.getTransaction(txnId);
        String previousState = str(before.get("state"));
        String txnType = str(before.get("txn_type"));

        String customMessage = reconcileTransaction(txnId, txnType, previousState, true);

        Map<String, Object> after = adminService.getTransaction(txnId);
        String currentState = str(after.get("state"));
        boolean changed = !previousState.equals(currentState);

        List<String> refs = collectSearchRefs(txnId, str(after.get("partner_ref")));
        List<Map<String, Object>> matchingCallbacks = findMatchingCallbacks(refs);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transaction", after);
        out.put("previousState", previousState);
        out.put("currentState", currentState);
        out.put("changed", changed);
        out.put("verifiedAt", Instant.now());
        out.put("callbacks", matchingCallbacks);
        out.put("message", (!changed && customMessage != null) ? customMessage : buildSummaryMessage(currentState, changed));
        return out;
    }

    /**
     * Allows an Admin to manually finalize a stuck {@code HOLD} / {@code PENDING} / {@code INITIATED}
     * transaction when the upstream banking switch is unresponsive:
     * <ul>
     *   <li>{@code CONFIRM} ({@code SUCCESS}): finalizes wallet deduction (releases hold or debits if not yet debited)</li>
     *   <li>{@code REVERSE} ({@code REVERSED}): releases hold and credits funds back to the retailer's wallet</li>
     * </ul>
     */
    @Transactional
    public Map<String, Object> adminResolve(
            AuthPrincipal adminUser,
            UUID txnId,
            String actionRaw,
            String partnerRefRaw,
            String reasonRaw) {
        String normAction = actionRaw == null ? "" : actionRaw.trim().toUpperCase(Locale.ROOT);
        boolean confirm;
        if ("CONFIRM".equals(normAction) || "SUCCESS".equals(normAction)) {
            confirm = true;
        } else if ("REVERSE".equals(normAction) || "REVERSED".equals(normAction)
                || "REFUND".equals(normAction) || "FAILED".equals(normAction)) {
            confirm = false;
        } else {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_RESOLUTION_ACTION",
                    "Action must be CONFIRM (mark confirmed & deduct wallet) or REVERSE (refund back to wallet).");
        }

        List<Map<String, Object>> lockedRows = jdbc.queryForList("""
                SELECT id, txn_type::text AS txn_type, state::text AS state,
                       agent_user_id, wallet_id, amount, fee, partner_ref
                  FROM transactions
                 WHERE id = ?
                 FOR UPDATE
                """, txnId);
        if (lockedRows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", "Transaction not found");
        }
        Map<String, Object> row = lockedRows.get(0);
        String previousState = str(row.get("state"));
        String txnType = str(row.get("txn_type"));
        if (!NON_TERMINAL_STATES.contains(previousState)) {
            throw ApiException.of(HttpStatus.CONFLICT, "TRANSACTION_ALREADY_TERMINAL",
                    "Transaction is already in terminal state: " + previousState);
        }

        String cleanRef = (partnerRefRaw == null || partnerRefRaw.isBlank()) ? null : partnerRefRaw.trim();
        String defaultReason = confirm
                ? "Manually confirmed by Admin"
                : "Manually reversed to wallet by Admin (upstream API unresponsive)";
        String cleanReason = (reasonRaw == null || reasonRaw.isBlank()) ? defaultReason : reasonRaw.trim();

        boolean handledByRail = false;
        if ("PAYOUT".equalsIgnoreCase(txnType)) {
            handledByRail = payoutService.adminResolveByTransactionId(
                    txnId, confirm ? "SUCCESS" : "REVERSED", cleanRef, cleanReason);
        } else if (("CASHOUT_AEPS".equalsIgnoreCase(txnType) || "AEPS_DEPOSIT".equalsIgnoreCase(txnType))
                && aepsService != null) {
            handledByRail = aepsService.adminResolveByTransactionId(
                    txnId, confirm ? "SUCCESS" : "REVERSED", cleanRef, cleanReason);
        } else if ("DMT".equalsIgnoreCase(txnType) && dmtCasaLedger != null) {
            handledByRail = dmtCasaLedger.adminResolveByTransactionId(txnId, confirm, cleanRef, cleanReason);
        }

        if (!handledByRail) {
            applyGenericAdminResolution(row, previousState, txnType, confirm, cleanRef, cleanReason);
        }

        UUID actorId = adminUser != null ? adminUser.userId() : null;
        log.info("ADMIN_TXN_RESOLVE txnId={} type={} from={} action={} by={} ref={} reason={}",
                txnId, txnType, previousState, confirm ? "CONFIRM" : "REVERSE", actorId, cleanRef, cleanReason);

        Map<String, Object> after = adminService.getTransaction(txnId);
        String currentState = str(after.get("state"));
        List<String> refs = collectSearchRefs(txnId, str(after.get("partner_ref")));
        List<Map<String, Object>> matchingCallbacks = findMatchingCallbacks(refs);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transaction", after);
        out.put("previousState", previousState);
        out.put("currentState", currentState);
        out.put("changed", !previousState.equals(currentState));
        out.put("verifiedAt", Instant.now());
        out.put("callbacks", matchingCallbacks);
        out.put("message", confirm
                ? "Transaction marked as SUCCESS and wallet deduction finalized."
                : "Transaction reversed and funds refunded back to retailer wallet.");
        return out;
    }

    private void applyGenericAdminResolution(
            Map<String, Object> row,
            String previousState,
            String txnType,
            boolean confirm,
            String cleanRef,
            String cleanReason) {
        UUID txnId = (UUID) row.get("id");
        UUID walletId = (UUID) row.get("wallet_id");
        BigDecimal amount = (BigDecimal) row.get("amount");
        BigDecimal fee = row.get("fee") instanceof BigDecimal f ? f : BigDecimal.ZERO;
        BigDecimal total = amount.add(fee);

        BigDecimal netDebited = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END), 0)
                  FROM ledger_entries
                 WHERE transaction_id = ? AND wallet_id = ?
                """, BigDecimal.class, txnId, walletId);
        if (netDebited == null) {
            netDebited = BigDecimal.ZERO;
        }

        WalletService.WalletInfo wallet = walletService.lockByWalletId(walletId);
        if (confirm) {
            if ("HOLD".equals(previousState)) {
                BigDecimal holdToRelease = wallet.hold().min(total);
                walletService.adjustCache(walletId, BigDecimal.ZERO, holdToRelease.negate());
            } else if (netDebited.signum() <= 0) {
                if (wallet.available().compareTo(total) < 0) {
                    throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_WALLET_BALANCE",
                            "Retailer available wallet balance (₹" + wallet.available()
                                    + ") is less than required deduction (₹" + total + ").");
                }
                ledgerService.post(txnId, walletId, "DEBIT", total, txnType + " confirmed by Admin");
                walletService.adjustCache(walletId, total.negate(), BigDecimal.ZERO);
            }
            transactionService.transition(txnId, "SUCCESS", cleanRef, null);
        } else {
            BigDecimal refundAmount = netDebited.signum() > 0 ? netDebited : ("HOLD".equals(previousState) ? total : BigDecimal.ZERO);
            BigDecimal holdToRelease = "HOLD".equals(previousState) ? wallet.hold().min(total) : BigDecimal.ZERO;
            if (refundAmount.signum() > 0) {
                ledgerService.post(txnId, walletId, "CREDIT", refundAmount, "Admin reversal: " + cleanReason);
            }
            if (refundAmount.signum() > 0 || holdToRelease.signum() > 0) {
                walletService.adjustCache(walletId, refundAmount, holdToRelease.negate());
            }
            transactionService.transition(txnId, "REVERSED", cleanRef, cleanReason);
        }
    }

    private String reconcileTransaction(UUID txnId, String txnType, String currentState, boolean forceAdmin) {
        List<String> refs = collectSearchRefs(txnId, null);

        // 1. Replay any received provider callback in case async processing was missed
        if (NON_TERMINAL_STATES.contains(currentState) && !refs.isEmpty()) {
            for (String ref : refs) {
                List<String> payloads = jdbc.queryForList("""
                        SELECT payload::text
                          FROM provider_callbacks
                         WHERE provider = 'EMERALD'
                           AND outcome = 'ACCEPTED'
                           AND (payload->>'externalRef' = ? OR payload->>'txnId' = ?)
                         ORDER BY id DESC
                         LIMIT 3
                        """, String.class, ref, ref);
                for (String rawJson : payloads) {
                    try {
                        JsonNode node = mapper.readTree(rawJson);
                        callbackService.replayPayloadIdempotently(node);
                    } catch (Exception ex) {
                        log.debug("Callback replay parse skipped txnId={}: {}", txnId, ex.getMessage());
                    }
                }
            }
        }

        // 2. Poll live upstream status endpoint where available
        if ("CASHOUT_UPI".equalsIgnoreCase(txnType)) {
            cashoutService.reconcileByTransactionId(txnId);
        } else if ("PAYOUT".equalsIgnoreCase(txnType)) {
            payoutService.reconcileByTransactionId(txnId);
        } else if (("CASHOUT_AEPS".equalsIgnoreCase(txnType) || "AEPS_DEPOSIT".equalsIgnoreCase(txnType))
                && aepsService != null) {
            aepsService.reconcileByTransactionId(txnId);
        } else if ("DMT".equalsIgnoreCase(txnType) && dmtCasaService != null) {
            Map<String, Object> dmtRes = dmtCasaService.reconcileByTransactionId(txnId, forceAdmin);
            if (dmtRes != null) {
                if (Boolean.TRUE.equals(dmtRes.get("statusCheckThrottled")) && dmtRes.get("statusCheckMessage") != null) {
                    return String.valueOf(dmtRes.get("statusCheckMessage"));
                }
                if (dmtRes.get("reconfirmedAt") != null && "SUCCESS".equalsIgnoreCase(str(dmtRes.get("state")))) {
                    return "DMT settlement reconfirmed with the bank.";
                }
            }
        }
        return null;
    }

    private List<String> collectSearchRefs(UUID txnId, String existingPartnerRef) {
        List<String> refs = new ArrayList<>();
        if (existingPartnerRef != null && !existingPartnerRef.isBlank()) {
            refs.add(existingPartnerRef.trim());
        } else {
            List<String> pRefs = jdbc.queryForList(
                    "SELECT partner_ref FROM transactions WHERE id = ? AND partner_ref IS NOT NULL",
                    String.class, txnId);
            pRefs.stream().filter(s -> s != null && !s.isBlank()).forEach(refs::add);
        }

        jdbc.queryForList("""
                SELECT external_ref, provider_txn_id FROM cashout_sessions WHERE transaction_id = ?
                """, txnId).forEach(r -> {
            addIfPresent(refs, r.get("external_ref"));
            addIfPresent(refs, r.get("provider_txn_id"));
        });

        jdbc.queryForList("""
                SELECT external_ref, provider_txn_id FROM payout_transfers WHERE transaction_id = ?
                """, txnId).forEach(r -> {
            addIfPresent(refs, r.get("external_ref"));
            addIfPresent(refs, r.get("provider_txn_id"));
        });

        jdbc.queryForList("""
                SELECT external_ref, provider_txn_id FROM aeps_transactions WHERE transaction_id = ?
                """, txnId).forEach(r -> {
            addIfPresent(refs, r.get("external_ref"));
            addIfPresent(refs, r.get("provider_txn_id"));
        });

        jdbc.queryForList("""
                SELECT external_ref, reference_id, provider_ackno FROM dmt_transfers WHERE transaction_id = ?
                """, txnId).forEach(r -> {
            addIfPresent(refs, r.get("external_ref"));
            addIfPresent(refs, r.get("reference_id"));
            addIfPresent(refs, r.get("provider_ackno"));
        });

        return refs.stream().distinct().toList();
    }

    private List<Map<String, Object>> findMatchingCallbacks(List<String> refs) {
        if (refs.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String ref : refs) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT id, provider, event, status, merchant_code, outcome, received_at AS created_at
                      FROM provider_callbacks
                     WHERE payload->>'externalRef' = ? OR payload->>'txnId' = ?
                     ORDER BY received_at DESC
                     LIMIT 5
                    """, ref, ref);
            out.addAll(rows);
        }
        return out;
    }

    private static String buildSummaryMessage(String currentState, boolean changed) {
        if (changed) {
            return "Transaction status updated to " + currentState + ".";
        }
        return switch (currentState) {
            case "SUCCESS" -> "Transaction is confirmed successful.";
            case "FAILED" -> "Transaction is marked failed.";
            case "REVERSED" -> "Transaction has been reversed.";
            default -> "Transaction is still awaiting final confirmation from the bank.";
        };
    }

    private static void addIfPresent(List<String> list, Object val) {
        if (val != null) {
            String s = String.valueOf(val).trim();
            if (!s.isEmpty()) list.add(s);
        }
    }

    private static String str(Object val) {
        return val == null ? "" : String.valueOf(val).trim();
    }
}

