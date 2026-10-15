package com.fintech.platform.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Append-only history of inbound callbacks. Does not dedupe and does not decide business outcome. */
@Service
public class ProviderCallbackLog {

    private static final Logger log = LoggerFactory.getLogger(ProviderCallbackLog.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ProviderCallbackLog(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String provider, String source, String event, String status, String merchantCode,
                       JsonNode payload, String outcome, String rejectionReason) {
        String json = null;
        if (payload != null && !payload.isNull() && !payload.isMissingNode()) {
            try {
                json = mapper.writeValueAsString(payload);
            } catch (Exception e) {
                json = null;
                if (rejectionReason == null || rejectionReason.isBlank()) {
                    rejectionReason = "payload_unreadable";
                }
            }
        }
        jdbc.update("""
                INSERT INTO provider_callbacks
                    (provider, source, event, status, merchant_code, payload, outcome, rejection_reason)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                """,
                provider,
                source,
                blank(event),
                blank(status),
                blank(merchantCode),
                json,
                outcome,
                clip(rejectionReason));
        log.info("PROVIDER_CALLBACK provider={} source={} event={} status={} outcome={}",
                provider, source, blank(event), blank(status), outcome);
    }

    private static String blank(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String clip(String value) {
        String text = blank(value);
        if (text == null) {
            return null;
        }
        return text.length() > 300 ? text.substring(0, 300) : text;
    }
}
