package com.fintech.paysprint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.web.ApiException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** PaySprint hosted UPI cash-out. The amount and QR live on their page; we only mint a token. */
@Component
public class PaysprintUpiCashoutClient {

    private static final Logger log = LoggerFactory.getLogger(PaysprintUpiCashoutClient.class);

    private static final String TOKEN_PATH = "/upi/cashout/get_token";
    private static final String STATUS_PATH = "/upi/cashout/txn_status";

    private final PaysprintProperties props;
    private final PaysprintTokenFactory tokens;
    private final RestClient restClient;
    private final ObjectMapper mapper;

    public PaysprintUpiCashoutClient(PaysprintProperties props,
                                     PaysprintTokenFactory tokens,
                                     RestClient restClient,
                                     ObjectMapper mapper) {
        this.props = props;
        this.tokens = tokens;
        this.restClient = restClient;
        this.mapper = mapper;
    }

    /** Hosted page URL. {@code redirectUrl} is required for the web flow. */
    public String getToken(String merchantCode, String redirectUrl) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("merchant_code", merchantCode);
        body.put("redirect_url", redirectUrl);
        log.info("UPI_CASHOUT token request merchantcode={}", merchantCode);
        JsonNode root = post(TOKEN_PATH, body);
        String url = root.path("url").asText("").trim();
        log.info("UPI_CASHOUT token response merchantcode={} response_code={} message={} url_present={} url={}",
                merchantCode, root.path("response_code").asText(""), root.path("message").asText(""),
                !url.isBlank(), url);
        if (!root.path("status").asBoolean(false) || url.isBlank()) {
            String message = url.isBlank() && root.path("status").asBoolean(false)
                    ? "Paysprint did not return a cash-out link"
                    : PaysprintMessages.fromNode(root, "Paysprint did not return a cash-out link");
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "PAYSPRINT_CASHOUT_ERROR", message);
        }
        return url;
    }

    public JsonNode txnStatus(String merchantCode, String refid) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("merchant_code", merchantCode);
        body.put("refid", refid);
        log.info("UPI_CASHOUT status request merchantcode={} refid={}", merchantCode, refid);
        JsonNode root = post(STATUS_PATH, body);
        JsonNode data = root.path("data");
        String txnStatus = data.path("txn_status").asText(root.path("txn_status").asText(""));
        log.info("UPI_CASHOUT status response merchantcode={} refid={} response_code={} txn_status={} message={}",
                merchantCode, refid, root.path("response_code").asText(""), txnStatus, root.path("message").asText(""));
        return root;
    }

    private JsonNode post(String path, Map<String, String> body) {
        String url = props.baseUrl() + path;
        String partner = props.partnerId() == null ? "" : props.partnerId().trim();
        if (partner.isBlank()) {
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "PAYSPRINT_CASHOUT_ERROR",
                    "Paysprint partner id is not configured");
        }
        // The JDK client sends an unsized body as chunked transfer. PaySprint then
        // sees an empty request and answers that every field is required.
        byte[] json = jsonBytes(body);
        log.info("PAYSPRINT_API request POST {} bytes={} partner={} body={}", url, json.length, partner, logRequest(body));
        String raw;
        try {
            raw = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .contentLength(json.length)
                    .header("Token", tokens.create())
                    .header("Authorisedkey", props.authorisedKey())
                    .header("User-Agent", partner)
                    .body(json)
                    .retrieve()
                    .body(String.class);
            log.info("PAYSPRINT_API response POST {} status=200 body={}", url, raw == null ? "" : raw);
        } catch (RestClientResponseException e) {
            String responseBody = e.getResponseBodyAsString();
            log.info("PAYSPRINT_API response POST {} status={} body={}", url, e.getStatusCode().value(),
                    responseBody == null ? "" : responseBody);
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "PAYSPRINT_CASHOUT_ERROR",
                    PaysprintMessages.fromBody(mapper, responseBody,
                            "Paysprint rejected the cash-out request (" + e.getStatusCode().value() + ")"));
        }
        try {
            return mapper.readTree(raw == null ? "" : raw);
        } catch (Exception e) {
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "PAYSPRINT_CASHOUT_ERROR",
                    "Could not parse Paysprint response");
        }
    }

    private byte[] jsonBytes(Map<String, String> body) {
        try {
            return mapper.writeValueAsBytes(body);
        } catch (Exception e) {
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "PAYSPRINT_CASHOUT_ERROR",
                    "Could not prepare the Paysprint request");
        }
    }

    /** Headers stay out of the log. A token or hosted link in the body is recorded only as present. */
    private static String logRequest(Map<String, String> body) {
        Map<String, Object> safe = new LinkedHashMap<>();
        body.forEach((key, value) -> {
            String name = key.toLowerCase(Locale.ROOT);
            if (name.equals("token") || name.equals("url") || name.equals("encdata")
                    || name.equals("aadhaar") || name.equals("piddata")) {
                safe.put(key + "_present", value != null && !value.isBlank());
            } else {
                safe.put(key, value);
            }
        });
        return safe.toString();
    }
}
