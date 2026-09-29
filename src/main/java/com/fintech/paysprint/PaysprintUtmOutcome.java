package com.fintech.paysprint;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.Set;

/** Interprets PaySprint UTM status-check and UTM-LEAD-STATUS callback fields. */
public final class PaysprintUtmOutcome {

    private static final Set<String> CONVERTED = Set.of(
            "SUCCESS", "APPROVED", "CONVERTED", "DISBURSED", "ISSUED", "ACTIVATED", "COMPLETED", "CARD ISSUED");
    private static final Set<String> REJECTED = Set.of(
            "FAILED", "REJECTED", "NOT_INTERESTED", "CANCELLED", "CANCELED", "DECLINED", "NOT INTERESTED");

    private PaysprintUtmOutcome() {}

    public static boolean converted(JsonNode param) {
        return tokenMatch(param, CONVERTED) || "1".equals(text(param, "status"));
    }

    public static boolean rejected(JsonNode param) {
        if (converted(param)) {
            return false;
        }
        return tokenMatch(param, REJECTED) || "4".equals(text(param, "status"));
    }

    public static String summary(JsonNode param) {
        String txn = text(param, "txn_status");
        String executive = first(param, "ex_status", "executive_status");
        String remarks = first(param, "ex_remarks", "executive_remarks", "message");
        String sub = text(param, "ex_sub_status");
        StringBuilder out = new StringBuilder();
        if (!txn.isBlank()) {
            out.append(txn);
        }
        if (!executive.isBlank()) {
            if (!out.isEmpty()) {
                out.append(" · ");
            }
            out.append(executive);
        }
        if (!sub.isBlank()) {
            if (!out.isEmpty()) {
                out.append(" · ");
            }
            out.append(sub);
        }
        if (!remarks.isBlank() && out.isEmpty()) {
            out.append(remarks);
        }
        return out.isEmpty() ? "UTM status" : out.toString();
    }

    private static boolean tokenMatch(JsonNode param, Set<String> tokens) {
        return matches(text(param, "txn_status"), tokens)
                || matches(first(param, "ex_status", "executive_status"), tokens)
                || matches(text(param, "status"), tokens);
    }

    private static boolean matches(String raw, Set<String> tokens) {
        if (raw.isBlank()) {
            return false;
        }
        String norm = raw.trim().toUpperCase(Locale.ROOT).replace('_', ' ');
        if (tokens.contains(norm) || tokens.contains(norm.replace(' ', '_'))) {
            return true;
        }
        for (String token : tokens) {
            if (norm.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static String first(JsonNode param, String... keys) {
        for (String key : keys) {
            String value = text(param, key);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String text(JsonNode param, String key) {
        if (param == null || param.isMissingNode()) {
            return "";
        }
        return param.path(key).asText("").trim();
    }
}
