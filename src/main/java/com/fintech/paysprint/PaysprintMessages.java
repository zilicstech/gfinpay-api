package com.fintech.paysprint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Turns a PaySprint body into the text the UI should show. */
public final class PaysprintMessages {

    private PaysprintMessages() {}

    static String fromBody(ObjectMapper mapper, String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return fromNode(mapper.readTree(raw), fallback);
        } catch (Exception ignored) {
            String trimmed = raw.strip();
            if (trimmed.startsWith("<")) {
                return fallback;
            }
            return trimmed.length() > 300 ? trimmed.substring(0, 300) : trimmed;
        }
    }

    public static String fromNode(JsonNode root, String fallback) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return fallback;
        }
        String message = root.path("message").asText("").trim();
        if (!message.isBlank()) {
            return message;
        }
        String code = root.path("response_code").asText("").trim();
        if (!code.isBlank()) {
            return "PaySprint response code " + code;
        }
        String text = root.toString();
        if (text.isBlank() || "null".equals(text) || "{}".equals(text)) {
            return fallback;
        }
        return text.length() > 300 ? text.substring(0, 300) : text;
    }
}
