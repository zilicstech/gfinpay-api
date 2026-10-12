package com.fintech.paysprint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Verifies PaySprint callback JWTs with the same HS256 key used to sign outbound tokens. */
@Component
public class PaysprintJwtVerifier {

    private final PaysprintProperties props;
    private final ObjectMapper mapper;

    public PaysprintJwtVerifier(PaysprintProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    public JsonNode verify(String compact) {
        String raw = props.jwtKey();
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("app.paysprint.jwt-key is not configured");
        }
        if (compact == null || compact.isBlank()) {
            throw new IllegalArgumentException("Missing PaySprint token");
        }
        SecretKey key = new SecretKeySpec(raw.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(compact.trim()).getPayload();
        return mapper.valueToTree(claims);
    }
}
