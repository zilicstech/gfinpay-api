package com.fintech.instantpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.cashout.CashoutService;
import com.fintech.payout.PayoutService;
import com.fintech.platform.webhook.ProviderCallbackLog;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processes inbound InstantPay webhooks ({@code GET}/{@code POST /webhooks/instantpay/callback})
 * per InstantPay's official Webhooks specification:
 * <ul>
 *   <li>7 parameters: {@code ipay_id}, {@code agent_id}, {@code opr_id}, {@code status}, {@code res_code}, {@code res_msg}, {@code txn_mode} ({@code CR}/{@code DR})</li>
 *   <li>Always acknowledges within {@code < 3s} with {@code {"ipay_id": "<echoed>", "success": true, "description": "callback called successfully"}}</li>
 *   <li>Never auto-fails or auto-refunds when {@code res_code == "TUP"} (timeout — keep pending for reconciliation)</li>
 *   <li>Handles {@code REFUND} on {@code DR} payouts (reversing both {@code HOLD}/{@code PENDING} and post-settlement {@code SUCCESS})</li>
 * </ul>
 */
@Service
public class InstantPayCallbackService {

    private static final Logger log = LoggerFactory.getLogger(InstantPayCallbackService.class);
    public static final String PROVIDER = "INSTANTPAY";

    public record CallbackAck(int httpStatus, Map<String, Object> body) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final InstantPayProperties props;
    private final ProviderCallbackLog callbackLog;
    private final CashoutService cashoutService;
    private final PayoutService payoutService;
    private final InstantPayAuditLogService auditLog;

