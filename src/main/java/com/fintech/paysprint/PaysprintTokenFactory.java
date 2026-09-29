package com.fintech.paysprint;

import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class PaysprintTokenFactory {

    private final PaysprintProperties props;

    public PaysprintTokenFactory(PaysprintProperties props) {
        this.props = props;
    }

    public String create() {
        String raw = props.jwtKey();
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("app.paysprint.jwt-key is not configured");
        }
        // Paysprint UAT verifies HS256 over the key string as issued (not base64-decoded).
        SecretKey key = new SecretKeySpec(raw.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        long timestamp = System.currentTimeMillis() / 1000;
        int reqid = ThreadLocalRandom.current().nextInt(1_000_000_000);
        return Jwts.builder()
                .header().type("JWT").and()
                .claim("iss", "PAYSPRINT")
                .claim("timestamp", timestamp)
                .claim("partnerId", props.partnerId())
                .claim("reqid", reqid)
                .claim("product", props.jwtProduct())
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
}
