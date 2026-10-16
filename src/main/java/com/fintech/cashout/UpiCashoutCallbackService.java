package com.fintech.cashout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fintech.commission.CommissionEngine;
import com.fintech.ledger.LedgerService;
import com.fintech.ledger.TransactionService;
import com.fintech.ledger.WalletService;
import com.fintech.paysprint.PaysprintJwtVerifier;
import com.fintech.platform.PlatformServiceGate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PaySprint UPI-CASHOUT callbacks. The initiate callback is where we mint {@code refid}.
 * Money moves only on {@code txn_status} 1. PaySprint's own commission and TDS are stored, not paid.
 */
@Service
public class UpiCashoutCallbackService {

    private static final Logger log = LoggerFactory.getLogger(UpiCashoutCallbackService.class);

    /** Returned to the controller so the webhook answers 400 instead of minting a reference. */
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
    private final CommissionEngine commissionEngine;
    private final PlatformServiceGate services;

    public UpiCashoutCallbackService(JdbcTemplate jdbc,
                                     PaysprintJwtVerifier jwt,
                                     TransactionService transactionService,
                                     WalletService walletService,
                                     LedgerService ledgerService,
                                     CommissionEngine commissionEngine,
                                     PlatformServiceGate services) {
        this.jdbc = jdbc;
        this.jwt = jwt;
        this.transactionService = transactionService;
        this.walletService = walletService;
        this.ledgerService = ledgerService;
        this.commissionEngine = commissionEngine;
        this.services = services;
    }

    /** Signature check. Merchant cross-check runs only when the token carries a merchant code. */
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

