package com.fintech.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Reads per-channel amount rules from {@code service_provider_channels.config_json}. */
public final class ChannelLimits {

    public record MoneyRule(int min, int max, int step) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChannelLimits() {}

    public static MoneyRule upiCashout(String configJson) {
        return rule(configJson, "upiCashout", 200, 5000, 100);
    }

    public static MoneyRule aeps(String configJson) {
        return rule(configJson, "aeps", 100, 10000, 50);
    }

    public static String text(String configJson, String field, String fallback) {
        JsonNode node = root(configJson).path(field);
        String value = node.isMissingNode() || node.isNull() ? "" : node.asText("").trim();
        return value.isBlank() ? fallback : value;
    }

    private static MoneyRule rule(String configJson, String field, int min, int max, int step) {
        JsonNode node = root(configJson).path(field);
        return new MoneyRule(
                node.path("min").asInt(min),
                node.path("max").asInt(max),
                node.path("step").asInt(step));
    }

    private static JsonNode root(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(configJson);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }
}
