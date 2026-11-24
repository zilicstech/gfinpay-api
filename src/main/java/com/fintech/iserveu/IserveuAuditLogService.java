package com.fintech.iserveu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fintech.emerald.EmeraldLogSanitizer;
import com.fintech.emerald.EmeraldTraceContext;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Append-only trace of outbound iServeU calls and inbound iServeU webhooks.
 * Pass keys, header secrets, and client secrets are never stored.
 */
@Service
public class IserveuAuditLogService {

    private static final Logger log = LoggerFactory.getLogger(IserveuAuditLogService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final IserveuProperties props;
    private final Executor auditExecutor;
    private final TransactionTemplate requiresNewTx;
    private final AtomicInteger pendingTasks = new AtomicInteger(0);
    private final Object idleMonitor = new Object();

    public IserveuAuditLogService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            IserveuProperties props,
            @Qualifier("iserveuAuditExecutor") Executor auditExecutor,
            PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.props = props;
        this.auditExecutor = auditExecutor != null ? auditExecutor : Runnable::run;
        if (txManager != null) {
            TransactionTemplate tt = new TransactionTemplate(txManager);
            tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.requiresNewTx = tt;
        } else {
            this.requiresNewTx = null;
        }
    }

    public void recordOutbound(
            String url,
            String headerSecretsCipher,
            String plainRequestJson,
            String wireRequestJson,
            Integer httpStatus,
            String rawResponse,
            String plainResponseJson,
            String errorMessage,
            long latencyMs) {
        String rail = railFromUrl(url);
        EmeraldTraceContext.Snapshot ctx = EmeraldTraceContext.current();
        String flow = ctx != null && ctx.flow() != null && !ctx.flow().isBlank()
                ? clip(ctx.flow() + " / " + rail, 64)
                : rail;
        UUID userId = ctx != null ? ctx.userId() : null;
        String contextRef = ctx != null ? ctx.externalRef() : null;
        submit(flow, () -> persistOutbound(
                url, rail, flow, userId, contextRef, headerSecretsCipher,
                plainRequestJson, wireRequestJson, httpStatus, rawResponse, plainResponseJson,
                errorMessage, latencyMs));
    }

    public void recordNotSent(String rail, String message, Object plainBody) {
        EmeraldTraceContext.Snapshot ctx = EmeraldTraceContext.current();
        String flow = ctx != null && ctx.flow() != null && !ctx.flow().isBlank()
                ? clip(ctx.flow() + " / " + rail, 64)
                : rail;
        UUID userId = ctx != null ? ctx.userId() : null;
        String contextRef = ctx != null ? ctx.externalRef() : null;
        String plain;
        try {
            plain = plainBody == null ? "" : mapper.writeValueAsString(plainBody);
        } catch (Exception e) {
            plain = "";
        }
        String safePlain = plain;
        submit(flow, () -> insert(
                "OUTBOUND",
                flow,
                "POST",
                "",
                "/" + rail.toLowerCase(Locale.ROOT),
                userId,
                refFromPlain(safePlain, contextRef),
                null,
                Map.of("Content-Type", "application/json", "pass_key", "[REDACTED]"),
                bodyDoc(safePlain, null),
                null,
                "NOT_SENT",
                clip(message, 2000),
                Map.of(),
                null,
                message,
                0L));
    }

    public void recordInbound(
            String path,
            String rawBody,
            int httpStatus,
            String ackJson,
            String outcome,
            String errorMessage,
            long latencyMs) {
        String flow = path != null && path.contains("payout") ? "WEBHOOK_PAYOUT" : "WEBHOOK_CASHPOINT";
        submit(flow, () -> persistInbound(path, flow, rawBody, httpStatus, ackJson, outcome, errorMessage, latencyMs));
    }

