package com.fintech.platform.outbox;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Inserts an outbox event inside the caller's transaction (transactional outbox pattern). */
@Component
public class OutboxWriter {

    private final JdbcTemplate jdbc;

    public OutboxWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void write(String aggregateType, UUID aggregateId, String eventType, String payloadJson) {
        jdbc.update("""
                INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, ?, ?, ?::jsonb)
                """, aggregateType, aggregateId, eventType, payloadJson);
    }
}
