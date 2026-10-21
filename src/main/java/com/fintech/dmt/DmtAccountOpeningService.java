package com.fintech.dmt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fintech.ledger.LedgerService;
import com.fintech.ledger.TransactionService;
import com.fintech.ledger.WalletService;
import com.fintech.paysprint.PaysprintJwtVerifier;
import com.fintech.platform.web.PublicAppUrl;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PaySprint tells us to debit the account-opening fee, then reports that the account was opened. */
@Service
public class DmtAccountOpeningService {

    private static final Logger log = LoggerFactory.getLogger(DmtAccountOpeningService.class);

    public static final class Rejected extends RuntimeException {
        public Rejected(String message) {
            super(message);
        }
    }

    private final JdbcTemplate jdbc;
    private final PaysprintJwtVerifier jwt;
    private final TransactionService transactionService;
    private final WalletService walletService;
    private final LedgerService ledgerService;
    private final DmtCasaLedger references;
    private final String publicAppUrl;

    public DmtAccountOpeningService(JdbcTemplate jdbc, PaysprintJwtVerifier jwt,
                                    TransactionService transactionService, WalletService walletService,
                                    LedgerService ledgerService, DmtCasaLedger references,
                                    @Value("${app.public-app-url}") String publicAppUrl) {
        this.jdbc = jdbc;
        this.jwt = jwt;
        this.transactionService = transactionService;
        this.walletService = walletService;
        this.ledgerService = ledgerService;
        this.references = references;
        this.publicAppUrl = PublicAppUrl.canonicalOrigin(publicAppUrl);
    }

    public String redirectUrl() {
        return publicAppUrl + "/agent/dmt/account-opening/return";
    }

    public JsonNode verifiedClaims(JsonNode body) {
        String enc = body.path("param_enc").asText("").trim();
        if (enc.isBlank()) {
            enc = body.path("param_inc").asText("").trim();
        }
        JsonNode claims = jwt.verify(enc);
        String claimMerchant = first(claims, "merchant_code", "merchantcode", "merchant_id");
        if (claimMerchant.isBlank()) {
            claimMerchant = first(claims.path("data"), "merchant_code", "merchantcode", "merchant_id");
        }
        String paramMerchant = merchantCode(body.path("param"));
        if (!claimMerchant.isBlank() && !paramMerchant.isBlank()
                && !claimMerchant.equalsIgnoreCase(paramMerchant)) {
            throw new IllegalArgumentException("Token does not match the merchant");
        }
        return claims;
    }

