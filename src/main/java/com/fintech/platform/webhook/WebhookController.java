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

    public WebhookController(WebhookService webhookService, WebhookSigner signer, ProviderCallbackLog callbacks,
                             ObjectMapper mapper) {
        this.webhookService = webhookService;
        this.signer = signer;
        this.callbacks = callbacks;
        this.mapper = mapper;
    }

    @PostMapping("/iserveu/upi-cashpoint")
    public ResponseEntity<Map<String, Object>> iserveuCashpoint(
            @RequestHeader(value = "pass_key", required = false) String passKey,
            @RequestHeader(value = "header_secrets", required = false) String headerSecrets,
            @RequestBody(required = false) String rawBody) {
        log.info("ISERVEU_CASHPOINT_WEBHOOK passKeyPresent={} headerSecretsPresent={} body={}",
                passKey != null && !passKey.isBlank(),
                headerSecrets != null && !headerSecrets.isBlank(),
                rawBody);
        return ResponseEntity.ok(Map.of("status", 0, "statusDesc", "success"));
    }

    @PostMapping("/{provider}")
    public ApiResponse<Map<String, String>> receive(@PathVariable String provider,
                                                    @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
                                                    @RequestBody String rawBody) throws Exception {
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