    /**
     * Customer asked to start a cash out. Returns the reference PaySprint must echo from here on.
     * A redelivery of the same retailer, amount, and mobile returns the reference already minted.
     */
    @Transactional
    public long initiate(JsonNode data) {
        String merchantCode = first(data, "merchant_code", "merchantcode");
        UUID retailerId = retailerId(merchantCode);
        jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, retailerId);
        if (!services.isEnabled(PlatformServiceGate.UPI_CASHOUT)) {
            throw new Rejected("UPI cash out is switched off");
        }
        if (!bank6Ready(retailerId)) {
            throw new Rejected("Cash out eKYC is not finished");
        }
        BigDecimal amount = requiredMoney(data, "amount");
        String mobile = mobile(data);
        String name = blankToNull(first(data, "customer_name"));
        expireStale(retailerId, amount, mobile);
        Long existing = openRefid(retailerId, amount, mobile);
        if (existing != null) {
            log.info("UPI_CASHOUT redelivery merchantcode={} refid={} amount={} mobile={}",
                    merchantCode, existing, amount, mobile);
            return existing;
        }
        WalletService.WalletInfo wallet = walletService.getByUser(retailerId);
        long refid = jdbc.queryForObject("SELECT nextval('upi_cashout_refid_seq')", Long.class);
        UUID txnId = transactionService.create("CASHOUT_UPI", retailerId, wallet.walletId(),
                amount, BigDecimal.ZERO, null, null, "UPI_PSP", null);
        jdbc.update("UPDATE transactions SET partner_ref = ? WHERE id = ?", Long.toString(refid), txnId);
        UUID sessionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cashout_sessions
                    (id, transaction_id, agent_user_id, channel, amount, status, expires_at,
                     customer_name, customer_mobile, provider_refid, latitude, longitude, device_id,
                     provider_comm, provider_tds, provider_initiated_at)
                VALUES (?, ?, ?, 'UPI_QR', ?, 'INITIATED', now() + interval '10 minutes',
                        ?, ?, ?, ?, ?, ?, ?, ?, now())
                """, sessionId, txnId, retailerId, amount, name, mobile, refid,
                blankToNull(first(data, "latitude")),
                blankToNull(first(data, "longitude")),
                blankToNull(first(data, "deviceid", "device_id")),
                optionalMoney(data, "comm"),
                optionalMoney(data, "tds"));
        log.info("UPI_CASHOUT initiate merchantcode={} refid={} amount={} mobile={} txnId={} session={}",
                merchantCode, refid, amount, mobile, txnId, sessionId);
        return refid;
    }

    /**
     * QR generated, success, failure, or expiry. Returns false when the status is unknown or the
     * cash out is already past the point this callback can change.
     */
    @Transactional
    public boolean acknowledge(JsonNode data) {
        return apply(refid(data), data);
    }

    /** Status poll for a session this retailer already owns. Ignores a payload that names another reference. */
    @Transactional
    public boolean acknowledgeOwned(long refid, JsonNode data) {
        if (data.hasNonNull("refid") && !data.path("refid").asText("").isBlank() && refid(data) != refid) {
            throw new Rejected("Reference does not match");
        }
        return apply(refid, data);
    }

    private boolean apply(long refid, JsonNode data) {
        String status = txnStatus(data);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, transaction_id, agent_user_id, amount, status::text AS status
                  FROM cashout_sessions
                 WHERE provider_refid = ?
                 FOR UPDATE
                """, refid);
        if (rows.isEmpty()) {
            throw new Rejected("Cash-out reference not found");
        }
        Map<String, Object> session = rows.get(0);
        UUID sessionId = (UUID) session.get("id");
        UUID txnId = (UUID) session.get("transaction_id");
        UUID retailerId = (UUID) session.get("agent_user_id");
        BigDecimal amount = (BigDecimal) session.get("amount");
        String current = String.valueOf(session.get("status"));
        String merchant = first(data, "merchant_code", "merchantcode");
        log.info("UPI_CASHOUT ack refid={} merchantcode={} txn_status={} session={} amount={} mobile={} ackno={} rrn={} errcode={}",
                refid, merchant, status, current, amount, first(data, "mobile"),
                first(data, "ackno"), first(data, "original_bank_rrn", "rrn"), first(data, "errcode"));
        stamp(sessionId, data);
        return switch (status) {
            case "3" -> markAwaiting(refid, sessionId, current, data);
            case "1" -> settle(refid, sessionId, txnId, retailerId, amount, data);
            case "0" -> close(refid, sessionId, txnId, "FAILED", "Cash out failed", data);
            case "4" -> close(refid, sessionId, txnId, "EXPIRED", "QR expired", data);
            default -> {
                log.info("UPI_CASHOUT ignored refid={} txn_status={} session={} reason=unknown_status", refid, status, current);
                yield false;
            }
        };
    }

    /** Biometric result. Informational; it never moves money. A missing token is expected on failure. */
    public void recordTwoFactor(JsonNode param) {
        log.info("UPI_CASHOUT 2fa merchantcode={} status={} device={} accessmode={} chargeable={} errorcode={} message={}",
                first(param, "merchantcode", "merchant_code"),
                param.path("status").asText(""),
                first(param, "device_mi"),
                first(param, "accessmode"),
                param.path("is_chargeable").asText(""),
                param.path("errorcode").asText(""),
                clip(first(param, "message")));
    }

    private boolean markAwaiting(long refid, UUID sessionId, String current, JsonNode data) {
        int updated = jdbc.update("""
                UPDATE cashout_sessions
                   SET status = 'AWAITING_PAYMENT',
                       ackno = COALESCE(?, ackno),
                       expires_at = GREATEST(expires_at, now() + interval '10 minutes')
                 WHERE id = ? AND status = 'INITIATED'
                """, blankToNull(first(data, "ackno")), sessionId);
        if (updated == 0) {
            jdbc.update("""
                    UPDATE cashout_sessions
                       SET ackno = COALESCE(?, ackno)
                     WHERE id = ? AND status = 'AWAITING_PAYMENT'
                    """, blankToNull(first(data, "ackno")), sessionId);
        }
        log.info("UPI_CASHOUT qr refid={} session={} from={} to={} ackno={}",
                refid, sessionId, current, updated > 0 ? "AWAITING_PAYMENT" : current, first(data, "ackno"));
        return updated > 0;
    }

    private boolean settle(long refid, UUID sessionId, UUID txnId, UUID retailerId, BigDecimal amount, JsonNode data) {
        String ackno = blankToNull(first(data, "ackno"));
        if (!transactionService.transition(txnId, "SUCCESS", ackno, null)) {
            log.info("UPI_CASHOUT noop refid={} txnId={} session={} txn_status=1 reason=already_terminal",
                    refid, txnId, sessionId);
            return false;
        }
        WalletService.WalletInfo wallet = walletService.lockByWalletId(
                (UUID) transactionService.getById(txnId).get("wallet_id"));
        ledgerService.post(txnId, wallet.walletId(), "CREDIT", amount, "UPI cash-out received");
        walletService.adjustCache(wallet.walletId(), amount, BigDecimal.ZERO);
        postCommissions(txnId, retailerId, amount, wallet);
        jdbc.update("""
                UPDATE cashout_sessions
                   SET status = 'PAID', paid_at = now(), ackno = COALESCE(?, ackno)
                 WHERE id = ?
                """, ackno, sessionId);
        log.info("UPI_CASHOUT paid refid={} txnId={} session={} amount={} ackno={} rrn={}",
                refid, txnId, sessionId, amount, ackno, first(data, "original_bank_rrn", "rrn"));
        return true;
    }

    private boolean close(long refid, UUID sessionId, UUID txnId, String sessionStatus, String reason, JsonNode data) {
        String detail = blankToNull(first(data, "errmsg", "message"));
        if (!transactionService.transition(txnId, "FAILED", blankToNull(first(data, "ackno")),
                detail == null ? reason : detail)) {
            log.info("UPI_CASHOUT noop refid={} txnId={} session={} to={} reason=already_terminal",
                    refid, txnId, sessionId, sessionStatus);
            return false;
        }
        jdbc.update("""
                UPDATE cashout_sessions
                   SET status = ?, ackno = COALESCE(?, ackno)
                 WHERE id = ? AND status IN ('INITIATED', 'AWAITING_PAYMENT')
                """, sessionStatus, blankToNull(first(data, "ackno")), sessionId);
        log.info("UPI_CASHOUT {} refid={} txnId={} session={} errcode={} errmsg={}",
                sessionStatus.toLowerCase(java.util.Locale.ROOT), refid, txnId, sessionId,
                first(data, "errcode"), clip(detail == null ? reason : detail));
        return true;
    }

    private void stamp(UUID sessionId, JsonNode data) {
        jdbc.update("""
                UPDATE cashout_sessions
                   SET bank_rrn = COALESCE(?, bank_rrn),
                       payer_va = COALESCE(?, payer_va),
                       payer_name = COALESCE(?, payer_name),
                       payer_account = COALESCE(?, payer_account),
                       payer_ifsc = COALESCE(?, payer_ifsc),
                       provider_comm = COALESCE(?, provider_comm),
                       provider_tds = COALESCE(?, provider_tds),
                       latitude = COALESCE(?, latitude),
                       longitude = COALESCE(?, longitude),
                       device_id = COALESCE(?, device_id),
                       err_code = COALESCE(?, err_code),
                       err_msg = COALESCE(?, err_msg),
                       customer_name = COALESCE(customer_name, ?)
                 WHERE id = ?
                """,
                blankToNull(first(data, "original_bank_rrn", "rrn")),
                blankToNull(first(data, "payer_va")),
                blankToNull(first(data, "payer_name")),
                blankToNull(first(data, "payer_account")),
                blankToNull(first(data, "payer_ifsc")),
                optionalMoney(data, "comm"),
                optionalMoney(data, "tds"),
                blankToNull(first(data, "latitude")),
                blankToNull(first(data, "longitude")),
                blankToNull(first(data, "deviceid", "device_id")),
                blankToNull(first(data, "errcode")),
                clip(blankToNull(first(data, "errmsg"))),
                blankToNull(first(data, "customer_name")),
                sessionId);
    }

    private void postCommissions(UUID txnId, UUID agentId, BigDecimal amount, WalletService.WalletInfo agentWallet) {
        CommissionEngine.Resolution res = commissionEngine.resolve(agentId, "CASHOUT_UPI", amount);
        BigDecimal retailerShare = res.retailerShare();
        BigDecimal distributorShare = res.distributorShare();
        if (retailerShare.add(distributorShare).signum() <= 0 && res.platformShare().signum() <= 0) {
            return;
        }
        UUID distributorId = jdbc.queryForObject("SELECT parent_id FROM users WHERE id = ?", UUID.class, agentId);
        UUID postingGroup = UUID.randomUUID();
        if (retailerShare.signum() > 0) {
            ledgerService.post(txnId, agentWallet.walletId(), "CREDIT", retailerShare, "Commission: retailer");
            walletService.adjustCache(agentWallet.walletId(), retailerShare, BigDecimal.ZERO);
        }
        if (distributorShare.signum() > 0 && distributorId != null) {
            WalletService.WalletInfo distWallet = walletService.lockByUser(distributorId);
            ledgerService.post(txnId, distWallet.walletId(), "CREDIT", distributorShare, "Commission: distributor");
            walletService.adjustCache(distWallet.walletId(), distributorShare, BigDecimal.ZERO);
        }
        UUID platformUser = jdbc.queryForObject(
                "SELECT id FROM users WHERE user_type = 'SUPER_ADMIN' LIMIT 1", UUID.class);
        CommissionEngine.EarningNetwork network = commissionEngine.networkForRetailer(agentId);
        if (retailerShare.signum() > 0) {
            commissionEngine.recordEarning(txnId, res.ruleId(), agentId, "RETAILER", retailerShare, postingGroup, network);
        }
        if (distributorShare.signum() > 0 && distributorId != null) {
            commissionEngine.recordEarning(txnId, res.ruleId(), distributorId, "DISTRIBUTOR", distributorShare, postingGroup, network);
        }
        if (res.platformShare().signum() > 0) {
            commissionEngine.recordEarning(txnId, res.ruleId(), platformUser, "PLATFORM", res.platformShare(), postingGroup, network);
        }
        log.info("COMMISSION_SPLIT txnId={} retailer={} distributor={} platform={}",
                txnId, retailerShare, distributorShare, res.platformShare());
    }

    private void expireStale(UUID retailerId, BigDecimal amount, String mobile) {
        List<UUID> txnIds = jdbc.query("""
                UPDATE cashout_sessions
                   SET status = 'EXPIRED'
                 WHERE agent_user_id = ? AND amount = ? AND customer_mobile = ?
                   AND status = 'INITIATED' AND expires_at < now()
                RETURNING transaction_id
                """, (rs, row) -> rs.getObject("transaction_id", UUID.class), retailerId, amount, mobile);
        for (UUID txnId : txnIds) {
            transactionService.transition(txnId, "FAILED", null, "Cash out expired before the QR was generated");
        }
    }

    private Long openRefid(UUID retailerId, BigDecimal amount, String mobile) {
        List<Long> rows = jdbc.query("""
                SELECT provider_refid FROM cashout_sessions
                 WHERE agent_user_id = ? AND amount = ? AND customer_mobile = ? AND status = 'INITIATED'
                """, (rs, row) -> rs.getLong("provider_refid"), retailerId, amount, mobile);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean bank6Ready(UUID retailerId) {
        Integer ready = jdbc.queryForObject("""
                SELECT count(*)::int FROM paysprint_bank_ekyc
                 WHERE user_id = ? AND bank_name = 'bank6' AND status = 'SUCCESS'
                """, Integer.class, retailerId);
        return ready != null && ready > 0;
    }

    private UUID retailerId(String merchantCode) {
        String code = merchantCode == null ? "" : merchantCode.trim();
        if (code.isBlank()) {
            throw new Rejected("Merchant code missing");
        }
        try {
            return jdbc.queryForObject("""
                    SELECT id FROM users
                     WHERE user_type = 'RETAILER' AND upper(code) = upper(?)
                    """, UUID.class, code);
        } catch (EmptyResultDataAccessException e) {
            throw new Rejected("Retailer not found");
        }
    }

    public static String merchantCode(JsonNode param) {
        String fromData = first(param.path("data"), "merchant_code", "merchantcode", "merchant_id");
        if (!fromData.isBlank()) {
            return fromData;
        }
        return first(param, "merchantcode", "merchant_code", "merchant_id");
    }

    public static String txnStatus(JsonNode data) {
        return data.path("txn_status").asText("").trim();
    }

    private static long refid(JsonNode data) {
        JsonNode node = data.path("refid");
        if (node.isMissingNode() || node.isNull()) {
            throw new Rejected("Reference missing");
        }
        if (node.isNumber()) {
            return node.longValue();
        }
        try {
            return Long.parseLong(node.asText("").trim());
        } catch (NumberFormatException e) {
            throw new Rejected("Reference is not a number");
        }
    }

    private static BigDecimal requiredMoney(JsonNode data, String field) {
        BigDecimal amount = optionalMoney(data, field);
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new Rejected("Amount missing");
        }
        return amount;
    }

    private static BigDecimal optionalMoney(JsonNode data, String field) {
        JsonNode node = data.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText("").trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String mobile(JsonNode data) {
        String raw = first(data, "mobile");
        String digits = raw.replaceAll("\\D", "");
        String mobile = digits.isBlank() ? raw : digits;
        if (mobile.length() > 15) {
            mobile = mobile.substring(0, 15);
        }
        if (mobile.isBlank()) {
            throw new Rejected("Customer mobile missing");
        }
        return mobile;
    }

    private static String first(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        for (String field : fields) {
            String value = node.path(field).asText("").trim();
            if (!value.isBlank() && !"null".equalsIgnoreCase(value)) {
                return value;
            }
        }
        return "";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String clip(String value) {
        if (value == null || value.length() <= 500) {
            return value;
        }
        return value.substring(0, 500);
    }
}
