package com.fintech.iserveu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fintech.provider.ServiceProviderRouter;
import com.fintech.provider.canonical.ProviderModels;
import com.fintech.provider.port.PayoutPort;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** iServeU wallet settlement. Transfer uses the cash-transfer URL; status uses the payout status URL. */
@Component
public class IserveuPayoutAdapter implements PayoutPort {

    private static final Logger log = LoggerFactory.getLogger(IserveuPayoutAdapter.class);
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final IserveuProperties props;
    private final IserveuGateway gateway;

    public IserveuPayoutAdapter(IserveuProperties props, IserveuGateway gateway) {
        this.props = props;
        this.gateway = gateway;
    }

    @Override
    public String providerCode() {
        return "ISERVEU";
    }

    @Override
    public ProviderModels.PayoutResult submit(ServiceProviderRouter.RoutedChannel channel, ProviderModels.PayoutRequest request) {
        if (!props.payoutSubmitReady()) {
            gateway.noteNotSent("PAYOUT", "Settlement transfer is not available on this terminal yet.", request);
            return failed("NOT_CONFIGURED", "Settlement transfer is not available on this terminal yet.");
        }
        String mode = request.mode() == null ? "" : request.mode().trim().toUpperCase(Locale.ROOT);
        if (!"IMPS".equals(mode) && !"NEFT".equals(mode)) {
            return failed("INVALID_TRANSFER_MODE", "iServeU settlement supports IMPS and NEFT.");
        }
        try {
            return interpretSubmit(gateway.post(props.payoutUrl(), transferBody(request, mode)));
        } catch (Exception e) {
            log.warn("ISERVEU_PAYOUT submit call failed: {}", e.getMessage());
            throw new IllegalStateException("iServeU settlement call did not complete", e);
        }
    }

    static Map<String, Object> transferBody(ProviderModels.PayoutRequest request, String mode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("beneName", clip(request.accountHolder(), 100));
        body.put("beneAccountNo", digits(request.accountNumber()));
        body.put("beneifsc", request.ifsc() == null ? "" : request.ifsc().trim().toUpperCase(Locale.ROOT));
        body.put("benePhoneNo", Long.parseLong(digits(request.customerMobile())));
        body.put("beneBankName", clip(request.bankName(), 50));
        body.put("clientReferenceNo", request.externalRef());
        body.put("amount", new BigDecimal(request.amount()).setScale(2, RoundingMode.HALF_UP));
        body.put("fundTransferType", mode);
        body.put("pincode", Integer.parseInt(digits(request.pincode())));
        body.put("custName", clip(request.customerName(), 25));
        body.put("custMobNo", Long.parseLong(digits(request.customerMobile())));
        body.put("custIpAddress", request.clientIp() == null ? "" : request.clientIp().trim());
        body.put("latlong", latLong(request.latitude(), request.longitude()));
        body.put("paramA", "");
        body.put("paramB", "");
        return body;
    }

