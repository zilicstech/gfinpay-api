package com.fintech.paysprint;

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

/** PaySprint hosted merchant onboarding. Does not call activate_merchant. */
@Component
public class PaysprintOnboardClient {

    private static final Logger log = LoggerFactory.getLogger(PaysprintOnboardClient.class);

    /**
     * SIT serves the OpenAPI path. The live host uses {@code /onboard/onboard/getonboardurl},
     * which returns an HTML 404 on sit.paysprint.in.
     */
    private static final String SIT_ONBOARD_URL_PATH = "/onboard/onboardnew/getonboardurl";
    private static final String LIVE_ONBOARD_URL_PATH = "/onboard/onboard/getonboardurl";
    private static final String STATUS_PATH = "/onboard/onboard/getonboardstatus";
    private static final String PIPE = "bank2";

    private final PaysprintProperties props;
    private final PaysprintTokenFactory tokens;
    private final RestClient restClient;
    private final ObjectMapper mapper;

    public PaysprintOnboardClient(PaysprintProperties props,
                                  PaysprintTokenFactory tokens,
                                  RestClient restClient,
                                  ObjectMapper mapper) {
        this.props = props;
        this.tokens = tokens;
        this.restClient = restClient;
        this.mapper = mapper;
    }

    public JsonNode onboardUrl(String merchantCode, String mobile, String email, String firm,
                               String callback, String isNew) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("merchantcode", merchantCode);
        body.put("mobile", mobile);
        body.put("is_new", isNew);
        if (email != null && !email.isBlank()) {
            body.put("email", email);
        }
        body.put("firm", firm);
        body.put("callback", callback);
        log.info("PAYSPRINT_ONBOARD url merchantcode={} is_new={}", merchantCode, isNew);
        return post(onboardUrlPath(), body, true);
    }

    /** Bank 2 pipe status. A false status means not onboarded yet and is returned as-is. */
    public JsonNode onboardStatus(String merchantCode, String mobile) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("merchantcode", merchantCode);
        body.put("mobile", mobile);
        body.put("pipe", PIPE);
        log.info("PAYSPRINT_ONBOARD status merchantcode={} pipe={}", merchantCode, PIPE);
        return post(STATUS_PATH, body, false);
    }

    /** SIT only serves the OpenAPI path. Live returns 404 for that path. */
    private String onboardUrlPath() {
        String base = props.baseUrl() == null ? "" : props.baseUrl();
        if (base.contains("sit.paysprint.in")) {
            return SIT_ONBOARD_URL_PATH;
        }
        return LIVE_ONBOARD_URL_PATH;
    }

    private JsonNode post(String path, Map<String, String> body, boolean requireStatus) {
        String url = props.baseUrl() + path;
        log.info("PAYSPRINT_API request POST {} body={}", url, mapper.valueToTree(body));
        String raw;
        try {
            raw = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Token", tokens.create())
                    .header("Authorisedkey", props.authorisedKey())
                    .body(body)
                    .retrieve()
                    .body(String.class);
            log.info("PAYSPRINT_API response POST {} status=200 body={}", url, logBody(raw));
        } catch (RestClientResponseException e) {
            String responseBody = e.getResponseBodyAsString();
            log.info("PAYSPRINT_API response POST {} status={} body={}", url, e.getStatusCode().value(),
                    logBody(responseBody));
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "PAYSPRINT_ONBOARD_ERROR", message(e, responseBody));
        }
        try {
            JsonNode root = mapper.readTree(raw == null ? "" : raw);
            if (requireStatus && !root.path("status").asBoolean(false)) {
                throw ApiException.of(HttpStatus.BAD_GATEWAY, "PAYSPRINT_ONBOARD_ERROR",
                        root.path("message").asText("Paysprint rejected the request"));
            }
            return root;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "PAYSPRINT_ONBOARD_ERROR",
                    "Could not parse Paysprint response");
        }
    }

    private String message(RestClientResponseException e, String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody == null ? "" : responseBody);
            String message = root.path("message").asText("");
            if (!message.isBlank()) {
                return message;
            }
        } catch (Exception ignored) {
            // HTML 404 and other non-JSON bodies fall through
        }
        if (e.getStatusCode().value() == 404) {
            return "Paysprint onboarding URL was not found (404)";
        }
        return "Paysprint rejected the request (" + e.getStatusCode().value() + ")";
    }

    private static String logBody(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.stripLeading();
        if (trimmed.startsWith("<")) {
            return "html status page";
        }
        return raw;
    }
}
