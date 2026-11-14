package com.fintech.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.cashout.CashoutService;
import com.fintech.emerald.EmeraldCallbackService;
import com.fintech.identity.AdminPlatformService;
import com.fintech.identity.DeskService;
import com.fintech.payout.PayoutService;
import com.fintech.platform.security.AuthPrincipal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final EmeraldCallbackService callbackService;

    public TransactionStatusVerificationService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            DeskService deskService,
            AdminPlatformService adminService,
            CashoutService cashoutService,
            PayoutService payoutService,
            EmeraldCallbackService callbackService) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.deskService = deskService;
        this.adminService = adminService;
        this.cashoutService = cashoutService;
        this.payoutService = payoutService;
        this.callbackService = callbackService;
    }

    @Transactional
    public Map<String, Object> verifyForDesk(AuthPrincipal me, UUID txnId) {
        Map<String, Object> before = deskService.getTransaction(me, txnId);
        String previousState = str(before.get("state"));
        String txnType = str(before.get("txn_type"));

        reconcileTransaction(txnId, txnType, previousState);

        Map<String, Object> after = deskService.getTransaction(me, txnId);
        String currentState = str(after.get("state"));
        boolean changed = !previousState.equals(currentState);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transaction", after);
        out.put("previousState", previousState);
        out.put("currentState", currentState);
        out.put("changed", changed);
        out.put("verifiedAt", Instant.now());
        out.put("message", buildSummaryMessage(currentState, changed));
        return out;
    }

    @Transactional
    public Map<String, Object> verifyForAdmin(UUID txnId) {
        Map<String, Object> before = adminService.getTransaction(txnId);
        String previousState = str(before.get("state"));
        String txnType = str(before.get("txn_type"));

        reconcileTransaction(txnId, txnType, previousState);

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
        out.put("message", buildSummaryMessage(currentState, changed));
        return out;
    }

    private void reconcileTransaction(UUID txnId, String txnType, String currentState) {
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
        }
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

        return refs.stream().distinct().toList();
    }

    private List<Map<String, Object>> findMatchingCallbacks(List<String> refs) {
        if (refs.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String ref : refs) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT id, provider, event, status, merchant_code, outcome, created_at
                      FROM provider_callbacks
                     WHERE payload->>'externalRef' = ? OR payload->>'txnId' = ?
                     ORDER BY id DESC
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

