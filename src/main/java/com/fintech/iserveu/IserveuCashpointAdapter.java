package com.fintech.iserveu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.provider.ChannelLimits;
import com.fintech.provider.ServiceProviderRouter;
import com.fintech.provider.canonical.ProviderModels;
import com.fintech.provider.port.UpiCashoutPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Cash-Point dynamic QR. The body and header secret are AES-encrypted; the pass key stays a header. */
@Component
public class IserveuCashpointAdapter implements UpiCashoutPort {

    private static final Logger log = LoggerFactory.getLogger(IserveuCashpointAdapter.class);

    private final IserveuProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public IserveuCashpointAdapter(IserveuProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    @Override
    public String providerCode() {
        return "ISERVEU";
    }

    @Override
    public ProviderModels.QrResult generateQr(ServiceProviderRouter.RoutedChannel channel, ProviderModels.QrRequest request) {
        if (!props.configured()) {
            return new ProviderModels.QrResult(false, "Cash terminal is not configured.", "", "", "", 90, "");
        }
        try {
            byte[] key = IserveuCrypto.key(props.encKey());
            String clientRef = request.externalRef().length() > 19
                    ? request.externalRef().substring(0, 19)
                    : request.externalRef();
            String payee = ChannelLimits.text(channel.configJson(), "payeeVpa", "");
            Map<String, Object> body = requestBody(request, clientRef, payee);
            String headerJson = mapper.writeValueAsString(Map.of(
                    "client_id", props.clientId(),
                    "client_secret", props.clientSecret(),
                    "epoch", String.valueOf(Instant.now().getEpochSecond())));
            String requestData = mapper.writeValueAsString(Map.of("RequestData", IserveuCrypto.encrypt(mapper.writeValueAsString(body), key)));
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(props.baseUrl()))
                    .timeout(Duration.ofSeconds(25))
                    .header("Content-Type", "application/json")
                    .header("pass_key", props.passKey())
                    .header("header_secrets", IserveuCrypto.encrypt(headerJson, key))
                    .POST(HttpRequest.BodyPublishers.ofString(requestData))
                    .build();
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            JsonNode node = readResponse(response.body(), key);
            ProviderModels.QrResult result = interpret(node);
            log.info("ISERVEU_QR status={} http={} clientRef={}", result.success() ? "OK" : "FAILED", response.statusCode(), clientRef);
            return result;
        } catch (Exception e) {
            log.warn("ISERVEU_QR call failed: {}", e.getMessage());
            return new ProviderModels.QrResult(false, "Unable to generate UPI QR right now. Please try again shortly.", "", "", "", 90, "");
        }
    }

    @Override
    public ProviderModels.StatusResult checkStatus(
            ServiceProviderRouter.RoutedChannel channel,
            String providerTxnId,
            String externalRef,
            String latitude,
            String longitude) {
        return new ProviderModels.StatusResult("PENDING", "", "Awaiting bank callback", providerTxnId, "", "", "");
    }

    static ProviderModels.QrResult interpret(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return new ProviderModels.QrResult(false, "Empty response from the cash terminal.", "", "", "", 90, "");
        }
        String status = text(node, "status").toUpperCase();
        String paymentState = text(node, "paymentState").toUpperCase();
        int statusCode = node.path("statusCode").asInt(0);
        String desc = text(node, "statusDesc");
        if (desc.isBlank()) {
            desc = text(node, "message");
        }
        String qrData = text(node, "qrData");
        String intent = text(node, "intentData");
        String txnId = text(node, "txnId");
        String amount = node.path("amount").isNumber() ? node.path("amount").asText() : text(node, "amount");
        boolean failed = "FAILED".equals(status) || "FAILED".equals(paymentState) || statusCode == -1 || qrData.isBlank() && intent.isBlank();
        if (failed) {
            return new ProviderModels.QrResult(false, desc.isBlank() ? "QR was not issued." : desc, txnId, "", "", 90, amount);
        }
        if (!qrData.isBlank()) {
            return new ProviderModels.QrResult(true, desc, txnId, "", qrData, 90, amount);
        }
        return new ProviderModels.QrResult(true, desc, txnId, intent, "", 90, amount);
    }

    private Map<String, Object> requestBody(ProviderModels.QrRequest request, String clientRef, String payee) {
        Map<String, Object> device = new LinkedHashMap<>();
        device.put("deviceType", "Web");
        device.put("androidOsVersion", "14");
        device.put("deviceVersion", "1.0");
        device.put("androidAgent", "Web");
        device.put("platformType", "WEB");
        device.put("deviceSerialNo", "WEB");
        device.put("mobileNo", request.mobile());
        device.put("ip", "127.0.0.1");
        device.put("packageName", "com.gfinpay.cash");
        device.put("version", "1.0.0");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("payeeVPA", payee);
        body.put("paymentMode", "UPI_CASHPOINT");
        body.put("tid", clientRef);
        body.put("txnAmount", String.valueOf(request.amountRupees()));
        body.put("channelId", "WEBUSER");
        body.put("txnNote", "Cashout");
        body.put("clientRefId", clientRef);
        body.put("merchantType", "BC");
        body.put("expiryTime", "10");
        body.put("param_a", "");
        body.put("param_b", "");
        body.put("param_c", "");
        body.put("mobilenumber", request.mobile());
        body.put("deviceInfo", device);
        body.put("lat_long", request.latitude() + "," + request.longitude());
        return body;
    }

    private JsonNode readResponse(String raw, byte[] key) throws Exception {
        if (raw == null || raw.isBlank()) {
            return mapper.createObjectNode();
        }
        JsonNode root = mapper.readTree(raw);
        String encrypted = root.path("ResponseData").asText("");
        if (!encrypted.isBlank()) {
            return mapper.readTree(IserveuCrypto.decrypt(encrypted, key));
        }
        return root;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }
}
