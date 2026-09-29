package com.fintech.platform.web;

import org.springframework.http.HttpStatus;

public final class FeatureUnavailable {

    private FeatureUnavailable() {}

    public static void throwIfCalled() {
        throw ApiException.of(HttpStatus.NOT_IMPLEMENTED, "FEATURE_UNAVAILABLE",
                "This feature is not available yet");
    }
}
