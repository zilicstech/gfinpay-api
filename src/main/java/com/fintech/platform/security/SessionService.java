package com.fintech.platform.security;

import com.fintech.persistence.identity.UserSessionRepository;
import com.fintech.platform.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-side session registry. One active session per user is a database invariant
 * (partial unique index on user_sessions), so a new login always displaces the old one.
 */
@Service
@EnableConfigurationProperties(AuthProperties.class)
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final JdbcTemplate jdbc;
    private final AuthProperties props;
    private final UserSessionRepository sessions;

    public SessionService(JdbcTemplate jdbc, AuthProperties props, UserSessionRepository sessions) {
        this.jdbc = jdbc;
        this.props = props;
        this.sessions = sessions;
    }

    /** Cookie payload: the session id plus the secret, so a stale secret can be attributed to its session. */
    public record Issued(UUID sessionId, UUID userId, String cookieValue, Instant expiresAt) {}

    @Transactional
    public Issued start(UUID userId, String ip, String userAgent) {
        revokeAllForUser(userId, "NEW_LOGIN");
        String secret = newSecret();
        Instant absoluteExpiry = Instant.now().plus(Duration.ofHours(props.sessionAbsoluteHours()));
        UUID sessionId = jdbc.queryForObject("""
                INSERT INTO user_sessions (user_id, refresh_token_hash, absolute_expires_at, ip, user_agent)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """, UUID.class, userId, sha256(secret), Timestamp.from(absoluteExpiry), ip, clip(userAgent));
        log.info("SESSION_CREATED userId={} sessionId={}", userId, sessionId);
        return new Issued(sessionId, userId, cookieValue(sessionId, secret), absoluteExpiry);
    }

    /** Either a rotated session or the reason it was refused. Never throws, so revocations commit. */
    public record Rotation(Issued issued, String failure) {
        public boolean ok() {
            return issued != null;
        }
    }

    /**
     * Validates the refresh cookie and rotates its secret. Enforces revocation, the absolute
     * session cap, and the idle window. A secret that does not match a live session is treated
     * as a replay and kills the session outright.
     *
     * <p>Refusals are returned rather than thrown: an exception here would roll back the very
     * revocation we just recorded. The caller turns a failure into the 401.
     */
    @Transactional
    public Rotation rotate(String cookieValue) {
        Parsed parsed = parse(cookieValue);
        if (parsed == null) {
            return new Rotation(null, "MALFORMED_COOKIE");
        }
        // FOR UPDATE so two concurrent refreshes of the same session cannot both rotate.
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT user_id, refresh_token_hash, last_activity_at, absolute_expires_at, revoked_at
                  FROM user_sessions
                 WHERE id = ?
                   FOR UPDATE
                """, parsed.sessionId());
        if (rows.isEmpty()) {
            return new Rotation(null, "UNKNOWN_SESSION");
        }
        Map<String, Object> row = rows.get(0);
        UUID userId = (UUID) row.get("user_id");
        if (row.get("revoked_at") != null) {
            return new Rotation(null, "REVOKED");
        }
        if (!MessageDigest.isEqual(
                sha256(parsed.secret()).getBytes(StandardCharsets.UTF_8),
                String.valueOf(row.get("refresh_token_hash")).getBytes(StandardCharsets.UTF_8))) {
            revoke(parsed.sessionId(), "TOKEN_REUSE");
            log.warn("SESSION_TOKEN_REUSE userId={} sessionId={}", userId, parsed.sessionId());
            return new Rotation(null, "TOKEN_REUSE");
        }
        Instant now = Instant.now();
        if (now.isAfter(instant(row.get("absolute_expires_at")))) {
            revoke(parsed.sessionId(), "ABSOLUTE_EXPIRY");
            return new Rotation(null, "ABSOLUTE_EXPIRY");
        }
        Instant idleCutoff = instant(row.get("last_activity_at")).plus(Duration.ofMinutes(props.idleMinutes()));
        if (now.isAfter(idleCutoff)) {
            revoke(parsed.sessionId(), "IDLE_TIMEOUT");
            return new Rotation(null, "IDLE_TIMEOUT");
        }
        String secret = newSecret();
        jdbc.update("""
                UPDATE user_sessions
                   SET refresh_token_hash = ?, last_activity_at = now(), rotations = rotations + 1
                 WHERE id = ?
                """, sha256(secret), parsed.sessionId());
        Issued issued = new Issued(parsed.sessionId(), userId, cookieValue(parsed.sessionId(), secret),
                instant(row.get("absolute_expires_at")));
        return new Rotation(issued, null);
    }

    public static ApiException sessionExpired() {
        return ApiException.of(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED",
                "Your session has ended. Please sign in again.");
    }

    @Transactional
    public void revoke(UUID sessionId, String reason) {
        if (sessionId == null) {
            return;
        }
        int n = sessions.revoke(sessionId, reason);
        if (n > 0) {
            log.info("SESSION_REVOKED sessionId={} reason={}", sessionId, reason);
        }
    }

    @Transactional
    public void revokeAllForUser(UUID userId, String reason) {
        if (userId == null) {
            return;
        }
        int n = sessions.revokeAllForUser(userId, reason);
        if (n > 0) {
            log.info("SESSION_REVOKED userId={} count={} reason={}", userId, n, reason);
        }
    }

    /** Revokes by cookie without requiring a valid access token, so logout always succeeds. */
    @Transactional
    public void revokeByCookie(String cookieValue, String reason) {
        Parsed parsed = parse(cookieValue);
        if (parsed != null) {
            revoke(parsed.sessionId(), reason);
        }
    }

    private record Parsed(UUID sessionId, String secret) {}

    /** Returns null for an absent or malformed cookie. */
    private static Parsed parse(String cookieValue) {
        if (cookieValue == null || cookieValue.isBlank()) {
            return null;
        }
        int dot = cookieValue.indexOf('.');
        if (dot <= 0 || dot == cookieValue.length() - 1) {
            return null;
        }
        try {
            return new Parsed(UUID.fromString(cookieValue.substring(0, dot)), cookieValue.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String cookieValue(UUID sessionId, String secret) {
        return sessionId + "." + secret;
    }

    private static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return B64.encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            return B64.encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        throw new IllegalStateException("Unexpected timestamp type: " + value);
    }

    private static String clip(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 400 ? value.substring(0, 400) : value;
    }
}
