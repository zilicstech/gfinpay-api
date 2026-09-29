package com.fintech.paysprint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.web.ApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class PaysprintLeadClient {

    public record Journey(String url, String encdata) {}

    private final PaysprintProperties props;
    private final PaysprintTokenFactory tokens;
    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final String selfBaseUrl;

    public PaysprintLeadClient(PaysprintProperties props,
                               PaysprintTokenFactory tokens,
                               RestClient restClient,
                               ObjectMapper mapper,
                               @Value("${app.self-base-url}") String selfBaseUrl) {
        this.props = props;
        this.tokens = tokens;
        this.restClient = restClient;
        this.mapper = mapper;
        this.selfBaseUrl = selfBaseUrl.replaceAll("/$", "");
    }

    public Journey generate(String refid, String name, String mobile, String email, String product, String redirectUrl) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("refid", refid);
        body.put("merchantcode", merchantCode());
        body.put("name", name);
        body.put("mobile_no", mobile);
        body.put("email", email == null ? "" : email);
        body.put("product", product);
        JsonNode data = post("/lead/generation", body);
        return new Journey(data.path("url").asText(), data.path("encdata").asText());
    }

    private String merchantCode() {
        return props.merchantCodeOrPartner();
    }

    private JsonNode post(String path, Map<String, String> body) {
        String raw;
        try {
            raw = restClient.post()
                    .uri(props.baseUrl() + path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Token", tokens.create())
                    .header("Authorisedkey", props.authorisedKey())
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "PAYSPRINT_LEAD_ERROR", message(e));
        }
        try {
            JsonNode root = mapper.readTree(raw);
            if (!root.path("status").asBoolean(false)) {
                throw ApiException.of(HttpStatus.BAD_GATEWAY, "PAYSPRINT_LEAD_ERROR",
                        root.path("message").asText("Paysprint rejected the request"));
            }
            return root.path("data");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "PAYSPRINT_LEAD_ERROR", "Could not parse Paysprint response");
        }
    }

    private String message(RestClientResponseException e) {
        try {
            String text = mapper.readTree(e.getResponseBodyAsString()).path("message").asText("");
            if (!text.isBlank()) {
                return text;
            }
        } catch (Exception ignored) {
            // use status fallback
        }
        return "Paysprint rejected the request (" + e.getStatusCode().value() + ")";
    }
}
