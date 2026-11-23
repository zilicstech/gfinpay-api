package com.fintech.instantpay;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound webhook endpoint for InstantPay UATM ({@code CR}) and Payout ({@code DR}) callbacks.
 * Supports both {@code GET} query parameters and {@code POST} form/JSON bodies at
 * {@code /webhooks/instantpay/callback} and {@code /webhooks/instantpay/callback/{secretToken}}.
 */
@RestController
public class InstantPayCallbackController {

    private final InstantPayCallbackService callbacks;

    public InstantPayCallbackController(InstantPayCallbackService callbacks) {
        this.callbacks = callbacks;
    }

    @GetMapping(
            value = {"/webhooks/instantpay/callback", "/webhooks/instantpay/callback/{secretToken}"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> receiveGet(
            @PathVariable(value = "secretToken", required = false) String pathSecret,
            @RequestHeader(value = "X-InstantPay-Webhook-Secret", required = false) String headerSecret,
            @RequestParam Map<String, String> queryParams,
            HttpServletRequest request) {
        String providedSecret = firstNonBlank(pathSecret, headerSecret, queryParams.get("token"));
        InstantPayCallbackService.CallbackAck ack = callbacks.handleInbound(
                "GET",
                providedSecret,
                queryParams,
                "",
                fullUrl(request),
                request.getRequestURI(),
                extractHeaders(request));
        return ResponseEntity.status(ack.httpStatus()).body(ack.body());
    }

    @PostMapping(
            value = {"/webhooks/instantpay/callback", "/webhooks/instantpay/callback/{secretToken}"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> receivePost(
            @PathVariable(value = "secretToken", required = false) String pathSecret,
            @RequestHeader(value = "X-InstantPay-Webhook-Secret", required = false) String headerSecret,
            @RequestParam Map<String, String> queryOrFormParams,
            @RequestBody(required = false) String rawBody,
            HttpServletRequest request) {
        String providedSecret = firstNonBlank(pathSecret, headerSecret, queryOrFormParams.get("token"));
        InstantPayCallbackService.CallbackAck ack = callbacks.handleInbound(
                "POST",
                providedSecret,
                queryOrFormParams,
                rawBody == null ? "" : rawBody,
                fullUrl(request),
                request.getRequestURI(),
                extractHeaders(request));
        return ResponseEntity.status(ack.httpStatus()).body(ack.body());
    }

    private static Map<String, String> extractHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) return headers;
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (name != null) {
                headers.put(name, request.getHeader(name));
            }
        }
        return headers;
    }

    private static String fullUrl(HttpServletRequest request) {
        StringBuffer url = request.getRequestURL();
        String qs = request.getQueryString();
        return qs == null || qs.isBlank() ? url.toString() : url.append('?').append(qs).toString();
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }
}
