package com.fintech.iserveu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.provider.canonical.ProviderModels;
import com.fintech.provider.port.ProviderCallbackPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Verifies the iServeU cash-point callback and maps it to a settlement event. */
@Component
public class IserveuCallbackAdapter implements ProviderCallbackPort {

    private final IserveuProperties props;
    private final ObjectMapper mapper;

    public IserveuCallbackAdapter(IserveuProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public String providerCode() {
        return "ISERVEU";
    }

    public boolean passKeyMatches(String provided) {
        if (props.passKey().isBlank() || provided == null || provided.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                props.passKey().getBytes(StandardCharsets.UTF_8),
                provided.trim().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public ProviderModels.SettlementEvent verify(Map<String, String> headers, String rawBody) {
        String pass = headers == null ? "" : headers.getOrDefault("pass_key", "");
        if (!passKeyMatches(pass)) {
            return null;
        }
        try {
            return interpret(mapper.readTree(rawBody == null ? "{}" : rawBody));
        } catch (Exception e) {
            return null;
        }
    }

    public static ProviderModels.SettlementEvent interpret(JsonNode node) {
        String status = text(node, "status").toUpperCase(Locale.ROOT);
        String paymentState = text(node, "paymentState").toUpperCase(Locale.ROOT);
        int code = node.path("statusCode").asInt(0);
        String mapped = switch (status.isBlank() ? paymentState : status) {
            case "SUCCESS", "COMPLETED" -> "SUCCESS";
            case "FAILED", "DECLINED" -> "FAILED";
            case "EXPIRED" -> "EXPIRED";
            case "REVERSED" -> "REVERSED";
            default -> code == -1 ? "FAILED" : "PENDING";
        };
        String ref = first(text(node, "clientRefId"), text(node, "clientRef"), text(node, "externalRef"));
        String txn = first(text(node, "txnId"), text(node, "transactionId"));
        String utr = first(text(node, "rrn"), text(node, "utr"), text(node, "bankRrn"));
        String payer = first(text(node, "payer_vpa"), text(node, "payerVPA"), text(node, "payerVpa"));
        String message = first(text(node, "statusDesc"), text(node, "message"));
        return new ProviderModels.SettlementEvent(ref, txn, mapped, message, utr, payer, text(node, "payerName"));
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
