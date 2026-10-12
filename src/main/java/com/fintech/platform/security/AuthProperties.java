package com.fintech.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        int accessTokenMinutes,
        int idleMinutes,
        int sessionAbsoluteHours,
        Cookie cookie) {

    /** Refresh-token cookie attributes. Scoped to the auth endpoints so it is never sent elsewhere. */
    public record Cookie(String name, String domain, boolean secure, String sameSite, String path) {}
}
