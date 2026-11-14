package com.fintech.platform.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(AuthProperties.class)
public class JwtService {

    public static final String SCOPE_FULL = "FULL";
    public static final String SCOPE_REVERIFY = "REVERIFY";

    private final SecretKey key;
    private final long ttlMillis;

    public JwtService(@Value("${app.jwt.secret}") String secret, AuthProperties props) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlMillis = Duration.ofMinutes(props.accessTokenMinutes()).toMillis();
    }

    /** Access tokens are bound to a session row; a token without {@code sid} is not accepted. */
    public IssuedToken issue(UUID userId, UUID sessionId, String name, String userType, List<String> permissions) {
        return issue(userId, sessionId, name, userType, permissions, SCOPE_FULL);
    }

    public IssuedToken issue(UUID userId, UUID sessionId, String name, String userType,
                             List<String> permissions, String scope) {
        long now = System.currentTimeMillis();
        Instant expiresAt = Instant.ofEpochMilli(now + ttlMillis);
        String effectiveScope = (scope == null || scope.isBlank()) ? SCOPE_FULL : scope;
        String compact = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                .claim("sid", sessionId.toString())
                .claim("name", name)
                .claim("userType", userType)
                .claim("permissions", permissions)
                .claim("scope", effectiveScope)
                .issuedAt(new Date(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new IssuedToken(compact, expiresAt);
    }

    public String issueResetToken(UUID userId, UUID challengeId) {
        long now = System.currentTimeMillis();
        Instant expiresAt = Instant.ofEpochMilli(now + Duration.ofMinutes(10).toMillis());
        return Jwts.builder()
                .id(challengeId.toString())
                .subject(userId.toString())
                .claim("purpose", "CREDENTIAL_RESET")
                .issuedAt(new Date(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    public record IssuedToken(String token, Instant expiresAt) {}
}