    public static ProviderModels.PayoutResult interpretSubmit(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isEmpty()) {
            return failed("EMPTY", "Settlement transfer was not confirmed.");
        }
        if (node.has("fault")) {
            return failed("REJECTED", first(text(node.path("fault"), "faultstring"), "Settlement transfer was rejected."));
        }
        String status = text(node, "status").toUpperCase(Locale.ROOT);
        Integer code = number(node, "statusCode");
        if (code == null) {
            code = number(node, "subStatus");
        }
        String txnId = first(text(node, "txnId"), text(node, "transactionId"), text(node, "referenceNo"));
        String rrn = text(node, "rrn");
        String desc = first(text(node, "statusDesc"), text(node, "message"));
        if ("SUCCESS".equals(status) || (Integer.valueOf(0).equals(code) && !"FAILED".equals(status))) {
            return new ProviderModels.PayoutResult(ProviderModels.Outcome.SUCCESS, status.isBlank() ? "SUCCESS" : status, desc, txnId, rrn);
        }
        if ("INPROGRESS".equals(status) || "IN_PROGRESS".equals(status) || (Integer.valueOf(1).equals(code) && !"FAILED".equals(status))) {
            return new ProviderModels.PayoutResult(
                    ProviderModels.Outcome.PENDING,
                    status.isBlank() ? "INPROGRESS" : status,
                    desc.isBlank() ? "Awaiting bank confirmation" : desc,
                    txnId,
                    rrn);
        }
        if ("FAILED".equals(status) || "REFUNDED".equals(status) || (code != null && code != 0)) {
            return failed(status.isBlank() ? String.valueOf(code) : status, desc.isBlank() ? "Settlement was declined." : desc, txnId, rrn);
        }
        if (!txnId.isBlank()) {
            return new ProviderModels.PayoutResult(ProviderModels.Outcome.PENDING, status, desc.isBlank() ? "Awaiting bank confirmation" : desc, txnId, rrn);
        }
        return failed("UNCONFIRMED", desc.isBlank() ? "Settlement transfer was not confirmed." : desc);
    }

    @Override
    public ProviderModels.PayoutStatus checkStatus(
            ServiceProviderRouter.RoutedChannel channel,
            String externalRef,
            String providerTxnId,
            String txnDate) {
        if (!props.payoutStatusReady() || externalRef == null || externalRef.isBlank()) {
            return pending(providerTxnId, "Awaiting bank confirmation");
        }
        String day = txnDate == null || txnDate.isBlank() ? LocalDate.now(INDIA).toString() : txnDate.trim();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("$1", props.payoutStatusOp());
        body.put("$4", day);
        body.put("$5", day);
        body.put("$6", externalRef);
        if (providerTxnId != null && !providerTxnId.isBlank()) {
            body.put("$10", providerTxnId);
        }
        try {
            return interpretStatus(gateway.post(props.payoutStatusUrl(), body), providerTxnId);
        } catch (Exception e) {
            log.warn("ISERVEU_PAYOUT status call failed: {}", e.getMessage());
            return pending(providerTxnId, "Awaiting bank confirmation");
        }
    }

    public static ProviderModels.PayoutStatus interpretStatus(JsonNode node, String fallbackTxnId) {
        if (node == null || node.isMissingNode()) {
            return pending(fallbackTxnId, "Awaiting bank confirmation");
        }
        int length = node.path("length").asInt(-1);
        String message = text(node, "message");
        JsonNode results = node.path("results");
        if (!results.isArray() || results.isEmpty() || length == 0) {
            return pending(fallbackTxnId, message.isBlank() ? "Awaiting bank confirmation" : message);
        }
        JsonNode row = results.get(0);
        String status = text(row, "status").toUpperCase(Locale.ROOT);
        Integer sub = row.hasNonNull("subStatus") ? row.path("subStatus").asInt() : null;
        ProviderModels.Outcome outcome = mapOutcome(status, sub);
        String txnId = first(text(row, "transactionId"), text(row, "txnId"), fallbackTxnId);
        String desc = first(text(row, "statusDesc"), message);
        return new ProviderModels.PayoutStatus(outcome, status, desc, text(row, "rrn"), txnId);
    }

    static ProviderModels.Outcome mapOutcome(String status, Integer subStatus) {
        if ("INPROGRESS".equals(status) || "IN_PROGRESS".equals(status)) {
            return ProviderModels.Outcome.PENDING;
        }
        if (subStatus != null) {
            if ("SUCCESS".equals(status) && (subStatus == 0 || subStatus == 1)) {
                return ProviderModels.Outcome.SUCCESS;
            }
            if ("FAILED".equals(status) && (subStatus == 0 || subStatus == -1 || subStatus == 1)) {
                return ProviderModels.Outcome.FAILED;
            }
            return ProviderModels.Outcome.PENDING;
        }
        return switch (status) {
            case "SUCCESS" -> ProviderModels.Outcome.SUCCESS;
            case "FAILED" -> ProviderModels.Outcome.FAILED;
            default -> ProviderModels.Outcome.PENDING;
        };
    }

    private static ProviderModels.PayoutStatus pending(String txnId, String message) {
        return new ProviderModels.PayoutStatus(ProviderModels.Outcome.PENDING, "", message, "", txnId == null ? "" : txnId);
    }

    private static ProviderModels.PayoutResult failed(String code, String message) {
        return failed(code, message, "", "");
    }

    private static ProviderModels.PayoutResult failed(String code, String message, String txnId, String rrn) {
        return new ProviderModels.PayoutResult(ProviderModels.Outcome.FAILED, code, message, txnId == null ? "" : txnId, rrn == null ? "" : rrn);
    }

    private static Integer number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asInt();
        }
        String text = value.asText("").trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    private static String clip(String value, int max) {
        String clean = value == null ? "" : value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private static String latLong(String latitude, String longitude) {
        String value = (latitude == null ? "" : latitude.trim()) + "," + (longitude == null ? "" : longitude.trim());
        return value.length() <= 22 ? value : value.substring(0, 22);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
