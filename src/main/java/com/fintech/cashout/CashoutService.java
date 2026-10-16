package com.fintech.cashout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fintech.paysprint.PaysprintUpiCashoutClient;
import com.fintech.platform.PlatformServiceGate;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import com.fintech.platform.web.PublicAppUrl;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CashoutService {

    private static final Logger log = LoggerFactory.getLogger(CashoutService.class);

    private static final String SESSION_COLUMNS = """
            c.id, c.amount, c.status::text AS status, c.customer_name, c.customer_mobile,
            c.bank_rrn, c.ackno, c.err_code, c.err_msg, c.payer_name, c.provider_refid,
            c.expires_at, c.paid_at, c.dispensed_at, c.created_at
            """;

    private final JdbcTemplate jdbc;
    private final PlatformServiceGate services;
    private final PaysprintUpiCashoutClient paysprint;
    private final UpiCashoutCallbackService callbacks;
    private final String publicAppUrl;

    public CashoutService(JdbcTemplate jdbc,
                          PlatformServiceGate services,
                          PaysprintUpiCashoutClient paysprint,
                          UpiCashoutCallbackService callbacks,
                          @Value("${app.public-app-url}") String publicAppUrl) {
        this.jdbc = jdbc;
        this.services = services;
        this.paysprint = paysprint;
        this.callbacks = callbacks;
        this.publicAppUrl = PublicAppUrl.canonicalOrigin(publicAppUrl);
    }

    /** Opens PaySprint's hosted page. The cash-out row is created later, by their initiate callback. */
    public Map<String, Object> startUpi(AuthPrincipal me) {
        services.requireEnabled(PlatformServiceGate.UPI_CASHOUT);
        if (!bank6Ready(me.userId())) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "EKYC_REQUIRED",
                    "Finish cash out eKYC before giving cash.");
        }
        String code = merchantCode(me.userId());
        String url = paysprint.getToken(code, publicAppUrl + "/agent/cash-out/upi/return");
        log.info("UPI_CASHOUT token issued merchantcode={} url_present={}", code, url != null && !url.isBlank());
        return Map.of("url", url);
    }

    public Map<String, Object> latest(AuthPrincipal me) {
        services.requireEnabled(PlatformServiceGate.UPI_CASHOUT);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT %s
                  FROM cashout_sessions c
                 WHERE c.agent_user_id = ? AND c.created_at > now() - interval '30 minutes'
                 ORDER BY c.created_at DESC
                 LIMIT 1
                """.formatted(SESSION_COLUMNS), me.userId());
        if (rows.isEmpty()) {
            return Map.of("status", "NONE");
        }
        return view(rows.get(0));
    }

    public Map<String, Object> get(AuthPrincipal me, UUID sessionId) {
        services.requireEnabled(PlatformServiceGate.UPI_CASHOUT);
        return view(load(me, sessionId));
    }

    /** Asks PaySprint for the latest status when a callback has not arrived. */
    @Transactional
    public Map<String, Object> refresh(AuthPrincipal me, UUID sessionId) {
        services.requireEnabled(PlatformServiceGate.UPI_CASHOUT);
        Map<String, Object> session = load(me, sessionId);
        String status = String.valueOf(session.get("status"));
        Object ref = session.get("provider_refid");
        log.info("UPI_CASHOUT refresh session={} refid={} from={}", sessionId, ref, status);
        if (!"INITIATED".equals(status) && !"AWAITING_PAYMENT".equals(status)) {
            return view(session);
        }
        if (ref == null) {
            log.info("UPI_CASHOUT refresh session={} result=no_refid", sessionId);
            return view(session);
        }
        JsonNode root = paysprint.txnStatus(merchantCode(me.userId()), String.valueOf(ref));
        JsonNode data = root.path("data");
        if (!data.isObject()) {
            data = root;
        }
        if (!UpiCashoutCallbackService.txnStatus(data).isBlank()) {
            callbacks.acknowledgeOwned(((Number) ref).longValue(), data);
        } else {
            log.info("UPI_CASHOUT refresh session={} refid={} result=no_status", sessionId, ref);
        }
        Map<String, Object> updated = load(me, sessionId);
        log.info("UPI_CASHOUT refresh session={} refid={} to={}", sessionId, ref, updated.get("status"));
        return view(updated);
    }

    @Transactional
    public Map<String, Object> dispense(AuthPrincipal me, UUID sessionId) {
        services.requireEnabled(PlatformServiceGate.UPI_CASHOUT);
        Map<String, Object> session = load(me, sessionId);
        if (!"PAID".equals(String.valueOf(session.get("status")))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_PAID",
                    "Hand over cash only after the payment is confirmed");
        }
        jdbc.update("""
                UPDATE cashout_sessions SET status = 'CASH_DISPENSED', dispensed_at = now() WHERE id = ?
                """, sessionId);
        log.info("UPI_CASHOUT dispensed session={} refid={}", sessionId, session.get("provider_refid"));
        return view(load(me, sessionId));
    }

    private Map<String, Object> load(AuthPrincipal me, UUID sessionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT %s
                  FROM cashout_sessions c
                 WHERE c.id = ? AND c.agent_user_id = ?
                """.formatted(SESSION_COLUMNS), sessionId, me.userId());
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "CASHOUT_NOT_FOUND", "Cash-out session not found");
        }
        return rows.get(0);
    }

    private boolean bank6Ready(UUID userId) {
        Integer ready = jdbc.queryForObject("""
                SELECT count(*)::int FROM paysprint_bank_ekyc
                 WHERE user_id = ? AND bank_name = 'bank6' AND status = 'SUCCESS'
                """, Integer.class, userId);
        return ready != null && ready > 0;
    }

    private String merchantCode(UUID userId) {
        String code = jdbc.queryForObject("SELECT code FROM users WHERE id = ?", String.class, userId);
        if (code == null || code.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "MERCHANT_CODE_MISSING",
                    "This retailer has no merchant code yet");
        }
        return code.trim();
    }

    private static Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.get("id"));
        out.put("amount", row.get("amount"));
        out.put("status", row.get("status"));
        out.put("customerName", row.get("customer_name"));
        out.put("customerMobile", row.get("customer_mobile"));
        out.put("bankRrn", row.get("bank_rrn"));
        out.put("ackno", row.get("ackno"));
        out.put("errCode", row.get("err_code"));
        out.put("errMsg", row.get("err_msg"));
        out.put("payerName", row.get("payer_name"));
        out.put("refid", row.get("provider_refid"));
        out.put("expiresAt", row.get("expires_at"));
        out.put("paidAt", row.get("paid_at"));
        out.put("dispensedAt", row.get("dispensed_at"));
        out.put("createdAt", row.get("created_at"));
        return out;
    }
}
