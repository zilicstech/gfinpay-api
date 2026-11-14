package com.fintech.emerald;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.aeps.AepsService;
import com.fintech.cashout.CashoutService;
import com.fintech.payout.PayoutService;
import com.fintech.platform.webhook.ProviderCallbackLog;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * Processes inbound Emerald webhooks ({@code POST /emerald/v1/callback}) across
 * {@code UPICASH}, {@code AEPS}, and {@code PAYOUT} with constant-time secret verification
 * and {@code (txnId, status)} idempotency in {@code webhook_inbox}.
 */
@Service
public class EmeraldCallbackService {

    private static final Logger log = LoggerFactory.getLogger(EmeraldCallbackService.class);
    public static final String PROVIDER = "EMERALD";

    public record CallbackOutcome(boolean authorized, boolean duplicate, UUID transactionId, String message) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final EmeraldProperties props;
    private final ProviderCallbackLog callbackLog;
    private final CashoutService cashoutService;
    private final AepsService aepsService;
    private final PayoutService payoutService;
    private final EmeraldAuditLogService auditLog;

    @Autowired
    public EmeraldCallbackService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            EmeraldProperties props,
            ProviderCallbackLog callbackLog,
            CashoutService cashoutService,
            AepsService aepsService,
            PayoutService payoutService,
            EmeraldAuditLogService auditLog) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.props = props;
        this.callbackLog = callbackLog;
        this.cashoutService = cashoutService;
        this.aepsService = aepsService;
        this.payoutService = payoutService;
        this.auditLog = auditLog;
    }

    public EmeraldCallbackService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            EmeraldProperties props,
            ProviderCallbackLog callbackLog,
            CashoutService cashoutService,
            AepsService aepsService,
            PayoutService payoutService) {
        this(jdbc, mapper, props, callbackLog, cashoutService, aepsService, payoutService, null);
    }

    public boolean verifySecret(String providedSecret) {
        String expected = props.callbackSecret();
        if (expected == null || expected.isBlank() || providedSecret == null || providedSecret.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.trim().getBytes(StandardCharsets.UTF_8),
                providedSecret.trim().getBytes(StandardCharsets.UTF_8));
    }

    @Transactional
    public CallbackOutcome handleInbound(String secretHeader, String rawBody) {
        Map<String, String> hdrs = new LinkedHashMap<>();
        if (secretHeader != null) {
            hdrs.put("X-Emerald-Callback-Secret", secretHeader);
        }
        return handleInbound(secretHeader, rawBody, "/emerald/v1/callback", "/emerald/v1/callback", hdrs);
    }

    @Transactional
    public CallbackOutcome handleInbound(
            String secretHeader,
            String rawBody,
            String requestUrl,
            String requestPath,
            Map<String, String> rawHeaders) {
        Instant occurredAt = Instant.now();
        long started = System.currentTimeMillis();
        String callId = String.format("emd_cb_%x_%04x", occurredAt.toEpochMilli(), ThreadLocalRandom.current().nextInt(0x10000));
        String requestId = MDC.get("requestId");
        Map<String, String> copiedHeaders = (rawHeaders == null || rawHeaders.isEmpty())
                ? Map.of()
                : new LinkedHashMap<>(rawHeaders);

        JsonNode payload = parseSafe(rawBody);
        String productCode = text(payload, "productCode").toUpperCase(Locale.ROOT);
        String txnType = text(payload, "txnType").toUpperCase(Locale.ROOT);
        String txnId = text(payload, "txnId");
        String externalRef = text(payload, "externalRef");
        String outletCode = text(payload, "outletCode");
        String status = text(payload, "status").toUpperCase(Locale.ROOT);
        String message = text(payload, "message");
        String eventName = productCode.isEmpty() ? "CALLBACK" : (txnType.isEmpty() ? productCode : productCode + ":" + txnType);
        String flowName = "WEBHOOK_" + eventName.replace(':', '_');
        String effectivePath = (requestPath == null || requestPath.isBlank()) ? "/emerald/v1/callback" : requestPath;
        String effectiveUrl = (requestUrl == null || requestUrl.isBlank()) ? effectivePath : requestUrl;

        if (!verifySecret(secretHeader)) {
            callbackLog.record(PROVIDER, "WEBHOOK", eventName, status, outletCode, payload, "REJECTED", "invalid_callback_secret");
            long elapsed = System.currentTimeMillis() - started;
            String resBody = "{\"success\":false,\"message\":\"Invalid callback secret\"}";
            dispatchWebhookAuditAsync(new EmeraldAuditLogService.WebhookTraceSnapshot(
                    callId, requestId, flowName, effectiveUrl, effectivePath,
                    outletCode, externalRef, txnId, copiedHeaders, rawBody,
                    401, "REJECTED_INVALID_SECRET", null, null,
                    status, "Invalid callback secret", resBody, "invalid_callback_secret",
                    elapsed, occurredAt));
            return new CallbackOutcome(false, false, null, "Invalid callback secret");
        }

        if (productCode.isEmpty() || status.isEmpty() || (txnId.isEmpty() && externalRef.isEmpty())) {
            callbackLog.record(PROVIDER, "WEBHOOK", eventName, status, outletCode, payload, "REJECTED", "missing_mandatory_fields");
            long elapsed = System.currentTimeMillis() - started;
            String resBody = "{\"success\":true}";
            dispatchWebhookAuditAsync(new EmeraldAuditLogService.WebhookTraceSnapshot(
                    callId, requestId, flowName, effectiveUrl, effectivePath,
                    outletCode, externalRef, txnId, copiedHeaders, rawBody,
                    200, "IGNORED_INCOMPLETE", null, null,
                    status, "Ignored incomplete payload", resBody, "missing_mandatory_fields",
                    elapsed, occurredAt));
            return new CallbackOutcome(true, false, null, "Ignored incomplete payload");
        }

        callbackLog.record(PROVIDER, "WEBHOOK", eventName, status, outletCode, payload, "ACCEPTED", null);

        String dedupeKey = (!txnId.isEmpty() ? txnId : externalRef) + ":" + status;
        String payloadJson = rawBody == null || rawBody.isBlank() ? "{}" : rawBody;
        List<Long> inserted = jdbc.queryForList("""
                INSERT INTO webhook_inbox (provider, external_event_id, payload, signature_valid, status)
                VALUES (?, ?, CAST(? AS jsonb), TRUE, 'RECEIVED')
                ON CONFLICT (provider, external_event_id) DO NOTHING
                RETURNING id
                """, Long.class, PROVIDER, dedupeKey, payloadJson);

        if (inserted.isEmpty()) {
            long elapsed = System.currentTimeMillis() - started;
            String resBody = "{\"success\":true}";
            dispatchWebhookAuditAsync(new EmeraldAuditLogService.WebhookTraceSnapshot(
                    callId, requestId, flowName, effectiveUrl, effectivePath,
                    outletCode, externalRef, txnId, copiedHeaders, rawBody,
                    200, "DUPLICATE_IGNORED", dedupeKey, null,
                    status, "Duplicate event ignored (" + dedupeKey + ")", resBody, null,
                    elapsed, occurredAt));
            return new CallbackOutcome(true, true, null, "Duplicate event ignored");
        }

        UUID matchedTxnId = dispatchPayload(payload);
        jdbc.update("""
                UPDATE webhook_inbox
                   SET status = 'PROCESSED',
                       transaction_id = ?,
                       processed_at = now()
                 WHERE id = ?
                """, matchedTxnId, inserted.get(0));

        long elapsed = System.currentTimeMillis() - started;
        String resBody = "{\"success\":true}";
        dispatchWebhookAuditAsync(new EmeraldAuditLogService.WebhookTraceSnapshot(
                callId, requestId, flowName, effectiveUrl, effectivePath,
                outletCode, externalRef, txnId, copiedHeaders, rawBody,
                200, "PROCESSED", dedupeKey, matchedTxnId,
                status, firstNonBlank(message, "Processed"), resBody, null,
                elapsed, occurredAt));

        return new CallbackOutcome(true, false, matchedTxnId, "Processed");
    }

    private void dispatchWebhookAuditAsync(EmeraldAuditLogService.WebhookTraceSnapshot snap) {
        if (auditLog != null) {
            auditLog.recordWebhookAsync(snap);
        } else {
            log.info("EMERALD_WEBHOOK callId={} flow={} outlet={} httpStatus={} outcome={} latencyMs={}",
                    snap.callId(), snap.flow(), firstNonBlank(snap.outletCode(), "-"),
                    snap.httpStatus(), snap.outcomeLabel(), snap.latencyMs());
        }
    }

    /**
     * Used by manual status verification (Step 6) to reconcile any stored callback payload
     * if a transaction is still pending.
     */
    @Transactional
    public UUID replayPayloadIdempotently(JsonNode payload) {
        if (payload == null || payload.isNull() || payload.isMissingNode()) {
            return null;
        }
        return dispatchPayload(payload);
    }

    private UUID dispatchPayload(JsonNode payload) {
        String productCode = text(payload, "productCode").toUpperCase(Locale.ROOT);
        String txnId = text(payload, "txnId");
        String externalRef = text(payload, "externalRef");
        String status = text(payload, "status").toUpperCase(Locale.ROOT);
        String message = text(payload, "message");
        JsonNode data = payload.path("data");

        String utr = firstNonBlank(
                text(data, "utr"),
                text(data, "rrn"),
                text(data, "bankRrn"),
                text(data, "npciTxnId"),
                txnId);

        return switch (productCode) {
            case "UPICASH" -> cashoutService.finalizeFromCallback(
                    externalRef,
                    txnId,
                    status,
                    message,
                    utr,
                    firstNonBlank(text(data, "payerVpa"), text(data.path("payer"), "vpa")),
                    firstNonBlank(text(data, "payerName"), text(data.path("payer"), "name")));
            case "AEPS" -> aepsService.finalizeFromCallback(
                    externalRef,
                    txnId,
                    status,
                    message,
                    utr,
                    parseDecimal(text(data, "availableBalance")),
                    data.has("miniStatement") && !data.get("miniStatement").isNull()
                            ? mapper.convertValue(data.get("miniStatement"), Object.class)
                            : null);
            case "PAYOUT" -> payoutService.finalizeFromCallback(
                    externalRef,
                    txnId,
                    status,
                    message,
                    utr);
            default -> {
                log.info("EMERALD_CALLBACK unhandled productCode={} ref={} txnId={}", productCode, externalRef, txnId);
                yield null;
            }
        };
    }

    private JsonNode parseSafe(String raw) {
        if (raw == null || raw.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private static BigDecimal parseDecimal(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return new BigDecimal(raw.trim()).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        JsonNode child = node.get(field);
        return (child == null || child.isNull()) ? "" : child.asText("").trim();
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return "";
    }
}