    /** Debit the fee PaySprint named. A repeat of the same retailer and mobile returns the same reference. */
    @Transactional
    public long debit(JsonNode data) {
        String merchant = first(data, "merchantcode", "merchant_code");
        UUID retailerId = retailerId(merchant);
        jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, retailerId);
        String mobile = mobile(data);
        BigDecimal amount = requiredMoney(data);
        Long existing = openRef(retailerId, mobile);
        if (existing != null) {
            log.info("DMT_CASA ao redelivery merchantcode={} mobile={} refid={} amount={}", merchant, mobile, existing, amount);
            return existing;
        }
        UUID senderId = jdbc.queryForObject("""
                INSERT INTO dmt_senders (id, mobile, full_name, registered_by)
                VALUES (?, ?, NULL, ?)
                ON CONFLICT (registered_by, mobile) DO UPDATE SET mobile = EXCLUDED.mobile
                RETURNING id
                """, UUID.class, UUID.randomUUID(), mobile, retailerId);
        WalletService.WalletInfo wallet = walletService.lockByUser(retailerId);
        if (wallet.available().compareTo(amount) < 0) {
            throw new Rejected("Wallet balance is less than the account opening fee");
        }
        long refid = Long.parseLong(references.nextReference());
        UUID txnId = transactionService.create("DMT_AO", retailerId, wallet.walletId(), amount, BigDecimal.ZERO,
                senderId, null, "PAYSPRINT", null);
        jdbc.update("UPDATE transactions SET partner_ref = ? WHERE id = ?", Long.toString(refid), txnId);
        ledgerService.post(txnId, wallet.walletId(), "DEBIT", amount, "DMT account opening");
        walletService.adjustCache(wallet.walletId(), amount.negate(), BigDecimal.ZERO);
        transactionService.transition(txnId, "SUCCESS", Long.toString(refid), null);
        jdbc.update("UPDATE dmt_senders SET ao_refid = ? WHERE id = ?", Long.toString(refid), senderId);
        log.info("DMT_CASA ao debit merchantcode={} mobile={} refid={} amount={} txnId={}",
                merchant, mobile, refid, amount, txnId);
        return refid;
    }

    @Transactional
    public void complete(JsonNode data) {
        String merchant = first(data, "merchantcode", "merchant_code");
        UUID retailerId = retailerId(merchant);
        String mobile = mobile(data);
        String accountNo = blankToNull(first(data, "accountno", "account_no"));
        String accountStatus = blankToNull(first(data, "accountstatus", "account_status"));
        String access = blankToNull(first(data, "access_type"));
        String verification = blankToNull(first(data, "verification_type"));
        int updated = jdbc.update("""
                UPDATE dmt_senders
                   SET ao_account_no = COALESCE(?, ao_account_no),
                       ao_account_status = COALESCE(?, ao_account_status),
                       ao_access_type = COALESCE(?, ao_access_type),
                       ao_verification_type = COALESCE(?, ao_verification_type),
                       provider_status = COALESCE(?, provider_status),
                       provider_checked_at = now()
                 WHERE registered_by = ? AND mobile = ?
                """, accountNo, accountStatus, access, verification, accountStatus, retailerId, mobile);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO dmt_senders
                        (id, mobile, full_name, registered_by, ao_account_no, ao_account_status,
                         ao_access_type, ao_verification_type, provider_status, provider_checked_at)
                    VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, now())
                    """, UUID.randomUUID(), mobile, retailerId, accountNo, accountStatus, access, verification, accountStatus);
        }
        log.info("DMT_CASA ao complete merchantcode={} mobile={} account_present={} account_status={} access={} verification={}",
                merchant, mobile, accountNo != null, accountStatus, access, verification);
    }

    private Long openRef(UUID retailerId, String mobile) {
        List<String> rows = jdbc.query("""
                SELECT t.partner_ref
                  FROM transactions t
                  JOIN dmt_senders s ON s.id = t.dmt_sender_id
                 WHERE t.agent_user_id = ? AND t.txn_type = 'DMT_AO' AND s.mobile = ? AND t.state <> 'FAILED'
                 ORDER BY t.created_at DESC
                 LIMIT 1
                """, (rs, row) -> rs.getString("partner_ref"), retailerId, mobile);
        if (rows.isEmpty() || rows.get(0) == null || rows.get(0).isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(rows.get(0).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private UUID retailerId(String merchantCode) {
        String code = merchantCode == null ? "" : merchantCode.trim();
        if (code.isBlank()) {
            throw new Rejected("Merchant code missing");
        }
        try {
            return jdbc.queryForObject("""
                    SELECT id FROM users WHERE user_type = 'RETAILER' AND upper(code) = upper(?)
                    """, UUID.class, code);
        } catch (EmptyResultDataAccessException e) {
            throw new Rejected("Retailer not found");
        }
    }

    private static BigDecimal requiredMoney(JsonNode data) {
        BigDecimal amount = DmtCasaTexts.money(data, "amount");
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new Rejected("Amount missing");
        }
        return amount;
    }

    private static String mobile(JsonNode data) {
        String digits = first(data, "mobile").replaceAll("\\D", "");
        if (digits.length() > 15) {
            digits = digits.substring(0, 15);
        }
        if (digits.isBlank()) {
            throw new Rejected("Customer mobile missing");
        }
        return digits;
    }

    static String merchantCode(JsonNode param) {
        String fromData = first(param.path("data"), "merchant_code", "merchantcode", "merchant_id");
        if (!fromData.isBlank()) {
            return fromData;
        }
        return first(param, "merchantcode", "merchant_code", "merchant_id");
    }

    private static String first(JsonNode node, String... fields) {
        return DmtCasaTexts.text(node, fields);
    }

    private static String blankToNull(String value) {
        return DmtCasaTexts.blankToNull(value);
    }
}
