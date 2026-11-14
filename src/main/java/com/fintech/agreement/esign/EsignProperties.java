package com.fintech.agreement.esign;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.esign")
public record EsignProperties(
        String mode,
        String baseUrl,
        String apiToken,
        String redirectUrl,
        Boolean signAllPages,
        Integer nameMatchScore,
        Integer statusCheckThrottleSeconds,
        Integer sessionTtlHours
) {
    public EsignProperties {
        if (mode == null || mode.isBlank()) {
            mode = "live";
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://kyc-api.surepass.app";
        }
        if (redirectUrl == null || redirectUrl.isBlank()) {
            redirectUrl = "http://localhost:3001/esign/return";
        }
        if (signAllPages == null) {
            signAllPages = true;
        }
        if (nameMatchScore == null || nameMatchScore <= 0) {
            nameMatchScore = 70;
        }
        if (statusCheckThrottleSeconds == null || statusCheckThrottleSeconds < 0) {
            statusCheckThrottleSeconds = 20;
        }
        if (sessionTtlHours == null || sessionTtlHours <= 0) {
            sessionTtlHours = 24;
        }
    }
}

