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

/** PaySprint hosted merchant onboarding and biometric activation. */
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
    private static final String LIVE_ACTIVATE_PATH = "/onboard/onboard/activate_merchant";
    private static final String SIT_ACTIVATE_PATH = "/onboard/onboardnew/activate_merchant";
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
        return onboardStatus(merchantCode, mobile, PIPE);
    }

    public JsonNode onboardStatus(String merchantCode, String mobile, String pipe) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("merchantcode", merchantCode);
        body.put("mobile", mobile);
        body.put("pipe", pipe);
        log.info("PAYSPRINT_ONBOARD status merchantcode={} pipe={}", merchantCode, pipe);
        return post(STATUS_PATH, body, false);
    }

    public JsonNode activateMerchant(String merchantCode, String aadhaar, String encryptedPid, String dob,
                                     String pipe, String isCasa, String accessMode, String latitude, String longitude) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("merchantcode", merchantCode);
        body.put("aadhaar", aadhaar);
        body.put("piddata", encryptedPid);
        body.put("dob", dob);
        body.put("is_casa", "1".equals(isCasa) ? "1" : "0");
        body.put("pipe", pipe);
        if ("bank6".equals(pipe)) {
            body.put("accessmode", accessMode);
            body.put("latitude", latitude);
            body.put("longitude", longitude);
        }
        log.info("MERCHANT_EKYC paysprint request pipe={} merchantcode={} is_casa={} pid_length={}",
                pipe, merchantCode, body.get("is_casa"), encryptedPid == null ? 0 : encryptedPid.length());
        return post(activatePath(), body, false);
    }

    /** SIT only serves the OpenAPI path. Live returns 404 for that path. */
    private String onboardUrlPath() {
        return sit() ? SIT_ONBOARD_URL_PATH : LIVE_ONBOARD_URL_PATH;
    }

    private String activatePath() {
        return sit() ? SIT_ACTIVATE_PATH : LIVE_ACTIVATE_PATH;
    }

    private boolean sit() {
        String base = props.baseUrl() == null ? "" : props.baseUrl();
        return base.contains("sit.paysprint.in");
    }

    private JsonNode post(String path, Map<String, String> body, boolean requireStatus) {
        String url = props.baseUrl() + path;
        // The JDK client sends an unsized body as chunked transfer. PaySprint then
        // sees an empty request and answers that every field is required.
        byte[] json = jsonBytes(body);
        log.info("PAYSPRINT_API request POST {} bytes={} body={}", url, json.length, redacted(body));
        String raw;
        try {
            raw = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .contentLength(json.length)
                    .header("Token", tokens.create())
                    .header("Authorisedkey", props.authorisedKey())
                    .body(json)
                    .retrieve()
                    .body(String.class);
            log.info("PAYSPRINT_API response POST {} status=200 body={}", url, logBody(raw));
        } catch (RestClientResponseException e) {
            String responseBody = e.getResponseBodyAsString();
            log.info("PAYSPRINT_API response POST {} status={} body={}", url, e.getStatusCode().value(),
                    logBody(responseBody));
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "PAYSPRINT_ONBOARD_ERROR",
                    PaysprintMessages.fromBody(mapper, responseBody, "Paysprint rejected the request (" + e.getStatusCode().value() + ")"));
        }
        try {
            JsonNode root = mapper.readTree(raw == null ? "" : raw);
            if (requireStatus && !root.path("status").asBoolean(false)) {
                throw ApiException.of(HttpStatus.BAD_GATEWAY, "PAYSPRINT_ONBOARD_ERROR",
                        PaysprintMessages.fromNode(root, "Paysprint rejected the request"));
            }
            return root;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "PAYSPRINT_ONBOARD_ERROR",
                    "Could not parse Paysprint response");
        }
    }

    private byte[] jsonBytes(Map<String, String> body) {
        try {
            return mapper.writeValueAsBytes(body);
        } catch (Exception e) {
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "PAYSPRINT_ONBOARD_ERROR",
                    "Could not prepare the Paysprint request");
        }
    }

    private static String redacted(Map<String, String> body) {
        Map<String, Object> safe = new LinkedHashMap<>();
        body.forEach((key, value) -> {
            String name = key.toLowerCase(java.util.Locale.ROOT);
            if (name.equals("aadhaar") || name.equals("piddata") || name.equals("dob")) {
                safe.put(key + "_length", value == null ? 0 : value.length());
            } else {
                safe.put(key, value);
            }
        });
        return safe.toString();
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
