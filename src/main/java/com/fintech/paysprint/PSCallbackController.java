package com.fintech.paysprint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fintech.cashout.UpiCashoutCallbackService;
import com.fintech.dmt.DmtAccountOpeningService;
import com.fintech.identity.RetailerPaysprintEkycService;
import com.fintech.identity.RetailerPaysprintEkycService.PaysprintCallbackRejected;
import com.fintech.platform.web.ApiException;
import com.fintech.platform.webhook.ProviderCallbackLog;
import io.jsonwebtoken.JwtException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The single PaySprint callback. Their portal has one URL, so every event arrives here
 * and is routed by {@code event}. PID data is never accepted.
 */
@RestController
@RequestMapping("/webhooks/paysprint/onboard")
public class PSCallbackController {

    private static final Logger log = LoggerFactory.getLogger(PSCallbackController.class);

    private static final String PROVIDER = "paysprint";

    private static final String CASHOUT_SOURCE = "upi-cashout";

    private final RetailerPaysprintEkycService ekyc;
    private final UpiCashoutCallbackService cashout;
    private final DmtAccountOpeningService accountOpening;
    private final ProviderCallbackLog callbacks;
    private final ObjectMapper mapper;

    public PSCallbackController(RetailerPaysprintEkycService ekyc,
                                UpiCashoutCallbackService cashout,
                                DmtAccountOpeningService accountOpening,
                                ProviderCallbackLog callbacks,
                                ObjectMapper mapper) {
        this.ekyc = ekyc;
        this.cashout = cashout;
        this.accountOpening = accountOpening;
        this.callbacks = callbacks;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> receive(@RequestBody String rawBody) {
        JsonNode body;
        try {
            body = mapper.readTree(rawBody == null ? "" : rawBody);
        } catch (Exception e) {
            log.info("PAYSPRINT_CALLBACK rejected reason=invalid_json");
            callbacks.record(PROVIDER, "callback", null, null, null, null, "REJECTED", "invalid_json");
            return failed();
        }
        String event = body.path("event").asText("");
        log.info("PAYSPRINT_CALLBACK event={} merchantcode={}", event, merchantCode(body));
        try {
            if ("MERCHANT_ONBOARDING".equals(event)) {
                JsonNode claims = ekyc.verifyCharge(body);
                callbacks.record(PROVIDER, "onboard", event, statusOf(body, claims), merchantCode(body),
                        document(event, body, claims), "IGNORED", null);
                return completed();
            }
            if ("UPI-CASHOUT".equals(event)) {
                return cashout(body, event);
            }
            if ("UPI-CASHOUT-2FA".equals(event)) {
                JsonNode param = body.path("param");
                log.info("UPI_CASHOUT in event=UPI-CASHOUT-2FA merchantcode={} status={} device={} accessmode={} chargeable={} message={}",
                        merchantCode(body),
                        param.path("status").asText(""),
                        text(param, "device_mi"),
                        text(param, "accessmode"),
                        param.path("is_chargeable").asText(""),
                        clip(param.path("message").asText("")));
                cashout.recordTwoFactor(param);
                callbacks.record(PROVIDER, CASHOUT_SOURCE, event, statusOf(body, null), merchantCode(body),
                        document(event, body, null), "IGNORED", null);
                log.info("UPI_CASHOUT out event=UPI-CASHOUT-2FA outcome=IGNORED http=200 merchantcode={}", merchantCode(body));
                return completed();
            }
            if ("DMT_CASA_AO_DEBIT_NOTIFY".equals(event)) {
                return accountOpening(body, event);
            }
            if ("MERCHANT_STATUS_ONBOARD".equals(event)) {
                JsonNode claims = ekyc.verifiedClaims(body);
                JsonNode stored = document(event, body, claims);
                boolean applied = PaysprintOnboardOutcome.statusCallbackComplete(body.path("param"));
                try {
                    ekyc.applyStatus(body);
                } catch (PaysprintCallbackRejected e) {
                    log.info("PAYSPRINT_CALLBACK rejected event={} reason={}", event, e.getMessage());
                    callbacks.record(PROVIDER, "onboard", event, statusOf(body, claims), merchantCode(body),
                            stored, "REJECTED", e.getMessage());
                    return failed();
                }
                callbacks.record(PROVIDER, "onboard", event, statusOf(body, claims), merchantCode(body),
                        stored, applied ? "APPLIED" : "IGNORED", null);
                return completed();
            }
            log.info("PAYSPRINT_CALLBACK rejected event={} reason=unknown_event", event);
            callbacks.record(PROVIDER, "callback", event, null, merchantCode(body), null, "REJECTED", "unknown_event");
            return failed();
        } catch (PaysprintCallbackRejected | JwtException | IllegalArgumentException e) {
            log.info("PAYSPRINT_CALLBACK rejected event={} reason={}", event, e.getMessage());
            callbacks.record(PROVIDER, "callback", event, null, merchantCode(body), null, "REJECTED", e.getMessage());
            return failed();
        }
    }

    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> redirect(@RequestParam(value = "data", required = false) String data) {
        if (data == null || data.isBlank()) {
            callbacks.record(PROVIDER, "redirect", null, null, null, null, "REJECTED", "missing_token");
            return html(HttpStatus.BAD_REQUEST, "This eKYC result could not be confirmed.");
        }
        JsonNode claims;
        try {
            claims = ekyc.redirectClaims(data);
        } catch (PaysprintCallbackRejected | JwtException | IllegalArgumentException e) {
            log.info("PAYSPRINT_CALLBACK redirect rejected reason={}", e.getMessage());
            callbacks.record(PROVIDER, "redirect", null, null, null, null, "REJECTED", e.getMessage());
            return html(HttpStatus.BAD_REQUEST, "This eKYC result could not be confirmed.");
        }
        try {
            boolean done = ekyc.applyRedirect(data);
            callbacks.record(PROVIDER, "redirect", text(claims, "event"), statusOf(null, claims),
                    first(claims, "merchantcode", "merchant_id"), claims, done ? "APPLIED" : "IGNORED", null);
            if (done) {
                return html(HttpStatus.OK, "eKYC is completed. You can close this page.");
            }
            return html(HttpStatus.OK, "eKYC is not finished yet. Close this page and open the link again.");
        } catch (PaysprintCallbackRejected | JwtException | IllegalArgumentException e) {
            log.info("PAYSPRINT_CALLBACK redirect rejected reason={}", e.getMessage());
            callbacks.record(PROVIDER, "redirect", text(claims, "event"), statusOf(null, claims),
                    first(claims, "merchantcode", "merchant_id"), claims, "REJECTED", e.getMessage());
            return html(HttpStatus.BAD_REQUEST, "This eKYC result could not be confirmed.");
        }
    }

    private ResponseEntity<Map<String, Object>> accountOpening(JsonNode body, String event) {
        JsonNode param = body.path("param");
        JsonNode data = param.path("data");
        int responseCode = param.path("response_code").asInt(-1);
        String merchant = merchantCode(body);
        log.info("DMT_CASA ao in event={} merchantcode={} response_code={} mobile={} amount={} message={}",
                event, merchant, responseCode, data.path("mobile").asText(""), data.path("amount").asText(""),
                clip(param.path("message").asText("")));
        JsonNode claims;
        try {
            claims = accountOpening.verifiedClaims(body);
        } catch (JwtException | IllegalArgumentException e) {
            log.info("DMT_CASA ao out outcome=REJECTED http=400 merchantcode={} response_code={} reason={}",
                    merchant, responseCode, e.getMessage());
            callbacks.record(PROVIDER, "dmt-casa", event, Integer.toString(responseCode), merchant, null, "REJECTED", e.getMessage());
            return failed();
        }
        JsonNode stored = document(event, body, claims);
        try {
            if (responseCode == 0) {
                long refid = accountOpening.debit(data);
                callbacks.record(PROVIDER, "dmt-casa", event, Integer.toString(responseCode), merchant, stored, "APPLIED", null);
                log.info("DMT_CASA ao out outcome=APPLIED http=200 merchantcode={} response_code={} refid={}",
                        merchant, responseCode, refid);
                return ResponseEntity.ok(Map.of(
                        "status", 200,
                        "message", "Success",
                        "refid", refid,
                        "redirect_url", accountOpening.redirectUrl()));
            }
            if (responseCode == 1) {
                accountOpening.complete(data);
                callbacks.record(PROVIDER, "dmt-casa", event, Integer.toString(responseCode), merchant, stored, "APPLIED", null);
                log.info("DMT_CASA ao out outcome=APPLIED http=200 merchantcode={} response_code=1", merchant);
                return completed();
            }
            log.info("DMT_CASA ao out outcome=IGNORED http=200 merchantcode={} response_code={}", merchant, responseCode);
            callbacks.record(PROVIDER, "dmt-casa", event, Integer.toString(responseCode), merchant, stored, "IGNORED", null);
            return completed();
        } catch (DmtAccountOpeningService.Rejected | ApiException e) {
            log.info("DMT_CASA ao out outcome=REJECTED http=400 merchantcode={} response_code={} reason={}",
                    merchant, responseCode, e.getMessage());
            callbacks.record(PROVIDER, "dmt-casa", event, Integer.toString(responseCode), merchant, stored, "REJECTED", e.getMessage());
            return failed();
        }
    }

    private ResponseEntity<Map<String, Object>> cashout(JsonNode body, String event) {
        JsonNode data = body.path("param").path("data");
        String txnStatus = UpiCashoutCallbackService.txnStatus(data);
        String merchant = merchantCode(body);
        String refid = data.path("refid").asText("");
        String responseCode = body.path("param").path("response_code").asText("");
        log.info("UPI_CASHOUT in event={} merchantcode={} response_code={} txn_status={} refid={} amount={} mobile={} message={}",
                event, merchant, responseCode, txnStatus, refid,
                data.path("amount").asText(""),
                data.path("mobile").asText(""),
                clip(body.path("param").path("message").asText("")));
        JsonNode claims;
        try {
            claims = cashout.verifiedClaims(body);
        } catch (JwtException | IllegalArgumentException e) {
            log.info("UPI_CASHOUT out event={} outcome=REJECTED http=400 merchantcode={} txn_status={} refid={} reason={}",
                    event, merchant, txnStatus, refid, e.getMessage());
            callbacks.record(PROVIDER, CASHOUT_SOURCE, event, txnStatus, merchant, null, "REJECTED", e.getMessage());
            return failed();
        }
        JsonNode stored = document(event, body, claims);
        boolean initiate = body.path("param").path("response_code").asInt(-1) == 0 && "2".equals(txnStatus);
        try {
            if (initiate) {
                long minted = cashout.initiate(data);
                callbacks.record(PROVIDER, CASHOUT_SOURCE, event, txnStatus, merchant, stored, "APPLIED", null);
                log.info("UPI_CASHOUT out event={} outcome=APPLIED http=200 merchantcode={} txn_status={} refid={}",
                        event, merchant, txnStatus, minted);
                return ResponseEntity.ok(Map.of(
                        "status", 200, "refid", minted, "message", "Transaction completed successfully"));
            }
            boolean applied = cashout.acknowledge(data);
            String outcome = applied ? "APPLIED" : "IGNORED";
            callbacks.record(PROVIDER, CASHOUT_SOURCE, event, txnStatus, merchant, stored, outcome, null);
            log.info("UPI_CASHOUT out event={} outcome={} http=200 merchantcode={} txn_status={} refid={} ackno={} rrn={} errcode={}",
                    event, outcome, merchant, txnStatus, refid,
                    data.path("ackno").asText(""),
                    first(data, "original_bank_rrn", "rrn"),
                    data.path("errcode").asText(""));
            return completed();
        } catch (UpiCashoutCallbackService.Rejected | ApiException e) {
            log.info("UPI_CASHOUT out event={} outcome=REJECTED http=400 merchantcode={} txn_status={} refid={} reason={}",
                    event, merchant, txnStatus, refid, e.getMessage());
            callbacks.record(PROVIDER, CASHOUT_SOURCE, event, txnStatus, merchant, stored, "REJECTED", e.getMessage());
            return failed();
        }
    }

    private JsonNode document(String event, JsonNode body, JsonNode claims) {
        ObjectNode document = mapper.createObjectNode();
        document.put("event", event);
        JsonNode param = body.path("param");
        if (param.isObject()) {
            document.set("param", param);
        }
        if (claims != null && !claims.isNull()) {
            document.set("claims", claims);
        }
        return document;
    }

    private static String statusOf(JsonNode body, JsonNode claims) {
        String fromParam = body == null ? "" : first(body.path("param"), "status", "txn_status", "ex_status");
        if (!fromParam.isBlank()) {
            return fromParam;
        }
        String fromData = body == null ? "" : body.path("param").path("data").path("txn_status").asText("").trim();
        if (!fromData.isBlank()) {
            return fromData;
        }
        return first(claims, "status", "txn_status", "ex_status");
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

    private static String text(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.path(field).asText("").trim();
        return value.isBlank() ? null : value;
    }

    private static String clip(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= 160 ? trimmed : trimmed.substring(0, 160);
    }

    private static String merchantCode(JsonNode body) {
        String code = UpiCashoutCallbackService.merchantCode(body.path("param"));
        if (!code.isBlank()) {
            return code;
        }
        return body.path("param").path("merchantcode").asText(
                body.path("param").path("merchant_id").asText(""));
    }

    private static ResponseEntity<Map<String, Object>> completed() {
        return ResponseEntity.ok(Map.of("status", 200, "message", "Transaction completed successfully"));
    }

    private static ResponseEntity<Map<String, Object>> failed() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("status", 400, "message", "Transaction Failed"));
    }

    private static ResponseEntity<String> html(HttpStatus status, String message) {
        String safe = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        String page = """
                <!doctype html>
                <html><head><meta charset="utf-8"><title>eKYC</title></head>
                <body style="font-family:sans-serif;padding:2rem"><p>%s</p></body></html>
                """.formatted(safe);
        return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).body(page);
    }
}
