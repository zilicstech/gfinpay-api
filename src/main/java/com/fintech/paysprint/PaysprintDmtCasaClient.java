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

/** PaySprint DMT CASA. The retailer merchant code is a body field, never the partner header. */
@Component
public class PaysprintDmtCasaClient {

    private static final Logger log = LoggerFactory.getLogger(PaysprintDmtCasaClient.class);

    private static final String QUERY_REMITTER = "/dmt-casa/Queryremitter";
    private static final String BENE_OTP = "/dmt-casa/beneficiary/sendotp";
    private static final String ADD_BENE = "/dmt-casa/beneficiary/add_bene";
    private static final String BENE_LIST = "/dmt-casa/beneficiary/benelist";
    private static final String DELETE_BENE = "/dmt-casa/beneficiary/deletebene";
    private static final String VERIFY_BENE = "/dmt-casa/beneficiary/benenameverify";
    private static final String FETCH_BENE = "/dmt-casa/beneficiary/fetch_single_bene";
    private static final String TXN_OTP = "/dmt-casa/transact/send_otp";
    private static final String PROCESS = "/dmt-casa/transact/process";
    private static final String STATUS = "/dmt-casa/transact/querytransact";
    private static final String REFUND_OTP = "/dmt-casa/refund/resendotp";
    private static final String REFUND = "/dmt-casa/refund/index";

    private final PaysprintProperties props;
    private final PaysprintTokenFactory tokens;
    private final RestClient restClient;
    private final ObjectMapper mapper;

    public PaysprintDmtCasaClient(PaysprintProperties props,
                                  PaysprintTokenFactory tokens,
                                  RestClient restClient,
                                  ObjectMapper mapper) {
        this.props = props;
        this.tokens = tokens;
        this.restClient = restClient;
        this.mapper = mapper;
    }

    public JsonNode queryRemitter(String merchantCode, String mobile) {
        return post(QUERY_REMITTER, body(merchantCode, mobile));
    }

    public JsonNode sendBeneOtp(String merchantCode, String mobile, String beneName,
                                String bankId, String accountNumber, String ifsc) {
        Map<String, Object> body = body(merchantCode, mobile);
        body.put("benename", beneName);
        body.put("bankid", bankId);
        body.put("accno", accountNumber);
        body.put("ifsccode", ifsc);
        return post(BENE_OTP, body);
    }

    public JsonNode addBene(String merchantCode, String mobile, String beneName, String bankId,
                            String accountNumber, String ifsc, String otp, String stateResp) {
        Map<String, Object> body = body(merchantCode, mobile);
        body.put("benename", beneName);
        body.put("bankid", bankId);
        body.put("accno", accountNumber);
        body.put("ifsccode", ifsc);
        body.put("otp", otp);
        body.put("stateresp", stateResp);
        return post(ADD_BENE, body);
    }

    public JsonNode beneList(String merchantCode, String mobile) {
        return post(BENE_LIST, body(merchantCode, mobile));
    }

    public JsonNode deleteBene(String merchantCode, String mobile, String beneId) {
        Map<String, Object> body = body(merchantCode, mobile);
        body.put("bene_id", beneId);
        return post(DELETE_BENE, body);
    }

    public JsonNode verifyBene(String merchantCode, String referenceId, String mobile, String beneId,
                               String accountNumber, String bankId, String ifsc, String beneName) {
        Map<String, Object> body = body(merchantCode, mobile);
        body.put("referenceid", referenceId);
        body.put("bene_id", beneId);
        body.put("accno", accountNumber);
        body.put("bankid", bankId);
        body.put("ifsccode", ifsc);
        body.put("benename", beneName);
        return post(VERIFY_BENE, body);
    }

    public JsonNode fetchBene(String merchantCode, String mobile, String beneId) {
        Map<String, Object> body = body(merchantCode, mobile);
        body.put("bene_id", beneId);
        return post(FETCH_BENE, body);
    }

    public JsonNode sendTxnOtp(String merchantCode, String mobile, String beneId, String amount) {
        Map<String, Object> body = body(merchantCode, mobile);
        body.put("bene_id", beneId);
        body.put("amount", amount);
        return post(TXN_OTP, body);
    }

    public JsonNode processTxn(String merchantCode, String mobile, String beneId, String referenceId,
                               String txnType, String amount, String otp, String stateResp) {
        Map<String, Object> body = body(merchantCode, mobile);
        body.put("bene_id", beneId);
        body.put("referenceid", referenceId);
        body.put("txntype", txnType);
        body.put("amount", amount);
        body.put("otp", otp);
        body.put("stateresp", stateResp);
        return post(PROCESS, body);
    }

    public JsonNode txnStatus(String referenceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("referenceid", referenceId);
        return post(STATUS, body);
    }

    public JsonNode refundOtp(String merchantCode, String ackno, String referenceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantcode", merchantCode);
        body.put("ackno", ackno);
        body.put("referenceid", referenceId);
        return post(REFUND_OTP, body);
    }

    public JsonNode refundClaim(String merchantCode, String ackno, String referenceId, String otp, String stateResp) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantcode", merchantCode);
        body.put("ackno", ackno);
        body.put("referenceid", referenceId);
        body.put("otp", otp);
        body.put("stateresp", stateResp);
        return post(REFUND, body);
    }

    /** Throws when PaySprint says the call failed. Query remitter and transfer status are interpreted by the caller. */
    public void requireOk(JsonNode root, String fallback) {
        if (root != null && root.path("status").asBoolean(false)) {
            return;
        }
        throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DMT_CASA_ERROR",
                PaysprintMessages.fromNode(root, fallback));
    }

    private static Map<String, Object> body(String merchantCode, String mobile) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantcode", merchantCode);
        body.put("mobile", mobile);
        return body;
    }

    private JsonNode post(String path, Map<String, Object> body) {
        String url = props.baseUrl() + path;
        String partner = props.partnerId() == null ? "" : props.partnerId().trim();
        if (partner.isBlank()) {
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "DMT_CASA_ERROR", "Paysprint partner id is not configured");
        }
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
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "DMT_CASA_ERROR",
                    PaysprintMessages.fromBody(mapper, responseBody,
                            "Paysprint rejected the money transfer request (" + e.getStatusCode().value() + ")"));
        }
        try {
            return mapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);
        } catch (Exception e) {
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "DMT_CASA_ERROR", "Could not parse Paysprint response");
        }
    }

    private byte[] jsonBytes(Map<String, Object> body) {
        try {
            return mapper.writeValueAsBytes(body);
        } catch (Exception e) {
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "DMT_CASA_ERROR", "Could not prepare the Paysprint request");
        }
    }

    /** Headers stay out of the log. An OTP, state token, or account number is recorded only as present. */
    private static String logRequest(Map<String, Object> body) {
        Map<String, Object> safe = new LinkedHashMap<>();
        body.forEach((key, value) -> {
            String name = key.toLowerCase(Locale.ROOT);
            if (name.equals("token") || name.equals("url") || name.equals("encdata")
                    || name.equals("aadhaar") || name.equals("piddata")
                    || name.equals("otp") || name.equals("stateresp")
                    || name.equals("accno") || name.equals("accountno") || name.equals("sdk_token")) {
                String text = value == null ? "" : String.valueOf(value);
                safe.put(key + "_present", !text.isBlank());
            } else {
                safe.put(key, value);
            }
        });
        return safe.toString();
    }
}
