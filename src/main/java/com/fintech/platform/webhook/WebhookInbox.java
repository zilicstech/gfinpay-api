package com.fintech.platform.webhook;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** One dedupe key format for every inbound provider callback: {@code provider + event id}. */
@Component
public class WebhookInbox {

    public static final int MAX_BODY_BYTES = 64 * 1024;

    private final JdbcTemplate jdbc;

    public WebhookInbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Long> store(String provider, String externalEventId, String payloadJson) {
        String body = payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson;
        List<Long> inserted = jdbc.queryForList("""
                INSERT INTO webhook_inbox (provider, external_event_id, payload, signature_valid, status)
                VALUES (?, ?, CAST(? AS jsonb), TRUE, 'RECEIVED')
                ON CONFLICT (provider, external_event_id) DO NOTHING
                RETURNING id
                """, Long.class, provider, externalEventId, body);
        return inserted.stream().findFirst();
    }

    public void markProcessed(long id, UUID transactionId) {
        jdbc.update("""
                UPDATE webhook_inbox
                   SET status = 'PROCESSED',
                       transaction_id = ?,
                       processed_at = now()
                 WHERE id = ?
                """, transactionId, id);
    }
}
