package com.fintech.platform.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Customer-facing site origin. Apply links and CORS use both apex and www. */
public final class PublicAppUrl {

    private PublicAppUrl() {}

    public static String canonicalOrigin(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String origin = raw.trim().replaceAll("/$", "");
        return origin.replaceFirst("(?i)://www\\.", "://");
    }

    public static List<String> corsOrigins(String allowedOriginsRaw) {
        if (allowedOriginsRaw == null || allowedOriginsRaw.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> origins = new LinkedHashSet<>();
        for (String part : allowedOriginsRaw.split("\\s*,\\s*")) {
            if (part.isBlank()) {
                continue;
            }
            String origin = part.trim().replaceAll("/$", "");
            origins.add(origin);
            origins.addAll(hostTwins(origin));
        }
        return List.copyOf(origins);
    }

    static List<String> hostTwins(String origin) {
        String lower = origin.toLowerCase(Locale.ROOT);
        if (lower.contains("localhost") || lower.contains("127.0.0.1")) {
            return List.of();
        }
        List<String> twins = new ArrayList<>();
        if (lower.contains("://www.")) {
            twins.add(origin.replaceFirst("(?i)://www\\.", "://"));
        } else if (lower.startsWith("https://") || lower.startsWith("http://")) {
            twins.add(origin.replaceFirst("://", "://www."));
        }
        return twins;
    }
}
