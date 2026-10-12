package com.fintech.paysprint;

import com.fasterxml.jackson.databind.JsonNode;

/** Bank 2 completion rules from PaySprint's onboard callback and status check. */
public final class PaysprintOnboardOutcome {

    private PaysprintOnboardOutcome() {}

    /** Status callback: overall Success and Bank 2 Active. */
    public static boolean statusCallbackComplete(JsonNode param) {
        return param != null
                && "success".equalsIgnoreCase(param.path("status").asText("").trim())
                && bank2Active(param.path("bank"));
    }

    /** Browser redirect `data` token: status 1 and Bank 2 1. */
    public static boolean redirectComplete(JsonNode data) {
        if (data == null) {
            return false;
        }
        String status = data.path("status").asText("").trim();
        return ("1".equals(status) || "success".equalsIgnoreCase(status)) && bank2Done(data.path("bank"));
    }

    /** Pipe status check for bank2: Accepted means that pipe is done. */
    public static boolean statusCheckAccepted(JsonNode root) {
        return root != null
                && root.path("status").asBoolean(false)
                && "accepted".equalsIgnoreCase(root.path("is_approved").asText("").trim());
    }

    private static boolean bank2Active(JsonNode bank) {
        return "active".equalsIgnoreCase(bank2Text(bank));
    }

    private static boolean bank2Done(JsonNode bank) {
        String value = bank2Text(bank);
        return "1".equals(value) || "active".equalsIgnoreCase(value);
    }

    private static String bank2Text(JsonNode bank) {
        if (bank == null || bank.isMissingNode() || bank.isNull()) {
            return "";
        }
        JsonNode value = bank.get("Bank2");
        if (value == null || value.isNull()) {
            return "";
        }
        if (value.isNumber()) {
            return Integer.toString(value.asInt());
        }
        if (value.isBoolean()) {
            return value.asBoolean() ? "1" : "0";
        }
        return value.asText("").trim();
    }
}