    public List<Map<String, Object>> listForUser(UUID userId, int limit) {
        int safeLimit = Math.max(1, Math.min(200, limit));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT l.id, l.call_id, l.request_id, l.direction, l.flow,
                       l.http_method, l.url, l.endpoint_path, l.user_id,
                       l.outlet_code, l.external_ref, l.provider_txn_id,
                       l.request_headers::text AS request_headers_json,
                       l.request_body,
                       l.http_status, l.provider_code, l.provider_message,
                       l.response_headers::text AS response_headers_json,
                       l.response_body, l.error_message, l.latency_ms, l.created_at
                  FROM iserveu_api_logs l
                 WHERE l.user_id = ?
                    OR l.external_ref IN (
                        SELECT s.external_ref FROM cashout_sessions s
                         WHERE s.agent_user_id = ? AND s.external_ref IS NOT NULL
                        UNION
                        SELECT p.external_ref FROM payout_transfers p
                         WHERE p.user_id = ? AND p.external_ref IS NOT NULL
                    )
                 ORDER BY l.created_at DESC
                 LIMIT ?
                """, userId, userId, userId, safeLimit);
        return rows.stream().map(this::toView).toList();
    }

    static String railFromUrl(String url) {
        String path = "";
        try {
            if (url != null && !url.isBlank()) {
                path = URI.create(url.trim()).getPath().toLowerCase(Locale.ROOT);
            }
        } catch (Exception ignored) {
            path = url == null ? "" : url.toLowerCase(Locale.ROOT);
        }
        if (path.contains("payout") && path.contains("status")) {
            return "PAYOUT_STATUS";
        }
        if (path.contains("payout")) {
            return "PAYOUT";
        }
        if (path.contains("status")) {
            return "CASHPOINT_STATUS";
        }
        return "CASHPOINT_QR";
    }

    static Map<String, String> outboundHeaders(String headerSecretsCipher) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("pass_key", "[REDACTED]");
        headers.put("header_secrets", clip(headerSecretsCipher, 8000));
        return headers;
    }

    private void persistOutbound(
            String url,
            String rail,
            String flow,
            UUID userId,
            String contextRef,
            String headerSecretsCipher,
            String plainRequestJson,
            String wireRequestJson,
            Integer httpStatus,
            String rawResponse,
            String plainResponseJson,
            String errorMessage,
            long latencyMs) {
        String safePlain = EmeraldLogSanitizer.sanitizeJsonOrRaw(mapper, plainRequestJson);
        String ref = refFromPlain(safePlain, contextRef);
        JsonNode plainRes = readTree(plainResponseJson);
        String txnId = txnFrom(plainRes);
        String providerCode = statusFrom(plainRes);
        String providerMessage = messageFrom(plainRes);
        Map<String, String> headers = outboundHeaders(headerSecretsCipher);
        log.info("ISERVEU_OUTBOUND flow={} url={} userId={} ref={} httpStatus={} latencyMs={} error={}",
                flow, url, userId, ref, httpStatus, latencyMs, errorMessage == null ? "-" : errorMessage);
        insert(
                "OUTBOUND",
                flow,
                "POST",
                url,
                endpointPath(url, rail),
                userId,
                ref,
                txnId,
                headers,
                bodyDoc(safePlain, wireRequestJson),
                httpStatus,
                providerCode,
                providerMessage,
                Map.of("Content-Type", "application/json"),
                bodyDoc(EmeraldLogSanitizer.sanitizeJsonOrRaw(mapper, plainResponseJson), rawResponse),
                errorMessage,
                latencyMs);
    }

    private void persistInbound(
            String path,
            String flow,
            String rawBody,
            int httpStatus,
            String ackJson,
            String outcome,
            String errorMessage,
            long latencyMs) {
        String plain = decryptWebhook(rawBody);
        String safePlain = EmeraldLogSanitizer.sanitizeJsonOrRaw(mapper, plain);
        JsonNode node = readTree(safePlain.isBlank() ? plain : safePlain);
        String ref = refFromPlain(safePlain, text(node, "clientRefId"));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        log.info("ISERVEU_WEBHOOK flow={} path={} ref={} outcome={} httpStatus={} latencyMs={}",
                flow, path, ref, outcome, httpStatus, latencyMs);
        insert(
                "INBOUND_WEBHOOK",
                flow,
                "POST",
                path,
                path,
                null,
                ref,
                txnFrom(node),
                headers,
                bodyDoc(safePlain, rawBody),
                httpStatus,
                firstNonBlank(statusFrom(node), outcome),
                messageFrom(node),
                Map.of("Content-Type", "application/json"),
                ackJson,
                errorMessage,
                latencyMs);
    }

    private String decryptWebhook(String rawBody) {
        if (rawBody == null || rawBody.isBlank() || props.encKey() == null || props.encKey().isBlank()) {
            return rawBody == null ? "" : rawBody;
        }
        try {
            JsonNode root = mapper.readTree(rawBody);
            String encrypted = root.path("ResponseData").asText("");
            if (encrypted.isBlank()) {
                return rawBody;
            }
            return IserveuCrypto.decrypt(encrypted, IserveuCrypto.key(props.encKey()));
        } catch (Exception e) {
            return rawBody;
        }
    }

    private void insert(
            String direction,
            String flow,
            String method,
            String url,
            String path,
            UUID userId,
            String externalRef,
            String providerTxnId,
            Map<String, String> requestHeaders,
            String requestBody,
            Integer httpStatus,
            String providerCode,
            String providerMessage,
            Map<String, String> responseHeaders,
            String responseBody,
            String errorMessage,
            long latencyMs) {
        try {
            UUID resolved = userId != null ? userId : resolveUserId(externalRef, providerTxnId);
            String callId = String.format("isu_%x_%04x", System.currentTimeMillis(), ThreadLocalRandom.current().nextInt(0x10000));
            String requestId = MDC.get("requestId");
            Runnable write = () -> jdbc.update("""
                    INSERT INTO iserveu_api_logs (
                        call_id, request_id, direction, flow, http_method, url, endpoint_path,
                        user_id, outlet_code, external_ref, provider_txn_id,
                        request_headers, request_body,
                        http_status, provider_code, provider_message,
                        response_headers, response_body,
                        error_message, latency_ms, created_at
                    ) VALUES (
                        ?, ?, ?, ?, ?, ?, ?,
                        ?, NULL, ?, ?,
                        CAST(? AS jsonb), ?,
                        ?, ?, ?,
                        CAST(? AS jsonb), ?,
                        ?, ?, ?
                    )
                    """,
                    callId,
                    blankToNull(requestId),
                    direction,
                    flow,
                    method,
                    url == null ? "" : url,
                    clip(path, 255),
                    resolved,
                    blankToNull(externalRef),
                    blankToNull(providerTxnId),
                    toJson(requestHeaders),
                    requestBody,
                    httpStatus,
                    blankToNull(clip(providerCode, 64)),
                    clip(providerMessage, 2000),
                    toJson(responseHeaders),
                    responseBody,
                    clip(errorMessage, 2000),
                    Math.max(0L, latencyMs),
                    Timestamp.from(Instant.now()));
            if (requiresNewTx != null) {
                requiresNewTx.executeWithoutResult(status -> write.run());
            } else {
                write.run();
            }
        } catch (Exception ex) {
            log.warn("ISERVEU_AUDIT_PERSIST_SKIPPED flow={} err={}", flow, ex.getMessage());
        }
    }

    private UUID resolveUserId(String externalRef, String providerTxnId) {
        String ref = firstNonBlank(externalRef, providerTxnId);
        if (ref == null) {
            return null;
        }
        List<UUID> cashout = jdbc.queryForList(
                "SELECT agent_user_id FROM cashout_sessions WHERE external_ref = ? OR provider_txn_id = ? LIMIT 1",
                UUID.class, ref, ref);
        if (!cashout.isEmpty()) {
            return cashout.get(0);
        }
        List<UUID> payout = jdbc.queryForList(
                "SELECT user_id FROM payout_transfers WHERE external_ref = ? OR provider_txn_id = ? LIMIT 1",
                UUID.class, ref, ref);
        return payout.isEmpty() ? null : payout.get(0);
    }

    private void submit(String flow, Runnable work) {
        pendingTasks.incrementAndGet();
        try {
            auditExecutor.execute(() -> {
                try {
                    work.run();
                } catch (Throwable t) {
                    log.warn("ISERVEU_AUDIT_ASYNC_FAILED flow={} err={}", flow, t.getMessage());
                } finally {
                    if (pendingTasks.decrementAndGet() == 0) {
                        synchronized (idleMonitor) {
                            idleMonitor.notifyAll();
                        }
                    }
                }
            });
        } catch (RuntimeException rex) {
            if (pendingTasks.decrementAndGet() == 0) {
                synchronized (idleMonitor) {
                    idleMonitor.notifyAll();
                }
            }
            log.warn("ISERVEU_AUDIT_ASYNC_SKIPPED flow={} err={}", flow, rex.getMessage());
        }
    }

    private String bodyDoc(String plainJson, String wireJson) {
        ObjectNode doc = mapper.createObjectNode();
        doc.set("plain", jsonOrText(plainJson));
        if (wireJson != null && !wireJson.isBlank()) {
            doc.set("wire", jsonOrText(clip(wireJson, 32000)));
        }
        try {
            return mapper.writeValueAsString(doc);
        } catch (Exception e) {
            return plainJson == null ? "" : plainJson;
        }
    }

    private JsonNode jsonOrText(String raw) {
        if (raw == null || raw.isBlank()) {
            return mapper.nullNode();
        }
        JsonNode parsed = readTree(raw);
        if (parsed != null && !parsed.isMissingNode()) {
            return parsed;
        }
        return mapper.getNodeFactory().textNode(raw);
    }

    private JsonNode readTree(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private String refFromPlain(String plainJson, String fallback) {
        JsonNode node = readTree(plainJson);
        String found = firstNonBlank(text(node, "clientRefId"), text(node, "clientRef"), text(node, "$6"), text(node, "externalRef"));
        return firstNonBlank(found, fallback);
    }

    private static String txnFrom(JsonNode node) {
        if (node == null) {
            return null;
        }
        String direct = firstNonBlank(text(node, "txnId"), text(node, "transactionId"));
        if (direct != null) {
            return direct;
        }
        JsonNode results = node.path("results");
        if (results.isArray() && !results.isEmpty()) {
            return firstNonBlank(text(results.get(0), "transactionId"), text(results.get(0), "txnId"));
        }
        return null;
    }

    private static String statusFrom(JsonNode node) {
        if (node == null) {
            return null;
        }
        String status = firstNonBlank(text(node, "status"), text(node, "paymentState"));
        if (status != null) {
            return status;
        }
        if (node.has("statusCode") && !node.path("statusCode").isNull()) {
            return node.path("statusCode").asText();
        }
        JsonNode results = node.path("results");
        if (results.isArray() && !results.isEmpty()) {
            return text(results.get(0), "status");
        }
        return null;
    }

    private static String messageFrom(JsonNode node) {
        if (node == null) {
            return null;
        }
        String direct = firstNonBlank(text(node, "statusDesc"), text(node, "message"));
        if (direct != null) {
            return direct;
        }
        JsonNode results = node.path("results");
        if (results.isArray() && !results.isEmpty()) {
            return text(results.get(0), "statusDesc");
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || field == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText("").trim();
        return text.isEmpty() ? null : text;
    }

    private String endpointPath(String url, String rail) {
        try {
            if (url != null && !url.isBlank()) {
                String path = URI.create(url.trim()).getPath();
                if (path != null && !path.isBlank()) {
                    return path;
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "/" + rail.toLowerCase(Locale.ROOT);
    }

    private String toJson(Map<String, String> headers) {
        try {
            return mapper.writeValueAsString(headers == null ? Map.of() : headers);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> toView(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.get("id"));
        view.put("callId", row.get("call_id"));
        view.put("requestId", row.get("request_id"));
        view.put("direction", row.get("direction"));
        view.put("flow", row.get("flow"));
        view.put("httpMethod", row.get("http_method"));
        view.put("url", row.get("url"));
        view.put("endpointPath", row.get("endpoint_path"));
        view.put("userId", row.get("user_id"));
        view.put("outletCode", row.get("outlet_code"));
        view.put("externalRef", row.get("external_ref"));
        view.put("providerTxnId", row.get("provider_txn_id"));
        view.put("requestHeaders", parseMap(str(row.get("request_headers_json"))));
        view.put("requestBody", row.get("request_body"));
        view.put("httpStatus", row.get("http_status"));
        view.put("providerCode", row.get("provider_code"));
        view.put("providerMessage", row.get("provider_message"));
        view.put("responseHeaders", parseMap(str(row.get("response_headers_json"))));
        view.put("responseBody", row.get("response_body"));
        view.put("errorMessage", row.get("error_message"));
        view.put("latencyMs", row.get("latency_ms"));
        view.put("createdAt", row.get("created_at"));
        return view;
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
