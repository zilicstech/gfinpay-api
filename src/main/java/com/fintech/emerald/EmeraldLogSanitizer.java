package com.fintech.emerald;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sanitizes Emerald HTTP headers, request bodies, and response bodies for structured logging and persistent
 * audit storage while preserving 100% of structural fields, status codes, error details, RD device metadata,
 * reference keys, transaction IDs, and UTRs needed when sharing traces with the Emerald engineering team.
 */
public final class EmeraldLogSanitizer {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final Set<String> SECRET_HEADERS = Set.of(
            "x-client-secret",
            "x-emerald-callback-secret",
            "authorization",
            "cookie",
            "set-cookie"
    );

    private static final Set<String> AADHAAR_KEYS = Set.of(
            "aadhaar",
            "aadhaarnumber",
            "aadhaarno",
            "aadhaar_number",
            "encryptedaadhaar",
            "customeraadhaar",
            "uid"
    );

    private static final Set<String> BIOMETRIC_CIPHERTEXT_KEYS = Set.of(
            "piddata",
            "encpiddata",
            "hmac",
            "enchmac",
            "skey",
            "encskey",
            "sessionkey",
            "mc",
            "pidxml"
    );

    private EmeraldLogSanitizer() {}

    public static Map<String, String> sanitizeHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            String val = entry.getValue();
            if (key == null) continue;
            if (SECRET_HEADERS.contains(key.toLowerCase(Locale.ROOT))) {
                out.put(key, maskSecret(val));
            } else {
                out.put(key, val == null ? "" : val);
            }
        }
        return out;
    }

    public static Object sanitizePayloadObject(Object obj) {
        return sanitizeNode(null, obj);
    }

    public static String sanitizeJsonOrRaw(ObjectMapper mapper, String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return "";
        }
        String trimmed = rawBody.trim();
        if (trimmed.startsWith("{")) {
            try {
                Map<String, Object> parsed = mapper.readValue(trimmed, MAP_TYPE);
                Object sanitized = sanitizeNode(null, parsed);
                return mapper.writeValueAsString(sanitized);
            } catch (Exception ignored) {
                return truncateIfHuge(rawBody);
            }
        }
        if (trimmed.startsWith("[")) {
            try {
                List<?> parsed = mapper.readValue(trimmed, List.class);
                Object sanitized = sanitizeNode(null, parsed);
                return mapper.writeValueAsString(sanitized);
            } catch (Exception ignored) {
                return truncateIfHuge(rawBody);
            }
        }
        return truncateIfHuge(rawBody);
    }

    @SuppressWarnings("unchecked")
    private static Object sanitizeNode(String parentKey, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            boolean insideBiometricMap = parentKey != null
                    && parentKey.toLowerCase(Locale.ROOT).contains("biometric");
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String k = String.valueOf(entry.getKey());
                Object v = entry.getValue();
                out.put(k, sanitizeEntry(k, v, insideBiometricMap));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(sanitizeNode(parentKey, item));
            }
            return out;
        }
        return value;
    }

    private static Object sanitizeEntry(
            String key,
            Object value,
            boolean insideBiometricMap) {
        if (value == null) {
            return null;
        }
        String normKey = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");

        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return sanitizeNode(key, value);
        }

        if (value instanceof String strVal) {
            // 1. Inside biometricData map, `data` is the encrypted UIDAI PID block; keep all RD device attributes intact
            if (insideBiometricMap && ("data".equalsIgnoreCase(key) || BIOMETRIC_CIPHERTEXT_KEYS.contains(normKey))) {
                return "[REDACTED_BIOMETRIC len=" + strVal.length() + "]";
            }
            if (BIOMETRIC_CIPHERTEXT_KEYS.contains(normKey)) {
                return "[REDACTED_BIOMETRIC len=" + strVal.length() + "]";
            }
            // 2. Aadhaar numbers -> XXXXXXXX1234 in logs/storage (outbound wire payload always sends full 12-digit Aadhaar)
            if (AADHAAR_KEYS.contains(normKey)) {
                return maskAadhaar(strVal);
            }
            // 3. Secret keys if ever echoed
            if (normKey.contains("secret") || normKey.contains("password")) {
                return maskSecret(strVal);
            }
        }
        return value;
    }

    public static String maskSecret(String raw) {
        if (raw == null || raw.isBlank()) {
            return "[EMPTY]";
        }
        String s = raw.trim();
        if (s.length() <= 6) {
            return "**** (len=" + s.length() + ")";
        }
        String prefix = s.substring(0, Math.min(4, s.length() - 4));
        String suffix = s.substring(s.length() - 4);
        return prefix + "****" + suffix;
    }

    public static String maskAadhaar(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() >= 4) {
            return "XXXXXXXX" + digits.substring(digits.length() - 4);
        }
        return "XXXX";
    }

    private static String truncateIfHuge(String raw) {
        if (raw == null) return "";
        int max = 32_768;
        return raw.length() <= max ? raw : raw.substring(0, max) + "...[TRUNCATED totalLen=" + raw.length() + "]";
    }
}