    public InstantPayCallbackService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            InstantPayProperties props,
            ProviderCallbackLog callbackLog,
            CashoutService cashoutService,
            PayoutService payoutService,
            InstantPayAuditLogService auditLog) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.props = props;
        this.callbackLog = callbackLog;
        this.cashoutService = cashoutService;
        this.payoutService = payoutService;
        this.auditLog = auditLog;
    }

    public boolean verifySecret(String providedSecret) {
        String expected = props.webhookSecret();
        if (expected == null || expected.isBlank()) {
            // Allow when webhook secret is not yet configured (e.g. initial portal verification)
            return true;
        }
        if (providedSecret == null || providedSecret.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.trim().getBytes(StandardCharsets.UTF_8),
                providedSecret.trim().getBytes(StandardCharsets.UTF_8));
    }

    @Transactional
    public CallbackAck handleInbound(
            String method,
            String providedSecret,
            Map<String, String> queryOrFormParams,
            String rawBody,
            String requestUrl,
            String requestPath,
            Map<String, String> rawHeaders) {
        Instant occurredAt = Instant.now();
        long started = System.currentTimeMillis();
        String callId = String.format("ipay_cb_%x_%04x", occurredAt.toEpochMilli(), ThreadLocalRandom.current().nextInt(0x10000));
        String requestId = MDC.get("requestId");

        Map<String, String> merged = extractFields(queryOrFormParams, rawBody);
        String ipayId = trim(merged.get("ipay_id"));
        String agentId = trim(merged.get("agent_id"));
        String oprId = trim(merged.get("opr_id"));
        String status = trim(merged.get("status")).toUpperCase(Locale.ROOT);
        String resCode = trim(merged.get("res_code")).toUpperCase(Locale.ROOT);
        String resMsg = trim(merged.get("res_msg"));
        String txnMode = trim(merged.get("txn_mode")).toUpperCase(Locale.ROOT);

        String flow = "DR".equals(txnMode) ? "WEBHOOK_PAYOUT_DR"
                : ("CR".equals(txnMode) ? "WEBHOOK_UATM_CR" : "WEBHOOK_CALLBACK");

        String serializedPayload = serializeCallbackPayload(merged, rawBody);
        JsonNode payloadNode = parseSafe(serializedPayload);

        if (!verifySecret(providedSecret)) {
            Map<String, Object> errBody = new LinkedHashMap<>();
            errBody.put("ipay_id", ipayId.isEmpty() ? null : ipayId);
            errBody.put("success", false);
            errBody.put("description", "unauthorized callback token");
            callbackLog.record(PROVIDER, "WEBHOOK", flow, status, "", payloadNode, "REJECTED", "invalid_webhook_secret");
            recordWebhookAudit(callId, requestId, flow, method, requestUrl, requestPath, agentId, ipayId,
                    rawHeaders, serializedPayload, 401, "UNAUTHORIZED", "Invalid webhook secret", errBody,
                    "invalid_webhook_secret", started, occurredAt);
            return new CallbackAck(401, errBody);
        }

        Map<String, Object> ackBody = new LinkedHashMap<>();
        ackBody.put("ipay_id", ipayId.isEmpty() ? null : ipayId);
        ackBody.put("success", true);
        ackBody.put("description", "callback called successfully");

        // Verification ping or incomplete callback — acknowledge 200 immediately per InstantPay spec
        if (status.isEmpty() || (ipayId.isEmpty() && agentId.isEmpty())) {
            callbackLog.record(PROVIDER, "WEBHOOK", flow, status, "", payloadNode, "ACCEPTED", "verification_or_empty");
            recordWebhookAudit(callId, requestId, flow, method, requestUrl, requestPath, agentId, ipayId,
                    rawHeaders, serializedPayload, 200, "PING_ACK", "Verification / empty callback acknowledged",
                    ackBody, null, started, occurredAt);
            return new CallbackAck(200, ackBody);
        }

        callbackLog.record(PROVIDER, "WEBHOOK", flow, status, "", payloadNode, "ACCEPTED", null);

        String dedupeKey = (txnMode.isEmpty() ? "ANY" : txnMode) + ":"
                + (!ipayId.isEmpty() ? ipayId : agentId) + ":"
                + status + ":"
                + (resCode.isEmpty() ? "-" : resCode);

        List<Long> inserted = jdbc.queryForList("""
                INSERT INTO webhook_inbox (provider, external_event_id, payload, signature_valid, status)
                VALUES (?, ?, CAST(? AS jsonb), TRUE, 'RECEIVED')
                ON CONFLICT (provider, external_event_id) DO NOTHING
                RETURNING id
                """, Long.class, PROVIDER, dedupeKey, serializedPayload);

        if (inserted.isEmpty()) {
            recordWebhookAudit(callId, requestId, flow, method, requestUrl, requestPath, agentId, ipayId,
                    rawHeaders, serializedPayload, 200, "DUPLICATE_IGNORED",
                    "Duplicate callback ignored (" + dedupeKey + ")", ackBody, null, started, occurredAt);
            return new CallbackAck(200, ackBody);
        }

        // CRITICAL InstantPay rule: res_code == "TUP" means upstream response timed out;
        // NEVER auto-fail or auto-refund — leave in PENDING/HOLD for reconciliation / Admin review.
        if ("TUP".equals(resCode)) {
            jdbc.update("""
                    UPDATE webhook_inbox
                       SET status = 'PROCESSED', processed_at = now()
                     WHERE id = ?
                    """, inserted.get(0));
            log.warn("INSTANTPAY_CALLBACK TUP timeout held pending agentId={} ipayId={} msg={}", agentId, ipayId, resMsg);
            recordWebhookAudit(callId, requestId, flow, method, requestUrl, requestPath, agentId, ipayId,
                    rawHeaders, serializedPayload, 200, "TUP_HELD_PENDING",
                    nonBlank(resMsg, "TUP timeout held pending"), ackBody, null, started, occurredAt);
            return new CallbackAck(200, ackBody);
        }

        UUID matchedTxnId = dispatchCallback(ipayId, agentId, oprId, status, resCode, resMsg, txnMode);

        jdbc.update("""
                UPDATE webhook_inbox
                   SET status = 'PROCESSED',
                       transaction_id = ?,
                       processed_at = now()
                 WHERE id = ?
                """, matchedTxnId, inserted.get(0));

        recordWebhookAudit(callId, requestId, flow, method, requestUrl, requestPath, agentId, ipayId,
                rawHeaders, serializedPayload, 200,
                resCode.isEmpty() ? status : resCode + ":" + status,
                nonBlank(resMsg, status), ackBody, null, started, occurredAt);

        return new CallbackAck(200, ackBody);
    }

    private UUID dispatchCallback(
            String ipayId,
            String agentId,
            String oprId,
            String status,
            String resCode,
            String resMsg,
            String txnMode) {
        boolean isPayout = "DR".equals(txnMode)
                || agentId.startsWith("PO")
                || (!"CR".equals(txnMode) && existsInPayouts(agentId, ipayId));

        if (isPayout) {
            return switch (status) {
                case "SUCCESS" -> payoutService.finalizeFromCallback(
                        agentId, ipayId, "SUCCESS", nonBlank(resMsg, "Transaction Successful"), oprId);
                case "REFUND", "REVERSED" -> payoutService.finalizeFromCallback(
                        agentId, ipayId, "REVERSED", nonBlank(resMsg, "Settlement refunded by bank"), oprId);
                case "FAILED", "DECLINED", "REJECTED" -> payoutService.finalizeFromCallback(
                        agentId, ipayId, "FAILED", nonBlank(resMsg, "Settlement failed"), oprId);
                default -> {
                    log.info("INSTANTPAY_CALLBACK payout non-terminal status={} agentId={} ipayId={}", status, agentId, ipayId);
                    yield null;
                }
            };
        }

        // Credit / UPI ATM QR Cashout (txn_mode == CR)
        return switch (status) {
            case "INITIATED", "PENDING" -> null;
            case "SUCCESS", "PAID", "COMPLETED" -> cashoutService.finalizeFromCallback(
                    agentId, ipayId, "SUCCESS", nonBlank(resMsg, "Cash Withdrawal is Successful"), oprId, "", "");
            case "FAILED", "DECLINED", "REJECTED" -> cashoutService.finalizeFromCallback(
                    agentId, ipayId, "FAILED", nonBlank(resMsg, "Cash Withdrawal failed"), oprId, "", "");
            case "EXPIRED" -> cashoutService.finalizeFromCallback(
                    agentId, ipayId, "EXPIRED", nonBlank(resMsg, "QR expired"), oprId, "", "");
            case "REFUND", "REVERSED" -> cashoutService.finalizeFromCallback(
                    agentId, ipayId, "REVERSED", nonBlank(resMsg, "Cash Withdrawal reversed"), oprId, "", "");
            default -> {
                log.info("INSTANTPAY_CALLBACK cashout unhandled status={} agentId={} ipayId={}", status, agentId, ipayId);
                yield null;
            }
        };
    }

    private boolean existsInPayouts(String agentId, String ipayId) {
        if (!agentId.isEmpty()) {
            Integer c = jdbc.queryForObject(
                    "SELECT COUNT(*)::int FROM payout_transfers WHERE external_ref = ?", Integer.class, agentId);
            if (c != null && c > 0) return true;
        }
        if (!ipayId.isEmpty()) {
            Integer c = jdbc.queryForObject(
                    "SELECT COUNT(*)::int FROM payout_transfers WHERE provider_txn_id = ?", Integer.class, ipayId);
            if (c != null && c > 0) return true;
        }
        return false;
    }

    private Map<String, String> extractFields(Map<String, String> params, String rawBody) {
        Map<String, String> out = new LinkedHashMap<>();
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    out.put(e.getKey().trim(), e.getValue().trim());
                }
            }
        }
        if (rawBody != null && !rawBody.isBlank()) {
            String trimmed = rawBody.trim();
            if (trimmed.startsWith("{")) {
                try {
                    JsonNode node = mapper.readTree(trimmed);
                    copyIfPresent(node, out, "ipay_id", "ipayId", "orderid");
                    copyIfPresent(node, out, "agent_id", "externalRef", "agentId");
                    copyIfPresent(node, out, "opr_id", "operatorId", "utr", "txnReferenceId");
                    copyIfPresent(node, out, "status", "qrStatus", "transactionStatus");
                    copyIfPresent(node, out, "res_code", "statuscode", "transactionStatusCode");
                    copyIfPresent(node, out, "res_msg", "qrStatusMsg", "message");
                    copyIfPresent(node, out, "txn_mode", "transactionMode", "mode");
                } catch (Exception ignored) {
                }
            } else if (trimmed.contains("=")) {
                for (String pair : trimmed.split("&")) {
                    int idx = pair.indexOf('=');
                    if (idx > 0) {
                        String k = urlDecode(pair.substring(0, idx));
                        String v = urlDecode(pair.substring(idx + 1));
                        out.putIfAbsent(k, v);
                    }
                }
            }
        }
        return out;
    }

    private static void copyIfPresent(JsonNode node, Map<String, String> target, String canonicalKey, String... aliases) {
        if (target.containsKey(canonicalKey) && !target.get(canonicalKey).isBlank()) {
            return;
        }
        JsonNode direct = node.get(canonicalKey);
        if (direct != null && !direct.isNull() && !direct.asText("").isBlank()) {
            target.put(canonicalKey, direct.asText("").trim());
            return;
        }
        for (String alias : aliases) {
            JsonNode alt = node.get(alias);
            if (alt != null && !alt.isNull() && !alt.asText("").isBlank()) {
                target.put(canonicalKey, alt.asText("").trim());
                return;
            }
        }
    }

    private String serializeCallbackPayload(Map<String, String> merged, String rawBody) {
        if (rawBody != null && rawBody.trim().startsWith("{")) {
            return rawBody.trim();
        }
        try {
            return mapper.writeValueAsString(merged);
        } catch (Exception e) {
            return "{}";
        }
    }

    private JsonNode parseSafe(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private void recordWebhookAudit(
            String callId,
            String requestId,
            String flow,
            String method,
            String url,
            String path,
            String externalRef,
            String providerTxnId,
            Map<String, String> headers,
            String reqBody,
            int httpStatus,
            String code,
            String msg,
            Map<String, Object> ackBody,
            String err,
            long startedMs,
            Instant occurredAt) {
        if (auditLog == null) return;
        String resJson;
        try {
            resJson = mapper.writeValueAsString(ackBody);
        } catch (Exception e) {
            resJson = "{\"success\":true}";
        }
        auditLog.recordWebhookAsync(new InstantPayAuditLogService.WebhookSnapshot(
                callId,
                requestId,
                flow,
                nonBlank(method, "POST"),
                nonBlank(url, "/webhooks/instantpay/callback"),
                nonBlank(path, "/webhooks/instantpay/callback"),
                null,
                externalRef,
                providerTxnId,
                headers == null ? Map.of() : headers,
                reqBody,
                httpStatus,
                code,
                msg,
                resJson,
                err,
                System.currentTimeMillis() - startedMs,
                occurredAt));
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return s.trim();
        }
    }

    private static String trim(String val) {
        return val == null ? "" : val.trim();
    }

    private static String nonBlank(String val, String fallback) {
        return (val == null || val.isBlank()) ? fallback : val.trim();
    }
}
