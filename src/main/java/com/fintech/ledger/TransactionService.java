package com.fintech.ledger;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** The transaction state machine: single place where states change, with a transition whitelist. */
@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "INITIATED", Set.of("HOLD", "SUCCESS", "FAILED"),
            "HOLD", Set.of("PENDING", "FAILED"),
            "PENDING", Set.of("SUCCESS", "FAILED"),
            "SUCCESS", Set.of("REVERSED"));

    private final JdbcTemplate jdbc;

    public TransactionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID create(String txnType, UUID agentUserId, UUID walletId, BigDecimal amount, BigDecimal fee,
                       UUID dmtSenderId, UUID beneficiaryId, String partnerCode, UUID idempotencyKeyId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO transactions
                    (id, txn_type, state, agent_user_id, wallet_id, amount, fee,
                     dmt_sender_id, beneficiary_id, partner_code, idempotency_key_id, state_history)
                VALUES (?, ?, 'INITIATED', ?, ?, ?, ?, ?, ?, ?, ?,
                        jsonb_build_array(jsonb_build_object('state', 'INITIATED', 'at', now())))
                """, id, txnType, agentUserId, walletId, amount, fee,
                dmtSenderId, beneficiaryId, partnerCode, idempotencyKeyId);
        return id;
    }

    /**
     * Locks the transaction row and applies the transition if legal.
     * Returns false (no-op) when already in the target or a terminal state — makes webhook
     * redelivery and poller/webhook races harmless.
     */
    public boolean transition(UUID txnId, String to, String partnerRef, String failureReason) {
        String current = jdbc.queryForObject(
                "SELECT state::text FROM transactions WHERE id = ? FOR UPDATE", String.class, txnId);
        if (to.equals(current)) {
            return false;
        }
        Set<String> allowed = ALLOWED.getOrDefault(current, Set.of());
        if (!allowed.contains(to)) {
            log.info("TXN_TRANSITION no-op txnId={} current={} requested={}", txnId, current, to);
            return false;
        }
        jdbc.update("""
                UPDATE transactions
                   SET state = ?,
                       partner_ref = COALESCE(?, partner_ref),
                       failure_reason = COALESCE(?, failure_reason),
                       state_history = state_history || jsonb_build_array(jsonb_build_object('state', ?, 'at', now())),
                       updated_at = now()
                 WHERE id = ?
                """, to, partnerRef, failureReason, to, txnId);
        log.info("TXN_TRANSITION txnId={} {} -> {}", txnId, current, to);
        return true;
    }

    public Map<String, Object> getById(UUID txnId) {
        return jdbc.queryForMap("""
                SELECT id, txn_type::text AS txn_type, state::text AS state, agent_user_id, wallet_id, amount, fee,
                       dmt_sender_id, beneficiary_id, partner_code, partner_ref, failure_reason,
                       created_at, updated_at
                  FROM transactions WHERE id = ?
                """, txnId);
    }

    public void setCustomerRef(UUID txnId, String customerRefJson) {
        jdbc.update("UPDATE transactions SET customer_ref = ?::jsonb WHERE id = ?", customerRefJson, txnId);
    }

    public List<Map<String, Object>> listByAgent(UUID agentUserId, int limit) {
        return jdbc.queryForList("""
                SELECT id, txn_type::text AS txn_type, state::text AS state, amount, fee, partner_ref, failure_reason, created_at, updated_at
                  FROM transactions
                 WHERE agent_user_id = ?
                 ORDER BY created_at DESC
                 LIMIT ?
                """, agentUserId, limit);
    }
}
