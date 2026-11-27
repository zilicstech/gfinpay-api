package com.fintech.instantpay;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Append-only async audit logger for outbound InstantPay API calls and inbound InstantPay webhooks.
 * Client secrets, encrypted/raw Aadhaar, OTPs, and RD biometric PID blocks are strictly redacted.
 */
@Service
public class InstantPayAuditLogService {

    private static final Logger log = LoggerFactory.getLogger(InstantPayAuditLogService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final Set<String> REDACTED_BODY_KEYS = Set.of(
            "aadhaar",
            "encryptedaadhaar",
            "piddata",
            "sessionkey",
            "hmac",
            "mc",
            "otp",
            "clientsecret"
    );

    private static final Set<String> MASKED_LAST4_KEYS = Set.of(
            "bankaccountno",
            "accountnumber",
            "pan"
    );

    public record OutboundSnapshot(
            String callId,
            String requestId,
            String flow,
            String httpMethod,
            String fullUrl,
            String endpointPath,
            UUID userId,
            String outletCode,
            String externalRef,
            String providerTxnId,
            Map<String, String> rawRequestHeaders,
            String rawRequestBody,
            Integer httpStatus,
            boolean upstreamFailed,
            String providerCode,
            String providerMessage,
            Map<String, String> rawResponseHeaders,
            String rawResponseBody,
            String errorMessage,
            long latencyMs,
            Instant occurredAt
    ) {}

    public record WebhookSnapshot(
            String callId,
            String requestId,
            String flow,
            String httpMethod,
            String url,
            String endpointPath,
            String outletCode,
            String externalRef,
            String providerTxnId,
            Map<String, String> rawRequestHeaders,
            String rawRequestBody,
            int httpStatus,
            String providerCode,
            String providerMessage,
            String responseBody,
            String errorMessage,
            long latencyMs,
            Instant occurredAt
    ) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Executor auditExecutor;
    private final TransactionTemplate requiresNewTx;
    private final AtomicInteger pendingTasks = new AtomicInteger(0);
    private final Object idleMonitor = new Object();

    @Autowired
    public InstantPayAuditLogService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            @Qualifier("instantPayAuditExecutor") Executor auditExecutor,
            PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.auditExecutor = auditExecutor != null ? auditExecutor : Runnable::run;
        if (txManager != null) {
            TransactionTemplate tt = new TransactionTemplate(txManager);
            tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.requiresNewTx = tt;
        } else {
            this.requiresNewTx = null;
        }
    }

