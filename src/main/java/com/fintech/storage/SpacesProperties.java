package com.fintech.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.spaces")
public record SpacesProperties(
        String endpoint,
        String region,
        String bucket,
        String prefix,
        String accessKey,
        String secretKey) {
}
