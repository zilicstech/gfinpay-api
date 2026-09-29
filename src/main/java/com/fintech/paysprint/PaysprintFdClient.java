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

/**
 * PaySprint credit-card UTM APIs used for FD cards.
 * @see <a href="https://pay-sprint.readme.io/reference/create-credit-card-generate-utm-link-api">Generate UTM</a>
 * @see <a href="https://pay-sprint.readme.io/reference/utm-status-check">UTM Status Check</a>
 */
@Component
public class PaysprintFdClient {

    private static final Logger log = LoggerFactory.getLogger(PaysprintFdClient.class);

    public record GenerateUrlResult(String url, String encdata) {}

    private static final String GENERATE_UTM_PATH = "/lead/creditcard/get_utm";
    private static final String STATUS_CHECK_PATH = "/lead/creditcard/status_check";
    /** Secured FD card, per PaySprint type enum. */
    private static final String SECURED_CARD_TYPE = "1";

    private final PaysprintProperties props;
    private final PaysprintTokenFactory tokens;
    private final RestClient restClient;
    private final ObjectMapper mapper;

    public PaysprintFdClient(PaysprintProperties props,
                             PaysprintTokenFactory tokens,
                             RestClient restClient,
                             ObjectMapper mapper) {
        this.props = props;
        this.tokens = tokens;
        this.restClient = restClient;
        this.mapper = mapper;
    }

    public GenerateUrlResult generateUrl(String refid, String merchantCode) {
        Map<String, String> request = new LinkedHashMap<>();
        request.put("refid", refid);
        request.put("merchantcode", merchantCode);
        request.put("type", SECURED_CARD_TYPE);
        log.info("PAYSPRINT_UTM generate refid={} merchantcode={} type={}", refid, merchantCode, SECURED_CARD_TYPE);
        JsonNode root = post(GENERATE_UTM_PATH, request, "PAYSPRINT_FD_ERROR");
        JsonNode data = root.path("data");
        String link = data.path("link").asText("");
        if (link.isBlank()) {
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "PAYSPRINT_FD_ERROR",
                    "Paysprint did not return a UTM link");
        }
        return new GenerateUrlResult(link, data.path("request_id").asText(""));
    }

    public JsonNode statusCheck(String refid) {
        Map<String, String> request = new LinkedHashMap<>();
        request.put("refid", refid);
        log.info("PAYSPRINT_UTM status_check refid={}", refid);
        return post(STATUS_CHECK_PATH, request, "PAYSPRINT_FD_STATUS_ERROR").path("data");
    }

    private JsonNode post(String path, Map<String, String> request, String errorCode) {
        String body;
        try {
            body = restClient.post()
                    .uri(props.baseUrl() + path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Token", tokens.create())
                    .header("Authorisedkey", props.authorisedKey())
                    .body(request)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw ApiException.of(HttpStatus.BAD_GATEWAY, errorCode, paysprintMessage(e));
        }
        try {
            JsonNode root = mapper.readTree(body);
            if (!root.path("status").asBoolean(false)) {
                throw ApiException.of(HttpStatus.BAD_GATEWAY, errorCode,
                        root.path("message").asText("Paysprint rejected the request"));
            }
            return root;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.of(HttpStatus.BAD_GATEWAY, errorCode, "Could not parse Paysprint response");
        }
    }

    private String paysprintMessage(RestClientResponseException e) {
        try {
            JsonNode root = mapper.readTree(e.getResponseBodyAsString());
            String message = root.path("message").asText("");
            if (!message.isBlank()) {
                return message;
            }
        } catch (Exception ignored) {
            // fall through to status text
        }
        return "Paysprint rejected the request (" + e.getStatusCode().value() + ")";
    }
}
