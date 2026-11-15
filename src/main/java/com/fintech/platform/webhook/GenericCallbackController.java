package com.fintech.platform.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Store-only inbox for providers that do not share the PaySprint or HMAC webhook.
 * The body is written to {@code provider_callbacks} and is not applied to a wallet or KYC row.
 */
@RestController
@RequestMapping("/webhooks/callback")
public class GenericCallbackController {

    private static final Logger log = LoggerFactory.getLogger(GenericCallbackController.class);
    private static final String SOURCE = "callback";
    private static final String DEFAULT_PROVIDER = "callback";

    private final ProviderCallbackLog callbacks;
    private final ObjectMapper mapper;
    private final String webhookSecret;

    public GenericCallbackController(ProviderCallbackLog callbacks, ObjectMapper mapper,
                                     @Value("${app.webhook.secret}") String webhookSecret) {
        this.callbacks = callbacks;
        this.mapper = mapper;
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret;
    }

    @PostMapping
    public ResponseEntity<Map<String, Boolean>> post(
            @RequestHeader(value = "X-Callback-Token", required = false) String token,
            @RequestBody(required = false) String rawBody,
                                                     @RequestParam(value = "provider", required = false) String providerParam,
                                                     @RequestHeader(value = "provider", required = false) String providerHeader,
                                                     HttpServletRequest request) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("received", false));
        }
        if (rawBody != null && rawBody.getBytes(StandardCharsets.UTF_8).length > WebhookInbox.MAX_BODY_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("received", false));
        }
        String provider = provider(providerParam, providerHeader);
        Body body = readBody(rawBody);
        callbacks.record(provider, SOURCE, text(body.node(), "event"), statusOf(body.node()), merchantOf(body.node()),
                body.node(), "STORED", body.reason());
        log.info("GENERIC_CALLBACK stored method=POST provider={} contentType={} bytes={}",
                provider, request.getContentType(), rawBody == null ? 0 : rawBody.length());
        return ResponseEntity.ok(Map.of("received", true));
    }

    @GetMapping
    public ResponseEntity<Map<String, Boolean>> get(
            @RequestHeader(value = "X-Callback-Token", required = false) String token,
            @RequestParam(value = "provider", required = false) String providerParam,
                                                    @RequestHeader(value = "provider", required = false) String providerHeader,
                                                    HttpServletRequest request) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("received", false));
        }
        String provider = provider(providerParam, providerHeader);
        ObjectNode node = mapper.createObjectNode();
        request.getParameterMap().forEach((key, values) -> {
            if (values == null || values.length == 0) {
                node.put(key, "");
            } else if (values.length == 1) {
                node.put(key, values[0]);
            } else {
                node.put(key, String.join(",", values));
            }
        });
        String reason = node.isEmpty() ? "empty_body" : null;
        callbacks.record(provider, SOURCE, text(node, "event"), statusOf(node), merchantOf(node),
                node, "STORED", reason);
        log.info("GENERIC_CALLBACK stored method=GET provider={} params={}", provider, node.size());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("received", true));
    }

    private boolean authorized(String token) {
        if (webhookSecret.isBlank() || token == null || token.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                webhookSecret.trim().getBytes(StandardCharsets.UTF_8),
                token.trim().getBytes(StandardCharsets.UTF_8));
    }

    private Body readBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return new Body(mapper.createObjectNode(), "empty_body");
        }
        try {
            JsonNode parsed = mapper.readTree(rawBody);
            if (parsed == null || parsed.isMissingNode() || parsed.isNull()) {
                return new Body(mapper.createObjectNode().put("raw", rawBody), "not_json");
            }
            return new Body(parsed, null);
        } catch (Exception e) {
            return new Body(mapper.createObjectNode().put("raw", rawBody), "not_json");
        }
    }

    private static String provider(String query, String header) {
        String value = firstText(query, header);
        return value.isBlank() ? DEFAULT_PROVIDER : value;
    }

    private static String statusOf(JsonNode body) {
        String direct = first(body, "status", "txn_status", "ex_status");
        if (!direct.isBlank()) {
            return direct;
        }
        return first(body.path("param"), "status", "txn_status", "ex_status");
    }

    private static String merchantOf(JsonNode body) {
        String direct = first(body, "merchantcode", "merchant_id", "merchantCode");
        if (!direct.isBlank()) {
            return direct;
        }
        return first(body.path("param"), "merchantcode", "merchant_id", "merchantCode");
    }

    private static String text(JsonNode node, String field) {
        String value = first(node, field);
        return value.isBlank() ? null : value;
    }

    private static String first(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) {
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

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private record Body(JsonNode node, String reason) {}
}
