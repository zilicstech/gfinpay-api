package com.fintech.onboarding;

import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/reverify")
@PreAuthorize("isAuthenticated()")
public class ReverifyController {

    public record OtpVerifyRequest(@NotBlank String otp) {}

    public record LocationRequest(Double latitude, Double longitude, Double accuracy) {}

    public record DigilockerStartRequest(String redirectUrl) {}

    public record DigilockerCompleteRequest(String clientId, @NotBlank String state) {}

    public record PanRequest(String pan, String aadhaar) {
        public PanRequest(String pan) {
            this(pan, null);
        }
    }

    public record PinRequest(String pin, String newPassword) {}

    private final ReverifyService reverify;

    public ReverifyController(ReverifyService reverify) {
        this.reverify = reverify;
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(reverify.status(me));
    }

    @PostMapping("/otp")
    public ApiResponse<Map<String, Object>> sendOtp(
            @AuthenticationPrincipal AuthPrincipal me,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return ApiResponse.ok(reverify.sendOtp(me, ip, userAgent));
    }

    @PostMapping("/otp/verify")
    public ApiResponse<Map<String, Object>> verifyOtp(
            @AuthenticationPrincipal AuthPrincipal me,
            @Valid @RequestBody OtpVerifyRequest req,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return ApiResponse.ok(reverify.verifyOtp(me, req.otp(), ip, userAgent));
    }

    @PostMapping("/location")
    public ApiResponse<Map<String, Object>> location(
            @AuthenticationPrincipal AuthPrincipal me,
            @RequestBody LocationRequest req,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return ApiResponse.ok(reverify.captureLocation(
                me, req.latitude(), req.longitude(), req.accuracy(), ip, userAgent));
    }

    @PostMapping("/digilocker/start")
    public ApiResponse<Map<String, Object>> startDigilocker(
            @AuthenticationPrincipal AuthPrincipal me,
            @RequestBody(required = false) DigilockerStartRequest req,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return ApiResponse.ok(reverify.startDigilocker(me, ip, userAgent));
    }

    @PostMapping("/digilocker/complete")
    public ApiResponse<Map<String, Object>> completeDigilocker(
            @AuthenticationPrincipal AuthPrincipal me,
            @Valid @RequestBody DigilockerCompleteRequest req,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return ApiResponse.ok(reverify.completeDigilocker(me, req.state(), ip, userAgent));
    }

    @PostMapping(value = "/pan", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> pan(
            @AuthenticationPrincipal AuthPrincipal me,
            @RequestBody(required = false) PanRequest req,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        String panVal = req == null ? null : req.pan();
        String aadhaarVal = req == null ? null : req.aadhaar();
        return ApiResponse.ok(reverify.verifyPan(me, panVal, aadhaarVal, null, true, ip, userAgent));
    }

    @PostMapping(value = "/pan", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> panMultipart(
            @AuthenticationPrincipal AuthPrincipal me,
            @RequestParam(value = "pan", required = false) String pan,
            @RequestParam(value = "aadhaar", required = false) String aadhaar,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return ApiResponse.ok(reverify.verifyPan(me, pan, aadhaar, file, true, ip, userAgent));
    }

    @PostMapping(value = "/selfie", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> selfie(
            @AuthenticationPrincipal AuthPrincipal me,
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return ApiResponse.ok(reverify.verifySelfie(me, file, ip, userAgent));
    }

    @PostMapping("/pin")
    public ApiResponse<Map<String, Object>> pin(
            @AuthenticationPrincipal AuthPrincipal me,
            @Valid @RequestBody PinRequest req,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return ApiResponse.ok(reverify.completeReverification(
                me, req.pin(), req.newPassword(), ip, userAgent));
    }
}

