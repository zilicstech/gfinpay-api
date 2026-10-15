package com.fintech.sales;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fintech.paysprint.PaysprintProperties;
import com.fintech.platform.web.ApiException;
import com.fintech.platform.web.ApiResponse;
import com.fintech.platform.webhook.ProviderCallbackLog;
import com.fintech.platform.webhook.WebhookService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhooks/paysprint")
public class PaysprintCallbackController {

    private static final Logger log = LoggerFactory.getLogger(PaysprintCallbackController.class);

    private static final String PROVIDER = "paysprint";

    private final WebhookService webhookService;
    private final ProviderCallbackLog callbacks;
    private final PaysprintProperties props;
    private final ObjectMapper mapper;

    public PaysprintCallbackController(WebhookService webhookService, ProviderCallbackLog callbacks,
                                       PaysprintProperties props, ObjectMapper mapper) {
        this.webhookService = webhookService;
        this.callbacks = callbacks;
        this.props = props;
        this.mapper = mapper;
    }

    @PostMapping({"/fd", "/lead", "/utm"})
    public ApiResponse<Map<String, String>> receive(HttpServletRequest request,
                                                    @RequestHeader(value = "X-Paysprint-Secret", required = false) String secret,
                                                    @RequestBody String rawBody) throws Exception {
        String source = source(request);
        log.info("PAYSPRINT_WEBHOOK url={} body={}", request.getRequestURL(), rawBody);
        if (props.callbackSecret() != null && !props.callbackSecret().isBlank()
                && (secret == null || !props.callbackSecret().equals(secret))) {
            log.info("PAYSPRINT_WEBHOOK rejected url={} reason=invalid_secret", request.getRequestURL());
            callbacks.record(PROVIDER, source, null, null, null, null, "REJECTED", "invalid_secret");
            throw ApiException.of(HttpStatus.UNAUTHORIZED, "WEBHOOK_SECRET_INVALID", "Invalid callback secret");
        }
        JsonNode incoming;
        try {
            incoming = mapper.readTree(rawBody);
        } catch (Exception e) {
            callbacks.record(PROVIDER, source, null, null, null, null, "REJECTED", "invalid_json");
            throw e;
        }
        JsonNode param = incoming.path("param");
        String refid = param.path("refid").asText("unknown");
        ObjectNode wrapped = mapper.createObjectNode();
        wrapped.put("eventId", eventId(incoming, param, refid));
        wrapped.set("event", incoming.path("event"));
        wrapped.set("param", param);
        String result;
        try {
            result = webhookService.ingest("paysprint-fd", mapper.writeValueAsString(wrapped), true);
        } catch (Exception e) {
            callbacks.record(PROVIDER, source, text(incoming, "event"), statusOf(incoming), merchantOf(incoming),
                    incoming, "REJECTED", e.getMessage());
            throw e;
        }
        callbacks.record(PROVIDER, source, text(incoming, "event"), statusOf(incoming), merchantOf(incoming),
                incoming, "PROCESSED".equals(result) ? "APPLIED" : "IGNORED", null);
        return ApiResponse.ok(Map.of("result", result));
    }

    private static String source(HttpServletRequest request) {
        String path = request.getRequestURI();
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
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

    private static String eventId(JsonNode incoming, JsonNode param, String refid) {
        String stamp = param.path("last_update_date").asText(
                param.path("ackno").asText(incoming.path("event").asText("UTM-LEAD-STATUS")));
        String txn = param.path("txn_status").asText(param.path("ex_status").asText("na"));
        return "SALES:" + refid + ":" + stamp + ":" + txn;
    }
}
