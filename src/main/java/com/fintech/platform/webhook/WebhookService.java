package com.fintech.platform.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Webhook inbox: verify, dedupe on (provider, external_event_id), route to the owning module. */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Map<String, WebhookHandler> handlers;

    public WebhookService(JdbcTemplate jdbc, ObjectMapper mapper, List<WebhookHandler> handlerList) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(WebhookHandler::provider, Function.identity()));
    }

    @Transactional
    public String ingest(String provider, String rawBody, boolean signatureValid) throws Exception {
        long startMs = System.currentTimeMillis();
        JsonNode payload = mapper.readTree(rawBody);
        String eventId = payload.path("eventId").asText();
        log.info("WEBHOOK_INGEST started provider={} eventId={}", provider, eventId);
        try {
            int inserted = jdbc.update("""
                    INSERT INTO webhook_inbox (provider, external_event_id, payload, signature_valid)
                    VALUES (?, ?, ?::jsonb, ?)
                    ON CONFLICT (provider, external_event_id) DO NOTHING
                    """, provider, eventId, rawBody, signatureValid);
            if (inserted == 0) {
                log.info("WEBHOOK_INGEST duplicate provider={} eventId={} — acked as no-op", provider, eventId);
                return "DUPLICATE";
            }
            WebhookHandler handler = handlers.get(provider);
            if (handler == null) {
                jdbc.update("UPDATE webhook_inbox SET status = 'IGNORED', processed_at = now() "
                        + "WHERE provider = ? AND external_event_id = ?", provider, eventId);
                log.warn("WEBHOOK_INGEST no handler for provider={} — marked IGNORED", provider);
                return "IGNORED";
            }
            UUID txnId = handler.handle(payload);
            jdbc.update("UPDATE webhook_inbox SET status = 'PROCESSED', processed_at = now(), transaction_id = ? "
                    + "WHERE provider = ? AND external_event_id = ?", txnId, provider, eventId);
            log.info("WEBHOOK_INGEST success provider={} eventId={} txnId={}", provider, eventId, txnId);
            return "PROCESSED";
        } finally {
            log.info("WEBHOOK_INGEST completed provider={} eventId={} in {} ms",
                    provider, eventId, System.currentTimeMillis() - startMs);
        }
    }
}
