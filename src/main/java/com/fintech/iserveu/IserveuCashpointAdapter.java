package com.fintech.iserveu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fintech.provider.ServiceProviderRouter;
import com.fintech.provider.canonical.ProviderModels;
import com.fintech.provider.port.UpiCashoutPort;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Cash-Point dynamic QR. The body and header secret are AES-encrypted; the pass key stays a header. */
@Component
public class IserveuCashpointAdapter implements UpiCashoutPort {

    private static final Logger log = LoggerFactory.getLogger(IserveuCashpointAdapter.class);

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final IserveuProperties props;
    private final IserveuGateway gateway;

    public IserveuCashpointAdapter(IserveuProperties props, IserveuGateway gateway) {
        this.props = props;
        this.gateway = gateway;
    }

    @Override
    public String providerCode() {
        return "ISERVEU";
    }

    @Override
    public ProviderModels.QrResult generateQr(ServiceProviderRouter.RoutedChannel channel, ProviderModels.QrRequest request) {
        if (!props.cashpointReady()) {
            gateway.noteNotSent("CASHPOINT_QR", "Cash terminal is not configured.", request);
            return new ProviderModels.QrResult(false, "Cash terminal is not configured.", "", "", "", 90, "");
        }
        String payee = channel.payeeVpa() == null ? "" : channel.payeeVpa().trim();
        if (payee.isBlank()) {
            gateway.noteNotSent("CASHPOINT_QR", "Cash terminal is not configured.", request);
            return new ProviderModels.QrResult(false, "Cash terminal is not configured.", "", "", "", 90, "");
        }
        try {
            String clientRef = request.externalRef().length() > 19
                    ? request.externalRef().substring(0, 19)
                    : request.externalRef();
            JsonNode node = gateway.post(props.cashpointUrl(), requestBody(request, clientRef, payee));
            ProviderModels.QrResult result = interpret(node);
            log.info("ISERVEU_QR status={} clientRef={}", result.success() ? "OK" : "FAILED", clientRef);
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
            String longitude,
            String txnDate) {
        if (!props.cashpointStatusReady() || externalRef == null || externalRef.isBlank()) {
            return new ProviderModels.StatusResult("PENDING", "", "Awaiting bank callback", providerTxnId, "", "", "");
        }
        String day = txnDate == null || txnDate.isBlank() ? LocalDate.now(INDIA).toString() : txnDate.trim();
        try {
            JsonNode node = gateway.post(props.cashpointStatusUrl(), Map.of(
                    "productCode", "UPI",
                    "txnDate", day,
                    "clientRefId", externalRef.length() > 19 ? externalRef.substring(0, 19) : externalRef));
            return interpretStatus(node, providerTxnId);
        } catch (Exception e) {
            log.warn("ISERVEU_QR status call failed: {}", e.getMessage());
            return new ProviderModels.StatusResult("PENDING", "", "Awaiting bank callback", providerTxnId, "", "", "");
        }
    }

    public static ProviderModels.StatusResult interpretStatus(JsonNode node, String fallbackTxnId) {
        if (node == null || node.isMissingNode()) {
            return new ProviderModels.StatusResult("PENDING", "", "Awaiting bank callback", fallbackTxnId, "", "", "");
        }
        int length = node.path("length").asInt(-1);
        String message = text(node, "message");
        JsonNode results = node.path("results");
        if (!results.isArray() || results.isEmpty() || length == 0 || message.toLowerCase(Locale.ROOT).contains("no transaction")) {
            return new ProviderModels.StatusResult("PENDING", "", message.isBlank() ? "Awaiting bank callback" : message, fallbackTxnId, "", "", "");
        }
        JsonNode row = results.get(0);
        String status = text(row, "status").toUpperCase(Locale.ROOT);
        String mapped = switch (status) {
            case "SUCCESS" -> "SUCCESS";
            case "FAILED" -> "FAILED";
            default -> "PENDING";
        };
        String txnId = firstText(text(row, "txnId"), fallbackTxnId);
        return new ProviderModels.StatusResult(
                mapped,
                status,
                firstText(text(row, "statusDesc"), message),
                txnId,
                text(row, "rrn"),
                firstText(text(row, "payerVPA"), text(row, "payer_vpa")),
                text(row, "userName"));
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

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }
}
