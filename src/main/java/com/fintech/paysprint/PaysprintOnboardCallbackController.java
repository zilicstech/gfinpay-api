package com.fintech.paysprint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fintech.identity.RetailerPaysprintEkycService;
import com.fintech.identity.RetailerPaysprintEkycService.PaysprintCallbackRejected;
import com.fintech.platform.webhook.ProviderCallbackLog;
import io.jsonwebtoken.JwtException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** PaySprint merchant-onboarding callbacks. PID data is never accepted here. */
@RestController
@RequestMapping("/webhooks/paysprint/onboard")
public class PaysprintOnboardCallbackController {

    private static final Logger log = LoggerFactory.getLogger(PaysprintOnboardCallbackController.class);

    private static final String PROVIDER = "paysprint";

    private final RetailerPaysprintEkycService ekyc;
    private final ProviderCallbackLog callbacks;
    private final ObjectMapper mapper;

    public PaysprintOnboardCallbackController(RetailerPaysprintEkycService ekyc, ProviderCallbackLog callbacks,
                                              ObjectMapper mapper) {
        this.ekyc = ekyc;
        this.callbacks = callbacks;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> receive(@RequestBody String rawBody) {
        JsonNode body;
        try {
            body = mapper.readTree(rawBody == null ? "" : rawBody);
        } catch (Exception e) {
            log.info("PAYSPRINT_ONBOARD callback rejected reason=invalid_json");
            callbacks.record(PROVIDER, "onboard", null, null, null, null, "REJECTED", "invalid_json");
            return failed();
        }
        String event = body.path("event").asText("");
        log.info("PAYSPRINT_ONBOARD callback event={} merchantcode={}", event, merchantCode(body));
        try {
            if ("MERCHANT_ONBOARDING".equals(event)) {
                JsonNode claims = ekyc.verifyCharge(body);
                callbacks.record(PROVIDER, "onboard", event, statusOf(body, claims), merchantCode(body),
                        document(event, body, claims), "IGNORED", null);
                return completed();
            }
            if ("MERCHANT_STATUS_ONBOARD".equals(event)) {
                JsonNode claims = ekyc.verifiedClaims(body);
                JsonNode stored = document(event, body, claims);
                boolean applied = PaysprintOnboardOutcome.statusCallbackComplete(body.path("param"));
                try {
                    ekyc.applyStatus(body);
                } catch (PaysprintCallbackRejected e) {
                    log.info("PAYSPRINT_ONBOARD callback rejected reason={}", e.getMessage());
                    callbacks.record(PROVIDER, "onboard", event, statusOf(body, claims), merchantCode(body),
                            stored, "REJECTED", e.getMessage());
                    return failed();
                }
                callbacks.record(PROVIDER, "onboard", event, statusOf(body, claims), merchantCode(body),
                        stored, applied ? "APPLIED" : "IGNORED", null);
                return completed();
            }
            log.info("PAYSPRINT_ONBOARD callback rejected reason=unknown_event");
            callbacks.record(PROVIDER, "onboard", event, null, merchantCode(body), null, "REJECTED", "unknown_event");
            return failed();
        } catch (PaysprintCallbackRejected | JwtException | IllegalArgumentException e) {
            log.info("PAYSPRINT_ONBOARD callback rejected reason={}", e.getMessage());
            callbacks.record(PROVIDER, "onboard", event, null, merchantCode(body), null, "REJECTED", e.getMessage());
            return failed();
        }
    }

    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> redirect(@RequestParam(value = "data", required = false) String data) {
        if (data == null || data.isBlank()) {
            callbacks.record(PROVIDER, "redirect", null, null, null, null, "REJECTED", "missing_token");
            return html(HttpStatus.BAD_REQUEST, "This eKYC result could not be confirmed.");
        }
        JsonNode claims;
        try {
            claims = ekyc.redirectClaims(data);
        } catch (PaysprintCallbackRejected | JwtException | IllegalArgumentException e) {
            log.info("PAYSPRINT_ONBOARD redirect rejected reason={}", e.getMessage());
            callbacks.record(PROVIDER, "redirect", null, null, null, null, "REJECTED", e.getMessage());
            return html(HttpStatus.BAD_REQUEST, "This eKYC result could not be confirmed.");
        }
        try {
            boolean done = ekyc.applyRedirect(data);
            callbacks.record(PROVIDER, "redirect", text(claims, "event"), statusOf(null, claims),
                    first(claims, "merchantcode", "merchant_id"), claims, done ? "APPLIED" : "IGNORED", null);
            if (done) {
                return html(HttpStatus.OK, "eKYC is completed. You can close this page.");
            }
            return html(HttpStatus.OK, "eKYC is not finished yet. Close this page and open the link again.");
        } catch (PaysprintCallbackRejected | JwtException | IllegalArgumentException e) {
            log.info("PAYSPRINT_ONBOARD redirect rejected reason={}", e.getMessage());
            callbacks.record(PROVIDER, "redirect", text(claims, "event"), statusOf(null, claims),
                    first(claims, "merchantcode", "merchant_id"), claims, "REJECTED", e.getMessage());
            return html(HttpStatus.BAD_REQUEST, "This eKYC result could not be confirmed.");
        }
    }

    private JsonNode document(String event, JsonNode body, JsonNode claims) {
        ObjectNode document = mapper.createObjectNode();
        document.put("event", event);
        JsonNode param = body.path("param");
        if (param.isObject()) {
            document.set("param", param);
        }
        if (claims != null && !claims.isNull()) {
            document.set("claims", claims);
        }
        return document;
    }

    private static String statusOf(JsonNode body, JsonNode claims) {
        String fromParam = body == null ? "" : first(body.path("param"), "status", "txn_status", "ex_status");
        if (!fromParam.isBlank()) {
            return fromParam;
        }
        return first(claims, "status", "txn_status", "ex_status");
    }

    private static String first(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        for (String field : fields) {
            String value = node.path(field).asText("").trim();
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.path(field).asText("").trim();
        return value.isBlank() ? null : value;
    }

    private static String merchantCode(JsonNode body) {
        return body.path("param").path("merchantcode").asText(
                body.path("param").path("merchant_id").asText(""));
    }

    private static ResponseEntity<Map<String, Object>> completed() {
        return ResponseEntity.ok(Map.of("status", 200, "message", "Transaction completed successfully"));
    }

    private static ResponseEntity<Map<String, Object>> failed() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("status", 400, "message", "Transaction Failed"));
    }

    private static ResponseEntity<String> html(HttpStatus status, String message) {
        String safe = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        String page = """
                <!doctype html>
                <html><head><meta charset="utf-8"><title>eKYC</title></head>
                <body style="font-family:sans-serif;padding:2rem"><p>%s</p></body></html>
                """.formatted(safe);
        return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).body(page);
    }
}
