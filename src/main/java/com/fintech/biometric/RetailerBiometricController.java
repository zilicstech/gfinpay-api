package com.fintech.biometric;

import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * White-label retailer endpoints for biometric eKYC activation and live cash terminal status checks.
 */
@RestController
@RequestMapping("/api/v1/me")
public class RetailerBiometricController {

    public record BiometricInitiateRequest(String aadhaarNumber) {}

    public record BiometricSubmitRequest(
            UUID sessionId,
            String pidXml,
            String latitude,
            String longitude
    ) {}

    public record DeviceEventRequest(
            String stage,
            Integer port,
            String status,
            String rdsId,
            String mi,
            String errCode,
            String detail
    ) {}

    private final BiometricActivationService biometric;

    public RetailerBiometricController(BiometricActivationService biometric) {
        this.biometric = biometric;
    }

    @GetMapping("/cash-activation")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> cashActivation(
            @AuthenticationPrincipal AuthPrincipal me,
            @RequestParam(defaultValue = "false") boolean refresh) {
        return ApiResponse.ok(biometric.getCashActivation(me, refresh));
    }

    @GetMapping("/biometric")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> biometricStatus(
            @AuthenticationPrincipal AuthPrincipal me,
            @RequestParam(defaultValue = "true") boolean refresh) {
        return ApiResponse.ok(biometric.getCashActivation(me, refresh));
    }

    @PostMapping("/biometric/{channelKey}/initiate")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> initiate(
            @AuthenticationPrincipal AuthPrincipal me,
            @PathVariable String channelKey,
            @RequestBody(required = false) BiometricInitiateRequest body) {
        String aadhaar = body == null ? null : body.aadhaarNumber();
        return ApiResponse.ok(biometric.initiateBiometric(me, channelKey, aadhaar));
    }

    @PostMapping("/biometric/{channelKey}/submit")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> submit(
            @AuthenticationPrincipal AuthPrincipal me,
            @PathVariable String channelKey,
            @RequestBody BiometricSubmitRequest body) {
        return ApiResponse.ok(biometric.submitBiometric(
                me,
                channelKey,
                body.sessionId(),
                body.pidXml(),
                body.latitude(),
                body.longitude()));
    }

    @PostMapping("/biometric/device-event")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> deviceEvent(
            @AuthenticationPrincipal AuthPrincipal me,
            @RequestBody DeviceEventRequest body,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        biometric.recordDeviceEvent(
                me,
                body.stage(),
                body.port(),
                body.status(),
                body.rdsId(),
                body.mi(),
                body.errCode(),
                body.detail(),
                requestId);
        return ApiResponse.ok(Map.of("logged", true));
    }
}

