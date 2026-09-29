package com.fintech.platform.idempotency;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fintech.platform.web.ApiException;

/**
 * Client-facing idempotency (doc 03): unique (agent, key); replay stored response on retry;
 * reject key reuse with a different payload.
 */
@Component
public class IdempotencyService {

    /** Result of claiming a key: either a fresh claim (proceed) or a replay of the stored response. */
    public record Claim(boolean replay, UUID keyId, Integer responseCode, String responseBody) {}

    private final JdbcTemplate jdbc;

    public IdempotencyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Claim claim(UUID agentUserId, String idemKey, String requestBody) {
        String hash = sha256(requestBody);
        UUID keyId = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO idempotency_keys (id, idem_key, agent_user_id, request_hash, status, expires_at)
                    VALUES (?, ?, ?, ?, 'IN_FLIGHT', now() + interval '48 hours')
                    """, keyId, idemKey, agentUserId, hash);
            return new Claim(false, keyId, null, null);
        } catch (DuplicateKeyException dup) {
            Map<String, Object> row = jdbc.queryForMap("""
                    SELECT id, request_hash, status, response_code, response_body::text AS response_body
                      FROM idempotency_keys WHERE agent_user_id = ? AND idem_key = ?
                    """, agentUserId, idemKey);
            if (!hash.equals(row.get("request_hash"))) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency key was already used with a different payload");
            }
            if ("IN_FLIGHT".equals(row.get("status"))) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_IN_FLIGHT",
                        "The original request with this key is still processing", null, true);
            }
            return new Claim(true, (UUID) row.get("id"),
                    ((Number) row.get("response_code")).intValue(), (String) row.get("response_body"));
        }
    }

    public void complete(UUID keyId, int responseCode, String responseBodyJson) {
        jdbc.update("""
                UPDATE idempotency_keys
                   SET response_code = ?, response_body = ?::jsonb, status = 'COMPLETED'
                 WHERE id = ?
                """, responseCode, responseBodyJson, keyId);
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
