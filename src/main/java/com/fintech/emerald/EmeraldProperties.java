package com.fintech.emerald;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for Emerald B2B Partner Gateway ({@code app.emerald.*}).
 */
@Component
public class EmeraldProperties {

    private final String baseUrl;
    private final String clientId;
    private final String clientSecret;
    private final String entityCode;
    private final String defaultPipe;
    private final String callbackSecret;
    private final String aepsTransactPath;
    private final String payoutStatusPath;
    private final boolean logPayloads;
    private final boolean maskAccountNumbers;

    @Autowired
    public EmeraldProperties(
            @Value("${app.emerald.base-url:https://myemerald.in}") String baseUrl,
            @Value("${app.emerald.client-id:}") String clientId,
            @Value("${app.emerald.client-secret:}") String clientSecret,
            @Value("${app.emerald.entity-code:ENT000002}") String entityCode,
            @Value("${app.emerald.default-pipe:INSTANTPAY}") String defaultPipe,
            @Value("${app.emerald.callback-secret:}") String callbackSecret,
            @Value("${app.emerald.aeps-transact-path:}") String aepsTransactPath,
            @Value("${app.emerald.payout-status-path:}") String payoutStatusPath,
            @Value("${app.emerald.log-payloads:true}") boolean logPayloads,
            @Value("${app.emerald.mask-account-numbers:true}") boolean maskAccountNumbers) {
        this.baseUrl = trimTrailingSlash(baseUrl == null || baseUrl.isBlank() ? "https://myemerald.in" : baseUrl.trim());
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.entityCode = entityCode == null || entityCode.isBlank() ? "ENT000002" : entityCode.trim();
        this.defaultPipe = defaultPipe == null || defaultPipe.isBlank() ? "INSTANTPAY" : defaultPipe.trim().toUpperCase();
        this.callbackSecret = callbackSecret == null ? "" : callbackSecret.trim();
        this.aepsTransactPath = aepsTransactPath == null ? "" : aepsTransactPath.trim();
        this.payoutStatusPath = payoutStatusPath == null ? "" : payoutStatusPath.trim();
        this.logPayloads = logPayloads;
        this.maskAccountNumbers = maskAccountNumbers;
    }

    public EmeraldProperties(
            String baseUrl,
            String clientId,
            String clientSecret,
            String entityCode,
            String defaultPipe,
            String callbackSecret,
            String aepsTransactPath,
            String payoutStatusPath) {
        this(baseUrl, clientId, clientSecret, entityCode, defaultPipe, callbackSecret, aepsTransactPath, payoutStatusPath, true, true);
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String clientId() {
        return clientId;
    }

    public String clientSecret() {
        return clientSecret;
    }

    public String entityCode() {
        return entityCode;
    }

    public String defaultPipe() {
        return defaultPipe;
    }

    public String callbackSecret() {
        return callbackSecret;
    }

    public String aepsTransactPath() {
        return aepsTransactPath;
    }

    public String payoutStatusPath() {
        return payoutStatusPath;
    }

    public boolean logPayloads() {
        return logPayloads;
    }

    public boolean maskAccountNumbers() {
        return maskAccountNumbers;
    }

    public boolean isConfigured() {
        return !clientId.isBlank() && !clientSecret.isBlank() && !entityCode.isBlank();
    }

    private static String trimTrailingSlash(String url) {
        String out = url;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}

