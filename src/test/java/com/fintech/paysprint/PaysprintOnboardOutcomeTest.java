package com.fintech.paysprint;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.SecurityException;
import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class PaysprintOnboardOutcomeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void statusCallbackRequiresSuccessAndBank2Active() {
        ObjectNode param = mapper.createObjectNode();
        param.put("status", "Success");
        param.putObject("bank").put("Bank2", "Active");
        assertTrue(PaysprintOnboardOutcome.statusCallbackComplete(param));

        param.put("status", "Success");
        param.putObject("bank").put("Bank2", "Pending");
        assertFalse(PaysprintOnboardOutcome.statusCallbackComplete(param));
    }

    @Test
    void redirectRequiresStatusOneAndBank2One() {
        ObjectNode data = mapper.createObjectNode();
        data.put("status", "1");
        data.putObject("bank").put("Bank2", 1);
        assertTrue(PaysprintOnboardOutcome.redirectComplete(data));

        data.put("status", "0");
        assertFalse(PaysprintOnboardOutcome.redirectComplete(data));

        data.put("status", "1");
        data.putObject("bank").put("Bank2", 0);
        assertFalse(PaysprintOnboardOutcome.redirectComplete(data));
    }

    @Test
    void statusCheckAcceptsOnlyBank2Accepted() {
        ObjectNode ok = mapper.createObjectNode();
        ok.put("status", true);
        ok.put("is_approved", "Accepted");
        assertTrue(PaysprintOnboardOutcome.statusCheckAccepted(ok));

        ObjectNode pending = mapper.createObjectNode();
        pending.put("status", true);
        pending.put("is_approved", "Pending");
        assertFalse(PaysprintOnboardOutcome.statusCheckAccepted(pending));
    }

    @Test
    void verifierAcceptsTheSameHs256KeyAndRejectsADifferentOne() {
        String key = "test-jwt-key-at-least-32-characters";
        PaysprintJwtVerifier verifier = new PaysprintJwtVerifier(properties(key), mapper);
        String token = Jwts.builder()
                .claim("status", "1")
                .claim("merchantcode", "GFINRBUY30")
                .signWith(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"), Jwts.SIG.HS256)
                .compact();
        assertTrue(verifier.verify(token).path("merchantcode").asText("").equals("GFINRBUY30"));
        assertThrows(SecurityException.class, () -> new PaysprintJwtVerifier(properties("other-key-at-least-32-characters"), mapper).verify(token));
    }

    private static PaysprintProperties properties(String jwtKey) {
        return new PaysprintProperties(
                "http://localhost",
                "partner",
                "merchant",
                jwtKey,
                "authorised",
                "1234567890123456",
                "1234567890123456",
                "FIXED_DEPOSIT",
                "1",
                true,
                "secret");
    }
}
