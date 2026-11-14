package com.fintech.emerald;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public webhook receiver for Emerald final transaction callbacks ({@code POST /emerald/v1/callback}).
 */
@RestController
public class EmeraldCallbackController {

    private final EmeraldCallbackService callbacks;

    public EmeraldCallbackController(EmeraldCallbackService callbacks) {
        this.callbacks = callbacks;
    }

    @PostMapping({"/emerald/v1/callback", "/webhooks/emerald/v1/callback"})
    public ResponseEntity<Map<String, Object>> receiveCallback(
            @RequestHeader(value = "X-Emerald-Callback-Secret", required = false) String secretHeader,
            @RequestHeader HttpHeaders allHeaders,
            @RequestBody(required = false) String rawBody,
            HttpServletRequest request) {
        Map<String, String> flatHeaders = new LinkedHashMap<>();
        if (allHeaders != null) {
            allHeaders.forEach((k, values) -> {
                if (values != null && !values.isEmpty()) {
                    flatHeaders.put(k, String.join(", ", values));
                }
            });
        }
        if (request != null && request.getRemoteAddr() != null) {
            flatHeaders.putIfAbsent("X-Remote-Addr", request.getRemoteAddr());
        }
        String reqPath = request != null && request.getRequestURI() != null
                ? request.getRequestURI()
                : "/emerald/v1/callback";
        String reqUrl = request != null && request.getRequestURL() != null
                ? request.getRequestURL().toString()
                : reqPath;

        EmeraldCallbackService.CallbackOutcome outcome = callbacks.handleInbound(
                secretHeader, rawBody, reqUrl, reqPath, flatHeaders);
        if (!outcome.authorized()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "Invalid callback secret"));
        }
        return ResponseEntity.ok(Map.of("success", true));
    }
}

