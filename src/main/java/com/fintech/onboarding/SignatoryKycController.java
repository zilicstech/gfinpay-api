package com.fintech.onboarding;

import com.fintech.platform.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/public/signatory")
public class SignatoryKycController {

    public static final String SESSION_HEADER = "X-Onboarding-Session";

    public record OtpVerifyRequest(@NotBlank String otp) {}

    public record LocationRequest(Double latitude, Double longitude, Double accuracy) {}

    public record DigilockerStartRequest(String redirectUrl) {}

    public record DigilockerCompleteRequest(String clientId, @NotBlank String state) {}

    public record PanRequest(String pan) {}

    private final SignatoryKycService signatoryService;

    public SignatoryKycController(SignatoryKycService signatoryService) {
        this.signatoryService = signatoryService;
    }

    @GetMapping("/{token}")
    public ApiResponse<Map<String, Object>> open(
            @PathVariable String token,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return ApiResponse.ok(signatoryService.openLink(token, ip, userAgent));
    }

    @PostMapping("/{token}/otp")
    public ApiResponse<Map<String, Object>> sendOtp(
            @PathVariable String token,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return ApiResponse.ok(signatoryService.sendOtp(token, ip, userAgent));
    }

    @PostMapping("/{token}/otp/verify")
    public ApiResponse<Map<String, Object>> verifyOtp(
            @PathVariable String token,
            @Valid @RequestBody OtpVerifyRequest req,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return ApiResponse.ok(signatoryService.verifyOtp(token, req.otp(), ip, userAgent));
    }

    @PostMapping("/session/location")
    public ApiResponse<Map<String, Object>> location(
            @RequestHeader(value = SESSION_HEADER, required = false) String session,
            @RequestBody LocationRequest req,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return ApiResponse.ok(signatoryService.captureLocation(
                session, req.latitude(), req.longitude(), req.accuracy(), ip, userAgent));
    }

    @PostMapping("/session/digilocker/start")
    public ApiResponse<Map<String, Object>> startDigilocker(
            @RequestHeader(value = SESSION_HEADER, required = false) String session,
            @RequestBody(required = false) DigilockerStartRequest req,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return ApiResponse.ok(signatoryService.startDigilocker(session, ip, userAgent));
    }

    @PostMapping("/session/digilocker/complete")
    public ApiResponse<Map<String, Object>> completeDigilocker(
            @RequestHeader(value = SESSION_HEADER, required = false) String session,
            @Valid @RequestBody DigilockerCompleteRequest req,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return ApiResponse.ok(signatoryService.completeDigilocker(session, req.state(), ip, userAgent));
    }

    @PostMapping(value = "/session/pan", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> pan(
            @RequestHeader(value = SESSION_HEADER, required = false) String session,
            @RequestBody(required = false) PanRequest req,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        String panVal = req == null ? null : req.pan();
        return ApiResponse.ok(signatoryService.verifyPan(session, panVal, null, ip, userAgent));
    }

    @PostMapping(value = "/session/pan", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> panMultipart(
            @RequestHeader(value = SESSION_HEADER, required = false) String session,
            @RequestParam(value = "pan", required = false) String pan,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return ApiResponse.ok(signatoryService.verifyPan(session, pan, file, ip, userAgent));
    }

    @PostMapping(value = "/session/selfie", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> selfie(
            @RequestHeader(value = SESSION_HEADER, required = false) String session,
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return ApiResponse.ok(signatoryService.verifySelfie(session, file, ip, userAgent));
    }
}

