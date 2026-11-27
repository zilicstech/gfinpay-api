package com.fintech.dmt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.commission.CommissionEngine;
import com.fintech.emerald.EmeraldMessageSanitizer;
import com.fintech.ledger.LedgerService;
import com.fintech.ledger.TransactionService;
import com.fintech.ledger.WalletService;
import com.fintech.platform.crypto.FieldCipher;
import com.fintech.platform.idempotency.ExternalRefs;
import com.fintech.platform.idempotency.IdempotencyService;
import com.fintech.platform.web.ApiException;
import com.fintech.provider.ServiceProviderRouter;
import com.fintech.provider.canonical.ProviderModels;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomic wallet &amp; ledger state transitions for Domestic Money Transfer (DMT).
 *
 * <p>External provider HTTP calls execute strictly outside these short transactions
 * via {@link com.fintech.platform.tx.ShortTransactions} so wallet locks are never held across network I/O.
 */
@Service
public class DmtCasaLedger {

    private static final Logger log = LoggerFactory.getLogger(DmtCasaLedger.class);
    private static final int MAX_OTP_ATTEMPTS = 3;

    public record Quote(BigDecimal fee, BigDecimal total, BigDecimal available) {}

    public record HeldTransfer(
            boolean replay,
            Map<String, Object> replayBody,
            UUID transferId,
            UUID txnId,
            UUID walletId,
            UUID senderId,
            UUID sessionId,
            UUID keyId,
            String externalRef,
            String remitterMobile,
            String accountNumber,
            String ifsc,
            String transferMode,
            BigDecimal amount,
            BigDecimal fee,
            BigDecimal total,
            String referenceKey,
            int attemptCount
    ) {}

    public record AppliedTransfer(Map<String, Object> view, String errorCode, String declineReason) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final IdempotencyService idempotencyService;
    private final CommissionEngine commissionEngine;
    private final WalletService walletService;
    private final TransactionService transactionService;
    private final LedgerService ledgerService;
    private final FieldCipher fieldCipher;

    public DmtCasaLedger(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            IdempotencyService idempotencyService,
            CommissionEngine commissionEngine,
            WalletService walletService,
            TransactionService transactionService,
            LedgerService ledgerService) {
        this(jdbc, mapper, idempotencyService, commissionEngine, walletService, transactionService, ledgerService, null);
    }

