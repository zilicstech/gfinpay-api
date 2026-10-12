package com.fintech.paysprint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.identity.RetailerPaysprintEkycService;
import com.fintech.identity.RetailerPaysprintEkycService.PaysprintCallbackRejected;
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

    private final RetailerPaysprintEkycService ekyc;
    private final ObjectMapper mapper;

    public PaysprintOnboardCallbackController(RetailerPaysprintEkycService ekyc, ObjectMapper mapper) {
        this.ekyc = ekyc;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> receive(@RequestBody String rawBody) {
        JsonNode body;
        try {
            body = mapper.readTree(rawBody == null ? "" : rawBody);
        } catch (Exception e) {
            log.info("PAYSPRINT_ONBOARD callback rejected reason=invalid_json");
            return failed();
        }
        String event = body.path("event").asText("");
        log.info("PAYSPRINT_ONBOARD callback event={} merchantcode={}", event, merchantCode(body));
        try {
            if ("MERCHANT_ONBOARDING".equals(event)) {
                ekyc.verifyCharge(body);
                return completed();
            }
            if ("MERCHANT_STATUS_ONBOARD".equals(event)) {
                ekyc.applyStatus(body);
                return completed();
            }
            log.info("PAYSPRINT_ONBOARD callback rejected reason=unknown_event");
            return failed();
        } catch (PaysprintCallbackRejected | JwtException | IllegalArgumentException e) {
            log.info("PAYSPRINT_ONBOARD callback rejected reason={}", e.getMessage());
            return failed();
        }
    }

    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> redirect(@RequestParam(value = "data", required = false) String data) {
        if (data == null || data.isBlank()) {
            return html(HttpStatus.BAD_REQUEST, "This eKYC result could not be confirmed.");
        }
        try {
            boolean done = ekyc.applyRedirect(data);
            if (done) {
                return html(HttpStatus.OK, "eKYC is completed. You can close this page.");
            }
            return html(HttpStatus.OK, "eKYC is not finished yet. Close this page and open the link again.");
        } catch (PaysprintCallbackRejected | JwtException | IllegalArgumentException e) {
            log.info("PAYSPRINT_ONBOARD redirect rejected reason={}", e.getMessage());
            return html(HttpStatus.BAD_REQUEST, "This eKYC result could not be confirmed.");
        }
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
                .body(Map.of("status", 400, "message", "Transaction failed"));
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
