package com.fintech.platform.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Processes one outbox event: lock row, call the partner, mark DISPATCHED / FAILED with backoff. */
@Component
public class OutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboxProcessor.class);
    private static final int MAX_ATTEMPTS = 8;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Map<String, OutboxEventHandler> handlers;

    public OutboxProcessor(JdbcTemplate jdbc, ObjectMapper mapper, List<OutboxEventHandler> handlerList) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(OutboxEventHandler::eventType, Function.identity()));
    }

    @Transactional
    public void process(long eventId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, event_type, payload::text AS payload, attempts
                  FROM outbox_events
                 WHERE id = ? AND status IN ('NEW','FAILED') AND next_retry_at <= now()
                 FOR UPDATE SKIP LOCKED
                """, eventId);
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Object> row = rows.get(0);
        String eventType = (String) row.get("event_type");
        int attempts = ((Number) row.get("attempts")).intValue();
        long startMs = System.currentTimeMillis();
        log.info("OUTBOX_DISPATCH started eventId={} type={} attempt={}", eventId, eventType, attempts + 1);
        try {
            OutboxEventHandler handler = handlers.get(eventType);
            if (handler == null) {
                throw new IllegalStateException("No handler for event type " + eventType);
            }
            JsonNode payload = mapper.readTree((String) row.get("payload"));
            handler.handle(payload);
            jdbc.update("UPDATE outbox_events SET status = 'DISPATCHED', dispatched_at = now(), attempts = ? WHERE id = ?",
                    attempts + 1, eventId);
            log.info("OUTBOX_DISPATCH success eventId={}", eventId);
        } catch (Exception e) {
            int newAttempts = attempts + 1;
            String status = newAttempts >= MAX_ATTEMPTS ? "DEAD" : "FAILED";
            long backoffSeconds = Math.min(300, 1L << newAttempts);
            jdbc.update("""
                    UPDATE outbox_events
                       SET status = ?, attempts = ?, next_retry_at = now() + make_interval(secs => ?)
                     WHERE id = ?
                    """, status, newAttempts, (double) backoffSeconds, eventId);
            log.error("OUTBOX_DISPATCH failed eventId={} attempt={} status={} reason={}",
                    eventId, newAttempts, status, e.getMessage());
        } finally {
            log.info("OUTBOX_DISPATCH completed eventId={} in {} ms", eventId, System.currentTimeMillis() - startMs);
        }
    }
}