    public InstantPayAuditLogService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this(jdbc, mapper, Runnable::run, null);
    }

    public void recordOutboundAsync(OutboundSnapshot snap) {
        if (snap == null) {
            return;
        }
        submitAsync(snap.callId(), () -> withRequestIdMdc(snap.requestId(), () -> {
            Map<String, String> safeReqHeaders = sanitizeHeaders(snap.rawRequestHeaders());
            String safeReqBody = sanitizeBody(snap.rawRequestBody());
            Map<String, String> safeResHeaders = sanitizeHeaders(snap.rawResponseHeaders());
            String safeResBody = sanitizeBody(snap.rawResponseBody());

            log.info("INSTANTPAY_OUTBOUND_REQUEST callId={} reqId={} flow={} method={} url={} userId={} outlet={} externalRef={} headers={} body={}",
                    snap.callId(),
                    nonBlank(snap.requestId(), "-"),
                    snap.flow(),
                    snap.httpMethod(),
                    snap.fullUrl(),
                    snap.userId() != null ? snap.userId() : "-",
                    nonBlank(snap.outletCode(), "-"),
                    nonBlank(snap.externalRef(), "-"),
                    toJsonString(safeReqHeaders),
                    safeReqBody.isEmpty() ? "-" : safeReqBody);

            if (snap.errorMessage() != null && snap.httpStatus() == null) {
                log.error("INSTANTPAY_OUTBOUND_ERROR callId={} flow={} url={} latencyMs={} error={}",
                        snap.callId(), snap.flow(), snap.fullUrl(), snap.latencyMs(), snap.errorMessage());
            } else if ((snap.httpStatus() != null && snap.httpStatus() >= 400) || snap.upstreamFailed()) {
                log.warn("INSTANTPAY_OUTBOUND_RESPONSE callId={} flow={} url={} outlet={} externalRef={} providerTxnId={} httpStatus={} code={} latencyMs={} body={}",
                        snap.callId(), snap.flow(), snap.fullUrl(), nonBlank(snap.outletCode(), "-"),
                        nonBlank(snap.externalRef(), "-"), nonBlank(snap.providerTxnId(), "-"),
                        snap.httpStatus(), nonBlank(snap.providerCode(), "-"), snap.latencyMs(), safeResBody);
            } else {
                log.info("INSTANTPAY_OUTBOUND_RESPONSE callId={} flow={} url={} outlet={} externalRef={} providerTxnId={} httpStatus={} code={} latencyMs={} body={}",
                        snap.callId(), snap.flow(), snap.fullUrl(), nonBlank(snap.outletCode(), "-"),
                        nonBlank(snap.externalRef(), "-"), nonBlank(snap.providerTxnId(), "-"),
                        snap.httpStatus(), nonBlank(snap.providerCode(), "-"), snap.latencyMs(), safeResBody);
            }

            insertRow(
                    snap.callId(),
                    snap.requestId(),
                    "OUTBOUND",
                    snap.flow(),
                    snap.httpMethod(),
                    snap.fullUrl(),
                    snap.endpointPath(),
                    snap.userId(),
                    snap.outletCode(),
                    snap.externalRef(),
                    snap.providerTxnId(),
                    safeReqHeaders,
                    safeReqBody,
                    snap.httpStatus(),
                    snap.providerCode(),
                    snap.providerMessage(),
                    safeResHeaders,
                    safeResBody.isEmpty() ? null : safeResBody,
                    snap.errorMessage(),
                    snap.latencyMs(),
                    snap.occurredAt());
        }));
    }

    public void recordWebhookAsync(WebhookSnapshot snap) {
        if (snap == null) {
            return;
        }
        submitAsync(snap.callId(), () -> withRequestIdMdc(snap.requestId(), () -> {
            Map<String, String> safeHeaders = sanitizeHeaders(snap.rawRequestHeaders());
            String safeBody = sanitizeBody(snap.rawRequestBody());
            String maskedPath = maskWebhookSecretPath(snap.endpointPath());
            String maskedUrl = maskWebhookSecretPath(snap.url());

            log.info("INSTANTPAY_WEBHOOK callId={} flow={} method={} path={} externalRef={} ipayId={} httpStatus={} code={} latencyMs={}",
                    snap.callId(), snap.flow(), snap.httpMethod(), maskedPath,
                    nonBlank(snap.externalRef(), "-"), nonBlank(snap.providerTxnId(), "-"),
                    snap.httpStatus(), nonBlank(snap.providerCode(), "-"), snap.latencyMs());

            insertRow(
                    snap.callId(),
                    snap.requestId(),
                    "INBOUND_WEBHOOK",
                    snap.flow(),
                    snap.httpMethod(),
                    maskedUrl,
                    maskedPath,
                    null,
                    snap.outletCode(),
                    snap.externalRef(),
                    snap.providerTxnId(),
                    safeHeaders,
                    safeBody,
                    snap.httpStatus(),
                    snap.providerCode(),
                    snap.providerMessage(),
                    Map.of("Content-Type", "application/json"),
                    snap.responseBody(),
                    snap.errorMessage(),
                    snap.latencyMs(),
                    snap.occurredAt());
        }));
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
                  FROM instantpay_api_logs l
                 WHERE l.user_id = ?
                    OR (l.outlet_code IS NOT NULL AND l.outlet_code IN (
                        SELECT m.outlet_code FROM provider_merchant_accounts m
                         WHERE m.user_id = ? AND m.provider_code = 'INSTANTPAY' AND m.outlet_code IS NOT NULL
                    ))
                    OR (l.external_ref IS NOT NULL AND l.external_ref IN (
                        SELECT s.external_ref FROM cashout_sessions s
                         WHERE s.agent_user_id = ? AND s.external_ref IS NOT NULL
                        UNION
                        SELECT p.external_ref FROM payout_transfers p
                         WHERE p.user_id = ? AND p.external_ref IS NOT NULL
                        UNION
                        SELECT a.external_ref FROM aeps_transactions a
                         WHERE a.user_id = ? AND a.external_ref IS NOT NULL
                    ))
                 ORDER BY l.created_at DESC
                 LIMIT ?
                """, userId, userId, userId, userId, userId, safeLimit);
        return rows.stream().map(this::toView).toList();
    }

    public List<Map<String, Object>> listGlobal(
            String direction,
            String flow,
            String outletCode,
            String externalRef,
            int limit) {
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
                  FROM instantpay_api_logs l
                 WHERE (? IS NULL OR l.direction = ?)
                   AND (? IS NULL OR l.flow = ?)
                   AND (? IS NULL OR l.outlet_code = ?)
                   AND (? IS NULL OR l.external_ref = ?)
                 ORDER BY l.created_at DESC
                 LIMIT ?
                """,
                blankToNull(direction), blankToNull(direction),
                blankToNull(flow), blankToNull(flow),
                blankToNull(outletCode), blankToNull(outletCode),
                blankToNull(externalRef), blankToNull(externalRef),
                safeLimit);
        return rows.stream().map(this::toView).toList();
    }

    public void awaitPending(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (idleMonitor) {
            while (pendingTasks.get() > 0) {
                long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (remainingMs <= 0) {
                    break;
                }
                try {
                    idleMonitor.wait(Math.min(remainingMs, 50L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    static Map<String, String> sanitizeHeaders(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> safe = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String k = e.getKey();
            String v = e.getValue() == null ? "" : e.getValue();
            if (k == null) continue;
            String lower = k.toLowerCase(Locale.ROOT);
            if (lower.contains("secret") || lower.contains("authorization") || lower.contains("cookie") || lower.contains("token")) {
                safe.put(k, v.length() > 4 ? "****" + v.substring(v.length() - 4) : "[REDACTED]");
            } else {
                safe.put(k, v);
            }
        }
        return safe;
    }

    String sanitizeBody(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.trim();
        try {
            JsonNode root = mapper.readTree(trimmed);
            sanitizeJsonNode(root);
            return mapper.writeValueAsString(root);
        } catch (Exception ignored) {
            return clip(trimmed, 8000);
        }
    }

    private void sanitizeJsonNode(JsonNode node) {
        if (node == null) return;
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            Iterator<String> names = obj.fieldNames();
            List<String> keys = new java.util.ArrayList<>();
            while (names.hasNext()) {
                keys.add(names.next());
            }
            for (String key : keys) {
                String norm = key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
                JsonNode child = obj.get(key);
                if (REDACTED_BODY_KEYS.contains(norm)) {
                    obj.put(key, "[REDACTED]");
                } else if (MASKED_LAST4_KEYS.contains(norm) && child != null && child.isTextual()) {
                    String val = child.asText("");
                    obj.put(key, val.length() > 4 ? "••••" + val.substring(val.length() - 4) : "••••");
                } else {
                    sanitizeJsonNode(child);
                }
            }
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                sanitizeJsonNode(arr.get(i));
            }
        }
    }

    private static String maskWebhookSecretPath(String pathOrUrl) {
        if (pathOrUrl == null || pathOrUrl.isBlank()) {
            return "/webhooks/instantpay/callback";
        }
        return pathOrUrl.replaceAll("(/webhooks/instantpay/callback/)[^?/#]+", "$1[REDACTED]");
    }

    private void insertRow(
            String callId,
            String requestId,
            String direction,
            String flow,
            String method,
            String url,
            String endpointPath,
            UUID userId,
            String outletCode,
            String externalRef,
            String providerTxnId,
            Map<String, String> reqHeaders,
            String reqBody,
            Integer httpStatus,
            String providerCode,
            String providerMessage,
            Map<String, String> resHeaders,
            String resBody,
            String errorMessage,
            long latencyMs,
            Instant occurredAt) {
        Runnable write = () -> jdbc.update("""
                INSERT INTO instantpay_api_logs
                    (id, call_id, request_id, direction, flow, http_method, url, endpoint_path,
                     user_id, outlet_code, external_ref, provider_txn_id,
                     request_headers, request_body, http_status, provider_code, provider_message,
                     response_headers, response_body, error_message, latency_ms, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                clip(nonBlank(callId, "ipay_" + System.currentTimeMillis()), 64),
                blankToNull(clip(requestId, 64)),
                clip(nonBlank(direction, "OUTBOUND"), 24),
                clip(nonBlank(flow, "API"), 64),
                clip(nonBlank(method, "POST"), 12),
                nonBlank(url, "/"),
                clip(nonBlank(endpointPath, "/"), 255),
                userId,
                blankToNull(clip(outletCode, 64)),
                blankToNull(clip(externalRef, 128)),
                blankToNull(clip(providerTxnId, 128)),
                toJsonString(reqHeaders),
                blankToNull(reqBody),
                httpStatus,
                blankToNull(clip(providerCode, 64)),
                blankToNull(clip(providerMessage, 2000)),
                toJsonString(resHeaders),
                blankToNull(resBody),
                blankToNull(clip(errorMessage, 2000)),
                Math.max(0L, latencyMs),
                Timestamp.from(occurredAt != null ? occurredAt : Instant.now()));

        try {
            if (requiresNewTx != null) {
                requiresNewTx.executeWithoutResult(status -> write.run());
            } else {
                write.run();
            }
        } catch (Exception ex) {
            log.warn("INSTANTPAY audit DB write skipped callId={}: {}", callId, ex.getMessage());
        }
    }

    private void submitAsync(String callId, Runnable task) {
        pendingTasks.incrementAndGet();
        try {
            auditExecutor.execute(() -> {
                try {
                    task.run();
                } catch (Exception ex) {
                    log.warn("INSTANTPAY async audit task failed callId={}: {}", callId, ex.getMessage());
                } finally {
                    if (pendingTasks.decrementAndGet() == 0) {
                        synchronized (idleMonitor) {
                            idleMonitor.notifyAll();
                        }
                    }
                }
            });
        } catch (RuntimeException ex) {
            if (pendingTasks.decrementAndGet() == 0) {
                synchronized (idleMonitor) {
                    idleMonitor.notifyAll();
                }
            }
            log.warn("INSTANTPAY async audit executor rejected callId={}: {}", callId, ex.getMessage());
        }
    }

    private static void withRequestIdMdc(String requestId, Runnable runnable) {
        String prev = MDC.get("requestId");
        try {
            if (requestId != null && !requestId.isBlank()) {
                MDC.put("requestId", requestId);
            }
            runnable.run();
        } finally {
            if (prev != null) {
                MDC.put("requestId", prev);
            } else {
                MDC.remove("requestId");
            }
        }
    }

    private Map<String, Object> toView(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.get("id"));
        out.put("callId", row.get("call_id"));
        out.put("requestId", row.get("request_id"));
        out.put("direction", row.get("direction"));
        out.put("flow", row.get("flow"));
        out.put("httpMethod", row.get("http_method"));
        out.put("url", row.get("url"));
        out.put("endpointPath", row.get("endpoint_path"));
        out.put("userId", row.get("user_id"));
        out.put("outletCode", row.get("outlet_code"));
        out.put("externalRef", row.get("external_ref"));
        out.put("providerTxnId", row.get("provider_txn_id"));
        out.put("requestHeaders", parseJsonMap(row.get("request_headers_json")));
        out.put("requestBody", row.get("request_body"));
        out.put("httpStatus", row.get("http_status"));
        out.put("providerCode", row.get("provider_code"));
        out.put("providerMessage", row.get("provider_message"));
        out.put("responseHeaders", parseJsonMap(row.get("response_headers_json")));
        out.put("responseBody", row.get("response_body"));
        out.put("errorMessage", row.get("error_message"));
        out.put("latencyMs", row.get("latency_ms"));
        out.put("createdAt", row.get("created_at"));
        out.put("provider", "InstantPay");
        return out;
    }

    private Map<String, Object> parseJsonMap(Object raw) {
        if (raw == null) return Map.of();
        String s = String.valueOf(raw).trim();
        if (s.isEmpty() || "null".equals(s)) return Map.of();
        try {
            return mapper.readValue(s, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String toJsonString(Map<String, String> map) {
        if (map == null || map.isEmpty()) return "{}";
        try {
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String clip(String val, int max) {
        if (val == null) return null;
        String t = val.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static String blankToNull(String val) {
        return (val == null || val.isBlank()) ? null : val.trim();
    }

    private static String nonBlank(String val, String fallback) {
        return (val == null || val.isBlank()) ? fallback : val.trim();
    }
}
