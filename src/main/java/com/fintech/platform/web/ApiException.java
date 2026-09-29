package com.fintech.platform.web;

import org.springframework.http.HttpStatus;

/** Domain/application exception carrying the error code and HTTP status for the envelope. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient Object details;
    private final boolean retriable;

    public ApiException(HttpStatus status, String code, String message, Object details, boolean retriable) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
        this.retriable = retriable;
    }

    public static ApiException of(HttpStatus status, String code, String message) {
        return new ApiException(status, code, message, null, false);
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public Object getDetails() { return details; }
    public boolean isRetriable() { return retriable; }
}
