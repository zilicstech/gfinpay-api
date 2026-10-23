package com.fintech.identity;

import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/ekyc")
public class RetailerEkycController {

    public record ActivateRequest(
            @NotBlank String aadhaar,
            @NotBlank String dob,
            @NotBlank String pidData,
            String latitude,
            String longitude,
            Boolean activateDmt,
            String pipe) {}

    public record DeviceEventRequest(
            @NotBlank String stage,
            String pipe,
            boolean ok,
            Integer port,
            String errCode,
            String errInfo,
            String reason,
            String deviceSerial,
            String deviceModel,
            String dpId,
            String rdsId,
            Integer qScore,
            Integer pidLength,
            Integer tookMs,
            Integer portsTried) {}

    private final MerchantActivationService activation;

    public RetailerEkycController(MerchantActivationService activation) {
        this.activation = activation;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> status(@AuthenticationPrincipal AuthPrincipal me,
                                                   @RequestParam(value = "prepare", defaultValue = "false") boolean prepare) {
        return ApiResponse.ok(prepare ? activation.prepare(me) : activation.status(me));
    }

    @PostMapping("/onboarding/refresh")
    public ApiResponse<Map<String, Object>> refreshOnboarding(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(activation.refreshOnboarding(me));
    }

    @PostMapping("/{pipe}/activate")
    public ApiResponse<Map<String, Object>> activate(@AuthenticationPrincipal AuthPrincipal me,
                                                     @PathVariable String pipe,
                                                     @Valid @RequestBody ActivateRequest body) {
        boolean activateDmt = body.activateDmt() == null || body.activateDmt();
        return ApiResponse.ok(activation.activate(me, pipe, body.pipe(), body.aadhaar(), body.dob(), body.pidData(),
                body.latitude(), body.longitude(), activateDmt));
    }

    @PostMapping("/device-event")
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Map<String, Object>> device(@AuthenticationPrincipal AuthPrincipal me,
                                                   @Valid @RequestBody DeviceEventRequest body) {
        activation.deviceEvent(me, body.stage(), body.pipe(), body.ok(), body.port(), body.errCode(),
                body.errInfo(), body.reason(), body.deviceSerial(), body.deviceModel(), body.dpId(),
                body.rdsId(), body.qScore(), body.pidLength(), body.tookMs(), body.portsTried());
        return ApiResponse.ok(Map.of("logged", true));
    }
}
