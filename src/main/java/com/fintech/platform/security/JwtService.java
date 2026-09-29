package com.fintech.platform.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtService {

    private final SecretKey key;
    private final long defaultTtlMillis;
    private final long retailerTtlMillis;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.ttl-hours}") long ttlHours,
                      @Value("${app.jwt.retailer-ttl-hours}") long retailerTtlHours) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.defaultTtlMillis = ttlHours * 3600_000L;
        this.retailerTtlMillis = retailerTtlHours * 3600_000L;
    }

    public IssuedToken issue(UUID userId, String name, String userType, List<String> permissions) {
        long now = System.currentTimeMillis();
        long ttl = ttlMillisFor(userType);
        Instant expiresAt = Instant.ofEpochMilli(now + ttl);
        String compact = Jwts.builder()
                .subject(userId.toString())
                .claim("name", name)
                .claim("userType", userType)
                .claim("permissions", permissions)
                .issuedAt(new Date(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new IssuedToken(compact, expiresAt);
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    private long ttlMillisFor(String userType) {
        return "RETAILER".equals(userType) ? retailerTtlMillis : defaultTtlMillis;
    }

    public record IssuedToken(String token, Instant expiresAt) {}
}
