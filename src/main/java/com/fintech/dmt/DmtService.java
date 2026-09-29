package com.fintech.dmt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.commission.CommissionEngine;
import com.fintech.ledger.LedgerService;
import com.fintech.ledger.TransactionService;
import com.fintech.ledger.WalletService;
import com.fintech.kyc.CustomerKycService;
import com.fintech.platform.PlatformServiceGate;
import com.fintech.platform.idempotency.IdempotencyService;
import com.fintech.platform.outbox.OutboxWriter;
import com.fintech.platform.web.ApiException;
import com.fintech.platform.web.FeatureUnavailable;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DMT initiation — doc 03 phase 1. Everything below happens in ONE database transaction:
 * idempotency claim, wallet hold posting, transaction row,
 * outbox event, idempotency completion. A crash anywhere rolls back all of it.
 */
@Service
public class DmtService {

    private static final Logger log = LoggerFactory.getLogger(DmtService.class);
    public record InitiationResult(boolean replay, Map<String, Object> body) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final IdempotencyService idempotencyService;
    private final CommissionEngine commissionEngine;
    private final WalletService walletService;
    private final TransactionService transactionService;
    private final LedgerService ledgerService;
    private final OutboxWriter outboxWriter;
    private final CustomerKycService kyc;
    private final PlatformServiceGate services;

