package com.fintech.bills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.web.ApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class KbillClient {

    private static final Logger log = LoggerFactory.getLogger(KbillClient.class);

    private final KbillProperties props;
    private final RestClient restClient;
    private final ObjectMapper mapper;

    public KbillClient(KbillProperties props, RestClient restClient, ObjectMapper mapper) {
        this.props = props;
        this.restClient = restClient;
        this.mapper = mapper;
    }

    public JsonNode get(String path, Map<String, ?> query) {
        requireConfigured();
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(props.base() + path);
        if (query != null) {
            query.forEach((k, v) -> {
                if (v != null && !String.valueOf(v).isBlank()) {
                    builder.queryParam(k, v);
                }
            });
        }
        String url = builder.encode().build().toUriString();
        try {
            String raw = restClient.get()
                    .uri(url)
                    .header("Authorization", "Bearer " + props.apiToken())
                    .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                    .retrieve()
                    .body(String.class);
            return parse(raw, url);
        } catch (RestClientResponseException e) {
            throw fromHttp(e, url);
        }
    }

    public JsonNode post(String path, Map<String, Object> body) {
        requireConfigured();
        String url = props.base() + path;
        try {
            String raw = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + props.apiToken())
                    .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parse(raw, url);
        } catch (RestClientResponseException e) {
            throw fromHttp(e, url);
        }
    }

    public JsonNode categories() {
        return get("/category", Map.of());
    }

    public JsonNode locations() {
        return get("/location", Map.of());
    }

    public JsonNode operators(int categoryId, Integer locationId) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("category_id", categoryId);
        if (locationId != null) {
            q.put("operator_location_id", locationId);
        }
        return get("/operator-list", q);
    }

    public JsonNode parameters(int operatorId, int categoryId) {
        return get("/operator-parameters", Map.of(
                "operator_id", operatorId,
                "category_id", categoryId));
    }

    public JsonNode fetchBill(Map<String, ?> query) {
        return get("/bill-fetched", query);
    }

    public JsonNode pay(int categoryId, Map<String, Object> body) {
        String path = KbillRails.payPath(categoryId);
        if (path == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "CATEGORY_PAY_UNAVAILABLE",
                    "Payment is not available for this bill type yet");
        }
        return post(path, body);
    }

    private void requireConfigured() {
        if (!props.configured()) {
            throw ApiException.of(HttpStatus.SERVICE_UNAVAILABLE, "KBILL_NOT_CONFIGURED",
                    "Bill pay is not configured");
        }
    }

    private JsonNode parse(String raw, String url) {
        JsonNode root = read(raw);
        if (root.path("status").asBoolean(false) || "01".equals(root.path("response_code").asText())) {
            return root;
        }
        log.info("KBILL reject {} code={} msg={}", url, root.path("response_code").asText(),
                root.path("message").asText());
        throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, kbillCode(root), kbillMessage(root));
    }

    private ApiException fromHttp(RestClientResponseException e, String url) {
        JsonNode root = read(e.getResponseBodyAsString());
        log.info("KBILL http {} status={} code={} msg={}", url, e.getStatusCode().value(),
                root.path("response_code").asText(), root.path("message").asText());
        if (!root.isMissingNode() && root.has("message")) {
            return ApiException.of(mapStatus(e.getStatusCode().value(), root), kbillCode(root), kbillMessage(root));
        }
        return ApiException.of(HttpStatus.BAD_GATEWAY, "KBILL_ERROR", "Could not reach bill pay partner");
    }

    private static HttpStatus mapStatus(int http, JsonNode root) {
        String code = root.path("response_code").asText("");
        if ("07".equals(code)) {
            return HttpStatus.UNPROCESSABLE_ENTITY;
        }
        if (http == 401) {
            return HttpStatus.BAD_GATEWAY;
        }
        if (http == 403) {
            return HttpStatus.BAD_GATEWAY;
        }
        if (http >= 400 && http < 500) {
            return HttpStatus.UNPROCESSABLE_ENTITY;
        }
        return HttpStatus.BAD_GATEWAY;
    }

    private static String kbillCode(JsonNode root) {
        String code = root.path("response_code").asText("");
        return switch (code) {
            case "02" -> "KBILL_AUTH";
            case "03" -> "KBILL_IP";
            case "05" -> "VALIDATION_FAILED";
            case "06" -> "OPERATOR_NOT_FOUND";
            case "07" -> "PARTNER_WALLET_SHORT";
            case "08" -> "BILL_PAY_REJECTED";
            default -> "KBILL_ERROR";
        };
    }

    private static String kbillMessage(JsonNode root) {
        String message = root.path("message").asText("").trim();
        if (message.isBlank() || "null".equalsIgnoreCase(message)) {
            return "Could not complete this bill request";
        }
        return message;
    }

    private JsonNode read(String raw) {
        if (raw == null || raw.isBlank()) {
            return mapper.missingNode();
        }
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            return mapper.missingNode();
        }
    }
}
