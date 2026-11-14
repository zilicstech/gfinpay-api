package com.fintech.emerald;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Asynchronous append-only persistence, payload sanitization, and Admin query service
 * for all outbound Emerald API calls and inbound Emerald webhooks.
 *
 * <p>All sanitization, SLF4J trace logging, and {@code emerald_api_logs} DB writes execute on
 * {@code emeraldAuditExecutor} using {@link TransactionDefinition#PROPAGATION_REQUIRES_NEW} so
 * synchronous API/webhook flows experience zero blocking or latency overhead, and failed/rolled-back
 * business transactions still preserve the exact upstream HTTP trace.
 */
@Service
public class EmeraldAuditLogService {

    private static final Logger log = LoggerFactory.getLogger(EmeraldAuditLogService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    public record Entry(
            String callId,
            String requestId,
            String direction,
            String flow,
            String httpMethod,
            String url,
            String endpointPath,
            UUID userId,
            String outletCode,
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
            long latencyMs,
            Instant occurredAt
    ) {
        public Entry(
                String callId,
                String requestId,
                String direction,
                String flow,
                String httpMethod,
                String url,
                String endpointPath,
                UUID userId,
                String outletCode,
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
            this(
                    callId, requestId, direction, flow, httpMethod, url, endpointPath,
                    userId, outletCode, externalRef, providerTxnId,
                    requestHeaders, requestBody, httpStatus, providerCode, providerMessage,
                    responseHeaders, responseBody, errorMessage, latencyMs, Instant.now());
        }
    }

    public record OutboundTraceSnapshot(
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
            boolean upstreamSuccessFlagFalse,
            String providerCode,
            String providerMessage,
            Map<String, String> rawResponseHeaders,
            String rawResponseBody,
            String errorMessage,
            long latencyMs,
            Instant occurredAt
    ) {}

    public record WebhookTraceSnapshot(
            String callId,
            String requestId,
            String flow,
            String url,
            String endpointPath,
            String outletCode,
            String externalRef,
            String providerTxnId,
            Map<String, String> rawRequestHeaders,
            String rawRequestBody,
            int httpStatus,
            String outcomeLabel,
            String dedupeKey,
            UUID matchedTxnId,
            String providerStatus,
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
    public EmeraldAuditLogService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            @Qualifier("emeraldAuditExecutor") Executor auditExecutor,
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

    public EmeraldAuditLogService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this(jdbc, mapper, Runnable::run, null);
    }

    /**
     * Asynchronously sanitizes headers/body, writes structured SLF4J request/response trace logs,
     * and inserts the audit record into {@code emerald_api_logs}. Returns immediately without blocking.
     */
    public void recordOutboundAsync(OutboundTraceSnapshot snap) {
        if (snap == null) {
            return;
        }
        submitAsync(snap.callId(), () -> {
            withRequestIdMdc(snap.requestId(), () -> {
                Map<String, String> safeReqHeaders = EmeraldLogSanitizer.sanitizeHeaders(snap.rawRequestHeaders());
                String sanitizedReqBody = (snap.rawRequestBody() == null || snap.rawRequestBody().isEmpty())
                        ? ""
                        : EmeraldLogSanitizer.sanitizeJsonOrRaw(mapper, snap.rawRequestBody());

                log.info("EMERALD_OUTBOUND_REQUEST callId={} reqId={} flow={} method={} url={} userId={} outlet={} externalRef={} headers={} body={}",
                        snap.callId(),
                        nonBlank(snap.requestId(), "-"),
                        snap.flow(),
                        snap.httpMethod(),
                        snap.fullUrl(),
                        snap.userId() != null ? snap.userId() : "-",
                        nonBlank(snap.outletCode(), "-"),
                        nonBlank(snap.externalRef(), "-"),
                        toJsonObjectString(safeReqHeaders),
                        sanitizedReqBody.isEmpty() ? "-" : sanitizedReqBody);

                if (snap.errorMessage() != null && snap.httpStatus() == null) {
                    log.error("EMERALD_OUTBOUND_ERROR callId={} reqId={} flow={} method={} url={} userId={} outlet={} externalRef={} latencyMs={} headers={} reqBody={} error={}",
                            snap.callId(),
                            nonBlank(snap.requestId(), "-"),
                            snap.flow(),
                            snap.httpMethod(),
                            snap.fullUrl(),
                            snap.userId() != null ? snap.userId() : "-",
                            nonBlank(snap.outletCode(), "-"),
                            nonBlank(snap.externalRef(), "-"),
                            snap.latencyMs(),
                            toJsonObjectString(safeReqHeaders),
                            sanitizedReqBody.isEmpty() ? "-" : sanitizedReqBody,
                            snap.errorMessage());

                    persistEntrySync(new Entry(
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
                            sanitizedReqBody,
                            null,
                            snap.providerCode(),
                            snap.providerMessage(),
                            Map.of(),
                            null,
                            snap.errorMessage(),
                            snap.latencyMs(),
                            snap.occurredAt()));
                    return;
                }

                Map<String, String> safeResHeaders = EmeraldLogSanitizer.sanitizeHeaders(snap.rawResponseHeaders());
                String sanitizedResBody = EmeraldLogSanitizer.sanitizeJsonOrRaw(mapper, snap.rawResponseBody());
                int status = snap.httpStatus() != null ? snap.httpStatus() : 0;

                if (status >= 400 || snap.upstreamSuccessFlagFalse()) {
                    log.warn("EMERALD_OUTBOUND_RESPONSE callId={} reqId={} flow={} method={} url={} userId={} outlet={} externalRef={} providerTxnId={} httpStatus={} providerCode={} latencyMs={} headers={} body={}",
                            snap.callId(),
                            nonBlank(snap.requestId(), "-"),
                            snap.flow(),
                            snap.httpMethod(),
                            snap.fullUrl(),
                            snap.userId() != null ? snap.userId() : "-",
                            nonBlank(snap.outletCode(), "-"),
                            nonBlank(snap.externalRef(), "-"),
                            nonBlank(snap.providerTxnId(), "-"),
                            status,
                            nonBlank(snap.providerCode(), "-"),
                            snap.latencyMs(),
                            toJsonObjectString(safeResHeaders),
                            sanitizedResBody.isEmpty() ? "<empty>" : sanitizedResBody);
                } else {
                    log.info("EMERALD_OUTBOUND_RESPONSE callId={} reqId={} flow={} method={} url={} userId={} outlet={} externalRef={} providerTxnId={} httpStatus={} providerCode={} latencyMs={} headers={} body={}",
                            snap.callId(),
                            nonBlank(snap.requestId(), "-"),
                            snap.flow(),
                            snap.httpMethod(),
                            snap.fullUrl(),
                            snap.userId() != null ? snap.userId() : "-",
                            nonBlank(snap.outletCode(), "-"),
                            nonBlank(snap.externalRef(), "-"),
                            nonBlank(snap.providerTxnId(), "-"),
                            status,
                            nonBlank(snap.providerCode(), "-"),
                            snap.latencyMs(),
                            toJsonObjectString(safeResHeaders),
                            sanitizedResBody.isEmpty() ? "<empty>" : sanitizedResBody);
                }

                persistEntrySync(new Entry(
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
                        sanitizedReqBody,
                        status,
                        snap.providerCode(),
                        snap.providerMessage(),
                        safeResHeaders,
                        sanitizedResBody,
                        null,
                        snap.latencyMs(),
                        snap.occurredAt()));
            });
        });
    }

    /**
     * Asynchronously sanitizes inbound webhook headers/body, writes structured SLF4J webhook logs,
     * and inserts the audit record into {@code emerald_api_logs}. Returns immediately without blocking.
     */
    public void recordWebhookAsync(WebhookTraceSnapshot snap) {
        if (snap == null) {
            return;
        }
        submitAsync(snap.callId(), () -> {
            withRequestIdMdc(snap.requestId(), () -> {
                Map<String, String> safeHeaders = EmeraldLogSanitizer.sanitizeHeaders(snap.rawRequestHeaders());
                String sanitizedReqBody = EmeraldLogSanitizer.sanitizeJsonOrRaw(mapper, snap.rawRequestBody());

                log.info("EMERALD_WEBHOOK_REQUEST callId={} reqId={} flow={} path={} outlet={} externalRef={} providerTxnId={} status={} headers={} body={}",
                        snap.callId(),
                        nonBlank(snap.requestId(), "-"),
                        snap.flow(),
                        snap.endpointPath(),
                        nonBlank(snap.outletCode(), "-"),
                        nonBlank(snap.externalRef(), "-"),
                        nonBlank(snap.providerTxnId(), "-"),
                        nonBlank(snap.providerStatus(), "-"),
                        toJsonObjectString(safeHeaders),
                        sanitizedReqBody.isEmpty() ? "<empty>" : sanitizedReqBody);

                if (snap.httpStatus() >= 400 || snap.errorMessage() != null) {
                    log.warn("EMERALD_WEBHOOK_RESPONSE callId={} reqId={} flow={} path={} outlet={} externalRef={} providerTxnId={} httpStatus={} outcome={} latencyMs={} body={}",
                            snap.callId(),
                            nonBlank(snap.requestId(), "-"),
                            snap.flow(),
                            snap.endpointPath(),
                            nonBlank(snap.outletCode(), "-"),
                            nonBlank(snap.externalRef(), "-"),
                            nonBlank(snap.providerTxnId(), "-"),
                            snap.httpStatus(),
                            nonBlank(snap.outcomeLabel(), "REJECTED"),
                            snap.latencyMs(),
                            nonBlank(snap.responseBody(), "{}"));
                } else if ("DUPLICATE_IGNORED".equals(snap.outcomeLabel())) {
                    log.info("EMERALD_WEBHOOK_RESPONSE callId={} reqId={} flow={} path={} outlet={} externalRef={} providerTxnId={} httpStatus={} outcome=DUPLICATE_IGNORED key={} latencyMs={} body={}",
                            snap.callId(),
                            nonBlank(snap.requestId(), "-"),
                            snap.flow(),
                            snap.endpointPath(),
                            nonBlank(snap.outletCode(), "-"),
                            nonBlank(snap.externalRef(), "-"),
                            nonBlank(snap.providerTxnId(), "-"),
                            snap.httpStatus(),
                            nonBlank(snap.dedupeKey(), "-"),
                            snap.latencyMs(),
                            nonBlank(snap.responseBody(), "{}"));
                } else {
                    log.info("EMERALD_WEBHOOK_RESPONSE callId={} reqId={} flow={} path={} outlet={} externalRef={} providerTxnId={} matchedTxnId={} httpStatus={} outcome={} latencyMs={} body={}",
                            snap.callId(),
                            nonBlank(snap.requestId(), "-"),
                            snap.flow(),
                            snap.endpointPath(),
                            nonBlank(snap.outletCode(), "-"),
                            nonBlank(snap.externalRef(), "-"),
                            nonBlank(snap.providerTxnId(), "-"),
                            snap.matchedTxnId() != null ? snap.matchedTxnId() : "-",
                            snap.httpStatus(),
                            nonBlank(snap.outcomeLabel(), "PROCESSED"),
                            snap.latencyMs(),
                            nonBlank(snap.responseBody(), "{}"));
                }

                persistEntrySync(new Entry(
                        snap.callId(),
                        snap.requestId(),
                        "INBOUND_WEBHOOK",
                        snap.flow(),
                        "POST",
                        snap.url(),
                        snap.endpointPath(),
                        null,
                        snap.outletCode(),
                        snap.externalRef(),
                        snap.providerTxnId(),
                        safeHeaders,
                        sanitizedReqBody,
                        snap.httpStatus(),
                        snap.providerStatus(),
                        snap.providerMessage(),
                        Map.of("Content-Type", "application/json"),
                        snap.responseBody(),
                        snap.errorMessage(),
                        snap.latencyMs(),
                        snap.occurredAt()));
            });
        });
    }

    /**
     * Asynchronously persists a pre-built {@link Entry} on {@code emeraldAuditExecutor}.
     */
    public void record(Entry e) {
        if (e == null) {
            return;
        }
        submitAsync(e.callId(), () -> persistEntrySync(e));
    }

    /**
     * Waits until all queued/running asynchronous Emerald audit log tasks finish or the timeout elapses.
     * Primarily intended for integration tests and graceful diagnostics.
     */
    public boolean awaitIdle(Duration timeout) {
        long deadlineNanos = System.nanoTime() + Math.max(1L, timeout.toNanos());
        synchronized (idleMonitor) {
            while (pendingTasks.get() > 0) {
                long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                if (remainingMs <= 0) {
                    return false;
                }
                try {
                    idleMonitor.wait(Math.min(remainingMs, 100L));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    private void submitAsync(String callId, Runnable work) {
        pendingTasks.incrementAndGet();
        try {
            auditExecutor.execute(() -> {
                try {
                    work.run();
                } catch (Throwable t) {
                    log.warn("EMERALD_AUDIT_ASYNC_FAILED callId={} err={}", callId, t.getMessage());
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
            log.warn("EMERALD_AUDIT_ASYNC_SKIPPED callId={} err={}", callId, rex.getMessage());
        }
    }

    private void persistEntrySync(Entry e) {
        try {
            UUID resolvedUserId = e.userId() != null
                    ? e.userId()
                    : runInNewTxResult(() -> resolveUserId(e.outletCode(), e.externalRef()));
            String reqHeadersJson = toJsonObjectString(e.requestHeaders());
            String resHeadersJson = toJsonObjectString(e.responseHeaders());
            Timestamp createdAt = Timestamp.from(e.occurredAt() != null ? e.occurredAt() : Instant.now());

            try {
                runInNewTx(() -> insertRow(e, resolvedUserId, reqHeadersJson, resHeadersJson, createdAt));
            } catch (DataIntegrityViolationException fkEx) {
                if (resolvedUserId != null) {
                    // Fallback if an outer transaction has not yet committed the referenced user_id
                    runInNewTx(() -> insertRow(e, null, reqHeadersJson, resHeadersJson, createdAt));
                } else {
                    throw fkEx;
                }
            }
        } catch (Exception ex) {
            log.warn("EMERALD_AUDIT_LOG_PERSIST_SKIPPED callId={} path={} err={}",
                    e.callId(), e.endpointPath(), ex.getMessage());
        }
    }

    private void insertRow(
            Entry e,
            UUID resolvedUserId,
            String reqHeadersJson,
            String resHeadersJson,
            Timestamp createdAt) {
        jdbc.update("""
                INSERT INTO emerald_api_logs (
                    call_id, request_id, direction, flow, http_method, url, endpoint_path,
                    user_id, outlet_code, external_ref, provider_txn_id,
                    request_headers, request_body,
                    http_status, provider_code, provider_message,
                    response_headers, response_body,
                    error_message, latency_ms, created_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?,
                    CAST(? AS jsonb), ?,
                    ?, ?, ?,
                    CAST(? AS jsonb), ?,
                    ?, ?, ?
                )
                """,
                nonBlank(e.callId(), "emd_unknown"),
                blankToNull(e.requestId()),
                nonBlank(e.direction(), "OUTBOUND"),
                nonBlank(e.flow(), "UNKNOWN"),
                nonBlank(e.httpMethod(), "POST"),
                nonBlank(e.url(), ""),
                nonBlank(e.endpointPath(), "/"),
                resolvedUserId,
                blankToNull(e.outletCode()),
                blankToNull(e.externalRef()),
                blankToNull(e.providerTxnId()),
                reqHeadersJson,
                e.requestBody(),
                e.httpStatus(),
                blankToNull(e.providerCode()),
                clip(e.providerMessage(), 2000),
                resHeadersJson,
                e.responseBody(),
                clip(e.errorMessage(), 2000),
                Math.max(0L, e.latencyMs()),
                createdAt);
    }

    private void runInNewTx(Runnable action) {
        if (requiresNewTx != null) {
            requiresNewTx.executeWithoutResult(status -> action.run());
        } else {
            action.run();
        }
    }

    private <T> T runInNewTxResult(java.util.function.Supplier<T> supplier) {
        if (requiresNewTx != null) {
            return requiresNewTx.execute(status -> supplier.get());
        }
        return supplier.get();
    }

    private static void withRequestIdMdc(String requestId, Runnable work) {
        String prev = MDC.get("requestId");
        try {
            if (requestId != null && !requestId.isBlank()) {
                MDC.put("requestId", requestId);
            } else {
                MDC.remove("requestId");
            }
            work.run();
        } finally {
            if (prev != null) {
                MDC.put("requestId", prev);
            } else {
                MDC.remove("requestId");
            }
        }
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
                  FROM emerald_api_logs l
                 WHERE l.user_id = ?
                    OR (l.outlet_code IS NOT NULL AND l.outlet_code IN (
                        SELECT m.outlet_code
                          FROM provider_merchant_accounts m
                         WHERE m.user_id = ? AND m.provider_code = 'EMERALD'
                    ))
                 ORDER BY l.created_at DESC
                 LIMIT ?
                """, userId, userId, safeLimit);
        return rows.stream().map(this::toView).toList();
    }

    public List<Map<String, Object>> listGlobal(
            String direction,
            String flow,
            String outletCode,
            String externalRef,
            int limit) {
        int safeLimit = Math.max(1, Math.min(200, limit));
        StringBuilder sql = new StringBuilder("""
                SELECT l.id, l.call_id, l.request_id, l.direction, l.flow,
                       l.http_method, l.url, l.endpoint_path, l.user_id,
                       u.code AS user_code, u.full_name AS user_name,
                       l.outlet_code, l.external_ref, l.provider_txn_id,
                       l.request_headers::text AS request_headers_json,
                       l.request_body,
                       l.http_status, l.provider_code, l.provider_message,
                       l.response_headers::text AS response_headers_json,
                       l.response_body, l.error_message, l.latency_ms, l.created_at
                  FROM emerald_api_logs l
                  LEFT JOIN users u ON u.id = l.user_id
                 WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (direction != null && !direction.isBlank()) {
            sql.append(" AND UPPER(l.direction) = UPPER(?)");
            args.add(direction.trim());
        }
        if (flow != null && !flow.isBlank()) {
            sql.append(" AND UPPER(l.flow) LIKE UPPER(?)");
            args.add("%" + flow.trim() + "%");
        }
        if (outletCode != null && !outletCode.isBlank()) {
            sql.append(" AND l.outlet_code = ?");
            args.add(outletCode.trim());
        }
        if (externalRef != null && !externalRef.isBlank()) {
            sql.append(" AND (l.external_ref = ? OR l.provider_txn_id = ? OR l.call_id = ?)");
            args.add(externalRef.trim());
            args.add(externalRef.trim());
            args.add(externalRef.trim());
        }
        sql.append(" ORDER BY l.created_at DESC LIMIT ?");
        args.add(safeLimit);

        return jdbc.queryForList(sql.toString(), args.toArray()).stream().map(this::toView).toList();
    }

    private UUID resolveUserId(String outletCode, String externalRef) {
        if (outletCode != null && !outletCode.isBlank()) {
            List<UUID> byOutlet = jdbc.queryForList("""
                    SELECT user_id FROM provider_merchant_accounts
                     WHERE provider_code = 'EMERALD' AND outlet_code = ?
                     LIMIT 1
                    """, UUID.class, outletCode.trim());
            if (!byOutlet.isEmpty()) {
                return byOutlet.get(0);
            }
        }
        if (externalRef != null && !externalRef.isBlank()) {
            String ref = externalRef.trim();
            List<UUID> byCashout = jdbc.queryForList(
                    "SELECT agent_user_id FROM cashout_sessions WHERE external_ref = ? OR provider_txn_id = ? LIMIT 1",
                    UUID.class, ref, ref);
            if (!byCashout.isEmpty()) {
                return byCashout.get(0);
            }
            List<UUID> byPayout = jdbc.queryForList(
                    "SELECT user_id FROM payout_transfers WHERE external_ref = ? OR provider_txn_id = ? LIMIT 1",
                    UUID.class, ref, ref);
            if (!byPayout.isEmpty()) {
                return byPayout.get(0);
            }
            List<UUID> byAeps = jdbc.queryForList(
                    "SELECT user_id FROM aeps_transactions WHERE external_ref = ? OR provider_txn_id = ? LIMIT 1",
                    UUID.class, ref, ref);
            if (!byAeps.isEmpty()) {
                return byAeps.get(0);
            }
        }
        return null;
    }

    private Map<String, Object> toView(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.get("id"));
        m.put("callId", r.get("call_id"));
        m.put("requestId", r.get("request_id"));
        m.put("direction", r.get("direction"));
        m.put("flow", r.get("flow"));
        m.put("httpMethod", r.get("http_method"));
        m.put("url", r.get("url"));
        m.put("endpointPath", r.get("endpoint_path"));
        m.put("userId", r.get("user_id"));
        if (r.containsKey("user_code")) {
            m.put("userCode", r.get("user_code"));
            m.put("userName", r.get("user_name"));
        }
        m.put("outletCode", r.get("outlet_code"));
        m.put("externalRef", r.get("external_ref"));
        m.put("providerTxnId", r.get("provider_txn_id"));
        m.put("requestHeaders", parseMapSafe(String.valueOf(r.getOrDefault("request_headers_json", "{}"))));
        m.put("requestBody", r.get("request_body"));
        m.put("httpStatus", r.get("http_status"));
        m.put("providerCode", r.get("provider_code"));
        m.put("providerMessage", r.get("provider_message"));
        m.put("responseHeaders", parseMapSafe(String.valueOf(r.getOrDefault("response_headers_json", "{}"))));
        m.put("responseBody", r.get("response_body"));
        m.put("errorMessage", r.get("error_message"));
        m.put("latencyMs", r.get("latency_ms"));
        m.put("createdAt", r.get("created_at"));
        return m;
    }

    private Map<String, Object> parseMapSafe(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String toJsonObjectString(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return "{}";
        }
        try {
            return mapper.writeValueAsString(headers);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String blankToNull(String val) {
        return (val == null || val.isBlank()) ? null : val.trim();
    }

    private static String nonBlank(String val, String fallback) {
        return (val == null || val.isBlank()) ? fallback : val.trim();
    }

    private static String clip(String val, int max) {
        if (val == null || val.isBlank()) return null;
        String s = val.trim();
        return s.length() <= max ? s : s.substring(0, max);
    }
}
