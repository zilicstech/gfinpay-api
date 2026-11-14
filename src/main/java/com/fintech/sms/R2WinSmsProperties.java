package com.fintech.sms;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.sms.r2win")
public record R2WinSmsProperties(
        String baseUrl,
        String accessKey,
        String header,
        String otpTemplateId,
        String otpTemplateText
) {}

