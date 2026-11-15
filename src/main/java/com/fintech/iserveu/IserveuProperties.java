package com.fintech.iserveu;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class IserveuProperties {

    private final boolean enabled;
    private final String baseUrl;
    private final String clientId;
    private final String clientSecret;
    private final String passKey;
    private final String encKey;
    private final String headerSecret;

    public IserveuProperties(
            @Value("${app.iserveu.enabled:false}") boolean enabled,
            @Value("${app.iserveu.base-url:}") String baseUrl,
            @Value("${app.iserveu.client-id:}") String clientId,
            @Value("${app.iserveu.client-secret:}") String clientSecret,
            @Value("${app.iserveu.pass-key:}") String passKey,
            @Value("${app.iserveu.enc-key:}") String encKey,
            @Value("${app.iserveu.header-secret:}") String headerSecret) {
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.passKey = passKey == null ? "" : passKey.trim();
        this.encKey = encKey == null ? "" : encKey.trim();
        this.headerSecret = headerSecret == null ? "" : headerSecret.trim();
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean configured() {
        return enabled && !baseUrl.isBlank() && !clientId.isBlank() && !clientSecret.isBlank()
                && !passKey.isBlank() && !encKey.isBlank();
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

    public String passKey() {
        return passKey;
    }

    public String headerSecret() {
        return headerSecret;
    }

    public String encKey() {
        return encKey;
    }
}
