package com.fintech.platform.web;

import org.slf4j.MDC;

/**
 * Generic response envelope for every endpoint.
 * Success: { "data": ..., "error": null, "meta": {...} }
 * Failure: { "data": null, "error": {...}, "meta": {...} } — built only by GlobalExceptionHandler.
 */
public record ApiResponse<T>(T data, ErrorBody error, Meta meta) {

    public record Meta(String requestId, Boolean idempotencyReplay) {}

    public record ErrorBody(String code, String message, Object details, boolean retriable) {}

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(data, null, new Meta(MDC.get("requestId"), false));
    }

    public static <T> ApiResponse<T> replay(T data) {
        return new ApiResponse<>(data, null, new Meta(MDC.get("requestId"), true));
    }

    public static ApiResponse<Void> failure(String code, String message, Object details, boolean retriable) {
        return new ApiResponse<>(null, new ErrorBody(code, message, details, retriable),
                new Meta(MDC.get("requestId"), null));
    }
}
