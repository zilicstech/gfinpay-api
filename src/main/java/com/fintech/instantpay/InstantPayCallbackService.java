package com.fintech.instantpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.aeps.AepsService;
import com.fintech.cashout.CashoutService;
import com.fintech.dmt.DmtCasaLedger;
import com.fintech.payout.PayoutService;
import com.fintech.platform.webhook.ProviderCallbackLog;
import com.fintech.provider.canonical.ProviderModels;
import java.math.BigDecimal;
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
import org.springframework.beans.factory.annotation.Autowired;
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
    private final AepsService aepsService;
    private final DmtCasaLedger dmtCasaLedger;
    private final InstantPayAuditLogService auditLog;

    public InstantPayCallbackService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            InstantPayProperties props,
            ProviderCallbackLog callbackLog,
            CashoutService cashoutService,
            PayoutService payoutService,
            InstantPayAuditLogService auditLog) {
        this(jdbc, mapper, props, callbackLog, cashoutService, payoutService, null, null, auditLog);
    }

    public InstantPayCallbackService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            InstantPayProperties props,
            ProviderCallbackLog callbackLog,
            CashoutService cashoutService,
            PayoutService payoutService,
            AepsService aepsService,
            InstantPayAuditLogService auditLog) {
        this(jdbc, mapper, props, callbackLog, cashoutService, payoutService, aepsService, null, auditLog);
    }

    @Autowired
    public InstantPayCallbackService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            InstantPayProperties props,
            ProviderCallbackLog callbackLog,
            CashoutService cashoutService,
            PayoutService payoutService,
            AepsService aepsService,
            DmtCasaLedger dmtCasaLedger,
            InstantPayAuditLogService auditLog) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.props = props;
        this.callbackLog = callbackLog;
        this.cashoutService = cashoutService;
        this.payoutService = payoutService;
        this.aepsService = aepsService;
        this.dmtCasaLedger = dmtCasaLedger;
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

        String flow = (agentId.startsWith("AEPS") || existsInAeps(agentId, ipayId))
                ? "WEBHOOK_AEPS_" + (txnMode.isEmpty() ? "CB" : txnMode)
                : ("DR".equals(txnMode) ? "WEBHOOK_PAYOUT_DR"
                        : ("CR".equals(txnMode) ? "WEBHOOK_UATM_CR" : "WEBHOOK_CALLBACK"));

        String serializedPayload = serializeCallbackPayload(merged, rawBody);
        JsonNode payloadNode = parseSafe(serializedPayload);

        String callbackKey = headerIgnoreCase(rawHeaders, "X-Ipay-Callback-Key");
        boolean isV2Callback = !callbackKey.isEmpty()
                || (payloadNode.has("statusCode") && payloadNode.has("data") && ipayId.isEmpty() && agentId.isEmpty());
        if (isV2Callback) {
            return handleV2JsonCallback(
                    callId, requestId, method, providedSecret, callbackKey,
                    payloadNode, serializedPayload, requestUrl, requestPath, rawHeaders, started, occurredAt);
        }

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
        boolean isAeps = aepsService != null && (agentId.startsWith("AEPS") || existsInAeps(agentId, ipayId));
        if (isAeps) {
            return switch (status) {
                case "INITIATED", "PENDING" -> null;
                case "SUCCESS", "PAID", "COMPLETED" -> aepsService.finalizeFromCallback(
                        agentId, ipayId, "SUCCESS", nonBlank(resMsg, "AePS Transaction Successful"), oprId, null, null);
                case "FAILED", "DECLINED", "REJECTED" -> aepsService.finalizeFromCallback(
                        agentId, ipayId, "FAILED", nonBlank(resMsg, "AePS Transaction Failed"), oprId, null, null);
                case "REFUND", "REVERSED" -> aepsService.finalizeFromCallback(
                        agentId, ipayId, "REVERSED", nonBlank(resMsg, "AePS Transaction Reversed"), oprId, null, null);
                default -> {
                    log.info("INSTANTPAY_CALLBACK aeps unhandled status={} agentId={} ipayId={}", status, agentId, ipayId);
                    yield null;
                }
            };
        }

        boolean isDmt = dmtCasaLedger != null && (agentId.startsWith("DMT") || existsInDmt(agentId, ipayId));
        if (isDmt) {
            return switch (status) {
                case "INITIATED", "PENDING" -> null;
                case "SUCCESS", "PAID", "COMPLETED" -> finalizeDmtCallback(
                        agentId, ipayId, oprId, "SUCCESS", resCode, nonBlank(resMsg, "DMT Transaction Successful"));
                case "REFUND", "REVERSED", "FAILED", "DECLINED", "REJECTED" -> finalizeDmtCallback(
                        agentId, ipayId, oprId, "FAILED", resCode, nonBlank(resMsg, "DMT Transaction Reversed / Failed"));
                default -> {
                    log.info("INSTANTPAY_CALLBACK dmt non-terminal status={} agentId={} ipayId={}", status, agentId, ipayId);
                    yield null;
                }
            };
        }

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

    private boolean existsInAeps(String agentId, String ipayId) {
        if (!agentId.isEmpty()) {
            Integer c = jdbc.queryForObject(
                    "SELECT COUNT(*)::int FROM aeps_transactions WHERE external_ref = ?", Integer.class, agentId);
            if (c != null && c > 0) return true;
        }
        if (!ipayId.isEmpty()) {
            Integer c = jdbc.queryForObject(
                    "SELECT COUNT(*)::int FROM aeps_transactions WHERE provider_txn_id = ?", Integer.class, ipayId);
            if (c != null && c > 0) return true;
        }
        return false;
    }

    private boolean existsInDmt(String agentId, String ipayId) {
        if (!agentId.isEmpty()) {
            Integer c = jdbc.queryForObject(
                    "SELECT COUNT(*)::int FROM dmt_transfers WHERE external_ref = ? OR reference_id = ?",
                    Integer.class, agentId, agentId);
            if (c != null && c > 0) return true;
        }
        if (!ipayId.isEmpty()) {
            Integer c = jdbc.queryForObject(
                    "SELECT COUNT(*)::int FROM dmt_transfers WHERE provider_ackno = ?", Integer.class, ipayId);
            if (c != null && c > 0) return true;
        }
        return false;
    }

    /**
     * Handles InstantPay v2 JSON callbacks carrying {@code X-Ipay-Callback-Key} (such as {@code EXTA-IFT}
     * pre-authorization / Portal Test Callback and DMT v2 postbacks), returning InstantPay's required
     * {@code {"statusCode": "TXN/ERR", "status": "...", "data": {"externalRef": "..."}}} envelope.
     */
    private CallbackAck handleV2JsonCallback(
            String callId,
            String requestId,
            String method,
            String providedSecret,
            String callbackKey,
            JsonNode payloadNode,
            String serializedPayload,
            String requestUrl,
            String requestPath,
            Map<String, String> rawHeaders,
            long started,
            Instant occurredAt) {
        String normKey = callbackKey.isEmpty() ? "V2-JSON" : callbackKey.toUpperCase(Locale.ROOT);
        String flow = "WEBHOOK_" + normKey.replace('-', '_');
        String headerOutlet = headerIgnoreCase(rawHeaders, "X-Ipay-Outlet-Id");
        String traceId = headerIgnoreCase(rawHeaders, "X-Ipay-Trace-Id");

        String v2StatusCode = jsonText(payloadNode, "statusCode", "statuscode").toUpperCase(Locale.ROOT);
        String v2StatusMsg = jsonText(payloadNode, "status", "message");
        JsonNode dataNode = payloadNode.path("data");
        String outletId = nonBlank(jsonText(dataNode, "outletId"), headerOutlet);
        String remitterMobile = jsonText(dataNode, "remitterMobile", "mobileNumber");
        String txnValueStr = jsonText(dataNode, "txnValue", "transferAmount", "amount");
        String dataExtRef = jsonText(dataNode, "externalRef", "agentId");
        String poolRef = jsonText(dataNode, "poolReferenceId", "ipayId", "orderId");
        String utr = jsonText(dataNode, "txnReferenceId", "oprId", "utr");

        if (!verifySecret(providedSecret)) {
            Map<String, Object> errBody = new LinkedHashMap<>();
            errBody.put("statusCode", "ERR");
            errBody.put("status", "Unauthorized callback token");
            errBody.put("data", Map.of());
            callbackLog.record(PROVIDER, "WEBHOOK", flow, v2StatusCode, outletId, payloadNode, "REJECTED", "invalid_webhook_secret");
            recordWebhookAudit(callId, requestId, flow, method, requestUrl, requestPath, dataExtRef, poolRef,
                    rawHeaders, serializedPayload, 401, "ERR", "Unauthorized callback token", errBody,
                    "invalid_webhook_secret", started, occurredAt);
            return new CallbackAck(401, errBody);
        }

        boolean isPreAuth = normKey.startsWith("EXTA")
                || v2StatusMsg.toLowerCase(Locale.ROOT).contains("pre authorization");

        if (isPreAuth) {
            String resolvedRef = resolveDmtPreAuthExternalRef(dataExtRef, outletId, remitterMobile, txnValueStr);
            Map<String, Object> ackBody = new LinkedHashMap<>();
            ackBody.put("statusCode", "TXN");
            ackBody.put("status", "Transaction Authorized");
            ackBody.put("data", Map.of("externalRef", resolvedRef));

            callbackLog.record(PROVIDER, "WEBHOOK", flow, v2StatusCode, outletId, payloadNode, "ACCEPTED", "preauth_authorized");
            recordWebhookAudit(callId, requestId, flow, method, requestUrl, requestPath, resolvedRef, traceId,
                    rawHeaders, serializedPayload, 200, "TXN", "Pre-authorization / test callback authorized",
                    ackBody, null, started, occurredAt);
            return new CallbackAck(200, ackBody);
        }

        // Post-transaction v2 status callback
        String ackRef = !dataExtRef.isEmpty()
                ? sanitizeAlphanumericRef(dataExtRef)
                : generateAlphanumericRef();
        Map<String, Object> ackBody = new LinkedHashMap<>();
        ackBody.put("statusCode", "TXN");
        ackBody.put("status", "Callback processed successfully");
        ackBody.put("data", Map.of("externalRef", ackRef));

        callbackLog.record(PROVIDER, "WEBHOOK", flow, v2StatusCode, outletId, payloadNode, "ACCEPTED", null);

        if (!"TUP".equals(v2StatusCode) && dmtCasaLedger != null && (!dataExtRef.isEmpty() || !poolRef.isEmpty())) {
            String mappedStatus = switch (v2StatusCode) {
                case "TXN", "SUCCESS", "COMPLETED" -> "SUCCESS";
                case "RFD", "REFUND", "REVERSED" -> "REVERSED";
                case "ERR", "FAILED", "DECLINED", "REJECTED" -> "FAILED";
                default -> "";
            };
            if (!mappedStatus.isEmpty()) {
                finalizeDmtCallback(dataExtRef, poolRef, utr, mappedStatus, v2StatusCode, v2StatusMsg);
            }
        }

        recordWebhookAudit(callId, requestId, flow, method, requestUrl, requestPath, ackRef,
                !poolRef.isEmpty() ? poolRef : traceId,
                rawHeaders, serializedPayload, 200, v2StatusCode.isEmpty() ? "TXN" : v2StatusCode,
                nonBlank(v2StatusMsg, "Callback processed"), ackBody, null, started, occurredAt);
        return new CallbackAck(200, ackBody);
    }

    private String resolveDmtPreAuthExternalRef(
            String incomingExternalRef,
            String outletId,
            String remitterMobile,
            String txnValueStr) {
        if (!incomingExternalRef.isEmpty()) {
            return sanitizeAlphanumericRef(incomingExternalRef);
        }
        BigDecimal parsedAmt = null;
        if (!txnValueStr.isEmpty()) {
            try {
                parsedAmt = new BigDecimal(txnValueStr);
            } catch (NumberFormatException ignored) {
            }
        }
        if (!outletId.isEmpty()) {
            if (parsedAmt != null && parsedAmt.compareTo(new BigDecimal("100")) >= 0) {
                List<String> heldRefs = jdbc.queryForList("""
                        SELECT t.external_ref
                          FROM dmt_transfers t
                          JOIN dmt_senders s ON s.id = t.sender_id
                         WHERE t.outlet_code = ?
                           AND t.status IN ('HOLD', 'PENDING')
                           AND t.amount = ?
                           AND (? = '' OR s.mobile = ?)
                           AND t.created_at >= now() - interval '15 minutes'
                         ORDER BY t.created_at DESC
                         LIMIT 1
                        """, String.class, outletId, parsedAmt, remitterMobile, remitterMobile);
                if (!heldRefs.isEmpty()) {
                    return sanitizeAlphanumericRef(heldRefs.get(0));
                }
            }
            List<String> ekycRefs = jdbc.queryForList("""
                    SELECT external_ref
                      FROM dmt_remitter_onboarding_sessions
                     WHERE outlet_code = ?
                       AND (? = '' OR mobile = ?)
                       AND created_at >= now() - interval '30 minutes'
                     ORDER BY created_at DESC
                     LIMIT 1
                    """, String.class, outletId, remitterMobile, remitterMobile);
            if (!ekycRefs.isEmpty()) {
                return sanitizeAlphanumericRef(ekycRefs.get(0));
            }
        }
        return generateAlphanumericRef();
    }

    private UUID finalizeDmtCallback(
            String externalRef,
            String providerTxnId,
            String utr,
            String normalizedStatus,
            String resCode,
            String resMsg) {
        if (dmtCasaLedger == null) return null;
        List<UUID> txnIds = List.of();
        if (!externalRef.isEmpty()) {
            txnIds = jdbc.queryForList("""
                    SELECT transaction_id FROM dmt_transfers
                     WHERE external_ref = ? OR reference_id = ?
                     LIMIT 1
                    """, UUID.class, externalRef, externalRef);
        }
        if (txnIds.isEmpty() && !providerTxnId.isEmpty()) {
            txnIds = jdbc.queryForList("""
                    SELECT transaction_id FROM dmt_transfers
                     WHERE provider_ackno = ?
                     LIMIT 1
                    """, UUID.class, providerTxnId);
        }
        if (txnIds.isEmpty()) {
            return null;
        }
        UUID txnId = txnIds.get(0);
        ProviderModels.Outcome outcome = "SUCCESS".equals(normalizedStatus)
                ? ProviderModels.Outcome.SUCCESS
                : ProviderModels.Outcome.FAILED;
        dmtCasaLedger.applyStatusReconfirmation(txnId, new ProviderModels.DmtTransferStatus(
                outcome,
                false,
                resCode.isEmpty() ? normalizedStatus : resCode,
                nonBlank(resMsg, "DMT callback status: " + normalizedStatus),
                utr,
                providerTxnId));
        return txnId;
    }

    private static String headerIgnoreCase(Map<String, String> headers, String target) {
        if (headers == null || target == null) return "";
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(target) && e.getValue() != null) {
                return e.getValue().trim();
            }
        }
        return "";
    }

    private static String jsonText(JsonNode node, String... keys) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        for (String key : keys) {
            JsonNode child = node.get(key);
            if (child != null && !child.isNull()) {
                String val = child.asText("").trim();
                if (!val.isEmpty()) return val;
            }
        }
        return "";
    }

    private static String sanitizeAlphanumericRef(String raw) {
        String clean = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9]", "");
        if (clean.length() < 6) {
            return generateAlphanumericRef();
        }
        return clean.length() > 26 ? clean.substring(0, 26) : clean;
    }

    private static String generateAlphanumericRef() {
        return "GFP" + System.currentTimeMillis() + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
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
