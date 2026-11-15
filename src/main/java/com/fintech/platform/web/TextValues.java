package com.fintech.platform.web;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Shared string and money helpers used by desk services. */
public final class TextValues {

    private TextValues() {}

    public static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    public static String jsonText(Object value) {
        if (value == null) {
            return "{}";
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() || "null".equals(text) ? "{}" : text;
    }

    public static BigDecimal money(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        return amount.setScale(4, RoundingMode.HALF_UP);
    }

    public static String maskAccount(String accountNumber) {
        String digits = str(accountNumber).replaceAll("\\s+", "");
        if (digits.length() <= 4) {
            return digits;
        }
        return "X".repeat(digits.length() - 4) + digits.substring(digits.length() - 4);
    }
}
