package com.fintech.recon;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ReconJson {

    private ReconJson() {}

    public static Map<String, Object> asMap(Object raw, ObjectMapper mapper) {
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        String json = jsonText(raw);
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of("_raw", json);
        }
    }

    private static String jsonText(Object raw) {
        try {
            Object value = raw.getClass().getMethod("getValue").invoke(raw);
            if (value != null) {
                return String.valueOf(value);
            }
        } catch (ReflectiveOperationException ignored) {
            // String / other JDBC mappings
        }
        return String.valueOf(raw);
    }
}
