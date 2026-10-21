package com.fintech.dmt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.commission.CommissionEngine;
import com.fintech.ledger.LedgerService;
import com.fintech.ledger.TransactionService;
import com.fintech.ledger.WalletService;
import com.fintech.paysprint.PaysprintMessages;
import com.fintech.platform.idempotency.IdempotencyService;
import com.fintech.platform.web.ApiException;
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

/**
 * Wallet and transfer rows. PaySprint is called by {@link DmtCasaService} between these transactions,
 * so a slow response never holds a wallet lock.
 */
@Service
public class DmtCasaLedger {

    private static final Logger log = LoggerFactory.getLogger(DmtCasaLedger.class);

    private static final String PARTNER = "PAYSPRINT";

    public record Quote(BigDecimal fee, BigDecimal total, BigDecimal available) {}

    /** A fresh hold, or the stored body when the same idempotency key is retried. */
    public record HoldOutcome(boolean replay, Map<String, Object> body, UUID txnId, UUID keyId,
                              String merchantCode, String mobile, String beneId, String referenceId,
                              String txnType, BigDecimal amount, String stateResp) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final IdempotencyService idempotencyService;
    private final CommissionEngine commissionEngine;
    private final WalletService walletService;
    private final TransactionService transactionService;
    private final LedgerService ledgerService;

