package com.fintech.emerald;

import java.util.regex.Pattern;

/**
 * Strips third-party provider, switch, pipe, and internal outlet identifiers from messages
 * before surfacing them to white-label retailer or distributor clients.
 */
public final class EmeraldMessageSanitizer {

    private static final Pattern URL_PATTERN = Pattern.compile("(?i)https?://[^\\s]+|myemerald\\.in");
    private static final Pattern VENDOR_NAMES = Pattern.compile(
            "(?i)\\b(emerald\\s*pay|emerald|instant\\s*pay|instantpay|paysprint|fino\\s*payments\\s*bank|fino|eko)\\b");
    private static final Pattern PIPE_PHRASE = Pattern.compile("(?i)pipe\\s+sync\\s+failed:\\s*");
    private static final Pattern ON_PIPE = Pattern.compile("(?i)\\bon\\s+(the\\s+)?pipe\\b(\\s+[A-Z0-9_-]+)?");
    private static final Pattern INTERNAL_CODES = Pattern.compile(
            "\\b(EMD[A-Z0-9_]*|UPITOCASH[0-9]+|OUT_[0-9A-Z_]+|ENT[0-9]{4,}|PRT[0-9]+|VAL[0-9]+|API[0-9]+)\\b");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s{2,}");

    private EmeraldMessageSanitizer() {}

    public static String sanitize(String rawMessage, String fallback) {
        String defaultText = (fallback == null || fallback.isBlank())
                ? "Unable to process request right now. Please try again."
                : fallback.trim();
        if (rawMessage == null || rawMessage.isBlank()) {
            return defaultText;
        }
        String cleaned = rawMessage.trim();
        cleaned = URL_PATTERN.matcher(cleaned).replaceAll("banking gateway");
        cleaned = PIPE_PHRASE.matcher(cleaned).replaceAll("");
        cleaned = ON_PIPE.matcher(cleaned).replaceAll("");
        cleaned = VENDOR_NAMES.matcher(cleaned).replaceAll("banking network");
        cleaned = INTERNAL_CODES.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replaceAll("(?i)\\bpipe\\b", "settlement rail");
        cleaned = MULTI_SPACE.matcher(cleaned).replaceAll(" ").trim();
        cleaned = cleaned.replaceAll("^[,:;\\-\\s]+|[,:;\\-\\s]+$", "").trim();
        if (cleaned.isEmpty() || cleaned.equalsIgnoreCase("banking network")) {
            return defaultText;
        }
        return cleaned;
    }

    public static String sanitizeNullable(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return null;
        }
        return sanitize(rawMessage, "Transaction could not be completed by the banking network.");
    }
}