    public DmtService(JdbcTemplate jdbc, ObjectMapper mapper, IdempotencyService idempotencyService,
                      CommissionEngine commissionEngine, WalletService walletService,
                      TransactionService transactionService, LedgerService ledgerService,
                      OutboxWriter outboxWriter, CustomerKycService kyc, PlatformServiceGate services) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.idempotencyService = idempotencyService;
        this.commissionEngine = commissionEngine;
        this.walletService = walletService;
        this.transactionService = transactionService;
        this.ledgerService = ledgerService;
        this.outboxWriter = outboxWriter;
        this.kyc = kyc;
        this.services = services;
    }

    @Transactional
    public Map<String, Object> registerSender(String mobile, String fullName, UUID agentId) {
        services.requireEnabled(PlatformServiceGate.DMT);
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO dmt_senders (id, mobile, full_name, registered_by) VALUES (?, ?, ?, ?)",
                    id, mobile, fullName, agentId);
            log.info("DMT_SENDER_REGISTERED senderId={} by agent={}", id, agentId);
        } catch (DuplicateKeyException e) {
            return findSenderByMobile(mobile);
        }
        return findSenderByMobile(mobile);
    }

    public Map<String, Object> findSenderByMobile(String mobile) {
        services.requireEnabled(PlatformServiceGate.DMT);
        var rows = jdbc.queryForList("""
                SELECT s.id, s.mobile, s.full_name, s.kyc_level, s.ovd_type, s.ovd_last4, s.address_line,
                       s.pan_last4, s.mobile_verified_at, s.ovd_captured_at
                  FROM dmt_senders s
                 WHERE s.mobile = ?
                """, mobile);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "SENDER_NOT_FOUND", "No sender registered with this mobile");
        }
        return kyc.withFlags(rows.get(0));
    }

    public Map<String, Object> getSender(UUID senderId) {
        services.requireEnabled(PlatformServiceGate.DMT);
        return kyc.getCustomer(senderId);
    }

    @Transactional
    public Map<String, Object> addBeneficiary(UUID senderId, String name, String accountNumber, String ifsc) {
        services.requireEnabled(PlatformServiceGate.DMT);
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM dmt_senders WHERE id = ?", Integer.class, senderId);
        if (exists == null || exists == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "SENDER_NOT_FOUND", "Customer is not registered");
        }
        String account = digits(accountNumber);
        if (account.length() < 9 || account.length() > 18) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_ACCOUNT",
                    "Account number must be 9 to 18 digits");
        }
        String code = ifsc == null ? "" : ifsc.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        if (!code.matches("[A-Z]{4}0[A-Z0-9]{6}")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_IFSC",
                    "IFSC should look like HDFC0001234 (11 characters, 5th is zero)");
        }
        String trimmedName = name == null ? "" : name.trim();
        if (trimmedName.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_NAME", "Beneficiary name is required");
        }
        UUID id = UUID.randomUUID();
        String last4 = account.substring(account.length() - 4);
        String enc = Base64.getEncoder().encodeToString(account.getBytes());
        jdbc.update("""
                INSERT INTO dmt_beneficiaries (id, sender_id, name, account_number_enc, account_last4, ifsc, verified_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                """, id, senderId, trimmedName, enc, last4, code);
        log.info("DMT_BENEFICIARY_ADDED beneId={} sender={}", id, senderId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", trimmedName);
        out.put("account_last4", last4);
        out.put("ifsc", code);
        out.put("verified", true);
        return out;
    }

    public static String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    public List<Map<String, Object>> listBeneficiaries(UUID senderId) {
        services.requireEnabled(PlatformServiceGate.DMT);
        return jdbc.queryForList("""
                SELECT id, name, account_last4, ifsc, verified_at
                  FROM dmt_beneficiaries WHERE sender_id = ? ORDER BY created_at
                """, senderId);
    }

    @Transactional
    public InitiationResult initiate(UUID agentId, String idemKey, UUID senderId, UUID beneficiaryId,
                                     BigDecimal amount, String transferMode, String consentOtp,
                                     String canonicalRequest) {
        long startMs = System.currentTimeMillis();
        services.requireEnabled(PlatformServiceGate.DMT);
        FeatureUnavailable.throwIfCalled();
        log.info("DMT_INITIATE started agent={} sender={} amount={}", agentId, senderId, amount);
        try {
            // 1. Idempotency claim — replay skips KYC/OTP so retries do not consume consent twice
            IdempotencyService.Claim claim = idempotencyService.claim(agentId, idemKey, canonicalRequest);
            if (claim.replay()) {
                Map<String, Object> stored = mapper.readValue(claim.responseBody(), new TypeReference<>() {});
                log.info("DMT_INITIATE replay agent={} idemKey={}", agentId, idemKey);
                return new InitiationResult(true, stored);
            }

            kyc.requireDmtReady(senderId);
            if (consentOtp == null || consentOtp.isBlank()) {
                kyc.requireRecentConsent(senderId, CustomerKycService.PURPOSE_DMT);
            } else {
                kyc.verifyOtp(senderId, CustomerKycService.PURPOSE_DMT, consentOtp);
            }

            // 2. Validate sender + beneficiary
            Integer beneOk = jdbc.queryForObject(
                    "SELECT count(*) FROM dmt_beneficiaries WHERE id = ? AND sender_id = ?",
                    Integer.class, beneficiaryId, senderId);
            if (beneOk == null || beneOk == 0) {
                throw ApiException.of(HttpStatus.NOT_FOUND, "BENEFICIARY_NOT_FOUND",
                        "Beneficiary does not exist for this sender");
            }

            // 3. Pricing (retailer override -> global rule)
            CommissionEngine.Resolution pricing = commissionEngine.resolve(agentId, "DMT", amount);
            BigDecimal fee = pricing.fee();
            BigDecimal total = amount.add(fee);

            // 4. Wallet balance check under row lock
            WalletService.WalletInfo wallet = walletService.lockByUser(agentId);
            if (wallet.available().compareTo(total) < 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_WALLET_BALANCE",
                        "Wallet balance is less than amount plus fee",
                        Map.of("required", total, "available", wallet.available()), false);
            }

            // 5. Transaction row + ledger HOLD posting
            UUID txnId = transactionService.create("DMT", agentId, wallet.walletId(), amount, fee,
                    senderId, beneficiaryId, "SPONSOR_BANK", claim.keyId());
            ledgerService.postGroup(txnId, List.of(
                    LedgerService.Entry.debit(wallet.availableAccountId(), total, "DMT hold"),
                    LedgerService.Entry.credit(wallet.holdAccountId(), total, "DMT hold")));
            walletService.adjustCache(wallet.walletId(), total.negate(), total);
            transactionService.transition(txnId, "HOLD", null, null);

            // 7. Outbox event — same DB transaction (transactional outbox)
            outboxWriter.write("TRANSACTION", txnId, "DMT_PAYOUT_REQUESTED", mapper.writeValueAsString(Map.of(
                    "transactionId", txnId.toString(),
                    "amount", amount,
                    "transferMode", transferMode)));

            // 8. Response snapshot stored for idempotent replay
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("transactionId", txnId);
            body.put("type", "DMT");
            body.put("state", "HOLD");
            body.put("amount", amount);
            body.put("fee", fee);
            body.put("totalDebited", total);
            body.put("transferMode", transferMode);
            body.put("walletBalanceAfter", wallet.available().subtract(total));
            body.put("statusPollUrl", "/api/v1/transactions/" + txnId);
            idempotencyService.complete(claim.keyId(), 202, mapper.writeValueAsString(body));

            log.info("DMT_INITIATE success txnId={} state=HOLD totalDebited={}", txnId, total);
            return new InitiationResult(false, body);
        } catch (ApiException e) {
            log.warn("DMT_INITIATE failed agent={} code={}", agentId, e.getCode());
            throw e;
        } catch (Exception e) {
            log.error("DMT_INITIATE failed agent={} reason={}", agentId, e.getMessage(), e);
            throw new RuntimeException(e);
        } finally {
            log.info("DMT_INITIATE completed in {} ms", System.currentTimeMillis() - startMs);
        }
    }
}
