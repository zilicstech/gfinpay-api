package com.fintech.platform.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** Builds and clears the httpOnly refresh cookie. The raw secret never reaches JavaScript. */
@Component
@EnableConfigurationProperties(AuthProperties.class)
public class RefreshCookies {

    private final AuthProperties props;

    public RefreshCookies(AuthProperties props) {
        this.props = props;
    }

    public String read(HttpServletRequest request) {
        Cookie[] all = request.getCookies();
        if (all == null) {
            return null;
        }
        String name = props.cookie().name();
        for (Cookie cookie : all) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public void attach(HttpHeaders headers, String value, Instant expiresAt) {
        long maxAge = Math.max(0, Duration.between(Instant.now(), expiresAt).getSeconds());
        headers.add(HttpHeaders.SET_COOKIE, base(value).maxAge(maxAge).build().toString());
    }

    public void clear(HttpHeaders headers) {
        headers.add(HttpHeaders.SET_COOKIE, base("").maxAge(0).build().toString());
    }

    /** Used when the handler is about to throw, so the dead cookie still gets discarded. */
    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, base("").maxAge(0).build().toString());
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        AuthProperties.Cookie cookie = props.cookie();
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(cookie.name(), value)
                .httpOnly(true)
                .secure(cookie.secure())
                .path(cookie.path())
                .sameSite(cookie.sameSite());
        if (cookie.domain() != null && !cookie.domain().isBlank()) {
            builder.domain(cookie.domain());
        }
        return builder;
    }
}