    public DmtCasaLedger(JdbcTemplate jdbc, ObjectMapper mapper, IdempotencyService idempotencyService,
                         CommissionEngine commissionEngine, WalletService walletService,
                         TransactionService transactionService, LedgerService ledgerService) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.idempotencyService = idempotencyService;
        this.commissionEngine = commissionEngine;
        this.walletService = walletService;
        this.transactionService = transactionService;
        this.ledgerService = ledgerService;
    }

    public String nextReference() {
        Long value = jdbc.queryForObject("SELECT nextval('dmt_reference_seq')", Long.class);
        return Long.toString(value);
    }

    @Transactional
    public Quote quote(UUID agentId, BigDecimal amount) {
        CommissionEngine.Resolution pricing = commissionEngine.resolve(agentId, "DMT", amount);
        BigDecimal total = amount.add(pricing.fee());
        WalletService.WalletInfo wallet = walletService.lockByUser(agentId);
        if (wallet.available().compareTo(total) < 0) {
            throw insufficient(total, wallet.available());
        }
        return new Quote(pricing.fee(), total, wallet.available());
    }

    @Transactional
    public HoldOutcome hold(UUID agentId, UUID sessionId, String idemKey, String canonical) {
        IdempotencyService.Claim claim = idempotencyService.claim(agentId, idemKey, canonical);
        if (claim.replay()) {
            try {
                Map<String, Object> stored = mapper.readValue(claim.responseBody(), new TypeReference<>() {});
                return new HoldOutcome(true, stored, null, null, null, null, null, null, null, null, null);
            } catch (Exception e) {
                throw new IllegalStateException("Stored transfer response could not be read", e);
            }
        }
        List<Map<String, Object>> sessions = jdbc.queryForList("""
                SELECT s.id, s.sender_id, s.beneficiary_id, s.provider_bene_id, s.amount, s.fee, s.txn_type,
                       s.reference_id, s.stateresp, s.expires_at, s.consumed_at, c.mobile, b.name AS bene_name,
                       COALESCE(b.account_masked, b.account_last4) AS account_masked
                  FROM dmt_txn_otp_sessions s
                  JOIN dmt_senders c ON c.id = s.sender_id
                  JOIN dmt_beneficiaries b ON b.id = s.beneficiary_id
                 WHERE s.id = ? AND s.agent_user_id = ?
                 FOR UPDATE OF s
                """, sessionId, agentId);
        if (sessions.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "OTP_NOT_FOUND", "Send a transfer OTP before confirming");
        }
        Map<String, Object> session = sessions.get(0);
        if (session.get("consumed_at") != null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "OTP_USED", "This transfer OTP was already used");
        }
        Boolean expired = jdbc.queryForObject(
                "SELECT expires_at < now() FROM dmt_txn_otp_sessions WHERE id = ?", Boolean.class, sessionId);
        if (Boolean.TRUE.equals(expired)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "OTP_EXPIRED", "The transfer OTP expired. Send a new one");
        }
        BigDecimal amount = (BigDecimal) session.get("amount");
        BigDecimal fee = (BigDecimal) session.get("fee");
        BigDecimal total = amount.add(fee);
        WalletService.WalletInfo wallet = walletService.lockByUser(agentId);
        if (wallet.available().compareTo(total) < 0) {
            throw insufficient(total, wallet.available());
        }
        UUID senderId = (UUID) session.get("sender_id");
        UUID beneficiaryId = (UUID) session.get("beneficiary_id");
        String referenceId = String.valueOf(session.get("reference_id"));
        UUID txnId = transactionService.create("DMT", agentId, wallet.walletId(), amount, fee,
                senderId, beneficiaryId, PARTNER, claim.keyId());
        jdbc.update("UPDATE transactions SET partner_ref = ? WHERE id = ?", referenceId, txnId);
        ledgerService.post(txnId, wallet.walletId(), "DEBIT", total, "DMT hold");
        walletService.adjustCache(wallet.walletId(), total.negate(), total);
        transactionService.transition(txnId, "HOLD", referenceId, null);
        jdbc.update("""
                INSERT INTO dmt_transfers
                    (id, transaction_id, agent_user_id, sender_id, beneficiary_id, reference_id,
                     amount, fee, txn_type, bene_name, account_masked, status, refund_state)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'HOLD', 'NONE')
                """, UUID.randomUUID(), txnId, agentId, senderId, beneficiaryId, referenceId,
                amount, fee, session.get("txn_type"), session.get("bene_name"), session.get("account_masked"));
        jdbc.update("UPDATE dmt_txn_otp_sessions SET consumed_at = now() WHERE id = ?", sessionId);
        log.info("DMT_CASA hold txnId={} referenceid={} amount={} fee={} retailer={}",
                txnId, referenceId, amount, fee, agentId);
        return new HoldOutcome(false, null, txnId, claim.keyId(), null,
                String.valueOf(session.get("mobile")), String.valueOf(session.get("provider_bene_id")),
                referenceId, String.valueOf(session.get("txn_type")), amount, String.valueOf(session.get("stateresp")));
    }

    @Transactional
    public Map<String, Object> markPending(UUID txnId, String message, UUID keyId) {
        transactionService.transition(txnId, "PENDING", null, clip(message));
        jdbc.update("""
                UPDATE dmt_transfers
                   SET status = 'PENDING', err_msg = ?, updated_at = now()
                 WHERE transaction_id = ? AND status = 'HOLD'
                """, clip(message), txnId);
        log.info("DMT_CASA pending txnId={} message={}", txnId, clip(message));
        Map<String, Object> body = view(txnId);
        complete(keyId, body);
        return body;
    }

    @Transactional
    public Map<String, Object> apply(UUID txnId, JsonNode root, UUID keyId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.id, t.transaction_id, t.agent_user_id, t.amount, t.fee, t.status, t.refund_state,
                       x.state AS txn_state, x.wallet_id
                  FROM dmt_transfers t
                  JOIN transactions x ON x.id = t.transaction_id
                 WHERE t.transaction_id = ?
                 FOR UPDATE OF t
                """, txnId);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer not found");
        }
        Map<String, Object> row = rows.get(0);
        stamp(txnId, root);
        String txnStatus = DmtCasaTexts.txnStatus(root);
        String current = String.valueOf(row.get("status"));
        String refundState = String.valueOf(row.get("refund_state"));
        BigDecimal total = ((BigDecimal) row.get("amount")).add((BigDecimal) row.get("fee"));
        UUID walletId = (UUID) row.get("wallet_id");
        UUID agentId = (UUID) row.get("agent_user_id");
        log.info("DMT_CASA apply txnId={} referenceid={} txn_status={} response_code={} from={} message={}",
                txnId, DmtCasaTexts.text(root, "referenceid"), txnStatus, DmtCasaTexts.code(root), current,
                DmtCasaTexts.clip(PaysprintMessages.fromNode(root, "")));
        switch (txnStatus) {
            case "1" -> settle(txnId, agentId, walletId, (BigDecimal) row.get("amount"), current);
            case "0" -> reverse(txnId, walletId, total, current, refundState, "AUTO_REFUNDED",
                    PaysprintMessages.fromNode(root, "Transfer failed and the amount was refunded"));
            case "5" -> failKept(txnId, current, refundState,
                    PaysprintMessages.fromNode(root, "Transfer failed. Claim the refund to return the amount"));
            case "2", "3", "4" -> keepHold(txnId, txnStatus);
            default -> unresolved(txnId, walletId, total, current, refundState, root);
        }
        Map<String, Object> body = view(txnId);
        complete(keyId, body);
        return body;
    }

    @Transactional
    public void rememberRefundOtp(UUID txnId, UUID agentId, String stateResp) {
        int updated = jdbc.update("""
                UPDATE dmt_transfers
                   SET refund_stateresp = ?, updated_at = now()
                 WHERE transaction_id = ? AND agent_user_id = ? AND refund_state = 'CLAIM_REQUIRED'
                """, stateResp, txnId, agentId);
        if (updated == 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "REFUND_NOT_DUE",
                    "This transfer does not have a refund waiting");
        }
    }

    @Transactional
    public Map<String, Object> refund(UUID txnId, UUID agentId, JsonNode root) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.amount, t.fee, t.refund_state, x.wallet_id
                  FROM dmt_transfers t
                  JOIN transactions x ON x.id = t.transaction_id
                 WHERE t.transaction_id = ? AND t.agent_user_id = ?
                 FOR UPDATE OF t
                """, txnId, agentId);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer not found");
        }
        Map<String, Object> row = rows.get(0);
        String refundState = String.valueOf(row.get("refund_state"));
        if ("REFUNDED".equals(refundState) || "AUTO_REFUNDED".equals(refundState)) {
            return view(txnId);
        }
        if (!"CLAIM_REQUIRED".equals(refundState)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "REFUND_NOT_DUE",
                    "This transfer does not have a refund waiting");
        }
        boolean accepted = root.path("status").asBoolean(false) && DmtCasaTexts.code(root) == 1;
        boolean already = DmtCasaTexts.code(root) == 2;
        if (!accepted && !already) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DMT_CASA_ERROR",
                    PaysprintMessages.fromNode(root, "PaySprint did not refund this transfer"));
        }
        BigDecimal total = ((BigDecimal) row.get("amount")).add((BigDecimal) row.get("fee"));
        creditBack((UUID) row.get("wallet_id"), txnId, total, "DMT refund");
        jdbc.update("""
                UPDATE dmt_transfers
                   SET refund_state = 'REFUNDED', status = 'REFUNDED', err_msg = ?, updated_at = now()
                 WHERE transaction_id = ?
                """, DmtCasaTexts.clip(PaysprintMessages.fromNode(root, "Refunded")), txnId);
        log.info("DMT_CASA refunded txnId={} amount={}", txnId, total);
        return view(txnId);
    }

    public Map<String, Object> viewOwned(UUID txnId, UUID agentId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*)::int FROM dmt_transfers WHERE transaction_id = ? AND agent_user_id = ?
                """, Integer.class, txnId, agentId);
        if (n == null || n == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer not found");
        }
        return view(txnId);
    }

    public Map<String, Object> transferFacts(UUID txnId, UUID agentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.reference_id, t.provider_ackno, t.refund_stateresp, t.refund_state, t.status,
                       c.mobile
                  FROM dmt_transfers t
                  JOIN dmt_senders c ON c.id = t.sender_id
                 WHERE t.transaction_id = ? AND t.agent_user_id = ?
                """, txnId, agentId);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer not found");
        }
        return rows.get(0);
    }

    private void settle(UUID txnId, UUID agentId, UUID walletId, BigDecimal amount, String current) {
        if (!open(current)) {
            log.info("DMT_CASA settled txnId={} result=noop from={}", txnId, current);
            return;
        }
        if (!transactionService.transition(txnId, "SUCCESS", null, null)) {
            return;
        }
        Map<String, Object> transfer = jdbc.queryForMap(
                "SELECT amount, fee FROM dmt_transfers WHERE transaction_id = ?", txnId);
        BigDecimal total = ((BigDecimal) transfer.get("amount")).add((BigDecimal) transfer.get("fee"));
        walletService.lockByWalletId(walletId);
        walletService.adjustCache(walletId, BigDecimal.ZERO, total.negate());
        postCommissions(txnId, agentId, amount, walletId);
        jdbc.update("""
                UPDATE dmt_transfers SET status = 'SUCCESS', refund_state = 'NONE', updated_at = now()
                 WHERE transaction_id = ?
                """, txnId);
        log.info("DMT_CASA settled txnId={} amount={}", txnId, amount);
    }

    private void reverse(UUID txnId, UUID walletId, BigDecimal total, String current, String refundState,
                         String nextRefund, String reason) {
        if ("AUTO_REFUNDED".equals(refundState) || "REFUNDED".equals(refundState) || "SUCCESS".equals(current)) {
            return;
        }
        transactionService.transition(txnId, "FAILED", null, clip(reason));
        if (!open(current) && !"CLAIM_REQUIRED".equals(refundState)) {
            return;
        }
        creditBack(walletId, txnId, total, "DMT refund");
        jdbc.update("""
                UPDATE dmt_transfers
                   SET status = 'FAILED', refund_state = ?, err_msg = ?, updated_at = now()
                 WHERE transaction_id = ?
                """, nextRefund, clip(reason), txnId);
        log.info("DMT_CASA failed txnId={} refund_state={} amount={}", txnId, nextRefund, total);
    }

    private void failKept(UUID txnId, String current, String refundState, String reason) {
        if ("SUCCESS".equals(current) || "AUTO_REFUNDED".equals(refundState) || "REFUNDED".equals(refundState)) {
            return;
        }
        transactionService.transition(txnId, "FAILED", null, clip(reason));
        jdbc.update("""
                UPDATE dmt_transfers
                   SET status = 'FAILED', refund_state = 'CLAIM_REQUIRED', err_msg = ?, updated_at = now()
                 WHERE transaction_id = ? AND refund_state = 'NONE'
                """, clip(reason), txnId);
        log.info("DMT_CASA failed txnId={} refund_state=CLAIM_REQUIRED", txnId);
    }

    private void keepHold(UUID txnId, String txnStatus) {
        String status = switch (txnStatus) {
            case "3" -> "SENT";
            case "4" -> "ON_HOLD";
            default -> "IN_PROCESS";
        };
        jdbc.update("""
                UPDATE dmt_transfers SET status = ?, updated_at = now()
                 WHERE transaction_id = ? AND status IN ('HOLD', 'PENDING', 'IN_PROCESS', 'SENT', 'ON_HOLD')
                """, status, txnId);
        log.info("DMT_CASA pending txnId={} provider_status={}", txnId, status);
    }

    private void unresolved(UUID txnId, UUID walletId, BigDecimal total, String current, String refundState, JsonNode root) {
        if (DmtCasaTexts.code(root) == 2 && !"SUCCESS".equals(current)) {
            reverse(txnId, walletId, total, current, refundState, "AUTO_REFUNDED",
                    PaysprintMessages.fromNode(root, "PaySprint has no record of this transfer"));
            return;
        }
        if (!root.path("status").asBoolean(false) && !"SUCCESS".equals(current)) {
            String reason = PaysprintMessages.fromNode(root, "PaySprint did not accept the transfer");
            transactionService.transition(txnId, "FAILED", null, clip(reason));
            if ((open(current) || "CLAIM_REQUIRED".equals(refundState))
                    && !"AUTO_REFUNDED".equals(refundState) && !"REFUNDED".equals(refundState)) {
                creditBack(walletId, txnId, total, "DMT refund");
            }
            jdbc.update("""
                    UPDATE dmt_transfers
                       SET status = 'FAILED', refund_state = 'AUTO_REFUNDED', err_msg = ?, updated_at = now()
                     WHERE transaction_id = ? AND refund_state NOT IN ('REFUNDED', 'AUTO_REFUNDED')
                    """, clip(reason), txnId);
            log.info("DMT_CASA failed txnId={} refund_state=AUTO_REFUNDED reason=rejected", txnId);
        }
    }

    private void creditBack(UUID walletId, UUID txnId, BigDecimal total, String narration) {
        WalletService.WalletInfo wallet = walletService.lockByWalletId(walletId);
        ledgerService.post(txnId, wallet.walletId(), "CREDIT", total, narration);
        walletService.adjustCache(wallet.walletId(), total, total.negate());
    }

    private void postCommissions(UUID txnId, UUID agentId, BigDecimal amount, UUID walletId) {
        commissionEngine.creditRewards(txnId, agentId, "DMT", amount);
    }

    private void stamp(UUID txnId, JsonNode root) {
        jdbc.update("""
                UPDATE dmt_transfers
                   SET provider_ackno = COALESCE(?, provider_ackno),
                       provider_utr = COALESCE(?, provider_utr),
                       provider_txn_status = COALESCE(?, provider_txn_status),
                       provider_charge = COALESCE(?, provider_charge),
                       provider_gst = COALESCE(?, provider_gst),
                       provider_tds = COALESCE(?, provider_tds),
                       provider_commission = COALESCE(?, provider_commission),
                       provider_bc_share = COALESCE(?, provider_bc_share),
                       bene_name = COALESCE(?, bene_name),
                       account_masked = COALESCE(?, account_masked),
                       npci_code = COALESCE(?, npci_code),
                       err_msg = COALESCE(?, err_msg),
                       updated_at = now()
                 WHERE transaction_id = ?
                """,
                DmtCasaTexts.blankToNull(DmtCasaTexts.text(root, "ackno")),
                DmtCasaTexts.blankToNull(DmtCasaTexts.text(root, "utr")),
                DmtCasaTexts.blankToNull(DmtCasaTexts.txnStatus(root)),
                DmtCasaTexts.money(root, "customercharge"),
                DmtCasaTexts.money(root, "gst"),
                DmtCasaTexts.money(root, "tds"),
                DmtCasaTexts.money(root, "netcommission"),
                DmtCasaTexts.money(root, "bc_share"),
                DmtCasaTexts.blankToNull(DmtCasaTexts.text(root, "benename")),
                DmtCasaTexts.blankToNull(DmtCasaTexts.text(root, "account_number", "account")),
                DmtCasaTexts.blankToNull(DmtCasaTexts.text(root, "NPCI_response_code")),
                DmtCasaTexts.blankToNull(DmtCasaTexts.clip(DmtCasaTexts.text(root, "remarks", "message"))),
                txnId);
    }

    private Map<String, Object> view(UUID txnId) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT t.transaction_id, x.state AS state, t.refund_state, t.status AS transfer_status,
                       t.amount, t.fee, t.txn_type, t.reference_id, t.provider_utr, t.provider_ackno,
                       t.provider_txn_status, t.provider_charge, t.provider_gst, t.provider_tds,
                       t.provider_commission, t.provider_bc_share, t.bene_name, t.account_masked,
                       t.err_msg, t.created_at
                  FROM dmt_transfers t
                  JOIN transactions x ON x.id = t.transaction_id
                 WHERE t.transaction_id = ?
                """, txnId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transactionId", row.get("transaction_id"));
        out.put("state", row.get("state"));
        out.put("transferStatus", row.get("transfer_status"));
        out.put("refundState", row.get("refund_state"));
        out.put("amount", row.get("amount"));
        out.put("fee", row.get("fee"));
        out.put("total", ((BigDecimal) row.get("amount")).add((BigDecimal) row.get("fee")));
        out.put("transferMode", row.get("txn_type"));
        out.put("referenceId", row.get("reference_id"));
        out.put("utr", row.get("provider_utr"));
        out.put("ackno", row.get("provider_ackno"));
        out.put("txnStatus", row.get("provider_txn_status"));
        out.put("providerCharge", row.get("provider_charge"));
        out.put("providerGst", row.get("provider_gst"));
        out.put("providerTds", row.get("provider_tds"));
        out.put("providerCommission", row.get("provider_commission"));
        out.put("providerBcShare", row.get("provider_bc_share"));
        out.put("beneficiaryName", row.get("bene_name"));
        out.put("accountMasked", row.get("account_masked"));
        out.put("message", row.get("err_msg"));
        out.put("createdAt", row.get("created_at"));
        return out;
    }

    private void complete(UUID keyId, Map<String, Object> body) {
        if (keyId == null) {
            return;
        }
        try {
            idempotencyService.complete(keyId, 200, mapper.writeValueAsString(body));
        } catch (Exception e) {
            throw new IllegalStateException("Could not store the transfer response", e);
        }
    }

    private static boolean open(String status) {
        return "HOLD".equals(status) || "PENDING".equals(status) || "IN_PROCESS".equals(status)
                || "SENT".equals(status) || "ON_HOLD".equals(status);
    }

    private static ApiException insufficient(BigDecimal required, BigDecimal available) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_WALLET_BALANCE",
                "Wallet balance is less than amount plus fee",
                Map.of("required", required, "available", available), false);
    }

    private static String clip(String value) {
        return DmtCasaTexts.clip(value);
    }
}
