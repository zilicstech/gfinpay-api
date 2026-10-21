package com.fintech.dmt;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Shared reading of PaySprint DMT bodies. Account numbers and OTPs are never assembled here for logs. */
final class DmtCasaTexts {

    private DmtCasaTexts() {}

    static String text(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        for (String field : fields) {
            String value = node.path(field).asText("").trim();
            if (!value.isBlank() && !"null".equalsIgnoreCase(value)) {
                return value;
            }
        }
        JsonNode data = node.path("data");
        if (data.isObject()) {
            for (String field : fields) {
                String value = data.path(field).asText("").trim();
                if (!value.isBlank() && !"null".equalsIgnoreCase(value)) {
                    return value;
                }
            }
        }
        return "";
    }

    static int code(JsonNode root) {
        if (root == null) {
            return -1;
        }
        JsonNode node = root.path("response_code");
        if (node.isNumber()) {
            return node.intValue();
        }
        String raw = node.asText("").trim();
        if (raw.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static String txnStatus(JsonNode root) {
        String value = text(root, "txn_status");
        if (value.endsWith(".0")) {
            return value.substring(0, value.length() - 2);
        }
        return value;
    }

    static BigDecimal money(JsonNode root, String field) {
        String raw = text(root, field);
        if (raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String amountText(BigDecimal amount) {
        BigDecimal stripped = amount.stripTrailingZeros();
        if (stripped.scale() <= 0) {
            return stripped.toPlainString();
        }
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    static String last4(String account) {
        String digits = account == null ? "" : account.replaceAll("\\D", "");
        if (digits.length() >= 4) {
            return digits.substring(digits.length() - 4);
        }
        return "0000";
    }

    static String ifsc(String value) {
        String code = value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        if (code.length() > 11) {
            return code.substring(0, 11);
        }
        return code;
    }

    static String clip(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500);
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
