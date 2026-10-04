package com.fintech.bills;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.kbill")
public record KbillProperties(String baseUrl, String apiToken) {

    public boolean configured() {
        return baseUrl != null && !baseUrl.isBlank() && apiToken != null && !apiToken.isBlank();
    }

    public String base() {
        String url = baseUrl == null ? "" : baseUrl.trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
