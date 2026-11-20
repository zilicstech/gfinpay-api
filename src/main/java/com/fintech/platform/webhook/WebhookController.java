package com.fintech.platform.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.web.ApiException;
import com.fintech.platform.web.ApiResponse;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WebhookService webhookService;
    private final WebhookSigner signer;
    private final ProviderCallbackLog callbacks;
    private final ObjectMapper mapper;
    private final InboundCallbackOrchestrator inbound;
    private final com.fintech.iserveu.IserveuProperties iserveu;
    private final com.fintech.iserveu.IserveuAuditLogService iserveuAudit;

    public WebhookController(WebhookService webhookService, WebhookSigner signer, ProviderCallbackLog callbacks,
                             ObjectMapper mapper, InboundCallbackOrchestrator inbound,
                             com.fintech.iserveu.IserveuProperties iserveu,
                             com.fintech.iserveu.IserveuAuditLogService iserveuAudit) {
        this.webhookService = webhookService;
        this.signer = signer;
        this.callbacks = callbacks;
        this.mapper = mapper;
        this.inbound = inbound;
        this.iserveu = iserveu;
        this.iserveuAudit = iserveuAudit;
    }

    @PostMapping("/iserveu/upi-cashpoint")
    public ResponseEntity<Map<String, Object>> iserveuCashpoint(
            @RequestHeader(value = "pass_key", required = false) String passKey,
            @RequestHeader(value = "header_secrets", required = false) String headerSecrets,
            @RequestBody(required = false) String rawBody) {
        return acceptIserveuWebhook("/webhooks/iserveu/upi-cashpoint", passKey, headerSecrets, rawBody, true);
    }

    @PostMapping("/iserveu/payout")
    public ResponseEntity<Map<String, Object>> iserveuPayout(
            @RequestHeader(value = "pass_key", required = false) String passKey,
            @RequestHeader(value = "header_secrets", required = false) String headerSecrets,
            @RequestBody(required = false) String rawBody) {
        return acceptIserveuWebhook("/webhooks/iserveu/payout", passKey, headerSecrets, rawBody, false);
    }

    private ResponseEntity<Map<String, Object>> acceptIserveuWebhook(
            String path, String passKey, String headerSecrets, String rawBody, boolean cashpoint) {
        long started = System.currentTimeMillis();
        int bytes = rawBody == null ? 0 : rawBody.length();
        boolean passAccepted = inbound.iserveuAuthorized(passKey);
        boolean headerAccepted = headerSecretsMatch(headerSecrets);
        String outcome = "REJECTED";
        String error = null;
        String ack = "{\"status\":1,\"statusDesc\":\"Failure\"}";
        Map<String, Object> body = Map.of("status", 1, "statusDesc", "Failure");
        try {
            log.info("ISERVEU_WEBHOOK path={} passAccepted={} headerAccepted={} bytes={}",
                    path, passAccepted, headerAccepted, bytes);
            if (!passAccepted || !headerAccepted) {
                error = "pass_key or header_secrets rejected";
                return ResponseEntity.ok(body);
            }
            if (bytes > WebhookInbox.MAX_BODY_BYTES) {
                error = "body_too_large";
                return ResponseEntity.ok(body);
            }
            if (cashpoint) {
                inbound.acceptIserveu(rawBody);
            } else {
                inbound.acceptIserveuPayout(rawBody);
            }
            outcome = "RECEIVED";
            error = null;
            ack = "{\"status\":0,\"statusDesc\":\"success\"}";
            return ResponseEntity.ok(Map.of("status", 0, "statusDesc", "success"));
        } finally {
            if (iserveuAudit != null) {
                iserveuAudit.recordInbound(
                        path, rawBody, passAccepted, headerAccepted, 200, ack, outcome, error,
                        System.currentTimeMillis() - started);
            }
        }
    }

    private boolean headerSecretsMatch(String provided) {
        String expected = iserveu.headerSecret();
        if (expected == null || expected.isBlank()) {
            return true;
        }
        if (provided == null || provided.isBlank()) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                provided.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @PostMapping("/{provider}")
    public ApiResponse<Map<String, String>> receive(@PathVariable String provider,
                                                    @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
                                                    @RequestBody String rawBody) throws Exception {
        if ("paysprint".equalsIgnoreCase(provider)) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "NOT_FOUND", "This callback has been removed");
        }
        if (!signer.verify(rawBody, signature)) {
            callbacks.record(provider, provider, null, null, null, null, "REJECTED", "invalid_signature");
            throw ApiException.of(HttpStatus.UNAUTHORIZED, "WEBHOOK_SIGNATURE_INVALID",
                    "HMAC signature verification failed");
        }
        JsonNode body;
        try {
            body = mapper.readTree(rawBody == null ? "" : rawBody);
        } catch (Exception e) {
            callbacks.record(provider, provider, null, null, null, null, "REJECTED", "invalid_json");
            throw e;
        }
        String result;
        try {
            result = webhookService.ingest(provider, rawBody, true);
        } catch (Exception e) {
            callbacks.record(provider, provider, text(body, "event"), statusOf(body), merchantOf(body),
                    body, "REJECTED", e.getMessage());
            throw e;
        }
        callbacks.record(provider, provider, text(body, "event"), statusOf(body), merchantOf(body),
                body, "PROCESSED".equals(result) ? "APPLIED" : "IGNORED", null);
        return ApiResponse.ok(Map.of("result", result));
    }

    private static String statusOf(JsonNode body) {
        String direct = first(body, "status", "txn_status", "ex_status");
        if (!direct.isBlank()) {
            return direct;
        }
        return first(body.path("param"), "status", "txn_status", "ex_status");
    }

    private static String merchantOf(JsonNode body) {
        String direct = first(body, "merchantcode", "merchant_id");
        if (!direct.isBlank()) {
            return direct;
        }
        return first(body.path("param"), "merchantcode", "merchant_id");
    }

    private static String text(JsonNode node, String field) {
        String value = first(node, field);
        return value.isBlank() ? null : value;
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
}