    @Autowired
    public DmtCasaLedger(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            IdempotencyService idempotencyService,
            CommissionEngine commissionEngine,
            WalletService walletService,
            TransactionService transactionService,
            LedgerService ledgerService,
            FieldCipher fieldCipher) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.idempotencyService = idempotencyService;
        this.commissionEngine = commissionEngine;
        this.walletService = walletService;
        this.transactionService = transactionService;
        this.ledgerService = ledgerService;
        this.fieldCipher = fieldCipher;
    }

    public String nextReference() {
        Long value = jdbc.queryForObject("SELECT nextval('dmt_reference_seq')", Long.class);
        return Long.toString(value);
    }

    @Transactional
    public Quote quote(UUID agentId, BigDecimal amount) {
        BigDecimal cleanAmount = amount.setScale(2, RoundingMode.HALF_UP);
        CommissionEngine.Resolution pricing = commissionEngine.resolve(agentId, "DMT", cleanAmount);
        BigDecimal fee = pricing.fee().setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = cleanAmount.add(fee);
        WalletService.WalletInfo wallet = walletService.lockByUser(agentId);
        if (!wallet.active()) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "WALLET_FROZEN", "Your operating wallet is frozen.");
        }
        if (wallet.available().compareTo(total) < 0) {
            throw insufficient(total, wallet.available());
        }
        return new Quote(fee, total, wallet.available());
    }

    @Transactional
    public HeldTransfer placeTransferHold(
            UUID agentId,
            UUID sessionId,
            ServiceProviderRouter.RoutedChannel channel,
            BigDecimal lat,
            BigDecimal lng,
            BigDecimal distanceFromShopKm,
            String idemKey,
            String canonical) {
        IdempotencyService.Claim claim = idempotencyService.claim(agentId, idemKey, canonical);
        if (claim.replay()) {
            if (claim.responseCode() != null && claim.responseCode() >= 400) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DMT_TRANSFER_DECLINED", claim.responseBody());
            }
            try {
                Map<String, Object> stored = mapper.readValue(claim.responseBody(), new TypeReference<>() {});
                return new HeldTransfer(true, stored, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, 0);
            } catch (Exception e) {
                throw new IllegalStateException("Stored transfer response could not be read", e);
            }
        }

        List<Map<String, Object>> sessions = jdbc.queryForList("""
                SELECT s.id, s.sender_id, s.beneficiary_id, s.provider_bene_id, s.amount, s.fee, s.txn_type,
                       s.reference_id, s.stateresp, s.attempt_count, s.expires_at, s.consumed_at,
                       c.mobile,
                       b.name AS bene_name, b.account_number_enc, b.ifsc, b.bank_name,
                       COALESCE(b.account_masked, '••••' || b.account_last4) AS account_masked
                  FROM dmt_txn_otp_sessions s
                  JOIN dmt_senders c ON c.id = s.sender_id
                  JOIN dmt_beneficiaries b ON b.id = s.beneficiary_id
                 WHERE s.id = ? AND s.agent_user_id = ?
                 FOR UPDATE OF s
                """, sessionId, agentId);
        if (sessions.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "OTP_NOT_FOUND", "Send a transfer OTP before confirming.");
        }
        Map<String, Object> session = sessions.get(0);
        if (session.get("consumed_at") != null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "OTP_USED",
                    "This transfer OTP was already used. Send a new OTP to initiate another transfer.");
        }
        Boolean expired = jdbc.queryForObject(
                "SELECT expires_at < now() FROM dmt_txn_otp_sessions WHERE id = ?", Boolean.class, sessionId);
        if (Boolean.TRUE.equals(expired)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "OTP_EXPIRED",
                    "The transfer OTP expired. Send a new OTP.");
        }

        String encAcc = DmtCasaTexts.blankToNull(str(session.get("account_number_enc")));
        if (encAcc == null || fieldCipher == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BENEFICIARY_ACCOUNT_MISSING",
                    "Please re-add this beneficiary account to complete the transfer.");
        }
        String plainAccount = fieldCipher.decrypt(encAcc);
        String ifsc = str(session.get("ifsc"));

        BigDecimal amount = ((BigDecimal) session.get("amount")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal fee = ((BigDecimal) session.get("fee")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = amount.add(fee);

        WalletService.WalletInfo wallet = walletService.lockByUser(agentId);
        if (!wallet.active()) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "WALLET_FROZEN", "Your operating wallet is frozen.");
        }
        if (wallet.available().compareTo(total) < 0) {
            throw insufficient(total, wallet.available());
        }

        UUID senderId = (UUID) session.get("sender_id");
        UUID beneficiaryId = (UUID) session.get("beneficiary_id");
        String externalRef = ExternalRefs.fromKey("DMT", claim.keyId());
        String mode = str(session.get("txn_type"));
        String beneName = str(session.get("bene_name"));
        String bankName = DmtCasaTexts.blankToNull(str(session.get("bank_name")));
        String accountMasked = str(session.get("account_masked"));
        String remitterMobile = str(session.get("mobile"));
        int nextAttempt = session.get("attempt_count") instanceof Number n ? n.intValue() + 1 : 1;

        UUID txnId = transactionService.create(
                "DMT", agentId, wallet.walletId(), amount, fee, senderId, beneficiaryId, channel.providerCode(), claim.keyId());
        transactionService.transition(txnId, "HOLD", externalRef, null);
        writeCustomerRef(txnId, Map.of(
                "remitterMobile", remitterMobile,
                "beneficiaryName", beneName,
                "accountMasked", accountMasked,
                "ifsc", ifsc,
                "bankName", bankName == null ? "" : bankName,
                "transferMode", mode,
                "externalRef", externalRef));

        ledgerService.post(txnId, wallet.walletId(), "DEBIT", total,
                "DMT hold (" + mode + " " + accountMasked + ")");
        walletService.adjustCache(wallet.walletId(), total.negate(), total);

        UUID transferId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO dmt_transfers
                    (id, transaction_id, agent_user_id, sender_id, beneficiary_id,
                     channel_id, provider_code, pipe_code, outlet_code,
                     reference_id, external_ref, amount, fee, txn_type,
                     bene_name, account_masked, beneficiary_ifsc, bank_name,
                     latitude, longitude, distance_from_shop_km, status, refund_state)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'HOLD', 'NONE')
                """,
                transferId, txnId, agentId, senderId, beneficiaryId,
                channel.channelId(), channel.providerCode(), channel.pipeCode(), channel.outletCode(),
                externalRef, externalRef, amount, fee, mode,
                beneName, accountMasked, ifsc, bankName,
                lat, lng, distanceFromShopKm);

        jdbc.update("""
                UPDATE dmt_txn_otp_sessions
                   SET consumed_at = now(),
                       attempt_count = ?
                 WHERE id = ?
                """, nextAttempt, sessionId);

        log.info("DMT hold placed txnId={} externalRef={} amount={} fee={} retailer={}",
                txnId, externalRef, amount, fee, agentId);

        return new HeldTransfer(
                false,
                null,
                transferId,
                txnId,
                wallet.walletId(),
                senderId,
                sessionId,
                claim.keyId(),
                externalRef,
                remitterMobile,
                plainAccount,
                ifsc,
                mode,
                amount,
                fee,
                total,
                str(session.get("stateresp")),
                nextAttempt);
    }

    @Transactional
    public AppliedTransfer applySyncTransferOutcome(HeldTransfer hold, ProviderModels.DmtTransferResult res) {
        UUID txnId = hold.txnId();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.id, t.agent_user_id, t.sender_id, t.amount, t.fee, t.status, t.refund_state, x.wallet_id
                  FROM dmt_transfers t
                  JOIN transactions x ON x.id = t.transaction_id
                 WHERE t.transaction_id = ?
                 FOR UPDATE OF t
                """, txnId);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer not found");
        }
        Map<String, Object> row = rows.get(0);
        UUID agentId = (UUID) row.get("agent_user_id");
        UUID walletId = (UUID) row.get("wallet_id");
        BigDecimal amount = (BigDecimal) row.get("amount");
        BigDecimal total = amount.add((BigDecimal) row.get("fee"));

        String providerTxnId = DmtCasaTexts.blankToNull(res.providerTxnId());
        String utr = DmtCasaTexts.blankToNull(res.utr());

        switch (res.outcome()) {
            case SUCCESS -> {
                String finalRef = utr != null ? utr : (providerTxnId != null ? providerTxnId : hold.externalRef());
                if (transactionService.transition(txnId, "SUCCESS", finalRef, null)) {
                    walletService.lockByWalletId(walletId);
                    walletService.adjustCache(walletId, BigDecimal.ZERO, total.negate());
                    commissionEngine.creditRewards(txnId, agentId, "DMT", amount);
                }
                jdbc.update("""
                        UPDATE dmt_transfers
                           SET status = 'SUCCESS',
                               refund_state = 'NONE',
                               provider_ackno = COALESCE(?, provider_ackno),
                               provider_utr = COALESCE(?, provider_utr),
                               provider_txn_status = ?,
                               pool_amount = COALESCE(?, pool_amount),
                               err_msg = ?,
                               updated_at = now()
                         WHERE transaction_id = ?
                        """,
                        providerTxnId, utr, res.statusCode(), res.poolAmount(),
                        clip(EmeraldMessageSanitizer.sanitize(res.message(), "Transfer successful")),
                        txnId);
                decrementCachedSenderLimits(hold.senderId(), amount);
                Map<String, Object> body = view(txnId);
                completeIdempotency(hold.keyId(), 200, body);
                return new AppliedTransfer(body, null, null);
            }
            case PENDING -> {
                String cleanMsg = clip(EmeraldMessageSanitizer.sanitize(
                        res.message(),
                        "Transfer is awaiting destination bank confirmation. Use Check Status to reconfirm."));
                transactionService.transition(txnId, "PENDING", providerTxnId != null ? providerTxnId : hold.externalRef(), cleanMsg);
                jdbc.update("""
                        UPDATE dmt_transfers
                           SET status = 'PENDING',
                               provider_ackno = COALESCE(?, provider_ackno),
                               provider_utr = COALESCE(?, provider_utr),
                               provider_txn_status = ?,
                               err_msg = ?,
                               updated_at = now()
                         WHERE transaction_id = ?
                        """, providerTxnId, utr, res.statusCode(), cleanMsg, txnId);
                Map<String, Object> body = view(txnId);
                completeIdempotency(hold.keyId(), 200, body);
                return new AppliedTransfer(body, null, null);
            }
            case OTP_REGENERATE_REQUIRED -> {
                String cleanMsg = "Invalid OTP limit reached. Please send a new OTP to retry this transfer.";
                releaseHoldToWallet(txnId, walletId, total, providerTxnId, res.statusCode(), cleanMsg);
                jdbc.update("""
                        UPDATE dmt_senders
                           SET profile_reference_key = NULL,
                               profile_expires_at = NULL,
                               updated_at = now()
                         WHERE id = ?
                        """, hold.senderId());
                completeIdempotencyText(hold.keyId(), 422, cleanMsg);
                return new AppliedTransfer(null, "DMT_OTP_REGENERATE_REQUIRED", cleanMsg);
            }
            case INVALID_OTP_RETRYABLE -> {
                boolean canRetrySameOtp = hold.attemptCount() < MAX_OTP_ATTEMPTS;
                String cleanMsg = canRetrySameOtp
                        ? "Incorrect OTP entered. Your wallet balance is intact — check the customer OTP and try again."
                        : "Maximum OTP attempts reached. Please send a new OTP to retry.";
                releaseHoldToWallet(txnId, walletId, total, providerTxnId, res.statusCode(), cleanMsg);
                if (canRetrySameOtp) {
                    jdbc.update("UPDATE dmt_txn_otp_sessions SET consumed_at = NULL WHERE id = ?", hold.sessionId());
                }
                completeIdempotencyText(hold.keyId(), 422, cleanMsg);
                return new AppliedTransfer(
                        null,
                        canRetrySameOtp ? "INVALID_OTP" : "DMT_OTP_REGENERATE_REQUIRED",
                        cleanMsg);
            }
            default -> {
                String cleanMsg = EmeraldMessageSanitizer.sanitize(
                        res.message(),
                        "Destination bank declined the transfer. Your wallet balance has been restored.");
                releaseHoldToWallet(txnId, walletId, total, providerTxnId, res.statusCode(), cleanMsg);
                completeIdempotencyText(hold.keyId(), 422, cleanMsg);
                return new AppliedTransfer(null, "DMT_TRANSFER_DECLINED", cleanMsg);
            }
        }
    }

    /**
     * Applies a webhook-free {@code POST /reports/txnStatus} check/reconfirmation result:
     * <ul>
     *   <li>{@code HOLD}/{@code PENDING} -> {@code SUCCESS}: captures hold, stamps UTR + {@code reconfirmed_at}, credits rewards</li>
     *   <li>{@code HOLD}/{@code PENDING} -> {@code FAILED}: releases hold &amp; credits {@code MAIN} wallet</li>
     *   <li>{@code SUCCESS} -> {@code SUCCESS}: stamps {@code reconfirmed_at} &amp; updates UTR</li>
     *   <li>{@code SUCCESS} -> {@code FAILED} (post-settlement bank return): transitions to {@code REVERSED}, credits {@code MAIN} wallet, and reverses {@code REWARDS} commissions idempotently</li>
     * </ul>
     */
    @Transactional
    public Map<String, Object> applyStatusReconfirmation(UUID txnId, ProviderModels.DmtTransferStatus upstream) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.id, t.agent_user_id, t.sender_id, t.amount, t.fee, t.status, t.refund_state,
                       x.state AS txn_state, x.wallet_id
                  FROM dmt_transfers t
                  JOIN transactions x ON x.id = t.transaction_id
                 WHERE t.transaction_id = ?
                 FOR UPDATE OF t
                """, txnId);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer not found");
        }
        if (upstream == null) {
            return view(txnId);
        }
        if (upstream.throttled()) {
            Map<String, Object> out = view(txnId);
            out.put("statusCheckThrottled", true);
            out.put("statusCheckMessage", upstream.message());
            return out;
        }

        Map<String, Object> row = rows.get(0);
        String current = str(row.get("status"));
        String refundState = str(row.get("refund_state"));
        UUID agentId = (UUID) row.get("agent_user_id");
        UUID senderId = (UUID) row.get("sender_id");
        UUID walletId = (UUID) row.get("wallet_id");
        BigDecimal amount = (BigDecimal) row.get("amount");
        BigDecimal total = amount.add((BigDecimal) row.get("fee"));
        String utr = DmtCasaTexts.blankToNull(upstream.utr());
        String ackno = DmtCasaTexts.blankToNull(upstream.txnId());

        if (open(current)) {
            if (upstream.outcome() == ProviderModels.Outcome.SUCCESS) {
                String finalRef = utr != null ? utr : ackno;
                if (transactionService.transition(txnId, "SUCCESS", finalRef, null)) {
                    walletService.lockByWalletId(walletId);
                    walletService.adjustCache(walletId, BigDecimal.ZERO, total.negate());
                    commissionEngine.creditRewards(txnId, agentId, "DMT", amount);
                    decrementCachedSenderLimits(senderId, amount);
                }
                jdbc.update("""
                        UPDATE dmt_transfers
                           SET status = 'SUCCESS',
                               refund_state = 'NONE',
                               provider_utr = COALESCE(?, provider_utr),
                               provider_ackno = COALESCE(?, provider_ackno),
                               provider_txn_status = ?,
                               err_msg = ?,
                               reconfirmed_at = now(),
                               updated_at = now()
                         WHERE transaction_id = ?
                        """,
                        utr, ackno, upstream.statusCode(),
                        clip(EmeraldMessageSanitizer.sanitize(upstream.message(), "Transfer confirmed successful")),
                        txnId);
            } else if (upstream.outcome() == ProviderModels.Outcome.FAILED) {
                String cleanReason = clip(EmeraldMessageSanitizer.sanitize(
                        upstream.message(), "Transfer failed at destination bank — wallet balance refunded."));
                releaseHoldToWallet(txnId, walletId, total, ackno, upstream.statusCode(), cleanReason);
                jdbc.update("UPDATE dmt_transfers SET reconfirmed_at = now() WHERE transaction_id = ?", txnId);
            } else {
                jdbc.update("""
                        UPDATE dmt_transfers
                           SET provider_ackno = COALESCE(?, provider_ackno),
                               provider_txn_status = COALESCE(?, provider_txn_status),
                               err_msg = COALESCE(?, err_msg),
                               updated_at = now()
                         WHERE transaction_id = ?
                        """,
                        ackno, DmtCasaTexts.blankToNull(upstream.statusCode()),
                        clip(EmeraldMessageSanitizer.sanitizeNullable(upstream.message())), txnId);
            }
        } else if ("SUCCESS".equalsIgnoreCase(current)) {
            if (upstream.outcome() == ProviderModels.Outcome.SUCCESS) {
                if (utr != null) {
                    jdbc.update("UPDATE transactions SET partner_ref = COALESCE(?, partner_ref), updated_at = now() WHERE id = ?",
                            utr, txnId);
                }
                jdbc.update("""
                        UPDATE dmt_transfers
                           SET provider_utr = COALESCE(?, provider_utr),
                               provider_ackno = COALESCE(?, provider_ackno),
                               provider_txn_status = ?,
                               err_msg = 'Transfer reconfirmed successful with bank',
                               reconfirmed_at = now(),
                               updated_at = now()
                         WHERE transaction_id = ?
                        """, utr, ackno, upstream.statusCode(), txnId);
            } else if (upstream.outcome() == ProviderModels.Outcome.FAILED
                    && !"AUTO_REFUNDED".equals(refundState)
                    && !"REFUNDED".equals(refundState)) {
                String cleanReason = clip(EmeraldMessageSanitizer.sanitize(
                        upstream.message(), "Transfer returned by destination bank — refunded to wallet."));
                if (transactionService.transition(txnId, "REVERSED", utr != null ? utr : ackno, cleanReason)) {
                    WalletService.WalletInfo wallet = walletService.lockByWalletId(walletId);
                    ledgerService.post(txnId, wallet.walletId(), "CREDIT", total, "DMT bank return refund");
                    walletService.adjustCache(wallet.walletId(), total, BigDecimal.ZERO);
                    commissionEngine.reverseRewards(txnId, "DMT commission reversal (bank return)");
                }
                jdbc.update("""
                        UPDATE dmt_transfers
                           SET status = 'REVERSED',
                               refund_state = 'AUTO_REFUNDED',
                               provider_txn_status = ?,
                               err_msg = ?,
                               reconfirmed_at = now(),
                               updated_at = now()
                         WHERE transaction_id = ?
                        """, upstream.statusCode(), cleanReason, txnId);
                log.warn("DMT post-settlement bank return reversed txnId={} refunded={}", txnId, total);
            }
        }

        Map<String, Object> out = view(txnId);
        out.put("statusCheckThrottled", false);
        return out;
    }

    @Transactional
    public boolean adminResolveByTransactionId(UUID txnId, boolean confirm, String partnerRef, String reason) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.id, t.transaction_id, t.agent_user_id, t.sender_id, t.amount, t.fee, t.status, t.refund_state,
                       x.state AS txn_state, x.wallet_id
                  FROM dmt_transfers t
                  JOIN transactions x ON x.id = t.transaction_id
                 WHERE t.transaction_id = ?
                 FOR UPDATE OF t
                """, txnId);
        if (rows.isEmpty()) {
            return false;
        }
        Map<String, Object> row = rows.get(0);
        String current = String.valueOf(row.get("status"));
        String refundState = String.valueOf(row.get("refund_state"));
        BigDecimal amount = (BigDecimal) row.get("amount");
        BigDecimal total = amount.add((BigDecimal) row.get("fee"));
        UUID walletId = (UUID) row.get("wallet_id");
        UUID agentId = (UUID) row.get("agent_user_id");
        UUID senderId = (UUID) row.get("sender_id");
        String cleanRef = DmtCasaTexts.blankToNull(partnerRef);
        String cleanReason = clip((reason == null || reason.isBlank())
                ? (confirm ? "Manually confirmed by Admin" : "Manually reversed to wallet by Admin")
                : reason.trim());

        if (confirm) {
            if (!open(current)) {
                throw ApiException.of(HttpStatus.CONFLICT, "TRANSFER_ALREADY_RESOLVED",
                        "DMT transfer is already in state: " + current);
            }
            if (transactionService.transition(txnId, "SUCCESS", cleanRef, null)) {
                walletService.lockByWalletId(walletId);
                walletService.adjustCache(walletId, BigDecimal.ZERO, total.negate());
                commissionEngine.creditRewards(txnId, agentId, "DMT", amount);
                decrementCachedSenderLimits(senderId, amount);
            }
            jdbc.update("""
                    UPDATE dmt_transfers
                       SET status = 'SUCCESS',
                           refund_state = 'NONE',
                           provider_utr = COALESCE(?, provider_utr),
                           err_msg = ?,
                           reconfirmed_at = now(),
                           updated_at = now()
                     WHERE transaction_id = ?
                    """, cleanRef, cleanReason, txnId);
            log.info("DMT admin confirmed txnId={} amount={} ref={}", txnId, amount, cleanRef);
        } else {
            if ("AUTO_REFUNDED".equals(refundState) || "REFUNDED".equals(refundState) || "REVERSED".equals(current)) {
                throw ApiException.of(HttpStatus.CONFLICT, "TRANSFER_ALREADY_RESOLVED",
                        "DMT transfer is already refunded");
            }
            boolean wasSettled = "SUCCESS".equals(current);
            if (transactionService.transition(txnId, "REVERSED", cleanRef, cleanReason)) {
                WalletService.WalletInfo wallet = walletService.lockByWalletId(walletId);
                ledgerService.post(txnId, wallet.walletId(), "CREDIT", total, "DMT admin reversal: " + cleanReason);
                walletService.adjustCache(wallet.walletId(), total, wasSettled ? BigDecimal.ZERO : total.negate());
                if (wasSettled) {
                    commissionEngine.reverseRewards(txnId, "DMT admin reversal");
                }
            }
            jdbc.update("""
                    UPDATE dmt_transfers
                       SET status = 'REVERSED',
                           refund_state = 'AUTO_REFUNDED',
                           err_msg = ?,
                           reconfirmed_at = now(),
                           updated_at = now()
                     WHERE transaction_id = ?
                    """, cleanReason, txnId);
            log.info("DMT admin reversed txnId={} total={}", txnId, total);
        }
        return true;
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

    public List<Map<String, Object>> historyForAgent(UUID agentId, int limit) {
        int safeLimit = Math.max(1, Math.min(50, limit));
        List<UUID> txnIds = jdbc.queryForList("""
                SELECT transaction_id
                  FROM dmt_transfers
                 WHERE agent_user_id = ?
                 ORDER BY created_at DESC
                 LIMIT ?
                """, UUID.class, agentId, safeLimit);
        return txnIds.stream().map(this::view).toList();
    }

    public Map<String, Object> loadTransferRow(UUID txnId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.*, c.mobile AS remitter_mobile
                  FROM dmt_transfers t
                  JOIN dmt_senders c ON c.id = t.sender_id
                 WHERE t.transaction_id = ?
                """, txnId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> view(UUID txnId) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT t.transaction_id, x.state AS state, t.refund_state, t.status AS transfer_status,
                       t.amount, t.fee, t.txn_type, t.reference_id, t.external_ref,
                       t.provider_utr, t.provider_ackno, t.provider_txn_status,
                       t.provider_charge, t.provider_gst, t.provider_tds,
                       t.provider_commission, t.provider_bc_share,
                       t.bene_name, t.account_masked, t.beneficiary_ifsc, t.bank_name,
                       t.err_msg, t.last_status_polled_at, t.reconfirmed_at, t.created_at,
                       c.mobile AS remitter_mobile
                  FROM dmt_transfers t
                  JOIN transactions x ON x.id = t.transaction_id
                  JOIN dmt_senders c ON c.id = t.sender_id
                 WHERE t.transaction_id = ?
                """, txnId);
        BigDecimal amount = ((BigDecimal) row.get("amount")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal fee = ((BigDecimal) row.get("fee")).setScale(2, RoundingMode.HALF_UP);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transactionId", row.get("transaction_id"));
        out.put("state", row.get("state"));
        out.put("transferStatus", row.get("transfer_status"));
        out.put("refundState", row.get("refund_state"));
        out.put("amount", amount);
        out.put("fee", fee);
        out.put("total", amount.add(fee));
        out.put("transferMode", row.get("txn_type"));
        out.put("referenceId", firstNonBlank(str(row.get("external_ref")), str(row.get("reference_id"))));
        out.put("utr", row.get("provider_utr"));
        out.put("ackno", row.get("provider_ackno"));
        out.put("txnStatus", row.get("provider_txn_status"));
        out.put("remitterMobile", row.get("remitter_mobile"));
        out.put("beneficiaryName", row.get("bene_name"));
        out.put("accountMasked", row.get("account_masked"));
        out.put("ifsc", row.get("beneficiary_ifsc"));
        out.put("bankName", row.get("bank_name"));
        out.put("message", row.get("err_msg"));
        out.put("lastStatusPolledAt", row.get("last_status_polled_at"));
        out.put("reconfirmedAt", row.get("reconfirmed_at"));
        out.put("createdAt", row.get("created_at"));
        return out;
    }

    private void releaseHoldToWallet(
            UUID txnId,
            UUID walletId,
            BigDecimal total,
            String providerTxnId,
            String statusCode,
            String cleanReason) {
        if (transactionService.transition(txnId, "FAILED", providerTxnId, clip(cleanReason))) {
            WalletService.WalletInfo wallet = walletService.lockByWalletId(walletId);
            ledgerService.post(txnId, wallet.walletId(), "CREDIT", total, "DMT reversal");
            walletService.adjustCache(wallet.walletId(), total, total.negate());
        }
        jdbc.update("""
                UPDATE dmt_transfers
                   SET status = 'FAILED',
                       refund_state = 'AUTO_REFUNDED',
                       provider_ackno = COALESCE(?, provider_ackno),
                       provider_txn_status = ?,
                       err_msg = ?,
                       updated_at = now()
                 WHERE transaction_id = ?
                """, providerTxnId, statusCode, clip(cleanReason), txnId);
    }

    private void decrementCachedSenderLimits(UUID senderId, BigDecimal amount) {
        if (senderId == null || amount == null) {
            return;
        }
        jdbc.update("""
                UPDATE dmt_senders
                   SET daily_limit_available = CASE
                           WHEN daily_limit_available IS NOT NULL THEN GREATEST(0, daily_limit_available - ?)
                           ELSE daily_limit_available
                       END,
                       monthly_limit_available = CASE
                           WHEN monthly_limit_available IS NOT NULL THEN GREATEST(0, monthly_limit_available - ?)
                           ELSE monthly_limit_available
                       END,
                       provider_limit = CASE
                           WHEN provider_limit IS NOT NULL THEN GREATEST(0, provider_limit - ?)
                           ELSE provider_limit
                       END,
                       updated_at = now()
                 WHERE id = ?
                """, amount, amount, amount, senderId);
    }

    private void writeCustomerRef(UUID txnId, Map<String, Object> refMap) {
        try {
            transactionService.setCustomerRef(txnId, mapper.writeValueAsString(refMap));
        } catch (Exception ignored) {
        }
    }

    private void completeIdempotency(UUID keyId, int code, Map<String, Object> body) {
        if (keyId == null) return;
        try {
            idempotencyService.complete(keyId, code, mapper.writeValueAsString(body));
        } catch (Exception e) {
            log.warn("DMT idempotency store failed key={}: {}", keyId, e.getMessage());
        }
    }

    private void completeIdempotencyText(UUID keyId, int code, String message) {
        if (keyId == null) return;
        try {
            String json = mapper.writeValueAsString(Map.of("message", message == null ? "" : message));
            idempotencyService.complete(keyId, code, json);
        } catch (Exception e) {
            log.warn("DMT idempotency text store failed key={}: {}", keyId, e.getMessage());
        }
    }

    private static boolean open(String status) {
        return "HOLD".equals(status) || "PENDING".equals(status) || "IN_PROCESS".equals(status)
                || "SENT".equals(status) || "ON_HOLD".equals(status);
    }

    private static ApiException insufficient(BigDecimal required, BigDecimal available) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_WALLET_BALANCE",
                "Wallet balance (₹" + available.setScale(2, RoundingMode.HALF_UP)
                        + ") is less than transfer amount plus surcharge (₹"
                        + required.setScale(2, RoundingMode.HALF_UP) + ").",
                Map.of("required", required, "available", available), false);
    }

    private static String clip(String value) {
        return DmtCasaTexts.clip(value);
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : (b == null ? "" : b);
    }

    private static String str(Object val) {
        return val == null ? "" : String.valueOf(val).trim();
    }
}
